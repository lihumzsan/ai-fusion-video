package com.stonewu.fusion.service.ai.comfyui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.ai.ComfyUiWorkflowVersion;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/** 将 Waoowaoo 的 H3 REF T8 双阶段模板转换成当前任务的 ComfyUI 执行图。 */
public final class ComfyUiH3RefProfile {

    public static final int MIN_REFERENCE_IMAGES = 1;
    public static final int MAX_REFERENCE_IMAGES = 9;
    public static final int MAX_REFERENCE_AUDIOS = 3;
    public static final int MIN_DURATION = 4;
    public static final int MAX_DURATION = 15;
    public static final int DEFAULT_DURATION = 5;
    public static final String DEFAULT_RATIO = "16:9";
    public static final String REFERENCE_IMAGES = "referenceImages";
    public static final String REFERENCE_AUDIOS = "referenceAudios";
    public static final List<String> INPUT_FIELDS = List.of(
            "prompt", "seed", "duration", "ratio", REFERENCE_IMAGES, REFERENCE_AUDIOS);
    public static final List<String> ASPECT_RATIOS = List.of("21:9", "16:9", "4:3", "1:1", "3:4", "9:16", "9:21");
    public static final List<Integer> DURATIONS = IntStream.rangeClosed(MIN_DURATION, MAX_DURATION).boxed().toList();

    static final String CONDITIONING_CLASS = "MiniMaxH3AudioConditioningT8";
    private static final String IMAGE_LOADER = "6";
    private static final String AUDIO_LOADER = "18";
    private static final String OUTPUT_NODE = "168";
    private static final String PROFILE_KEY = "aiFusionVideoProfile";
    private static final String FIXED_PROFILE = "fixed-bindings";
    private static final String EMPTY_BINDINGS = "{}";
    private static final List<String> CONDITIONING_NODES = List.of("7", "14");
    private static final Map<String, String> REQUIRED_NODES = Map.of(
            IMAGE_LOADER, "LoadImage", "7", CONDITIONING_CLASS, "11", "RandomNoise",
            "13", "MiniMaxH3LearnedLatentUpscaleT8Advanced", "14", CONDITIONING_CLASS,
            AUDIO_LOADER, "LoadAudio", "28", "CR Prompt Text", "55", "ImageResizeKJv2", OUTPUT_NODE, "VHS_VideoCombine");
    private static final int FRAMES_PER_SECOND = 24;
    private static final int MIN_FRAME_COUNT = 107;
    private static final int FRAME_GRID = 17;
    private static final int FRAME_GRID_REMAINDER = 5;
    private static final int DIMENSION_ALIGNMENT = 32;

    private ComfyUiH3RefProfile() {
    }

    public static boolean matches(int modelType, ComfyUiWorkflowVersion version,
                                  ComfyUiWorkflowDocumentService documents) {
        return modelType == 3 && matches(version, documents.parseApiWorkflow(version.getApiWorkflowJson()));
    }

    /** 兼容曾按模板形状启用动态输入且无固定绑定的已发布开发版本，保留其原文与哈希。 */
    static boolean matches(ComfyUiWorkflowVersion version, ObjectNode graph) {
        return validateExplicit(graph) || Boolean.TRUE.equals(version.getPublished())
                && EMPTY_BINDINGS.equals(version.getInputBindingsJson()) && isUnmarkedTemplate(graph);
    }

    /** 旧草稿须先保存模式，避免发布后执行与试运行不同的输入合同。 */
    static boolean requiresProfileOnPublication(int modelType, ComfyUiWorkflowVersion version,
                                                 ComfyUiWorkflowDocumentService documents) {
        return modelType == 3 && !Boolean.TRUE.equals(version.getPublished())
                && EMPTY_BINDINGS.equals(version.getInputBindingsJson())
                && isUnmarkedTemplate(documents.parseApiWorkflow(version.getApiWorkflowJson()));
    }

    /** 显式启用后，模板结构必须符合动态输入合同。 */
    static boolean validateExplicit(ObjectNode graph) {
        if (!isMarked(graph)) {
            return false;
        }
        String error = templateError(graph);
        if (error != null) {
            throw invalid(error);
        }
        return true;
    }

    /** 新保存的同形空绑定版本记录固定模式，发布后与已有动态版本区分。 */
    static void markFixedBindings(ObjectNode graph) {
        if (!isUnmarkedTemplate(graph)) {
            return;
        }
        var output = (ObjectNode) graph.get(OUTPUT_NODE);
        if (output.get("_meta") instanceof ObjectNode metadata) {
            metadata.put(PROFILE_KEY, FIXED_PROFILE);
        } else {
            output.putObject("_meta").put(PROFILE_KEY, FIXED_PROFILE);
        }
    }

    private static boolean isUnmarkedTemplate(ObjectNode graph) {
        return profile(graph).isMissingNode() && templateError(graph) == null;
    }

