package com.stonewu.fusion.service.ai.comfyui;

import cn.hutool.core.util.StrUtil;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.ai.ApiConfig;
import com.stonewu.fusion.service.ai.comfyui.client.ComfyUiNativeClient;
import com.stonewu.fusion.service.ai.comfyui.client.ComfyUiUploadResult;
import com.stonewu.fusion.service.ai.proxy.AiProxySupport;
import com.stonewu.fusion.service.media.MediaProbe;
import com.stonewu.fusion.service.media.model.MediaFacts;
import com.stonewu.fusion.service.media.model.MediaKind;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 读取生成任务的参考素材，并通过 ComfyUI 原生接口上传到 input 目录。
 */
@Service
public class ComfyUiInputResourceService {

    public static final int MAX_H3_REFERENCE_AUDIO_BYTES = 15 * 1024 * 1024;
    public static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;
    private static final long MIN_H3_AUDIO_DURATION_MS = 2_000L;
    private static final long MAX_H3_TOTAL_AUDIO_DURATION_MS = 15_000L;
    private static final String INPUT_SUBFOLDER = "ai-fusion-video";

    private final ComfyUiNativeClient nativeClient;
    private final MediaProbe mediaProbe;
    private final OkHttpClient baseClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.MINUTES)
            .build();

    public ComfyUiInputResourceService(ComfyUiNativeClient nativeClient, MediaProbe mediaProbe) {
        this.nativeClient = nativeClient;
        this.mediaProbe = mediaProbe;
    }

    public List<String> uploadImages(ApiConfig apiConfig,
                                     String taskKey,
                                     String field,
                                     List<String> sources) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        List<String> uploaded = new ArrayList<>();
        for (int index = 0; index < sources.size(); index++) {
            MediaBytes image = loadImage(apiConfig, sources.get(index));
            String extension = imageExtension(image.contentType());
            String fileName = sanitizePart(taskKey) + "-" + sanitizePart(field)
                    + "-" + index + "." + extension;
            ComfyUiUploadResult result = nativeClient.uploadImage(
                    apiConfig, image.bytes(), fileName, image.contentType(), INPUT_SUBFOLDER);
            uploaded.add(result.workflowValue());
        }
        return List.copyOf(uploaded);
    }

    /**
     * 校验全部参考音频的实际格式与总时长，之后才按输入顺序上传。调用方须先将授权素材转换为 Data URI。
     */
    public List<String> uploadH3ReferenceAudios(ApiConfig apiConfig, String taskKey, List<String> sources) {
        List<MediaBytes> audios = new ArrayList<>(sources.size());
        long totalDuration = 0;
        for (String source : sources) {
            MediaBytes audio = parseDataUri(source, MediaKind.AUDIO);
            MediaFacts facts = inspectAudio(audio);
            if (facts.kind() != MediaKind.AUDIO || !audio.contentType().equals(facts.mimeType())) {
                throw new BusinessException(400, "H3 REF 参考音频内容与声明格式不符，请使用 MP3 或 WAV");
            }
            if (facts.durationMs() < MIN_H3_AUDIO_DURATION_MS) {
                throw new BusinessException(400, "H3 REF 每段参考音频至少 2 秒");
            }
            totalDuration += facts.durationMs();
            if (totalDuration > MAX_H3_TOTAL_AUDIO_DURATION_MS) {
                throw new BusinessException(400, "H3 REF 参考音频总时长不得超过 15 秒");
            }
            audios.add(audio);
        }

        List<String> uploaded = new ArrayList<>(audios.size());
        for (int index = 0; index < audios.size(); index++) {
            MediaBytes audio = audios.get(index);
            String extension = "audio/mpeg".equals(audio.contentType()) ? "mp3" : "wav";
            String fileName = sanitizePart(taskKey) + "-referenceAudios-" + index + "." + extension;
            ComfyUiUploadResult result = nativeClient.uploadAudio(
                    apiConfig, audio.bytes(), fileName, audio.contentType(), INPUT_SUBFOLDER);
            uploaded.add(result.workflowValue());
        }
        return List.copyOf(uploaded);
    }

    private MediaFacts inspectAudio(MediaBytes audio) {
        try (var file = new TemporaryFile(Files.createTempFile("afv-h3-audio-", ".media"))) {
            Files.write(file.path(), audio.bytes());
            return mediaProbe.inspect(file.path(), audio.contentType());
        } catch (IOException failure) {
            var error = new BusinessException(502, "无法读取或检测 H3 REF 参考音频");
            error.initCause(failure);
            throw error;
        }
    }

    private MediaBytes loadImage(ApiConfig apiConfig, String source) {
        if (StrUtil.isBlank(source)) {
            throw new BusinessException(400, "ComfyUI 图片输入不能为空");
        }
        String value = source.trim();
        if (value.startsWith("data:")) {
            return parseDataUri(value, MediaKind.IMAGE);
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return downloadHttp(apiConfig, value);
        }
        throw new BusinessException(400,
                "ComfyUI 图片输入必须先转换为 Data URI 或公网 URL；本地素材请启用 data_uri 传输");
    }

    private MediaBytes parseDataUri(String value, MediaKind kind) {
        boolean audio = kind == MediaKind.AUDIO;
        String label = audio ? "H3 REF 参考音频" : "ComfyUI 图片";
        int maxBytes = audio ? MAX_H3_REFERENCE_AUDIO_BYTES : MAX_IMAGE_BYTES;
        int maxMegabytes = maxBytes / (1024 * 1024);
        int comma = value.indexOf(',');
        if (!value.startsWith("data:") || comma <= 5) {
            throw new BusinessException(400, label + " Data URI 格式无效");
        }
        String metadata = value.substring(5, comma);
        String[] metadataParts = metadata.split(";");
        boolean base64 = false;
        for (int index = 1; index < metadataParts.length; index++) {
            if ("base64".equalsIgnoreCase(metadataParts[index].trim())) {
                base64 = true;
                break;
            }
        }
        if (!base64) {
            throw new BusinessException(400, label + " Data URI 必须使用 base64");
        }
        String contentType = metadataParts[0].trim().toLowerCase(Locale.ROOT);
        if (audio) {
            contentType = switch (contentType) {
                case "audio/mpeg", "audio/mp3" -> "audio/mpeg";
                case "audio/wav", "audio/x-wav", "audio/wave" -> "audio/wav";
                default -> throw new BusinessException(400, "H3 REF 参考音频仅支持 MP3 或 WAV");
            };
        } else {
            imageExtension(contentType);
        }
        String encoded = value.substring(comma + 1);
        int maxEncodedLength = 4 * ((maxBytes + 2) / 3);
        if (encoded.length() > maxEncodedLength) {
            throw new BusinessException(400, label + "不能超过 " + maxMegabytes + "MB");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            requireSize(bytes.length, maxBytes, label);
            return new MediaBytes(bytes, contentType);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, label + " Data URI base64 无效");
        }
    }

    private MediaBytes downloadHttp(ApiConfig apiConfig, String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "ComfyUI 图片 URL 无效");
        }
        if (uri.getHost() == null) {
            throw new BusinessException(400, "ComfyUI 图片 URL 缺少主机");
        }
        Request request = new Request.Builder().url(value).header("Accept", "image/*").get().build();
        OkHttpClient client = AiProxySupport.okHttpClient(baseClient, apiConfig);
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new BusinessException(502,
                        "下载 ComfyUI 图片输入失败，HTTP " + response.code());
            }
            long length = response.body().contentLength();
            if (length > MAX_IMAGE_BYTES) {
                throw new BusinessException(400, "ComfyUI 单张输入图片不能超过 20MB");
            }
            byte[] bytes = readBounded(response.body().byteStream(), MAX_IMAGE_BYTES);
            requireSize(bytes.length, MAX_IMAGE_BYTES, "ComfyUI 单张输入图片");
            if (response.body().contentType() == null) {
                throw new BusinessException(502, "ComfyUI 图片输入响应缺少 Content-Type");
            }
            String contentType = response.body().contentType().toString()
                    .split(";", 2)[0].toLowerCase(Locale.ROOT);
            imageExtension(contentType);
            return new MediaBytes(bytes, contentType);
        } catch (IOException e) {
            throw new BusinessException(502,
                    "下载 ComfyUI 图片输入异常: " + StrUtil.blankToDefault(e.getMessage(), "I/O error"));
        }
    }

    private void requireSize(int size, int maxBytes, String label) {
        if (size <= 0 || size > maxBytes) {
            throw new BusinessException(400, label + "大小必须在 1B 到 " + (maxBytes / (1024 * 1024)) + "MB 之间");
        }
    }

    private String imageExtension(String contentType) {
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            case "image/bmp" -> "bmp";
            case "image/tiff" -> "tiff";
            default -> throw new BusinessException(400, "ComfyUI 输入文件不是受支持的栅格图片");
        };
    }

    static byte[] readBounded(InputStream input, int maxBytes) throws IOException {
        byte[] buffer = new byte[8192];
        int total = 0;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8192))) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (total > maxBytes - read) {
                    throw new BusinessException(400, "ComfyUI 单张输入图片不能超过 20MB");
                }
                output.write(buffer, 0, read);
                total += read;
            }
            return output.toByteArray();
        }
    }

    private String sanitizePart(String value) {
        String normalized = StrUtil.blankToDefault(value, "task")
                .replaceAll("[^a-zA-Z0-9_-]", "-");
        return normalized.getBytes(StandardCharsets.UTF_8).length > 64
                ? normalized.substring(0, 64) : normalized;
    }

    private record MediaBytes(byte[] bytes, String contentType) {
    }

    private record TemporaryFile(Path path) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            Files.deleteIfExists(path);
        }
    }
}
