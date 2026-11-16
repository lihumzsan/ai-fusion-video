package com.stonewu.fusion.service.generation.job;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.entity.ai.ApiConfig;
import com.stonewu.fusion.service.ai.comfyui.ComfyUiInputResourceService;
import com.stonewu.fusion.service.canvas.CanvasJson;
import com.stonewu.fusion.service.canvas.model.CanvasActor;
import com.stonewu.fusion.service.generation.GenerationModelCapabilityService;
import com.stonewu.fusion.service.generation.ReferenceImageTransportService;
import com.stonewu.fusion.service.media.MediaInputTransport;
import com.stonewu.fusion.service.media.MediaIntakeService;
import com.stonewu.fusion.service.media.model.MediaKind;
import com.stonewu.fusion.service.resource.model.ResourceCommandContext;
import com.stonewu.fusion.service.storage.StorageConfigService;
import com.stonewu.fusion.service.system.PresetArtStyleResourceResolver;
import com.stonewu.fusion.service.system.SystemConfigService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class GenerationProviderInputsTests {
    private static final String IMAGE = "media://00000000-0000-0000-0000-000000000001";
    private static final String AUDIO = "media://00000000-0000-0000-0000-000000000002";
    private static final String VIDEO = "media://00000000-0000-0000-0000-000000000003";
    private static final String IMAGE_DATA = "data:image/png;base64,aW1hZ2U=";
    private static final String AUDIO_DATA = "data:audio/wav;base64,YXVkaW8=";
    private static final String VIDEO_DATA = "data:video/mp4;base64,dmlkZW8=";

    private final ObjectMapper json = new ObjectMapper();
    private final CanvasActor actor = new CanvasActor(11L, 23L);
    private final AiModel model = AiModel.builder().modelType(3).build();
    private final RemoteGenerationJob.Claim claim = new RemoteGenerationJob.Claim("job", "worker", 1);
    private final MediaInputTransport media = mock(MediaInputTransport.class);
    private final GenerationModelCapabilityService capabilities = mock(GenerationModelCapabilityService.class);
    private final GenerationProviderInputs inputs = new GenerationProviderInputs(new CanvasJson(json), capabilities,
            new ReferenceImageTransportService(mock(StorageConfigService.class), mock(SystemConfigService.class),
                    mock(PresetArtStyleResourceResolver.class), media),
            media, mock(GenerationInputVersions.class));

    @Test
    void h3ResolvesAuthorizedTaskReferencesAndIgnoresWorkflowSlotOverrides() {
        var body = json.createObjectNode()
                .put("referenceImageUrls", JSONUtil.toJsonStr(List.of(IMAGE)))
                .put("referenceAudioUrls", JSONUtil.toJsonStr(List.of(AUDIO)));
        var workflow = body.putObject("workflowInputs").put("duration", 4);
        workflow.putArray("referenceImages").add("https://example.test/ignored.png");
        workflow.putArray("referenceAudios").add("https://example.test/ignored.wav");
        var config = JSONUtil.createObj().set("h3Ref", true).set("referenceImageInputFormats", List.of("data_uri"));
        when(media.dataUri(IMAGE, actor, MediaKind.IMAGE, ComfyUiInputResourceService.MAX_IMAGE_BYTES))
                .thenReturn(IMAGE_DATA);
        when(media.dataUri(AUDIO, actor, MediaKind.AUDIO, ComfyUiInputResourceService.MAX_H3_REFERENCE_AUDIO_BYTES))
                .thenReturn(AUDIO_DATA);

        var result = inputs.video(job(body), submission(config), ApiConfig.builder().platform("comfyui").build(), true);

        assertThat(result.getReferenceImageUrls()).isEqualTo(JSONUtil.toJsonStr(List.of(IMAGE_DATA)));
        assertThat(result.getReferenceAudioUrls()).isEqualTo(JSONUtil.toJsonStr(List.of(AUDIO_DATA)));
        assertThat(result.getWorkflowInputs()).isEqualTo(json.createObjectNode().put("duration", 4));
        assertThat(workflow.has("referenceImages") && workflow.has("referenceAudios")).isTrue();
        verify(media).dataUri(IMAGE, actor, MediaKind.IMAGE, ComfyUiInputResourceService.MAX_IMAGE_BYTES);
        verify(media).dataUri(AUDIO, actor, MediaKind.AUDIO, ComfyUiInputResourceService.MAX_H3_REFERENCE_AUDIO_BYTES);
        verifyNoMoreInteractions(media);
    }

    @Test
    void otherProvidersKeepConfiguredVideoDataUrisAndSignedAudioUrls() {
        String audioUrl = "https://example.test/signed.wav";
        var body = json.createObjectNode().put("referenceVideoUrls", JSONUtil.toJsonStr(List.of(VIDEO)));
        body.putObject("workflowInputs").putArray("referenceAudios").add(AUDIO);
        var config = JSONUtil.createObj().set("referenceVideoInputFormats", List.of("data_uri"));
        when(media.dataUri(VIDEO, actor, MediaKind.VIDEO, MediaIntakeService.MAX_UPLOAD_BYTES)).thenReturn(VIDEO_DATA);
        when(media.generationUrl(AUDIO, actor, MediaKind.AUDIO, claim)).thenReturn(audioUrl);

        var result = inputs.video(job(body), submission(config), ApiConfig.builder().platform("gemini").build(), true);

        assertThat(result.getReferenceVideoUrls()).isEqualTo(JSONUtil.toJsonStr(List.of(VIDEO_DATA)));
        assertThat(result.getWorkflowInputs().path("referenceAudios").path(0).asText()).isEqualTo(audioUrl);
    }

    private RemoteGenerationJob job(ObjectNode body) {
        var result = mock(RemoteGenerationJob.class);
        when(result.input()).thenReturn(body);
        when(result.claim()).thenReturn(claim);
        return result;
    }

    private GenerationSubmission submission(JSONObject config) {
        var result = mock(GenerationSubmission.class);
        when(result.context()).thenReturn(ResourceCommandContext.user(actor, null));
        when(result.model()).thenReturn(model);
        when(capabilities.getMergedModelConfig(model)).thenReturn(config);
        return result;
    }
}