    private static String templateError(ObjectNode graph) {
        for (var expected : REQUIRED_NODES.entrySet()) {
            String nodeId = expected.getKey();
            if (!hasTemplateNode(graph, nodeId) || !graph.path(nodeId).path("inputs").isObject()) {
                return "H3 REF 工作流的节点 " + nodeId + " 与 T8 双阶段模板不匹配";
            }
        }
        for (String nodeId : CONDITIONING_NODES) {
            if (!"Ref2VA".equals(inputs(graph, nodeId).path("task_type").asText())) {
                return "H3 REF 工作流的条件节点必须使用 Ref2VA";
            }
            if (!"native".equals(inputs(graph, nodeId).path("audio_mode").asText())) {
                return "H3 REF 工作流的条件节点必须使用原生音频模式";
            }
        }
        ObjectNode output = inputs(graph, OUTPUT_NODE);
        if (output.path("frame_rate").asInt() != FRAMES_PER_SECOND || !output.path("save_output").asBoolean()
                || !"video/h264-mp4".equals(output.path("format").asText())) {
            return "H3 REF 工作流必须保存 24 fps 的 H.264 MP4 视频";
        }
        return null;
    }

    /** 使用原生节点元数据将模式纳入版本内容及其哈希。 */
    public static boolean isMarked(ObjectNode graph) {
        return "h3-ref".equals(profile(graph).asText());
    }

    private static JsonNode profile(ObjectNode graph) {
        return graph.path(OUTPUT_NODE).path("_meta").path(PROFILE_KEY);
    }

    private static boolean hasTemplateNode(ObjectNode graph, String nodeId) {
        return REQUIRED_NODES.get(nodeId).equals(graph.path(nodeId).path("class_type").asText());
    }

    /** 在向 ComfyUI 上传文件前校验两类动态输入的数量和结构。 */
    public static void validateReferences(Map<String, Object> values) {
        references(values, REFERENCE_IMAGES, MIN_REFERENCE_IMAGES, MAX_REFERENCE_IMAGES);
        references(values, REFERENCE_AUDIOS, 0, MAX_REFERENCE_AUDIOS);
    }

    static boolean isReferencePlaceholder(String nodeId, String inputName) {
        return IMAGE_LOADER.equals(nodeId) && "image".equals(inputName)
                || AUDIO_LOADER.equals(nodeId) && "audio".equals(inputName);
    }

    /** 在线确认目标 H3 插件暴露了与动态连线相符的可变长素材端口。 */
    static void validateReferenceSlots(JsonNode definition, List<String> invalidInputs) {
        JsonNode optional = definition.path("input").path("optional");
        if (!supportsReferenceSlots(optional.path("ref_images"), "ref_image", "IMAGE", MAX_REFERENCE_IMAGES)) {
            invalidInputs.add(CONDITIONING_CLASS + ".ref_images 不支持 1 至 9 张参考图");
        }
        if (!supportsReferenceSlots(optional.path("ref_audios"), "ref_audio", "AUDIO", MAX_REFERENCE_AUDIOS)) {
            invalidInputs.add(CONDITIONING_CLASS + ".ref_audios 不支持 0 至 3 段参考音频");
        }
    }

    private static boolean supportsReferenceSlots(JsonNode definition, String item, String type, int maximum) {
        JsonNode template = definition.path(1).path("template");
        return "COMFY_AUTOGROW_V3".equals(definition.path(0).asText())
                && type.equals(template.path("input").path("required").path(item).path(0).asText())
                && (item + "_").equals(template.path("prefix").asText())
                && template.path("max").asInt() >= maximum;
    }

    /** 动态填充两阶段共享的参考槽，并按 H3 的帧格与尺寸规则生成参数。 */
    static ObjectNode render(ObjectNode graph, Map<String, Object> values) {
        List<?> images = references(values, REFERENCE_IMAGES, MIN_REFERENCE_IMAGES, MAX_REFERENCE_IMAGES);
        List<?> audios = references(values, REFERENCE_AUDIOS, 0, MAX_REFERENCE_AUDIOS);
        int duration = duration(values.get("duration"));
        String ratio = values.get("ratio") == null ? DEFAULT_RATIO : values.get("ratio").toString();
        if (!ASPECT_RATIOS.contains(ratio)) {
            throw invalid("H3 REF 不支持当前画面比例");
        }
        if (!(values.get("prompt") instanceof String prompt) || prompt.isBlank()) {
            throw invalid("H3 REF 需要填写提示词");
        }

        List<ObjectNode> conditioning = CONDITIONING_NODES.stream().map(node -> inputs(graph, node)).toList();
        applyReferences(graph, conditioning, IMAGE_LOADER, 60, "image", "ref_images.ref_image_", images);
        applyReferences(graph, conditioning, AUDIO_LOADER, 70, "audio", "ref_audios.ref_audio_", audios);
        inputs(graph, "28").put("prompt", prompt);
        if (values.get("seed") instanceof Number seed) {
            inputs(graph, "11").put("noise_seed", seed.longValue());
        }

        int minimumFrames = Math.max(MIN_FRAME_COUNT, duration * FRAMES_PER_SECOND);
        int frames = minimumFrames + Math.floorMod(FRAME_GRID_REMAINDER - minimumFrames, FRAME_GRID);
        PassSizes passes = passSizes(duration);
        Dimensions coarse = dimensions(ratio, passes.coarse());
        Dimensions refine = dimensions(ratio, passes.refine());
        Dimensions output = dimensions(ratio, 2.0);
        conditioning.getFirst().put("width", coarse.width()).put("height", coarse.height()).put("length", frames);
        conditioning.getLast().put("length", frames);
        inputs(graph, "13").put("target_megapixels", passes.refine())
                .put("target_width", refine.width()).put("target_height", refine.height());
        inputs(graph, "55").put("width", output.width()).put("height", output.height());
        return graph;
    }

