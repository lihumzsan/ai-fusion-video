package com.stonewu.fusion.service.canvas;

import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.entity.ai.ApiConfig;
import com.stonewu.fusion.service.ai.AiModelService;
import com.stonewu.fusion.service.ai.ApiConfigService;
import com.stonewu.fusion.service.ai.comfyui.ComfyUiWorkflowService;
import com.stonewu.fusion.service.canvas.capability.CanvasModelCatalog;
import com.stonewu.fusion.service.generation.GenerationModelCapabilityService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CanvasModelCatalogTests {
    private final AiModelService models = mock(AiModelService.class);
    private final ApiConfigService apis = mock(ApiConfigService.class);
    private final GenerationModelCapabilityService capabilities = mock(GenerationModelCapabilityService.class);
    private final CanvasModelCatalog catalog = new CanvasModelCatalog(models, apis, capabilities,
            mock(ComfyUiWorkflowService.class), new ObjectMapper());

    @Test void exposesOnlyDeclaredInputConstraintsWithoutCredentialsOrRawConfig() throws Exception {
        AiModel model = model();
        ApiConfig api = new ApiConfig(); api.setId(10L); api.setStatus(1); api.setApiKey("test-only-sensitive-value");
        when(apis.getById(10L)).thenReturn(api);
        when(models.getEnabledList()).thenReturn(List.of(model));
        when(capabilities.buildImageCapabilitySnapshot(model)).thenReturn(JSONUtil.createObj()
                .set("supportedAspectRatios", List.of("1:1", "16:9")).set("apiKey", "must-not-project"));
        when(capabilities.resolveImageCapability(model)).thenReturn(new GenerationModelCapabilityService.ImageModelCapability(
                true, 0, 4, List.of("url"), true, false));
        var result = catalog.list();
        assertThat(result.getFirst().inputs().get("referenceImages").maximum()).isEqualTo(4);
        assertThat(result.getFirst().parameters().get("supportedAspectRatios")).isEqualTo(List.of("1:1", "16:9"));
        String serialized = new ObjectMapper().writeValueAsString(result);
        assertThat(serialized).doesNotContain("test-only-sensitive-value", "must-not-project", "raw-config-secret", "apiKey");
    }

    @Test void readsSupplierAvailabilityAgainOnEveryValidation() {
        AiModel model = model();
        when(models.getById(1L)).thenReturn(model);
        ApiConfig api = new ApiConfig(); api.setId(10L); api.setStatus(0);
        when(apis.getById(10L)).thenReturn(api);
        assertThatThrownBy(() -> catalog.require(1)).hasMessageContaining("停用");
        api.setStatus(1);
        when(capabilities.buildImageCapabilitySnapshot(model)).thenReturn(JSONUtil.createObj());
        when(capabilities.resolveImageCapability(model)).thenReturn(new GenerationModelCapabilityService.ImageModelCapability(
                false, 0, 0, List.of(), false, false));
        assertThat(catalog.require(1).inputs()).doesNotContainKey("referenceImages");
        model.setStatus(0);
        assertThatThrownBy(() -> catalog.require(1)).hasMessageContaining("停用");
    }

    @Test void audioPickerExposesOnlyNamesAndRejectsDisabledOrWrongProviders() throws Exception {
        ApiConfig api = new ApiConfig(); api.setId(12L); api.setName("配乐服务");
        api.setPlatform("sonilo"); api.setStatus(1); api.setApiKey("test-only-audio-key");
        when(apis.getListByPlatform("sonilo")).thenReturn(List.of(api));
        when(apis.getById(12L)).thenReturn(api);
        assertThat(catalog.audioServices()).containsExactly(new CanvasModelCatalog.AudioService(12, "配乐服务"));
        assertThat(new ObjectMapper().writeValueAsString(catalog.audioServices())).doesNotContain("test-only-audio-key");
        catalog.requireAudio(12);
        api.setStatus(0);
        assertThatThrownBy(() -> catalog.requireAudio(12)).hasMessageContaining("Sonilo");
        api.setStatus(1); api.setPlatform("openai");
        assertThatThrownBy(() -> catalog.requireAudio(12)).hasMessageContaining("Sonilo");
    }

    @Test
    void voicePickerUsesComfyUiServicesAndRejectsOtherOrDisabledConnections() throws Exception {
        var comfy = ApiConfig.builder().id(21L).name("音色工作站").platform("comfyui").status(1)
                .apiKey("voice-test-only-token").build();
        var sonilo = ApiConfig.builder().id(22L).name("配乐").platform("sonilo").status(1).build();
        when(apis.getListByPlatform("comfyui")).thenReturn(List.of(comfy));
        when(apis.getListByPlatform("sonilo")).thenReturn(List.of(sonilo));
        when(apis.getById(21L)).thenReturn(comfy);
        when(apis.getById(22L)).thenReturn(sonilo);

        assertThat(catalog.voiceServices()).containsExactly(new CanvasModelCatalog.AudioService(21, "音色工作站"));
        assertThat(catalog.audioServices()).containsExactly(new CanvasModelCatalog.AudioService(22, "配乐"));
        assertThat(new ObjectMapper().writeValueAsString(catalog.voiceServices()))
                .doesNotContain("voice-test-only-token");
        assertThat(catalog.requireVoice(21)).isSameAs(comfy);
        assertThatThrownBy(() -> catalog.requireVoice(22)).hasMessageContaining("ComfyUI");
        comfy.setStatus(0);
        assertThatThrownBy(() -> catalog.requireVoice(21)).hasMessageContaining("ComfyUI");
    }

    @Test
    void codexIsAvailableToCanvasAgentsOnTheLocalBackend() {
        var model = AiModel.builder().id(25L).name("本机文本模型").status(1).modelType(1).modelProtocol("codex")
                .code("codex-model").apiConfigId(26L).build();
        var api = ApiConfig.builder().id(26L).status(1).platform("codex").build();
        when(models.getById(25L)).thenReturn(model);
        when(apis.getById(26L)).thenReturn(api);
        when(apis.isLocalCodexAvailable()).thenReturn(true, false);

        CanvasModelCatalog.requireAgentModel(catalog.require(25));
        assertThatThrownBy(() -> catalog.require(25)).hasMessageContaining("仅在本机环境可用");
    }

    @Test
    void voiceModelsRequireTheRegisteredProtocolAndFreezeTheirOwnConnection() throws Exception {
        var voice = AiModel.builder().id(31L).name("音色模型").status(1).modelType(4).modelProtocol("comfyui")
                .code("qwen3-tts-voice-design-1.7b").apiConfigId(21L).build();
        var comfy = ApiConfig.builder().id(21L).status(1).platform("comfyui").apiKey("voice-model-test-token").build();
        var alternate = ApiConfig.builder().id(22L).status(1).platform("comfyui").build();
        when(models.getEnabledList()).thenReturn(List.of(voice));
        when(models.getById(31L)).thenReturn(voice);
        when(apis.getById(21L)).thenReturn(comfy);
        when(apis.getById(22L)).thenReturn(alternate);

        assertThat(catalog.list()).singleElement().satisfies(option -> {
            assertThat(option.kind()).isEqualTo("voice");
            assertThat(option.resultType()).isEqualTo("audio");
            assertThat(option.versions()).isEmpty();
            assertThat(option.unavailable()).isNull();
        });
        assertThat(new ObjectMapper().writeValueAsString(catalog.list())).doesNotContain("voice-model-test-token");
        assertThatThrownBy(() -> CanvasModelCatalog.requireAgentModel(catalog.require(31))).hasMessageContaining("文本模型");
        var original = catalog.requireVoiceModel(31);
        assertThat(original.api()).isSameAs(comfy);
        String fingerprint = catalog.voiceModelFingerprint(original.model());
        voice.setName("更新显示名称");
        assertThat(catalog.voiceModelFingerprint(catalog.requireVoiceModel(31).model())).isEqualTo(fingerprint);
        voice.setApiConfigId(22L);
        var updated = catalog.requireVoiceModel(31);
        assertThat(updated.api()).isSameAs(alternate);
        assertThat(catalog.voiceModelFingerprint(updated.model())).isNotEqualTo(fingerprint);
        alternate.setStatus(0);
        assertThatThrownBy(() -> catalog.requireVoiceModel(31)).hasMessageContaining("绑定的 ComfyUI 服务已停用");
        alternate.setStatus(1);
        voice.setModelProtocol("openai");
        assertThatThrownBy(() -> catalog.requireVoiceModel(31)).hasMessageContaining("Qwen3-TTS");
        voice.setModelProtocol("comfyui");
        voice.setCode("different-voice-model");
        assertThatThrownBy(() -> catalog.requireVoiceModel(31)).hasMessageContaining("Qwen3-TTS");
    }

    private AiModel model() {
        AiModel model = new AiModel(); model.setId(1L); model.setName("测试图片模型"); model.setModelType(2);
        model.setStatus(1); model.setApiConfigId(10L); model.setModelProtocol("openai");
        model.setConfig("{\"apiKey\":\"raw-config-secret\"}");
        return model;
    }
}
