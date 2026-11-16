package com.stonewu.fusion.service.ai.comfyui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.ai.ApiConfig;
import com.stonewu.fusion.service.ai.comfyui.client.ComfyUiJobResult;
import com.stonewu.fusion.service.ai.comfyui.client.ComfyUiNativeClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/** Qwen3-TTS 1.7B VoiceDesign 及原生 SaveAudio 的工作流协议。 */
@Component
@RequiredArgsConstructor
public class ComfyUiVoiceDesign {
    public static final int MODEL_TYPE = 4;
    public static final String MODEL_CODE = "qwen3-tts-voice-design-1.7b";
    public static final List<String> LANGUAGES = List.of("Auto", "Chinese", "English", "Japanese", "Korean",
            "German", "French", "Russian", "Portuguese", "Spanish", "Italian");
    private static final String DESIGN_CLASS = "FB_Qwen3TTSVoiceDesign";
    private static final String OUTPUT_CLASS = "SaveAudio";
    private static final List<ComfyUiOutputBinding> OUTPUT = List.of(new ComfyUiOutputBinding("2", "audio", "primary"));

    private final ObjectMapper json;
    private final ComfyUiNativeClient client;
    private final ComfyUiOutputResolver resolver;

    /** 每次提交固定独立输出路径，并用同一任务标识复原种子。 */
    public ObjectNode render(String description, String previewText, String language, String promptId) {
        if (description.isBlank() || description.length() > 4000) {
            throw new BusinessException(400, "音色描述须填写且不能超过 4000 字符");
        }
        if (previewText.isBlank() || previewText.length() > 10000) {
            throw new BusinessException(400, "试听台词须填写且不能超过 10000 字符");
        }
        if (!LANGUAGES.contains(language)) {
            throw new BusinessException(400, "Qwen3-TTS 音色设计不支持所选语言");
        }
        ObjectNode graph = json.createObjectNode();
        graph.putObject("1").put("class_type", DESIGN_CLASS).putObject("inputs")
                .put("text", previewText.strip()).put("instruct", description.strip()).put("language", language)
                .put("model_choice", "1.7B").put("device", "cuda").put("precision", "bf16")
                .put("seed", Long.parseLong(promptId.replace("-", "").substring(0, 12), 16))
                .put("max_new_tokens", 2048).put("top_p", 1.0).put("top_k", 50).put("temperature", 0.9)
                .put("repetition_penalty", 1.05).put("attention", "sdpa").put("unload_model_after_generate", true);
        var save = graph.putObject("2").put("class_type", OUTPUT_CLASS).putObject("inputs");
        save.put("filename_prefix", "ai-fusion-video/voice-design/" + promptId);
        save.putArray("audio").add("1").add(0);
        return graph;
    }

    /** 提交意图写入前核对目标实例的节点和图中显式选择的选项。 */
    public void validateTarget(ApiConfig api, ObjectNode graph) {
        JsonNode design = requireNode(api, DESIGN_CLASS);
        JsonNode save = requireNode(api, OUTPUT_CLASS);
        if (!"AUDIO".equals(design.path("output").path(0).asText())
                || !save.path("output_node").asBoolean()
                || !"AUDIO".equals(save.path("input").path("required").path("audio").path(0).asText())) {
            throw new BusinessException(400, "目标 ComfyUI 的音色工作流节点未提供所需的 AUDIO 端口");
        }
        JsonNode inputs = design.path("input");
        JsonNode graphInputs = graph.path("1").path("inputs");
        for (String field : List.of("model_choice", "language", "device", "precision", "attention")) {
            JsonNode parameter = inputs.path("required").path(field);
            if (parameter.isMissingNode()) {
                parameter = inputs.path("optional").path(field);
            }
            JsonNode choices = parameter.path(0);
            boolean supported = false;
            for (JsonNode choice : choices) {
                if (choice.equals(graphInputs.path(field))) {
                    supported = true;
                    break;
                }
            }
            if (!choices.isArray() || !supported) {
                throw new BusinessException(400,
                        "目标 ComfyUI 音色节点不支持 " + field + "=" + graphInputs.path(field).asText());
            }
        }
    }

    /** 原生 SaveAudio 的单个 FLAC 输出是后续 WAV 转换的唯一来源。 */
    public ComfyUiRemoteOutput output(ComfyUiJobResult job) {
        if (!job.outputs().path("2").path("audio").isArray()) {
            throw new BusinessException(502, "ComfyUI SaveAudio 响应缺少 audio 输出");
        }
        var files = resolver.resolve(job, OUTPUT);
        var file = files.getFirst();
        if (files.size() != 1 || !"output".equals(file.type())
                || !file.filename().toLowerCase(Locale.ROOT).endsWith(".flac")) {
            throw new BusinessException(502, "ComfyUI SaveAudio 必须返回单个 FLAC 输出文件");
        }
        return file;
    }

    private JsonNode requireNode(ApiConfig api, String className) {
        JsonNode definition = client.getNodeInfo(api, className).path(className);
        if (!definition.isObject()) {
            throw new BusinessException(400, "目标 ComfyUI 缺少音色工作流节点 " + className);
        }
        return definition;
    }
}
