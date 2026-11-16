package com.stonewu.fusion.service.canvas.run.media;

import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.ai.ApiConfig;
import com.stonewu.fusion.service.ai.comfyui.ComfyUiVoiceDesign;
import com.stonewu.fusion.service.ai.comfyui.client.ComfyUiJobResult;
import com.stonewu.fusion.service.ai.comfyui.client.ComfyUiNativeClient;
import com.stonewu.fusion.service.canvas.CanvasJson;
import com.stonewu.fusion.service.canvas.run.CanvasRunData.Ports;
import com.stonewu.fusion.service.canvas.run.CanvasRunSources;
import com.stonewu.fusion.service.generation.transport.GenerationHttpClients;
import com.stonewu.fusion.service.generation.transport.GenerationProviderFailure;
import com.stonewu.fusion.service.media.MediaReference;
import com.stonewu.fusion.service.media.job.MediaJob;
import com.stonewu.fusion.service.media.job.MediaJobOutputs;
import com.stonewu.fusion.service.media.job.MediaJobRepository;
import com.stonewu.fusion.service.media.job.MediaProcessRunner;
import com.stonewu.fusion.service.media.model.MediaKind;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 执行画布音色设计，产出可供 H3 引用的 WAV 媒体。 */
@Component
@RequiredArgsConstructor
public class CanvasVoiceProcessor {
    private static final String AUDIO = "audio";

    private final CanvasMediaJobs tasks;
    private final MediaJobRepository jobs;
    private final ComfyUiNativeClient client;
    private final ComfyUiVoiceDesign voice;
    private final MediaJobOutputs outputs;
    private final MediaProcessRunner processes;
    private final CanvasRunSources sources;
    private final CanvasJson json;
    private final Environment environment;

    /** 外部查询只使用已固化的请求标识；保存成功前保留可恢复的远端终态。 */
    public void step(MediaJob job, CanvasMediaInput input, Path work) throws IOException {
        GenerationHttpClients.requireNoTransaction();
        var original = jobs.submission(job.id(), AUDIO);
        var received = original.isPresent() ? tasks.received(job.id()) : null;
        if (job.cancelRequested() && (original.isEmpty() || received != null)) {
            tasks.finish(job.claim(), "CANCELLED", "参考音频任务已停止", null, false);
            return;
        }
        if (!jobs.now().isBefore(job.deadline())) {
            boolean unknown = original.isPresent() && received == null;
            tasks.finish(job.claim(), unknown ? "UNKNOWN_SUBMISSION" : "FAILED",
                    unknown ? "等待 ComfyUI 超时，远端结果未知" : "参考音频处理超过等待期限", null, false);
            return;
        }
        ApiConfig api = tasks.api(input);
        if (original.isEmpty()) {
            submit(job, input, api);
            return;
        }
        if (received == null) {
            var terminal = poll(job, api, original.get());
            if (terminal == null) {
                return;
            }
            received = json.object(terminal);
            tasks.received(job.claim(), received);
        }
        finish(job, api, json.read(received.toString(), ComfyUiJobResult.class), work);
    }

    /** 复用画布媒体提交意图；响应丢失后只查原 prompt_id。 */
    private void submit(MediaJob job, CanvasMediaInput input, ApiConfig api) {
        var config = input.config();
        var graph = voice.render(config.path("voiceDescription").asText(), config.path("previewText").asText(),
                config.path("language").asText(), job.id());
        voice.validateTarget(api, graph);
        var intent = tasks.beginAudio(job.claim());
        if (!intent.created()) {
            return;
        }
        try {
            client.submitPrompt(api, graph, job.id());
        } catch (BusinessException failure) {
            var rejected = GenerationProviderFailure.find(failure);
            if (rejected != null && rejected.httpStatus() >= 400 && rejected.httpStatus() < 500) {
                tasks.finish(job.claim(), "FAILED", rejected.getMessage(), null, false);
            } else {
                tasks.waitNext(job.claim(), "ComfyUI 受理状态待确认，继续查询原任务", true);
            }
            return;
        }
        jobs.acknowledge(intent.attempt(), job.id());
        tasks.waitNext(job.claim(), "ComfyUI 已受理，等待参考音频", false);
    }

