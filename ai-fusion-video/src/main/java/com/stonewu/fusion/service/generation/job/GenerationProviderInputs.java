package com.stonewu.fusion.service.generation.job;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.fusion.entity.ai.ApiConfig;
import com.stonewu.fusion.entity.generation.ImageTask;
import com.stonewu.fusion.entity.generation.VideoTask;
import com.stonewu.fusion.service.ai.comfyui.ComfyUiInputResourceService;
import com.stonewu.fusion.service.canvas.CanvasJson;
import com.stonewu.fusion.service.generation.GenerationModelCapabilityService;
import com.stonewu.fusion.service.generation.ReferenceImageTransportService;
import com.stonewu.fusion.service.media.MediaInputTransport;
import com.stonewu.fusion.service.media.MediaIntakeService;
import com.stonewu.fusion.service.media.MediaReference;
import com.stonewu.fusion.service.media.model.MediaKind;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 每次使用固定输入重新签发传输地址，不把短时地址写回任务或画布。
 */
@Component
@RequiredArgsConstructor
public class GenerationProviderInputs {
    private final CanvasJson json;
    private final GenerationModelCapabilityService capabilities;
    private final ReferenceImageTransportService references;
    private final MediaInputTransport media;
    private final GenerationInputVersions versions;

    public ImageTask image(RemoteGenerationJob job, GenerationSubmission submission, ApiConfig api,
                           boolean resolveMedia) {
        var body = job.input().deepCopy();
        body.remove("_resultItemIds");
        var task = json.read(json.write(body), ImageTask.class);
        task.setExecutionContextJson(json.write(GenerationExecutionIdentity.worker(submission.context())));
        if (resolveMedia) {
            versions.validate(task.getInputMediaVersions(), submission.context().actor());
            capabilities.validateImageTask(submission.model(), task);
            var modelConfig = capabilities.getMergedModelConfig(submission.model());
            var values = references.parseJsonInputs(task.getRefImageUrls(), "refImageUrls");
            if (!values.isEmpty()) {
                task.setRefImageUrls(JSONUtil.toJsonStr(references.resolveInputs(submission.model(),
                        modelConfig, values, api,
                        submission.context().actor(),
                        job.claim())));
            }
            task.setWorkflowInputs(workflow(task.getWorkflowInputs(), submission, modelConfig, api, job.claim(), false));
        }
        return task;
    }

    public VideoTask video(RemoteGenerationJob job, GenerationSubmission submission, ApiConfig api,
                           boolean resolveMedia) {
        var body = job.input().deepCopy();
        body.remove("_resultItemIds");
        var task = json.read(json.write(body), VideoTask.class);
        task.setExecutionContextJson(json.write(GenerationExecutionIdentity.worker(submission.context())));
        if (!resolveMedia) {
            return task;
        }
        versions.validate(task.getInputMediaVersions(), submission.context().actor());
        capabilities.validateVideoTask(submission.model(), task);
        var modelConfig = capabilities.getMergedModelConfig(submission.model());
        boolean h3 = Boolean.TRUE.equals(modelConfig.getBool("h3Ref"));
        versions.validateVideoMedia(task, modelConfig, submission.context().actor());
        if (task.getFirstFrameImageUrl() != null) {
            task.setFirstFrameImageUrl(
                    images(List.of(task.getFirstFrameImageUrl()), submission, modelConfig, api, job.claim()).getFirst());
        }
        if (task.getLastFrameImageUrl() != null) {
            task.setLastFrameImageUrl(
                    images(List.of(task.getLastFrameImageUrl()), submission, modelConfig, api, job.claim()).getFirst());
        }
        var refs = references.parseJsonInputs(task.getReferenceImageUrls(), "referenceImageUrls");
        if (!refs.isEmpty()) {
            task.setReferenceImageUrls(JSONUtil.toJsonStr(images(refs, submission, modelConfig, api, job.claim())));
        }
        task.setReferenceVideoUrls(
                mediaList(task.getReferenceVideoUrls(), "referenceVideoUrls", MediaKind.VIDEO, submission,
                        modelConfig, job.claim(), false));
        task.setReferenceAudioUrls(
                mediaList(task.getReferenceAudioUrls(), "referenceAudioUrls", MediaKind.AUDIO, submission,
                        modelConfig, job.claim(), h3));
        task.setWorkflowInputs(workflow(task.getWorkflowInputs(), submission, modelConfig, api, job.claim(), h3));
        return task;
    }

    private List<String> images(List<String> input, GenerationSubmission submission, JSONObject modelConfig,
                                ApiConfig api, RemoteGenerationJob.Claim claim) {
        return references.resolveInputs(submission.model(), modelConfig, input, api, submission.context().actor(), claim);
    }

    private ObjectNode workflow(ObjectNode raw, GenerationSubmission submission, JSONObject modelConfig,
                                ApiConfig api, RemoteGenerationJob.Claim claim, boolean h3) {
        if (raw == null) {
            return null;
        }
        ObjectNode result = raw.deepCopy();
        if (h3) {
            // H3 参考素材通过 VideoTask 的字段传递，工作流覆盖项只保留其他参数。
            result.remove(List.of("referenceImages", "referenceAudios"));
        }
        for (String port : List.of("firstFrame", "lastFrame")) {
            if (raw.hasNonNull(port)) {
                result.put(port, images(List.of(raw.path(port).asText()), submission, modelConfig, api, claim).getFirst());
            }
        }
        for (String port : List.of("referenceImages", "referenceVideos", "referenceAudios")) {
            if (!result.has(port)) {
                continue;
            }
            List<String> values = new ArrayList<>();
            raw.path(port).forEach(value -> values.add(value.asText()));
            List<String> resolved = switch (port) {
                case "referenceImages" -> images(values, submission, modelConfig, api, claim);
                case "referenceVideos" -> resolveMedia(values, MediaKind.VIDEO, submission, modelConfig, claim, false);
                default -> resolveMedia(values, MediaKind.AUDIO, submission, modelConfig, claim, false);
            };
            var output = result.putArray(port);
            resolved.forEach(output::add);
        }
        return result;
    }

    private String mediaList(String input, String field, MediaKind kind, GenerationSubmission submission,
                             JSONObject modelConfig, RemoteGenerationJob.Claim claim, boolean h3Audio) {
        if (input == null || input.isBlank()) {
            return input;
        }
        return JSONUtil.toJsonStr(resolveMedia(references.parseJsonInputs(input, field), kind, submission,
                modelConfig, claim, h3Audio));
    }

    /** 本机 ComfyUI 的 H3 以文件上传接收音频，生成工作者只读取已授权的托管音频。 */
    private List<String> resolveMedia(List<String> values, MediaKind kind, GenerationSubmission submission,
                                      JSONObject modelConfig, RemoteGenerationJob.Claim claim, boolean h3Audio) {
        var actor = submission.context().actor();
        if (h3Audio) {
            return values.stream().map(value -> media.dataUri(value, actor, MediaKind.AUDIO,
                    ComfyUiInputResourceService.MAX_H3_REFERENCE_AUDIO_BYTES)).toList();
        }
        var formats = modelConfig.getJSONArray(kind == MediaKind.VIDEO
                ? "referenceVideoInputFormats" : "referenceAudioInputFormats");
        if (formats != null && formats.contains("data_uri")) {
            return values.stream().map(value -> MediaReference.managed(value)
                    ? media.dataUri(value, actor, kind, MediaIntakeService.MAX_UPLOAD_BYTES) : value).toList();
        }
        return values.stream().map(value -> MediaReference.managed(value) ? media.generationUrl(value, actor, kind,
                claim) : value).toList();
    }
}
