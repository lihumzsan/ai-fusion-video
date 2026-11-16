package com.stonewu.fusion.service.ai.comfyui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.ai.ApiConfig;
import com.stonewu.fusion.service.ai.comfyui.client.ComfyUiNativeClient;
import com.stonewu.fusion.service.ai.comfyui.client.ComfyUiUploadResult;
import com.stonewu.fusion.service.media.MediaProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ComfyUiInputResourceServiceTests {

    @Mock
    private ComfyUiNativeClient nativeClient;
    private final MediaProbe mediaProbe = new MediaProbe(new ObjectMapper(), "ffprobe");

    @Test
    void uploadImagesDecodesSupportedDataUriBeforeNativeUpload() {
        ComfyUiInputResourceService service = new ComfyUiInputResourceService(nativeClient, mediaProbe);
        ApiConfig apiConfig = ApiConfig.builder().id(7L).build();
        byte[] image = "png-data".getBytes(StandardCharsets.UTF_8);
        String source = "data:image/png;base64," + Base64.getEncoder().encodeToString(image);
        when(nativeClient.uploadImage(any(), any(), any(), any(), any()))
                .thenReturn(new ComfyUiUploadResult("task-referenceImages-0.png",
                        "ai-fusion-video", "input"));

        assertThat(service.uploadImages(apiConfig, "task", "referenceImages", List.of(source)))
                .containsExactly("ai-fusion-video/task-referenceImages-0.png");

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(nativeClient).uploadImage(eq(apiConfig), bytes.capture(),
                eq("task-referenceImages-0.png"), eq("image/png"), eq("ai-fusion-video"));
        assertThat(bytes.getValue()).isEqualTo(image);
    }

    @Test
    void uploadsProbedReferenceAudioInInputOrder() throws IOException {
        var service = new ComfyUiInputResourceService(nativeClient, mediaProbe);
        var config = ApiConfig.builder().id(7L).build();
        when(nativeClient.uploadAudio(eq(config), any(), any(), eq("audio/wav"), eq("ai-fusion-video")))
                .thenAnswer(call -> new ComfyUiUploadResult(call.getArgument(2), "ai-fusion-video", "input"));

        assertThat(service.uploadH3ReferenceAudios(config, "test", List.of(waveDataUri(2), waveDataUri(3))))
                .containsExactly("ai-fusion-video/test-referenceAudios-0.wav", "ai-fusion-video/test-referenceAudios-1.wav");
    }

    @Test
    void rejectsShortAudioBeforeUploadingAnyReferences() throws IOException {
        var service = new ComfyUiInputResourceService(nativeClient, mediaProbe);
        List<String> sources = List.of(waveDataUri(2), waveDataUri(1));

        assertThatThrownBy(() -> service.uploadH3ReferenceAudios(ApiConfig.builder().build(), "test", sources))
                .isInstanceOf(BusinessException.class).hasMessageContaining("至少 2 秒");
        verifyNoInteractions(nativeClient);
    }

    @Test
    void rejectsAudioExceedingTheCombinedDurationBeforeUploading() throws IOException {
        var service = new ComfyUiInputResourceService(nativeClient, mediaProbe);
        List<String> sources = List.of(waveDataUri(8), waveDataUri(8));

        assertThatThrownBy(() -> service.uploadH3ReferenceAudios(ApiConfig.builder().build(), "test", sources))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不得超过 15 秒");
        verifyNoInteractions(nativeClient);
    }

    @Test
    void rejectsAudioWithAMismatchedDataUriMimeType() throws IOException {
        var service = new ComfyUiInputResourceService(nativeClient, mediaProbe);
        String mislabeledAudio = waveDataUri(2).replace("audio/wav", "audio/mpeg");

        assertThatThrownBy(() -> service.uploadH3ReferenceAudios(ApiConfig.builder().build(), "test", List.of(mislabeledAudio)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("音频内容与声明格式不符");
        verifyNoInteractions(nativeClient);
    }

    @Test
    void readBoundedStopsBeforeWritingPastConfiguredLimit() {
        byte[] body = "123456789".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> ComfyUiInputResourceService.readBounded(
                new ByteArrayInputStream(body), 8))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能超过 20MB");
    }

    private String waveDataUri(int seconds) throws IOException {
        int samples = 8_000 * seconds;
        AudioFormat format = new AudioFormat(8_000, 16, 1, true, false);
        try (var audio = new AudioInputStream(new ByteArrayInputStream(new byte[samples * 2]), format, samples);
             var output = new ByteArrayOutputStream()) {
            AudioSystem.write(audio, AudioFileFormat.Type.WAVE, output);
            return "data:audio/wav;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
        }
    }
}
