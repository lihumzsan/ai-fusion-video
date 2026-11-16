package com.stonewu.fusion.service.ai;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.common.PageResult;
import com.stonewu.fusion.controller.ai.vo.AiModelConnectivityRespVO;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.entity.ai.ApiConfig;
import com.stonewu.fusion.mapper.ai.AiModelMapper;
import com.stonewu.fusion.service.ai.comfyui.ComfyUiVoiceDesign;
import com.stonewu.fusion.service.ai.comfyui.ComfyUiWorkflowService;
import com.stonewu.fusion.service.ai.google.GoogleModelFamily;
import com.stonewu.fusion.service.ai.model.AiModelMetadata;
import com.stonewu.fusion.service.ai.model.AiModelMetadataResolver;
import com.stonewu.fusion.service.generation.GenerationModelCapabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.stonewu.fusion.service.ai.AiModelMultimodalCapabilities.TRANSPORT_BASE64;
import static com.stonewu.fusion.service.ai.AiModelMultimodalCapabilities.TYPE_IMAGE;
import static com.stonewu.fusion.service.ai.comfyui.ComfyUiVoiceDesign.MODEL_CODE;
import static com.stonewu.fusion.service.ai.comfyui.ComfyUiWorkflowService.PLATFORM;
import static com.stonewu.fusion.service.ai.model.AiModelMetadata.CODEX;

/** 管理已登记的模型及模型调用配置。 */
@Service
@RequiredArgsConstructor
public class AiModelService {

    private static final int MODEL_TYPE_TEXT = 1;
    private static final int MODEL_TYPE_AUDIO = 4;
    private static final String CONNECTIVITY_TEST_MESSAGE = "Connectivity test. Reply with OK only.";

    private final AiModelMapper aiModelMapper;
    private final ApiConfigService apiConfigService;
    private final ModelPresetService modelPresetService;
    private final AiModelMetadataResolver aiModelMetadataResolver;
    private final ChatModelFactory chatModelFactory;
    private final ComfyUiWorkflowService comfyUiWorkflowService;
    private final GenerationModelCapabilityService generationCapabilities;

    @Transactional
    public Long createAiModel(AiModel aiModel) {
        ApiConfig apiConfig = requireApiConfig(aiModel.getApiConfigId());
        boolean makeDefault = Boolean.TRUE.equals(aiModel.getDefaultModel());
        if (makeDefault) {
            validateDefaultModel(aiModel.getStatus(), apiConfig);
        }
        validateUniqueCode(null, aiModel.getApiConfigId(), aiModel.getCode());
        prepareModelConfiguration(aiModel, apiConfig);
        try {
            aiModelMapper.insert(aiModel);
        } catch (DuplicateKeyException e) {
            throwDuplicateCodeException(aiModel.getApiConfigId(), e);
        }
        if (makeDefault) {
            clearOtherDefaults(aiModel.getModelType(), aiModel.getId());
        }
        return aiModel.getId();
    }