    /** 原生取消回执只代表已发送取消，若排队任务已被移除则查询返回 404。 */
    private ComfyUiJobResult poll(MediaJob job, ApiConfig api, MediaJobRepository.Submission submission) {
        var claim = job.claim();
        var current = jobs.observe(claim);
        boolean cancelAcknowledged = current.checkpoint().path(CanvasMediaJobs.VOICE_CANCEL_ACKNOWLEDGED).asBoolean();
        try {
            var remote = client.findJob(api, job.id());
            if (remote.isEmpty()) {
                if (cancelAcknowledged) {
                    tasks.finish(claim, "CANCELLED", "ComfyUI 已移除排队任务", null, false);
                } else {
                    tasks.waitNext(claim, "等待确认原 ComfyUI 任务状态", true);
                }
                return null;
            }
            if (submission.providerId() == null) {
                jobs.acknowledge(submission.attempt(), job.id());
            }
            var result = remote.get();
            if (result.completed() || result.failed()) {
                return result;
            }
            if (current.cancelRequested() && !cancelAcknowledged && client.cancelJob(api, job.id())) {
                tasks.recordVoiceCancellation(claim);
            }
        } catch (BusinessException failure) {
            if (failure.getCode() != 502) {
                throw failure;
            }
            tasks.waitNext(claim, "ComfyUI 查询或取消暂不可用，稍后检查原任务", true);
            return null;
        }
        tasks.waitNext(claim, current.cancelRequested() ? "等待 ComfyUI 停止原任务" : "等待 ComfyUI 生成参考音频", false);
        return null;
    }

    /** 原生 SaveAudio 输出 FLAC，转 PCM WAV 后按媒体任务要求幂等入库。 */
    private void finish(MediaJob job, ApiConfig api, ComfyUiJobResult result, Path work) throws IOException {
        var claim = job.claim();
        if (jobs.observe(claim).cancelRequested() || "cancelled".equals(result.status())) {
            tasks.finish(claim, "CANCELLED", "ComfyUI 参考音频任务已结束", null, false);
            return;
        }
        if (result.failed()) {
            var error = result.executionError();
            String detail = error == null ? "" : error.path("exception_message").asText("").strip();
            tasks.finish(claim, "FAILED", detail.isEmpty() ? "ComfyUI 音色生成失败"
                    : "ComfyUI 音色生成失败：" + detail.substring(0, Math.min(detail.length(), 1500)), null, false);
            return;
        }
        var previous = outputs.existing(claim, AUDIO, MediaKind.AUDIO);
        String reference;
        if (previous.isPresent()) {
            reference = previous.get();
        } else {
            var output = voice.output(result);
            Path wav = work.resolve("voice-reference.wav");
            try (var flac = client.downloadOutput(api, output.filename(), output.subfolder(), output.type())) {
                String ffmpeg = environment.getProperty("video.compose.ffmpeg-path", "ffmpeg");
                processes.run(List.of(ffmpeg, "-v", "error", "-nostdin", "-y", "-protocol_whitelist", "file,pipe",
                        "-f", "flac", "-i", flac.path().toString(), "-map", "0:a:0", "-c:a", "pcm_s16le",
                        wav.toString()), work, wav, () -> tasks.check(claim, false));
            }
            reference = outputs.store(claim, AUDIO, wav, "audio/wav", MediaKind.AUDIO);
        }
        var value = sources.mediaValue(MediaReference.id(reference), job.context().actor(), job.projectId());
        tasks.finish(claim, "SUCCEEDED", null, new Ports(Map.of(AUDIO, List.of(value))), false);
    }
}
