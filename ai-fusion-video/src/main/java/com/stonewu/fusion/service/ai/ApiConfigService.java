package com.stonewu.fusion.service.ai;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.common.PageResult;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.entity.ai.ApiConfig;
import com.stonewu.fusion.mapper.ai.AiModelMapper;
import com.stonewu.fusion.mapper.ai.ApiConfigMapper;
import com.stonewu.fusion.service.ai.proxy.AiProxySupport;
import com.stonewu.fusion.service.ai.catalog.ProviderCatalogService;
import com.stonewu.fusion.service.ai.sonilo.SoniloAudioClient;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.stonewu.fusion.service.ai.model.AiModelMetadata.CODEX;

/** 管理模型供应商接入方式和能力协议。 */
@Service
@RequiredArgsConstructor
public class ApiConfigService {

    private final ApiConfigMapper apiConfigMapper;
    private final AiModelMapper aiModelMapper;
    private final ObjectProvider<ChatModelFactory> chatModelFactoryProvider;
    private final ProviderCatalogService providerCatalog;

    @CacheEvict(value = "episodeBgmConfig", allEntries = true)
    @Transactional
    public Long createApiConfig(ApiConfig apiConfig) {
        apiConfig.setPlatform(normalizeCodexPlatform(apiConfig.getPlatform()));
        normalizeProviderBinding(apiConfig);
        applyPlatformDefaults(apiConfig);
        if (apiConfig.getAutoAppendV1Path() == null) {
            apiConfig.setAutoAppendV1Path(true);
        }
        apiConfig.setTextProtocol(normalizeProtocol(apiConfig.getTextProtocol()));
        apiConfig.setImageProtocol(normalizeProtocol(apiConfig.getImageProtocol()));
        apiConfig.setVideoProtocol(normalizeProtocol(apiConfig.getVideoProtocol()));
        apiConfig.setApiUrl(normalizeApiUrl(apiConfig.getPlatform(), apiConfig.getApiUrl()));
        normalizeProxyConfig(apiConfig);
        apiConfigMapper.insert(apiConfig);
        return apiConfig.getId();
    }

    /** 更新接入配置，停用前须解除启用模型的默认标记。 */
    @CacheEvict(value = "episodeBgmConfig", allEntries = true)
    @Transactional
    public void updateApiConfig(Long id, String name, String platform, String providerId,
                                String textProtocol, String imageProtocol, String videoProtocol,
                                String apiUrl,
                                Boolean autoAppendV1Path,
                                String proxyType, String proxyHost, Integer proxyPort,
                                String proxyUsername, String proxyPassword,
                                String apiKey, String appId, String appSecret,
                                Long modelId, Integer status, String remark) {
        ApiConfig config = apiConfigMapper.selectById(id);
        if (config == null) {
            throw new BusinessException(404, "API配置不存在");
        }
        if (Integer.valueOf(1).equals(config.getStatus()) && status != null && !Integer.valueOf(1).equals(status)) {
            requireNoEnabledDefaultModel(id);
        }
        String savedPlatform = normalizeCodexPlatform(config.getPlatform());
        String effectivePlatform = platform != null ? normalizeCodexPlatform(platform) : savedPlatform;
        if (!Objects.equals(effectivePlatform, savedPlatform)
                && (CODEX.equals(effectivePlatform) || CODEX.equals(savedPlatform))) {
            throw new BusinessException(400, "Codex 需要独立的本机接入配置，请新建配置后绑定模型");
        }
        if (name != null) {
            config.setName(name);
        }
        if (platform != null || CODEX.equals(effectivePlatform)) {
            config.setPlatform(effectivePlatform);
        }
        if (providerId != null) {
            config.setProviderId(providerId);
        }
        if (textProtocol != null) {
            config.setTextProtocol(normalizeProtocol(textProtocol));
        }
        if (imageProtocol != null) {
            config.setImageProtocol(normalizeProtocol(imageProtocol));
        }
        if (videoProtocol != null) {
            config.setVideoProtocol(normalizeProtocol(videoProtocol));
        }
        if (apiUrl != null) {
            config.setApiUrl(normalizeApiUrl(effectivePlatform, apiUrl));
        }
        if (autoAppendV1Path != null) {
            config.setAutoAppendV1Path(autoAppendV1Path);
        }
        if (proxyType != null) {
            config.setProxyType(proxyType);
        }
        if (proxyHost != null) {
            config.setProxyHost(proxyHost);
        }
        if (proxyPort != null) {
            config.setProxyPort(proxyPort);
        }
        if (proxyUsername != null) {
            config.setProxyUsername(proxyUsername);
        }
        if (proxyPassword != null) {
            config.setProxyPassword(proxyPassword);
        }
        if (apiKey != null) {
            config.setApiKey(apiKey);
        }
        if (appId != null) {
            config.setAppId(appId);
        }
        if (appSecret != null) {
            config.setAppSecret(appSecret);
        }
        if (modelId != null) {
            config.setModelId(modelId);
        }
        if (status != null) {
            config.setStatus(status);
        }
        if (remark != null) {
            config.setRemark(remark);
        }
        applyPlatformDefaults(config);
        normalizeProviderBinding(config);
        normalizeProxyConfig(config);
        apiConfigMapper.updateById(config);
        evictModelCaches();
    }