    /** 更新模型时，在默认归属变更前校验生效的模型状态和接入。 */
    @Transactional
    public void updateAiModel(Long id, String name, String code, String modelProtocol,
                              String capabilityPresetCode, Integer modelType, String icon,
                              String description, Integer sort, Integer status,
                              String config, Boolean defaultModel, Long apiConfigId,
                              Integer maxConcurrency, List<String> multimodalInputTypes,
                              Map<String, List<String>> multimodalInputTransports,
                              Boolean supportReasoning, List<String> reasoningEffortLevels,
                              Integer contextWindow, Long comfyuiWorkflowId) {
        AiModel model = aiModelMapper.selectById(id);
        if (model == null) {
            throw new BusinessException(404, "AI模型不存在");
        }
        boolean changesType = modelType != null && !modelType.equals(model.getModelType());
        Long nextApiConfigId = apiConfigId != null ? apiConfigId : model.getApiConfigId();
        String nextCode = code != null ? code : model.getCode();
        boolean changesDefaultConfig = changesType
                || (apiConfigId != null && !apiConfigId.equals(model.getApiConfigId()))
                || (status != null && !status.equals(model.getStatus()));
        boolean updateDefault = Boolean.TRUE.equals(defaultModel)
                || (defaultModel == null && Boolean.TRUE.equals(model.getDefaultModel()) && changesDefaultConfig);
        ApiConfig nextApi = updateDefault || apiConfigId != null
                ? requireApiConfig(nextApiConfigId) : aiModelMetadataResolver.resolveApiConfig(model);
        if (updateDefault) {
            Integer nextStatus = status != null ? status : model.getStatus();
            validateDefaultModel(nextStatus, nextApi);
        }
        String nextProtocol = modelProtocol != null ? aiModelMetadataResolver.normalizeProtocol(modelProtocol)
                : model.getModelProtocol();
        Integer nextModelType = modelType != null ? modelType : model.getModelType();
        ApiConfig existingApi = Objects.equals(nextApiConfigId, model.getApiConfigId()) ? nextApi : null;
        boolean currentVoiceDesign = ComfyUiVoiceDesign.isModelCandidate(
                model.getModelType(), model.getCode(), model.getModelProtocol(), existingApi);
        if (currentVoiceDesign != ComfyUiVoiceDesign.isModelCandidate(nextModelType, nextCode, nextProtocol, nextApi)) {
            throw new BusinessException(400, "参考音频模型不能与其他模型类型互相转换，请新建模型");
        }
        validateUniqueCode(id, nextApiConfigId, nextCode);
        if (name != null) {
            model.setName(name);
        }
        if (code != null) {
            model.setCode(code);
        }
        if (modelProtocol != null) {
            model.setModelProtocol(nextProtocol);
        }
        if (capabilityPresetCode != null) {
            model.setCapabilityPresetCode(normalizeCapabilityPresetCode(capabilityPresetCode));
        }
        if (modelType != null) {
            model.setModelType(modelType);
        }
        if (icon != null) {
            model.setIcon(icon);
        }
        if (description != null) {
            model.setDescription(description);
        }
        if (sort != null) {
            model.setSort(sort);
        }
        if (status != null) {
            model.setStatus(status);
        }
        if (config != null) {
            model.setConfig(config);
        }
        if (maxConcurrency != null) {
            model.setMaxConcurrency(maxConcurrency > 0 ? maxConcurrency : 5);
        }
        if (defaultModel != null) {
            model.setDefaultModel(defaultModel);
        }
        if (multimodalInputTypes != null) {
            model.setMultimodalInputTypes(multimodalInputTypes);
        }
        if (multimodalInputTransports != null) {
            model.setMultimodalInputTransports(multimodalInputTransports);
        }
        if (supportReasoning != null) {
            model.setSupportReasoning(supportReasoning);
        }
        if (reasoningEffortLevels != null) {
            model.setReasoningEffortLevels(reasoningEffortLevels);
        }
        if (contextWindow != null) {
            model.setContextWindow(contextWindow > 0 ? contextWindow : null);
        }
        if (apiConfigId != null) {
            model.setApiConfigId(apiConfigId);
        }
        if (comfyuiWorkflowId != null) {
            model.setComfyuiWorkflowId(comfyuiWorkflowId > 0 ? comfyuiWorkflowId : null);
        }
        prepareModelConfiguration(model, nextApi);
        try {
            // 请求协议由实体映射写入；其余显式清空的字段通过同一条 SQL 持久化。
            aiModelMapper.update(model, new LambdaUpdateWrapper<AiModel>()
                    .eq(AiModel::getId, id)
                    .set(capabilityPresetCode != null && model.getCapabilityPresetCode() == null,
                            AiModel::getCapabilityPresetCode, null)
                    .set(comfyuiWorkflowId != null && model.getComfyuiWorkflowId() == null,
                            AiModel::getComfyuiWorkflowId, null));
        } catch (DuplicateKeyException e) {
            throwDuplicateCodeException(nextApiConfigId, e);
        }
        if (updateDefault) {
            clearOtherDefaults(model.getModelType(), model.getId());
        }
        chatModelFactory.evict(id);
    }

    @Transactional
    public void deleteAiModel(Long id) {
        aiModelMapper.softDeleteById(id);
        chatModelFactory.evict(id);
    }

    public AiModel getById(Long id) {
        return aiModelMapper.selectById(id);
    }

