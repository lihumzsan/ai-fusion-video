package com.stonewu.fusion.service.ai;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.controller.ai.vo.AiModelConnectivityRespVO;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.entity.ai.ApiConfig;
import com.stonewu.fusion.mapper.ai.AiModelMapper;
import com.stonewu.fusion.service.ai.comfyui.ComfyUiWorkflowService;
import com.stonewu.fusion.service.ai.model.AiModelMetadataResolver;
import com.stonewu.fusion.service.generation.GenerationModelCapabilityService;
import org.junit.jupiter.api.BeforeEach;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class AiModelServiceTests {

    @BeforeAll
    static void initMybatisPlusTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiModel.class);
    }

    @Mock
    private AiModelMapper aiModelMapper;

    @Mock
    private ApiConfigService apiConfigService;

    @Mock
    private ModelPresetService modelPresetService;

    @Mock
    private ChatModelFactory chatModelFactory;

    @Mock
    private ComfyUiWorkflowService comfyUiWorkflowService;

    @Mock
    private GenerationModelCapabilityService generationCapabilities;

    private AiModelService aiModelService;

    @BeforeEach
    void setUp() {
        aiModelService = new AiModelService(
                aiModelMapper,
                apiConfigService,
                modelPresetService,
                new AiModelMetadataResolver(apiConfigService),
                chatModelFactory,
                comfyUiWorkflowService,
                generationCapabilities
        );
    }

    @Test
    void createAiModelShouldClearOtherDefaultsWhenSavedAsDefault() {
        AiModel model = AiModel.builder()
                .name("Flux Kontext")
                .code("flux-kontext")
                .modelType(2)
                .apiConfigId(1L)
                .defaultModel(true)
                .build();
        when(apiConfigService.getById(1L)).thenReturn(ApiConfig.builder()
                .id(1L)
                .name("OpenAI Compatible")
                .imageProtocol("openai")
                .build());
        doAnswer(invocation -> {
            AiModel inserted = invocation.getArgument(0);
            inserted.setId(101L);
            return 1;
        }).when(aiModelMapper).insert(model);

        Long id = aiModelService.createAiModel(model);

        assertEquals(101L, id);
        verifyClearOtherDefaults(2, 101L);
    }

    @ParameterizedTest
    @CsvSource({"0, 1, 模型未启用", "1, 0, API 配置未启用"})
    void creatingAnUnavailableDefaultPreservesTheExistingDefault(int modelStatus, int apiStatus, String error) {
        var candidate = AiModel.builder().name("候选模型").code("chat").modelType(1).apiConfigId(1L)
                .defaultModel(true).status(modelStatus).build();
        when(apiConfigService.getById(1L)).thenReturn(ApiConfig.builder().id(1L).textProtocol("openai")
                .status(apiStatus).build());

        assertThatThrownBy(() -> aiModelService.createAiModel(candidate)).hasMessageContaining(error);
        verify(aiModelMapper, never()).insert(any(AiModel.class));
        verify(aiModelMapper, never()).update(isNull(), any());
    }

    @Test
    void codexTextModelCanBecomeTheDefaultAssistantModel() {
        AiModel model = AiModel.builder().id(202L).name("Codex").code("gpt-6.1-sol").modelType(1)
                .apiConfigId(1L).defaultModel(false).build();
        when(aiModelMapper.selectById(202L)).thenReturn(model);
        when(apiConfigService.getById(1L)).thenReturn(ApiConfig.builder()
                .platform("codex").textProtocol("codex").build());

        aiModelService.updateAiModel(202L, null, null, null, null, null, null, null, null, null,
                null, true, null, null, null, null, null, null, null, null);

        assertThat(model.getDefaultModel()).isTrue();
        verify(aiModelMapper).update(eq(model), any());
        verifyClearOtherDefaults(1, 202L);
    }

    @Test
    void codexTextModelRejectsImageUrlTransport() {
        AiModel model = AiModel.builder().name("Codex").code("gpt-6.1-sol").modelType(1)
                .apiConfigId(1L).multimodalInputTypes(List.of("image"))
                .multimodalInputTransports(Map.of("image", List.of("url"))).build();
        when(apiConfigService.getById(1L)).thenReturn(ApiConfig.builder()
                .platform("codex").textProtocol("codex").build());

        assertThatThrownBy(() -> aiModelService.createAiModel(model))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Base64");
        verify(aiModelMapper, never()).insert(any(AiModel.class));
    }

    @Test
    void qwenVoiceDesignCanBeCreatedWithoutImportedWorkflow() {
        var model = AiModel.builder().name("Qwen 音色设计").code("qwen3-tts-voice-design-1.7b")
                .modelType(4).modelProtocol("comfyui").apiConfigId(1L).build();
        when(apiConfigService.getById(1L)).thenReturn(ApiConfig.builder().platform("comfyui").status(1).build());
        doAnswer(invocation -> {
            model.setId(101L);
            return 1;
        }).when(aiModelMapper).insert(model);

        assertThat(aiModelService.createAiModel(model)).isEqualTo(101L);
        assertThat(model.getComfyuiWorkflowId()).isNull();
        verifyNoInteractions(comfyUiWorkflowService, generationCapabilities);
    }

    @Test
    void voiceDesignRejectsUnusableBindingsBeforeSaving() {
        var model = AiModel.builder().name("Qwen 音色设计").code("qwen3-tts-voice-design-1.7b")
                .modelType(4).modelProtocol("comfyui").apiConfigId(1L).build();
        var api = ApiConfig.builder().platform("sonilo").status(1).build();
        when(apiConfigService.getById(1L)).thenReturn(api);

        assertThatThrownBy(() -> aiModelService.createAiModel(model)).hasMessageContaining("ComfyUI");
        api.setPlatform("comfyui");
        api.setStatus(0);
        assertThatThrownBy(() -> aiModelService.createAiModel(model)).hasMessageContaining("启用");
        api.setStatus(1);
        model.setModelProtocol("openai_compatible");
        assertThatThrownBy(() -> aiModelService.createAiModel(model)).hasMessageContaining("请求协议");
        model.setModelProtocol("comfyui");
        model.setCode("other-voice-model");
        assertThatThrownBy(() -> aiModelService.createAiModel(model)).hasMessageContaining("qwen3-tts-voice-design-1.7b");
        model.setCode("qwen3-tts-voice-design-1.7b");
        model.setComfyuiWorkflowId(44L);
        assertThatThrownBy(() -> aiModelService.createAiModel(model)).hasMessageContaining("工作流");
        verify(aiModelMapper, never()).insert(any(AiModel.class));
    }

    @ParameterizedTest
    @CsvSource({"gemini, gemini", "vertex_ai, vertex_ai", "vertex_ai_api_key, vertex_ai"})
    void googleAudioUsesItsChannelProtocolWithoutComfyuiBinding(String platform, String protocol) {
        var model = AiModel.builder().name("Gemini TTS").code("gemini-2.5-flash-preview-tts")
                .modelType(4).modelProtocol(protocol.equals("gemini") ? "vertex_ai" : "gemini")
                .apiConfigId(1L).build();
        when(apiConfigService.getById(1L)).thenReturn(ApiConfig.builder().platform(platform).build());

        assertThatThrownBy(() -> aiModelService.createAiModel(model)).hasMessageContaining("Google 渠道");
        verify(aiModelMapper, never()).insert(any(AiModel.class));

        model.setModelProtocol(protocol);
        aiModelService.createAiModel(model);
        verify(aiModelMapper).insert(model);
        verifyNoInteractions(comfyUiWorkflowService, generationCapabilities);
        assertThatThrownBy(() -> aiModelService.requireAgentModel(model)).hasMessageContaining("不能用于助手对话");
    }

    @Test
    void existingVoiceAndVideoModelsCannotConvertIntoEachOther() {
        var voice = AiModel.builder().id(12L).modelType(4).modelProtocol("comfyui")
                .code("qwen3-tts-voice-design-1.7b").build();
        var video = AiModel.builder().id(13L).modelType(3).apiConfigId(1L).build();
        when(apiConfigService.getById(1L)).thenReturn(ApiConfig.builder().platform("comfyui").build());
        when(apiConfigService.getById(2L)).thenReturn(ApiConfig.builder().platform("gemini").build());
        when(aiModelMapper.selectById(12L)).thenReturn(voice);
        when(aiModelMapper.selectById(13L)).thenReturn(video);

        assertThatThrownBy(() -> aiModelService.updateAiModel(12L, null, null, null, null, 3, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null))
                .hasMessageContaining("不能与其他模型类型互相转换");
        assertThatThrownBy(() -> aiModelService.updateAiModel(13L, null, null, null, null, 4, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null))
                .hasMessageContaining("不能与其他模型类型互相转换");
        assertThatThrownBy(() -> aiModelService.updateAiModel(12L, null, "gemini-2.5-flash-preview-tts", "gemini",
                null, 4, null, null, null, null, null, null, 2L, null, null, null, null, null, null, null))
                .hasMessageContaining("不能与其他模型类型互相转换");
        assertThatThrownBy(() -> aiModelService.requireAgentModel(voice)).hasMessageContaining("生成参考音频");
        verify(aiModelMapper, never()).update(any(AiModel.class), any());
    }

    @Test
    void assistantListAndDirectSelectionIncludeCodexTextModels() {
        AiModel codex = AiModel.builder().code("gpt-6.1-sol").modelType(1)
                .modelProtocol("codex").apiConfigId(12L).build();
        AiModel cloud = AiModel.builder().code("chat-model").modelType(1).apiConfigId(34L).build();
        when(aiModelMapper.selectList(any())).thenReturn(List.of(codex, cloud));
        when(apiConfigService.isLocalCodexAvailable()).thenReturn(true);

        assertThat(aiModelService.getListByType(1)).containsExactly(codex, cloud);
        aiModelService.requireAgentModel(codex);
    }

    @Test
    void assistantListExcludesCodexModelsOutsideLocalEnvironment() {
        AiModel codex = AiModel.builder().code("gpt-6.1-sol").modelType(1)
                .modelProtocol("codex").build();
        AiModel inheritedCodex = AiModel.builder().code("gpt-6.1-sol").modelType(1).apiConfigId(12L).build();
        AiModel cloud = AiModel.builder().code("chat-model").modelType(1).apiConfigId(34L).build();
        when(aiModelMapper.selectList(any())).thenReturn(List.of(codex, inheritedCodex, cloud));
        when(apiConfigService.getIdsByPlatform("codex")).thenReturn(Set.of(12L));

        assertThat(aiModelService.getListByType(1)).containsExactly(cloud);
    }

    @Test
    void updateAiModelShouldClearOtherDefaultsWhenMarkedAsDefault() {
        AiModel existing = AiModel.builder()
                .id(202L)
                .name("Wan 2.2")
                .code("wan-2.2")
                .modelType(3)
                .apiConfigId(1L)
                .defaultModel(false)
                .status(0)
                .build();
        when(aiModelMapper.selectById(202L)).thenReturn(existing);
        when(apiConfigService.getById(2L)).thenReturn(ApiConfig.builder()
                .id(2L)
                .videoProtocol("dashscope")
                .build());

        aiModelService.updateAiModel(
                202L,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                1,
                null,
                true,
                2L,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertTrue(existing.getDefaultModel());
        verify(aiModelMapper).update(eq(existing), any());
        verify(chatModelFactory).evict(202L);
        verifyClearOtherDefaults(3, 202L);
    }

    @ParameterizedTest
    @CsvSource({"0, 1, 模型未启用", "1, 0, API 配置未启用", "1, , API 配置不存在"})
    void promotingAnUnavailableDefaultPreservesTheExistingDefault(int modelStatus, Integer apiStatus, String error) {
        var candidate = AiModel.builder().id(202L).name("候选模型").code("chat").modelType(1)
                .modelProtocol("openai").apiConfigId(1L).defaultModel(false).build();
        when(aiModelMapper.selectById(202L)).thenReturn(candidate);
        if (apiStatus != null) {
            when(apiConfigService.getById(1L)).thenReturn(ApiConfig.builder().id(1L).textProtocol("openai")
                    .status(apiStatus).build());
        }

        assertThatThrownBy(() -> aiModelService.updateAiModel(202L, null, null, null, null, null, null, null, null,
                modelStatus, null, true, null, null, null, null, null, null, null, null)).hasMessageContaining(error);
        verify(aiModelMapper, never()).update(any(AiModel.class), any());
        verify(aiModelMapper, never()).update(isNull(), any());
    }

    @ParameterizedTest
    @CsvSource({"0, , , 1, 模型未启用", ", 2, , 0, API 配置未启用", ", , 2, 0, API 配置未启用"})
    void changingADefaultModelRequiresTheEffectiveConfigurationToBeUsable(
            Integer modelStatus, Long apiConfigId, Integer modelType, int apiStatus, String error) {
        var current = AiModel.builder().id(202L).name("默认模型").code("chat").modelType(1)
                .modelProtocol("openai").apiConfigId(1L).defaultModel(true).build();
        when(aiModelMapper.selectById(202L)).thenReturn(current);
        long effectiveApiId = apiConfigId != null ? apiConfigId : current.getApiConfigId();
        when(apiConfigService.getById(effectiveApiId)).thenReturn(ApiConfig.builder().id(effectiveApiId)
                .textProtocol("openai").status(apiStatus).build());

        assertThatThrownBy(() -> aiModelService.updateAiModel(202L, null, null, null, null, modelType, null,
                null, null, modelStatus, null, null, apiConfigId, null, null, null, null, null, null, null))
                .hasMessageContaining(error);
        verify(aiModelMapper, never()).update(any(AiModel.class), any());
        verify(aiModelMapper, never()).update(isNull(), any());
    }

    @Test
    void clearingTheDefaultAllowsDisablingTheModel() {
        var current = AiModel.builder().id(202L).name("默认模型").code("chat").modelType(1)
                .modelProtocol("openai").apiConfigId(1L).defaultModel(true).build();
        when(aiModelMapper.selectById(202L)).thenReturn(current);
        when(apiConfigService.getById(1L)).thenReturn(ApiConfig.builder().id(1L).status(0).build());

        aiModelService.updateAiModel(202L, null, null, null, null, null, null, null, null,
                0, null, false, null, null, null, null, null, null, null, null);

        assertThat(current.getStatus()).isZero();
        assertThat(current.getDefaultModel()).isFalse();
        verify(aiModelMapper).update(eq(current), any());
        verify(aiModelMapper, never()).update(isNull(), any());
    }

    @Test
    void createAiModelShouldNormalizeReasoningEffortLevelsInDeclaredOrder() {
        AiModel model = AiModel.builder()
                .name("Reasoning model")
                .code("reasoning-model")
                .modelType(1)
                .apiConfigId(1L)
                .supportReasoning(true)
                .reasoningEffortLevels(java.util.List.of(" max ", "", "high", "max", "low"))
                .build();
        when(apiConfigService.getById(1L)).thenReturn(ApiConfig.builder()
                .id(1L)
                .textProtocol("openai_compatible")
                .build());

        aiModelService.createAiModel(model);

        assertEquals(java.util.List.of("max", "high", "low"), model.getReasoningEffortLevels());
    }

    @Test
    void testTextModelConnectivityShouldReturnResponseText() {
        AiModel model = AiModel.builder()
                .id(303L)
                .name("Gemini Flash")
                .code("gemini-2.5-flash")
                .modelType(1)
                .build();
        when(aiModelMapper.selectById(303L)).thenReturn(model);
        when(chatModelFactory.checkConnectivity(model, "Connectivity test. Reply with OK only.")).thenReturn("OK");

        AiModelConnectivityRespVO result = aiModelService.testTextModelConnectivity(303L);

        assertEquals(303L, result.getModelId());
        assertEquals("Gemini Flash", result.getModelName());
        assertEquals("OK", result.getResponseText());
        assertThat(result.getDurationMs()).isNotNull().isGreaterThanOrEqualTo(0L);
        assertThat(result.getTestedAt()).isNotNull();
    }

    @Test
    void testTextModelConnectivityShouldRejectNonTextModel() {
        AiModel model = AiModel.builder()
                .id(304L)
                .name("Flux Kontext")
                .code("flux-kontext")
                .modelType(2)
                .build();
        when(aiModelMapper.selectById(304L)).thenReturn(model);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> aiModelService.testTextModelConnectivity(304L));

        assertEquals(400, exception.getCode());
        assertEquals("仅支持文本模型连通性检测", exception.getMessage());
    }

    @Test
    void createAiModelShouldNotSelectCapabilityPresetFromModelCode() {
        AiModel model = AiModel.builder()
                .name("GPT Image 1")
                .code("gpt-image-1")
                .modelProtocol("openai")
                .modelType(2)
                .apiConfigId(1L)
                .build();
        when(apiConfigService.getById(1L)).thenReturn(ApiConfig.builder()
                .id(1L)
                .platform("openai_compatible")
                .imageProtocol("openai")
                .build());
        doAnswer(invocation -> {
            AiModel inserted = invocation.getArgument(0);
            inserted.setId(500L);
            return 1;
        }).when(aiModelMapper).insert(model);

        aiModelService.createAiModel(model);

        assertNull(model.getCapabilityPresetCode());
    }

        @Test
        void createAiModelShouldNormalizeExplicitMetadata() {
                AiModel model = AiModel.builder()
                                .name("Kling via NewAPI")
                                .code("kling-v1")
                                .capabilityPresetCode(" agnes-video-v2.0 ")
                                .modelProtocol(" Sora ")
                                .modelType(3)
                                .apiConfigId(1L)
                                .build();

                when(apiConfigService.getById(1L)).thenReturn(ApiConfig.builder().id(1L).platform("newapi").build());
                when(modelPresetService.getPreset("agnes-video-v2.0")).thenReturn(JSONUtil.createObj()
                                .set("modelType", 3)
                                .set("modelProtocol", "sora"));
                doAnswer(invocation -> {
                        AiModel inserted = invocation.getArgument(0);
                        inserted.setId(501L);
                        return 1;
                }).when(aiModelMapper).insert(model);

                aiModelService.createAiModel(model);

                assertEquals("agnes-video-v2.0", model.getCapabilityPresetCode());
                assertEquals("sora", model.getModelProtocol());
        }

    private void verifyClearOtherDefaults(int expectedModelType, long excludeId) {
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<LambdaUpdateWrapper> wrapperCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);

        verify(aiModelMapper).update(isNull(), wrapperCaptor.capture());

        LambdaUpdateWrapper<?> wrapper = wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSet()).contains("default_model");
        assertThat(wrapper.getSqlSegment()).contains("default_model")
                .contains("model_type")
                .contains("id");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(expectedModelType, excludeId, true, false);
    }

    @Test
    void conflictingWorkflowLimitsCannotBeSavedEvenWithoutFrontendValidation() {
        var model = AiModel.builder().name("工作流").code("local").modelType(2).apiConfigId(1L)
                .comfyuiWorkflowId(2L).config("{\"maxReferenceImages\":2}").build();
        when(apiConfigService.getById(1L)).thenReturn(ApiConfig.builder().id(1L).platform("comfyui")
                .imageProtocol("comfyui").build());
        when(generationCapabilities.getMergedModelConfig(model)).thenThrow(new BusinessException(400, "使用上限与工作流冲突"));
        assertThrows(BusinessException.class, () -> aiModelService.createAiModel(model));
        verify(aiModelMapper, never()).insert(model);
    }
}