    /** 删除接入前保留启用默认模型所依赖的配置。 */
    @CacheEvict(value = "episodeBgmConfig", allEntries = true)
    @Transactional
    public void deleteApiConfig(Long id) {
        requireNoEnabledDefaultModel(id);
        apiConfigMapper.deleteById(id);
        evictModelCaches();
    }

    /** 防止停用或删除仍供默认模型使用的接入。 */
    private void requireNoEnabledDefaultModel(Long apiConfigId) {
        if (aiModelMapper.exists(new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getApiConfigId, apiConfigId)
                .eq(AiModel::getStatus, 1)
                .eq(AiModel::getDefaultModel, true))) {
            throw new BusinessException(409, "此 API 配置仍供默认模型使用，请先切换或取消默认模型");
        }
    }

    public ApiConfig getById(Long id) {
        return apiConfigMapper.selectById(id);
    }

    public void validateModelType(ApiConfig config, Integer modelType) {
        if (config != null && config.getProviderId() != null
                && !providerCatalog.require(config.getProviderId()).supportedModelTypes().contains(modelType)) {
            throw new BusinessException(400, "当前提供商不支持所选模型类型");
        }
    }

    public PageResult<ApiConfig> getPage(String name, String platform, Integer status, int pageNo, int pageSize) {
        LambdaQueryWrapper<ApiConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(name != null, ApiConfig::getName, name)
                .eq(platform != null, ApiConfig::getPlatform, platform)
                .eq(status != null, ApiConfig::getStatus, status)
                .orderByDesc(ApiConfig::getId);
        return PageResult.of(apiConfigMapper.selectPage(new Page<>(pageNo, pageSize), wrapper));
    }

    public List<ApiConfig> getEnabledList() {
        return apiConfigMapper.selectList(new LambdaQueryWrapper<ApiConfig>().eq(ApiConfig::getStatus, 1));
    }

    /** 批量查询指定供应商的配置标识。 */
    public Set<Long> getIdsByPlatform(String platform) {
        return apiConfigMapper.selectList(new LambdaQueryWrapper<ApiConfig>()
                        .select(ApiConfig::getId).eq(ApiConfig::getPlatform, platform)).stream()
                .map(ApiConfig::getId)
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isLocalCodexAvailable() {
        return providerCatalog.isLocalCodexAvailable();
    }

    public void requireLocalCodex() {
        providerCatalog.requireLocalCodex();
    }

    /**
     * 按平台标识获取启用的 API 配置列表
     */
    public List<ApiConfig> getListByPlatform(String platform) {
        return apiConfigMapper.selectList(new LambdaQueryWrapper<ApiConfig>()
                .eq(ApiConfig::getStatus, 1)
                .eq(ApiConfig::getPlatform, platform));
    }

    /**
     * 按多个平台标识获取启用的 API 配置列表
     */
    public List<ApiConfig> getListByPlatforms(List<String> platforms) {
        return apiConfigMapper.selectList(new LambdaQueryWrapper<ApiConfig>()
                .eq(ApiConfig::getStatus, 1)
                .in(ApiConfig::getPlatform, platforms));
    }

    private String normalizeApiUrl(String platform, String apiUrl) {
        if (StrUtil.isBlank(apiUrl)) {
            return null;
        }
        String normalizedApiUrl = apiUrl.trim();
        String defaultApiUrl = getPlatformDefaultApiUrl(platform);
        if (StrUtil.isNotBlank(defaultApiUrl) && isSameApiUrl(normalizedApiUrl, defaultApiUrl)) {
            return null;
        }
        return normalizedApiUrl;
    }

    private String normalizeProtocol(String protocol) {
        if (StrUtil.isBlank(protocol)) {
            return null;
        }
        return protocol.trim().toLowerCase().replace(' ', '_').replace('-', '_');
    }

    private String normalizeCodexPlatform(String platform) {
        return platform != null && CODEX.equalsIgnoreCase(platform.trim()) ? CODEX : platform;
    }

    private void normalizeProxyConfig(ApiConfig config) {
        String proxyType = AiProxySupport.normalizeProxyType(config.getProxyType());
        if (AiProxySupport.TYPE_NONE.equals(proxyType)) {
            config.setProxyType(null);
            config.setProxyHost(null);
            config.setProxyPort(null);
            config.setProxyUsername(null);
            config.setProxyPassword(null);
            return;
        }
        if (!AiProxySupport.isSupportedProxyType(proxyType)) {
            throw new BusinessException("不支持的代理类型: " + config.getProxyType());
        }
        if (StrUtil.isBlank(config.getProxyHost())) {
            throw new BusinessException("启用代理时代理主机不能为空");
        }
        Integer proxyPort = config.getProxyPort();
        if (proxyPort == null || proxyPort <= 0 || proxyPort > 65535) {
            throw new BusinessException("启用代理时代理端口必须在 1-65535 之间");
        }
        String proxyUsername = StrUtil.trim(config.getProxyUsername());
        if (StrUtil.isBlank(proxyUsername)) {
            if (StrUtil.isNotBlank(config.getProxyPassword())) {
                throw new BusinessException("启用代理认证时代理用户名不能为空");
            }
            config.setProxyUsername(null);
            config.setProxyPassword(null);
        } else {
            config.setProxyUsername(proxyUsername);
        }
        config.setProxyType(proxyType);
        config.setProxyHost(config.getProxyHost().trim());
    }

    private boolean isSameApiUrl(String currentApiUrl, String defaultApiUrl) {
        return normalizeComparableApiUrl(currentApiUrl)
                .equalsIgnoreCase(normalizeComparableApiUrl(defaultApiUrl));
    }

    private String normalizeComparableApiUrl(String apiUrl) {
        if (StrUtil.isBlank(apiUrl)) {
            return "";
        }
        return apiUrl.trim().replaceAll("/+$", "");
    }

    private String getPlatformDefaultApiUrl(String platform) {
        if (StrUtil.isBlank(platform)) {
            return null;
        }
        return switch (platform) {
            case "openai_compatible", "openai" -> "https://api.openai.com";
            case "newapi" -> "https://docs.newapi.ai";
            case "volcengine" -> "https://ark.cn-beijing.volces.com";
            case "vertex_ai" -> "us-central1";
            case "GoogleFlowReverseApi" -> "http://localhost:8000";
            case "dashscope" -> "https://dashscope.aliyuncs.com";
            case "anthropic" -> "https://api.anthropic.com";
            case "ollama" -> "http://localhost:11434";
            case "comfyui" -> "http://localhost:8188";
            case SoniloAudioClient.PLATFORM -> SoniloAudioClient.DEFAULT_BASE_URL;
            default -> null;
        };
    }

    private void applyPlatformDefaults(ApiConfig config) {
        if (config == null) {
            return;
        }
        if (CODEX.equals(normalizeProtocol(config.getImageProtocol()))
                || CODEX.equals(normalizeProtocol(config.getVideoProtocol()))
                || (CODEX.equals(normalizeProtocol(config.getTextProtocol())) && !CODEX.equals(config.getPlatform()))) {
            throw new BusinessException(400, "Codex 协议只适用于使用本机 Codex 接入的文本模型");
        }
        if (StrUtil.isBlank(config.getPlatform())) {
            return;
        }
        if (CODEX.equals(config.getPlatform())) {
            validateCodexConfig(config);
            config.setAutoAppendV1Path(false);
            return;
        }
        if (SoniloAudioClient.PLATFORM.equalsIgnoreCase(config.getPlatform())) {
            SoniloAudioClient.requireApiKey(config);
            // 音频生成接入：只有地址与密钥，不参与文本 / 图片 / 视频协议路由
            config.setTextProtocol(null);
            config.setImageProtocol(null);
            config.setVideoProtocol(null);
            config.setAutoAppendV1Path(false);
            return;
        }
        if (!"comfyui".equalsIgnoreCase(config.getPlatform())) {
            return;
        }
        config.setTextProtocol(null);
        if (StrUtil.isBlank(config.getImageProtocol())) {
            config.setImageProtocol("comfyui");
        }
        if (StrUtil.isBlank(config.getVideoProtocol())) {
            config.setVideoProtocol("comfyui");
        }
        if (!"comfyui".equalsIgnoreCase(config.getImageProtocol())
                || !"comfyui".equalsIgnoreCase(config.getVideoProtocol())) {
            throw new BusinessException(400, "ComfyUI 图片和视频默认协议必须为 comfyui");
        }
        config.setAutoAppendV1Path(false);
    }

    /** 本机登录接入的配置只登记文本协议，不持久化连接地址和鉴权信息。 */
    private void validateCodexConfig(ApiConfig config) {
        requireLocalCodex();
        if (!CODEX.equals(normalizeProtocol(config.getTextProtocol()))
                || StrUtil.isNotBlank(config.getImageProtocol()) || StrUtil.isNotBlank(config.getVideoProtocol())) {
            throw new BusinessException(400, "Codex 接入只支持 Codex 文本协议");
        }
        if (StrUtil.isNotBlank(config.getApiUrl()) || StrUtil.isNotBlank(config.getApiKey())
                || StrUtil.isNotBlank(config.getAppId()) || StrUtil.isNotBlank(config.getAppSecret())
                || !AiProxySupport.TYPE_NONE.equals(AiProxySupport.normalizeProxyType(config.getProxyType()))) {
            throw new BusinessException(400, "Codex 接入使用本机 ChatGPT 登录，无需填写地址、密钥或代理");
        }
    }

    private void evictModelCaches() {
        ChatModelFactory chatModelFactory = chatModelFactoryProvider.getIfAvailable();
        if (chatModelFactory != null) {
            chatModelFactory.evictAll();
        }
    }

    private void normalizeProviderBinding(ApiConfig config) {
        config.setProviderId(StrUtil.isBlank(config.getProviderId()) ? null : config.getProviderId().trim());
        providerCatalog.validateBinding(config);
    }
}