    public AiModel getByCodeAndApiConfig(String code, Long apiConfigId) {
        if (StrUtil.isBlank(code) || apiConfigId == null) {
            return null;
        }
        return aiModelMapper.selectPage(new Page<>(1, 1, false), new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getCode, code)
                .eq(AiModel::getApiConfigId, apiConfigId)
                .eq(AiModel::getStatus, 1)).getRecords().stream().findFirst().orElse(null);
    }

    public PageResult<AiModel> getPage(String name, String code, Integer modelType, Integer status,
                                       int pageNo, int pageSize) {
        LambdaQueryWrapper<AiModel> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(name != null, AiModel::getName, name)
                .like(code != null, AiModel::getCode, code)
                .eq(modelType != null, AiModel::getModelType, modelType)
                .eq(status != null, AiModel::getStatus, status)
                .orderByAsc(AiModel::getSort)
                .orderByDesc(AiModel::getId);
        return PageResult.of(aiModelMapper.selectPage(new Page<>(pageNo, pageSize), wrapper));
    }

    public List<AiModel> getEnabledList() {
        return aiModelMapper.selectList(new LambdaQueryWrapper<AiModel>().eq(AiModel::getStatus, 1));
    }

    /** 查询当前环境可选择的已启用模型。 */
    public List<AiModel> getListByType(Integer modelType) {
        var models = aiModelMapper.selectList(new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getStatus, 1)
                .eq(AiModel::getModelType, modelType));
        if (!Integer.valueOf(MODEL_TYPE_TEXT).equals(modelType) || models.isEmpty()
                || apiConfigService.isLocalCodexAvailable()) {
            return models;
        }
        var codexApiIds = apiConfigService.getIdsByPlatform(CODEX);
        return models.stream()
                .filter(model -> !CODEX.equals(model.getModelProtocol())
                        && (model.getApiConfigId() == null || !codexApiIds.contains(model.getApiConfigId())))
                .toList();
    }

    public AiModel getDefaultByType(Integer modelType) {
        return aiModelMapper.selectPage(new Page<>(1, 1, false), new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getDefaultModel, true)
                .eq(AiModel::getModelType, modelType)
                .eq(AiModel::getStatus, 1)
                .orderByAsc(AiModel::getSort)).getRecords().stream().findFirst().orElse(null);
    }

    /** 助手及恢复的智能体运行校验所选模型能否承担对话。 */
    public void requireAgentModel(AiModel model) {
        if (ComfyUiVoiceDesign.isModelCandidate(model.getModelType(), model.getCode(), model.getModelProtocol(), null)) {
            throw new BusinessException(400, "参考音频模型仅用于画布生成参考音频节点");
        }
        if (Integer.valueOf(MODEL_TYPE_AUDIO).equals(model.getModelType())) {
            throw new BusinessException(400, "音频生成模型不能用于助手对话");
        }
    }

    public AiModelConnectivityRespVO testTextModelConnectivity(Long id) {
        AiModel model = aiModelMapper.selectById(id);
        if (model == null) {
            throw new BusinessException(404, "AI模型不存在");
        }
        if (model.getModelType() == null || model.getModelType() != MODEL_TYPE_TEXT) {
            throw new BusinessException(400, "仅支持文本模型连通性检测");
        }

        long startTime = System.currentTimeMillis();
        try {
            String response = chatModelFactory.checkConnectivity(model, CONNECTIVITY_TEST_MESSAGE);

            AiModelConnectivityRespVO respVO = new AiModelConnectivityRespVO();
            respVO.setModelId(model.getId());
            respVO.setModelName(model.getName());
            respVO.setResponseText(
                    StrUtil.blankToDefault(response, "模型已响应，但未返回文本内容"));
            respVO.setDurationMs(System.currentTimeMillis() - startTime);
            respVO.setTestedAt(LocalDateTime.now());
            return respVO;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("模型连通性检测失败: "
                    + StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    private ApiConfig requireApiConfig(Long apiConfigId) {
        if (apiConfigId == null) {
            throw new BusinessException(400, "请选择 API 配置");
        }
        ApiConfig apiConfig = apiConfigService.getById(apiConfigId);
        if (apiConfig == null) {
            throw new BusinessException(404, "API 配置不存在");
        }
        return apiConfig;
    }

    /** 默认模型必须使用已启用的模型和接入，校验通过才可替换旧默认项。 */
    private void validateDefaultModel(Integer modelStatus, ApiConfig apiConfig) {
        if (!Integer.valueOf(1).equals(modelStatus)) {
            throw new BusinessException(400, "模型未启用，不能设为默认模型");
        }
        if (!Integer.valueOf(1).equals(apiConfig.getStatus())) {
            throw new BusinessException(400, "API 配置未启用，不能设为默认模型");
        }
    }

    private void validateUniqueCode(Long currentId, Long apiConfigId, String code) {
        if (StrUtil.isBlank(code)) {
            return;
        }
        LambdaQueryWrapper<AiModel> wrapper = new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getCode, code);
        if (apiConfigId != null) {
            wrapper.eq(AiModel::getApiConfigId, apiConfigId);
        } else {
            wrapper.isNull(AiModel::getApiConfigId);
        }
        if (currentId != null) {
            wrapper.ne(AiModel::getId, currentId);
        }
        if (aiModelMapper.exists(wrapper)) {
            throw new BusinessException(400,
                    apiConfigId != null ? "同一 API 配置下模型标识已存在" : "未绑定 API 配置的模型标识已存在");
        }
    }

    private void throwDuplicateCodeException(Long apiConfigId, DuplicateKeyException e) {
        throw new BusinessException(400,
                apiConfigId != null ? "同一 API 配置下模型标识已存在" : "未绑定 API 配置的模型标识已存在");
    }

    private void normalizeMetadata(AiModel model) {
        if (model == null) {
            return;
        }
        normalizeReasoningEffortLevels(model);
        model.setModelProtocol(aiModelMetadataResolver.normalizeProtocol(model.getModelProtocol()));
        model.setCapabilityPresetCode(normalizeCapabilityPresetCode(model.getCapabilityPresetCode()));
        AiModelMultimodalCapabilities.normalizeModel(model);
    }

    private void normalizeReasoningEffortLevels(AiModel model) {
        if (!Boolean.TRUE.equals(model.getSupportReasoning())) {
            model.setReasoningEffortLevels(List.of());
            return;
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (model.getReasoningEffortLevels() != null) {
            model.getReasoningEffortLevels().stream()
                    .filter(StrUtil::isNotBlank)
                    .map(String::trim)
                    .forEach(normalized::add);
        }
        model.setReasoningEffortLevels(List.copyOf(normalized));
    }

    private String normalizeCapabilityPresetCode(String code) {
        return StrUtil.isBlank(code) ? null : code.trim();
    }

    /** ComfyUI 音色设计与各渠道的音频生成共用模型类型，只有前者使用内置工作流。 */
    private void prepareModelConfiguration(AiModel model, ApiConfig apiConfig) {
        apiConfigService.validateModelType(apiConfig, model.getModelType());
        normalizeMetadata(model);
        if (ComfyUiVoiceDesign.isModelCandidate(model.getModelType(), model.getCode(), model.getModelProtocol(), apiConfig)) {
            validateVoiceModel(model, apiConfig);
            return;
        }
        validateRequestProtocol(model, apiConfig);
        validateCapabilityPreset(model);
        validateComfyUiBinding(model, apiConfig);
    }

    /** 校验 Qwen 音色设计模型及其 ComfyUI 服务绑定。 */
    private void validateVoiceModel(AiModel model, ApiConfig api) {
        if (api == null || !Integer.valueOf(1).equals(api.getStatus()) || !PLATFORM.equals(api.getPlatform())) {
            throw new BusinessException(400, "参考音频模型必须绑定已启用的 ComfyUI API 配置");
        }
        if (!PLATFORM.equals(model.getModelProtocol())) {
            throw new BusinessException(400, "参考音频模型的请求协议必须为 comfyui");
        }
        if (!MODEL_CODE.equals(model.getCode())) {
            throw new BusinessException(400, "参考音频模型标识必须为 " + MODEL_CODE);
        }
        if (model.getComfyuiWorkflowId() != null || model.getCapabilityPresetCode() != null) {
            throw new BusinessException(400, "参考音频模型使用内置音色工作流，不能绑定工作流或能力预设");
        }
        if (!model.getMultimodalInputTypes().isEmpty()) {
            throw new BusinessException(400, "参考音频模型不支持多模态输入");
        }
    }

    private void validateCapabilityPreset(AiModel model) {
        if (model == null || StrUtil.isBlank(model.getCapabilityPresetCode())) {
            return;
        }
        var preset = modelPresetService.getPreset(model.getCapabilityPresetCode());
        if (preset == null) {
            throw new BusinessException(400, "模型能力预设不存在: " + model.getCapabilityPresetCode());
        }
        Integer presetModelType = preset.getInt("modelType");
        if (presetModelType != null && !presetModelType.equals(model.getModelType())) {
            throw new BusinessException(400, "模型能力预设与模型类型不匹配");
        }
        if (StrUtil.isNotBlank(preset.getStr("providerId"))) {
            var config = apiConfigService.getById(model.getApiConfigId());
            if (!preset.getStr("providerId").equals(config.getProviderId())
                    || !preset.getStr("modelProtocol").equals(aiModelMetadataResolver.resolve(model, config).modelProtocol())) {
                throw new BusinessException(400, "赞助平台能力预设与当前提供商或请求协议不匹配");
            }
        }
    }

    /** 校验有效请求协议，包括 Google 音频的显式渠道协议和本机 Codex 的文本限制。 */
    private void validateRequestProtocol(AiModel model, ApiConfig apiConfig) {
        var metadata = aiModelMetadataResolver.resolve(model, apiConfig);
        if (metadata.usesLocalCodex()) {
            validateCodexModel(model, metadata);
        }
        apiConfigService.validateModelProtocol(apiConfig, metadata.modelProtocol());
        if (Integer.valueOf(MODEL_TYPE_AUDIO).equals(model.getModelType())) {
            if (GoogleModelFamily.googlePlatform(metadata.platform())
                    && !metadata.normalizedPlatform().equals(metadata.modelProtocol())) {
                throw new BusinessException("请选择与当前 Google 渠道对应的请求协议");
            }
            return;
        }
        if (StrUtil.isBlank(metadata.modelProtocol()) || "generic".equals(metadata.modelProtocol())) {
            String capability = switch (model.getModelType()) {
                case 1 -> "文本";
                case 2 -> "图片";
                case 3 -> "视频";
                default -> "当前";
            };
            throw new BusinessException(400, capability
                    + "模型未配置有效请求协议：请在模型中设置覆盖协议，或在 API 配置中设置对应的默认协议");
        }
    }

    /** 校验本机 Codex 的模型类型和图片输入协议。 */
    private void validateCodexModel(AiModel model, AiModelMetadata metadata) {
        apiConfigService.requireLocalCodex();
        if (!Integer.valueOf(MODEL_TYPE_TEXT).equals(model.getModelType())
                || !CODEX.equals(metadata.normalizedPlatform()) || !CODEX.equals(metadata.modelProtocol())) {
            throw new BusinessException(400, "Codex 接入必须绑定使用 Codex 协议的文本模型");
        }
        var inputTypes = model.getMultimodalInputTypes();
        if (!inputTypes.isEmpty() && (!List.of(TYPE_IMAGE).equals(inputTypes)
                || !List.of(TRANSPORT_BASE64).equals(model.getMultimodalInputTransports().get(TYPE_IMAGE)))) {
            throw new BusinessException(400, "Codex 仅支持 Base64 图片输入");
        }
    }

    private void validateComfyUiBinding(AiModel model, ApiConfig apiConfig) {
        if (apiConfig == null) {
            return;
        }
        if (!ComfyUiWorkflowService.PLATFORM.equalsIgnoreCase(apiConfig.getPlatform())) {
            model.setComfyuiWorkflowId(null);
            return;
        }
        comfyUiWorkflowService.validateModelBinding(model, apiConfig);
        generationCapabilities.getMergedModelConfig(model);
    }

    private void clearOtherDefaults(Integer modelType, Long excludeId) {
        if (modelType == null || excludeId == null) {
            return;
        }
        aiModelMapper.update(null, new LambdaUpdateWrapper<AiModel>()
                .set(AiModel::getDefaultModel, false)
                .eq(AiModel::getDefaultModel, true)
                .eq(AiModel::getModelType, modelType)
                .ne(AiModel::getId, excludeId));
    }

}
