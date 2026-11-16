package com.stonewu.fusion.service.ai.comfyui;

import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.entity.ai.ApiConfig;
import com.stonewu.fusion.entity.ai.ComfyUiWorkflow;
import com.stonewu.fusion.entity.ai.ComfyUiWorkflowVersion;
import com.stonewu.fusion.service.ai.ApiConfigService;
import com.stonewu.fusion.service.ai.comfyui.client.ComfyUiNativeClient;
import com.stonewu.fusion.service.canvas.CanvasJson;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ComfyUiH3RefProfileTests {

    private final ObjectMapper json = new ObjectMapper();
    private final ComfyUiWorkflowDocumentService documents = new ComfyUiWorkflowDocumentService(json);
    private final ComfyUiWorkflowRenderer renderer = new ComfyUiWorkflowRenderer(json, documents);

    @Test
    void buildsBothConditioningPassesWithOrderedImagesAndAudiosAtRequestedDuration() throws IOException {
        var version = h3Version();
        var result = renderer.render(3, version, Map.of(
                "prompt", "H3 test prompt", "seed", 91, "ratio", "9:16", "duration", 15,
                "referenceImages", IntStream.rangeClosed(1, 9).mapToObj(i -> "image" + i + ".png").toList(),
                "referenceAudios", List.of("audio1.wav", "audio2.wav", "audio3.wav")));

        assertThat(result.at("/6/inputs/image").asText()).isEqualTo("image1.png");
        assertThat(result.at("/67/inputs/image").asText()).isEqualTo("image9.png");
        assertThat(result.at("/71/inputs/audio").asText()).isEqualTo("audio3.wav");
        for (String nodeId : List.of("7", "14")) {
            assertThat(result.at("/" + nodeId + "/inputs/ref_images.ref_image_8/0").asText()).isEqualTo("67");
            assertThat(result.at("/" + nodeId + "/inputs/ref_audios.ref_audio_2/0").asText()).isEqualTo("71");
            assertThat(result.at("/" + nodeId + "/inputs/length").asInt()).isEqualTo(362);
        }
        assertThat(result.at("/7/inputs/width").asInt()).isEqualTo(512);
        assertThat(result.at("/13/inputs/target_height").asInt()).isEqualTo(1120);
        assertThat(result.at("/55/inputs/width").asInt()).isEqualTo(1088);
        assertThat(result.at("/55/inputs/height").asInt()).isEqualTo(1920);
        assertThat(result.at("/28/inputs/prompt").asText()).isEqualTo("H3 test prompt");
        assertThat(result.at("/11/inputs/noise_seed").asLong()).isEqualTo(91L);
        assertThat(result.at("/168/inputs/format").asText()).isEqualTo("video/h264-mp4");
        assertThat(version.getApiWorkflowJson()).contains("reference-image-00.png");
    }

    @Test
    void usesOneImageWithoutRetainingPlaceholderAudioOrUnconnectedLoaders() throws IOException {
        var result = renderer.render(3, h3Version(), Map.of(
                "referenceImages", List.of("image.png"), "prompt", "H3 test prompt"));

        assertThat(result.has("18")).isFalse();
        assertThat(result.has("60")).isFalse();
        assertThat(result.at("/7/inputs/ref_audios.ref_audio_0").isMissingNode()).isTrue();
        assertThat(result.at("/14/inputs/ref_audios.ref_audio_0").isMissingNode()).isTrue();
        assertThat(result.at("/7/inputs/length").asInt()).isEqualTo(124);
        assertThat(result.at("/7/inputs/audio_mode").asText()).isEqualTo("native");
    }

    @Test
    void rejectsOutOfRangeReferencesAndTimings() throws IOException {
        var version = h3Version();
        assertThatThrownBy(() -> renderer.render(3, version, Map.of()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("1 至 9 张参考图");
        assertThatThrownBy(() -> renderer.render(3, version, Map.of(
                "referenceImages", List.of("image.png"), "referenceAudios", List.of("a", "b", "c", "d"))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("最多支持 3 段参考音频");
        assertThatThrownBy(() -> renderer.render(3, version, Map.of(
                "referenceImages", List.of("image.png"), "duration", 16)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("4 至 15 秒");
        assertThatThrownBy(() -> renderer.render(3, version, Map.of("referenceImages", List.of("image.png"))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("需要填写提示词");
    }

    @Test
    void derivesVariableSlotsAndTimingWithoutFixedBindings() throws IOException {
        var resolver = new ComfyUiWorkflowCapabilities(mock(ComfyUiWorkflowService.class), documents);
        var model = AiModel.builder().modelType(3).build();
        var capabilities = resolver.describe(model, h3Version(), JSONUtil.createObj());

        assertThat(capabilities.getBool("h3Ref")).isTrue();
        assertThat(capabilities.getInt("minImageInputs")).isEqualTo(1);
        assertThat(capabilities.getInt("minReferenceImages")).isEqualTo(1);
        assertThat(capabilities.getInt("maxReferenceImages")).isEqualTo(9);
        assertThat(capabilities.getInt("maxReferenceAudios")).isEqualTo(3);
        assertThat(capabilities.getJSONArray("comfyuiFields").toList(String.class))
                .contains("referenceImages", "referenceAudios", "duration", "ratio", "prompt", "seed");
        assertThat(capabilities.getJSONArray("supportedDurations").toList(Integer.class))
                .containsExactly(4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15);
    }

    @Test
    void renamedOutputStillReceivesDynamicReferenceInputs() throws IOException {
        var version = h3Version();
        var graph = documents.parseApiWorkflow(version.getApiWorkflowJson());
        ((ObjectNode) graph.at("/168/inputs")).put("filename_prefix", "results/new-prefix");
        version.setApiWorkflowJson(graph.toString());

        var result = renderer.render(3, version, Map.of(
                "prompt", "New output name", "referenceImages", List.of("reference.png")));

        assertThat(result.at("/168/inputs/filename_prefix").asText()).isEqualTo("results/new-prefix");
        assertThat(result.at("/6/inputs/image").asText()).isEqualTo("reference.png");
        assertThat(result.at("/28/inputs/prompt").asText()).isEqualTo("New output name");
    }

    @Test
    void unmarkedPublishedGraphKeepsItsFixedBindingsAndDefaultReferences() throws IOException {
        var version = h3Version();
        version.setPublished(true);
        var graph = documents.parseApiWorkflow(version.getApiWorkflowJson());
        ((ObjectNode) graph.at("/168")).remove("_meta");
        version.setApiWorkflowJson(graph.toString());
        version.setInputBindingsJson("""
                {"prompt":[{"nodeId":"28","inputName":"prompt","valueType":"string"}]}
                """);

        var result = renderer.render(3, version, Map.of("prompt", "Keep the saved reference"));

        assertThat(result.at("/28/inputs/prompt").asText()).isEqualTo("Keep the saved reference");
        assertThat(result.at("/6/inputs/image")).isEqualTo(graph.at("/6/inputs/image"));
        assertThat(result.at("/18/inputs/audio")).isEqualTo(graph.at("/18/inputs/audio"));
        var capabilities = new ComfyUiWorkflowCapabilities(mock(ComfyUiWorkflowService.class), documents)
                .describe(AiModel.builder().modelType(3).build(), version, JSONUtil.createObj());
        assertThat(capabilities.containsKey("h3Ref")).isFalse();
        assertThat(capabilities.getJSONArray("comfyuiFields").toList(String.class)).containsExactly("prompt");
    }

    @Test
    void publishedLegacyGraphWithoutFixedBindingsStillUsesDynamicReferences() throws IOException {
        var version = h3Version();
        var graph = documents.parseApiWorkflow(version.getApiWorkflowJson());
        ((ObjectNode) graph.at("/168")).remove("_meta");
        version.setApiWorkflowJson(graph.toString());
        version.setPublished(true);

        var result = renderer.render(3, version, Map.of("prompt", "Dynamic legacy prompt",
                "referenceImages", List.of("legacy-input.png")));

        assertThat(result.at("/6/inputs/image").asText()).isEqualTo("legacy-input.png");
        assertThat(result.at("/28/inputs/prompt").asText()).isEqualTo("Dynamic legacy prompt");
        assertThat(version.getApiWorkflowJson()).isEqualTo(graph.toString());
    }

    @Test
    void newlySavedGenericGraphKeepsItsDefaultsWhenPublished() throws IOException {
        var version = h3Version();
        var graph = documents.parseApiWorkflow(version.getApiWorkflowJson());
        ((ObjectNode) graph.at("/168")).remove("_meta");
        var saved = documents.normalize(3, null, graph.toString(), "{}", version.getOutputBindingsJson());
        version.setApiWorkflowJson(saved.apiWorkflowJson());
        version.setPublished(true);

        var result = renderer.render(3, version, Map.of());

        assertThat(result.at("/6/inputs/image")).isEqualTo(graph.at("/6/inputs/image"));
        assertThat(result.at("/18/inputs/audio")).isEqualTo(graph.at("/18/inputs/audio"));
        assertThat(result.at("/28/inputs/prompt")).isEqualTo(graph.at("/28/inputs/prompt"));
    }

    @Test
    void aPartiallyMatchingPublishedGraphRemainsGeneric() throws IOException {
        var version = h3Version();
        var graph = documents.parseApiWorkflow(version.getApiWorkflowJson());
        ((ObjectNode) graph.at("/168")).remove("_meta");
        ((ObjectNode) graph.at("/14/inputs")).put("task_type", "I2VA");
        version.setApiWorkflowJson(graph.toString());
        version.setPublished(true);

        assertThat(renderer.render(3, version, Map.of())).isEqualTo(graph);
    }

    @Test
    void keepsCustomNodesWhenAllocatingReferenceLoaders() throws IOException {
        var version = h3Version();
        var graph = documents.parseApiWorkflow(version.getApiWorkflowJson());
        graph.putObject("60").put("class_type", "CustomFilter").putObject("inputs");
        graph.putObject("70").put("class_type", "CustomOutput").putObject("inputs")
                .putArray("input").add("60").add(0);
        graph.putObject("72").put("class_type", "CustomAudioOutput").putObject("inputs")
                .putArray("audio").add("18").add(0);
        version.setApiWorkflowJson(graph.toString());

        var result = renderer.render(3, version, Map.of("prompt", "Keep the custom nodes",
                "referenceImages", List.of("image1.png", "image2.png"),
                "referenceAudios", List.of("audio1.wav", "audio2.wav")));

        assertThat(result.path("60")).isEqualTo(graph.path("60"));
        assertThat(result.path("70")).isEqualTo(graph.path("70"));
        assertThat(result.path("18")).isEqualTo(graph.path("18"));
        var imageOnly = renderer.render(3, version, Map.of("prompt", "Keep static audio for the custom output",
                "referenceImages", List.of("image1.png")));
        assertThat(imageOnly.path("18")).isEqualTo(graph.path("18"));
        assertThat(imageOnly.at("/7/inputs/ref_audios.ref_audio_0").isMissingNode()).isTrue();
        String secondImage = result.at("/7/inputs/ref_images.ref_image_1/0").asText();
        String secondAudio = result.at("/7/inputs/ref_audios.ref_audio_1/0").asText();
        assertThat(result.path(secondImage).at("/inputs/image").asText()).isEqualTo("image2.png");
        assertThat(result.path(secondAudio).at("/inputs/audio").asText()).isEqualTo("audio2.wav");
    }

    @Test
    void workflowAllowsDedicatedInputsAndRequiresTheVideoOutput() throws IOException {
        var version = h3Version();
        var marked = documents.normalize(3, null, version.getApiWorkflowJson(), "{}", version.getOutputBindingsJson());
        assertThat(marked.inputBindings()).isEmpty();
        assertThat(ComfyUiH3RefProfile.isMarked(documents.parseApiWorkflow(marked.apiWorkflowJson()))).isTrue();
        var unmarkedGraph = documents.parseApiWorkflow(version.getApiWorkflowJson());
        ((ObjectNode) unmarkedGraph.at("/168")).remove("_meta");
        var unmarked = documents.normalize(3, null, unmarkedGraph.toString(), "{}", version.getOutputBindingsJson());
        assertThat(marked.workflowHash()).isNotEqualTo(unmarked.workflowHash());
        assertThatThrownBy(() -> documents.normalize(3, null, version.getApiWorkflowJson(), """
                {"prompt":[{"nodeId":"28","inputName":"prompt","valueType":"string"}]}
                """, version.getOutputBindingsJson()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("内置动态输入");
        assertThatThrownBy(() -> documents.normalize(3, null, version.getApiWorkflowJson(), "{}", """
                [{"nodeId":"55","mediaType":"video","role":"primary"}]
                """))
                .isInstanceOf(BusinessException.class).hasMessageContaining("节点 168");
    }

    @Test
    void presetUsesModelPathsAdvertisedByTheSelectedInstance() throws IOException {
        var workflows = mock(ComfyUiWorkflowService.class);
        var apis = mock(ApiConfigService.class);
        var nativeClient = mock(ComfyUiNativeClient.class);
        var target = ApiConfig.builder().id(2L).platform("comfyui").status(1).build();
        when(apis.getById(2L)).thenReturn(target);
        when(nativeClient.getNodeInfo(any(), anyString())).thenAnswer(invocation -> {
            String className = invocation.getArgument(1);
            var definition = nodeInfo(className);
            if ("VAELoader".equals(className)) {
                ((ObjectNode) definition.at("/VAELoader/input/required")).putArray("vae_name").addArray()
                        .add("h3\\minimax_h3_video_vae_fp16.safetensors")
                        .add("h3\\minimax_h3_audio_vae_fp32.safetensors")
                        .add("h3/minimax_h3_audio_vae_fp32.safetensors");
            }
            return definition;
        });
        var validation = new ComfyUiWorkflowValidationService(apis, workflows, documents, nativeClient,
                new CanvasJson(json));

        var imported = documents.parseApiWorkflow(validation.h3RefPreset(2L));

        assertThat(imported.at("/1/inputs/vae_name").asText()).isEqualTo("h3\\minimax_h3_video_vae_fp16.safetensors");
        assertThat(imported.at("/2/inputs/vae_name").asText()).isEqualTo("h3/minimax_h3_audio_vae_fp32.safetensors");
        assertThat(ComfyUiH3RefProfile.isMarked(imported)).isTrue();
    }

    @Test
    void onlineValidationChecksModelsWithoutRequiringUploadedPlaceholderFiles() throws IOException {
        var version = h3Version();
        var workflows = mock(ComfyUiWorkflowService.class);
        var apis = mock(ApiConfigService.class);
        var nativeClient = mock(ComfyUiNativeClient.class);
        when(workflows.requireVersion(10L)).thenReturn(version);
        when(workflows.requireWorkflow(1L)).thenReturn(ComfyUiWorkflow.builder().apiConfigId(2L).modelType(3).build());
        when(apis.getById(2L)).thenReturn(ApiConfig.builder().id(2L).platform("comfyui").status(1).build());
        when(nativeClient.getNodeInfo(any(), anyString())).thenAnswer(invocation -> {
            String className = invocation.getArgument(1);
            var definition = nodeInfo(className);
            if ("LoadImage".equals(className)) {
                ((ObjectNode) definition.at("/LoadImage/input/required")).putArray("image").addArray()
                        .add("real-reference.png");
            }
            return definition;
        });
        var validation = new ComfyUiWorkflowValidationService(apis, workflows, documents, nativeClient,
                new CanvasJson(json));
        assertThat(validation.validateVersion(10L).isValid()).isTrue();

        when(nativeClient.getNodeInfo(any(), anyString())).thenAnswer(invocation -> {
            String className = invocation.getArgument(1);
            var definition = nodeInfo(className);
            if ("UNETLoader".equals(className)) {
                ((ObjectNode) definition.at("/UNETLoader/input/required")).putArray("unet_name").addArray()
                        .add("other-weights.safetensors");
            }
            return definition;
        });
        var invalid = validation.validateVersion(10L);
        assertThat(invalid.isValid()).isFalse();
        assertThat(invalid.getInvalidModelInputs()).singleElement().asString().startsWith("4.unet_name=");
    }

    private ObjectNode nodeInfo(String className) throws IOException {
        if ("MiniMaxH3AudioConditioningT8".equals(className)) {
            return (ObjectNode) json.readTree("""
                    {"MiniMaxH3AudioConditioningT8":{"input":{"optional":{
                      "ref_images":["COMFY_AUTOGROW_V3",{"template":{
                        "input":{"required":{"ref_image":["IMAGE",{}]}},"prefix":"ref_image_","max":9}}],
                      "ref_audios":["COMFY_AUTOGROW_V3",{"template":{
                        "input":{"required":{"ref_audio":["AUDIO",{}]}},"prefix":"ref_audio_","max":3}}]
                    }}}}
                    """);
        }
        var definition = json.createObjectNode();
        definition.putObject(className).putObject("input").putObject("required");
        return definition;
    }

    private ComfyUiWorkflowVersion h3Version() throws IOException {
        try (var input = getClass().getResourceAsStream("/comfyui/h3-reference-t8-dual-stage-2mp.json")) {
            return ComfyUiWorkflowVersion.builder().id(10L).workflowId(1L).versionNo(1)
                    .apiWorkflowJson(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .inputBindingsJson("{}")
                    .outputBindingsJson("[{\"nodeId\":\"168\",\"mediaType\":\"video\",\"role\":\"primary\"}]")
                    .build();
        }
    }
}