    private static List<?> references(Map<String, Object> values, String field, int minimum, int maximum) {
        Object value = values.get(field);
        List<?> files = value == null ? List.of() : value instanceof List<?> list ? list : List.of(value);
        if (files.size() < minimum || files.size() > maximum) {
            throw invalid(REFERENCE_IMAGES.equals(field)
                    ? "H3 REF 需要 1 至 9 张参考图" : "H3 REF 最多支持 3 段参考音频");
        }
        for (Object file : files) {
            if (!(file instanceof String name) || name.isBlank()) {
                throw invalid(REFERENCE_IMAGES.equals(field) ? "H3 REF 参考图不能为空" : "H3 REF 参考音频不能为空");
            }
        }
        return files;
    }

    private static void applyReferences(ObjectNode graph, List<ObjectNode> conditioning, String loaderId,
                                        int nextId, String fileInput, String slotPrefix, List<?> files) {
        for (ObjectNode nodeInputs : conditioning) {
            List<String> previousSlots = nodeInputs.properties().stream().map(Map.Entry::getKey)
                    .filter(key -> key.startsWith(slotPrefix)).toList();
            nodeInputs.remove(previousSlots);
        }
        ObjectNode template = (ObjectNode) graph.get(loaderId);
        boolean sharedLoader = graph.properties().stream()
                .flatMap(entry -> entry.getValue().path("inputs").properties().stream())
                .map(Map.Entry::getValue)
                .anyMatch(value -> value.isArray() && value.size() == 2 && loaderId.equals(value.get(0).textValue())
                        && value.get(1).isIntegralNumber());
        if (files.isEmpty() && !sharedLoader) {
            graph.remove(loaderId);
        }
        for (int index = 0; index < files.size(); index++) {
            String nodeId = loaderId;
            if (index > 0 || sharedLoader) {
                do {
                    nodeId = Integer.toString(nextId++);
                } while (graph.has(nodeId));
            }
            ObjectNode loader = template.deepCopy();
            ((ObjectNode) loader.get("inputs")).put(fileInput, ((String) files.get(index)).trim());
            graph.set(nodeId, loader);
            for (ObjectNode nodeInputs : conditioning) {
                nodeInputs.putArray(slotPrefix + index).add(nodeId).add(0);
            }
        }
    }

    private static int duration(Object value) {
        if (value == null) {
            return DEFAULT_DURATION;
        }
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue()
                || number.intValue() < MIN_DURATION || number.intValue() > MAX_DURATION) {
            throw invalid("H3 REF 视频时长须为 4 至 15 秒的整数");
        }
        return number.intValue();
    }

    private static PassSizes passSizes(int duration) {
        return switch (duration) {
            case 11 -> new PassSizes(0.61, 0.88);
            case 12 -> new PassSizes(0.58, 0.83);
            case 13 -> new PassSizes(0.52, 0.75);
            case 14 -> new PassSizes(0.49, 0.71);
            case 15 -> new PassSizes(0.47, 0.67);
            default -> new PassSizes(0.70, 1.00);
        };
    }

    private static Dimensions dimensions(String ratio, double megapixels) {
        String[] terms = ratio.split(":");
        int width = Integer.parseInt(terms[0]);
        int height = Integer.parseInt(terms[1]);
        double scale = Math.sqrt(megapixels * 1024 * 1024 / (width * height));
        return new Dimensions(align(width * scale), align(height * scale));
    }

    private static int align(double dimension) {
        return (int) Math.max(DIMENSION_ALIGNMENT,
                Math.round(dimension / DIMENSION_ALIGNMENT) * DIMENSION_ALIGNMENT);
    }

    private static ObjectNode inputs(ObjectNode graph, String nodeId) {
        return (ObjectNode) graph.get(nodeId).get("inputs");
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(400, message);
    }

    private record Dimensions(int width, int height) {
    }

    private record PassSizes(double coarse, double refine) {
    }
}
