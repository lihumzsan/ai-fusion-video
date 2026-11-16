package com.stonewu.fusion.service.ai.comfyui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.fusion.entity.ai.ComfyUiWorkflow;
import com.stonewu.fusion.entity.ai.ComfyUiWorkflowVersion;
import com.stonewu.fusion.service.ai.comfyui.test.ComfyUiTestInputs;
import com.stonewu.fusion.service.canvas.model.CanvasActor;
import com.stonewu.fusion.service.media.MediaInputTransport;
import com.stonewu.fusion.service.media.MediaReference;
import com.stonewu.fusion.service.media.MediaResourceQueries;
import com.stonewu.fusion.service.media.model.MediaException;
import com.stonewu.fusion.service.media.model.MediaFacts;
import com.stonewu.fusion.service.media.model.MediaKind;
import com.stonewu.fusion.service.media.model.MediaResource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ComfyUiH3TestInputsTests {
    private static final CanvasActor ACTOR = new CanvasActor(1, null);
    private static final String AUDIO_ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String IMAGE = "data:image/png;base64,aW1hZ2U=";
    private static final String AUDIO = "data:audio/wav;base64,YXVkaW8=";
    private static final String PROMPT = "subject_definitions:\n<Subject 1> is represented by <Picture 1>.";

    private final MediaResourceQueries media = mock(MediaResourceQueries.class);
    private final MediaInputTransport transport = mock(MediaInputTransport.class);
    private final ComfyUiTestInputs inputs;
    private final ComfyUiWorkflow workflow = ComfyUiWorkflow.builder().modelType(3).build();

    ComfyUiH3TestInputsTests() {
        var mapper = new ObjectMapper();
        var documents = new ComfyUiWorkflowDocumentService(mapper);
        inputs = new ComfyUiTestInputs(documents, new ComfyUiWorkflowRenderer(mapper, documents), media, transport);
    }

    @Test
    void trialFreezesManagedReferenceAudioAndResolvesBytesAtExecution() throws IOException {
        String reference = MediaReference.of(AUDIO_ID);
        when(media.get(AUDIO_ID, ACTOR)).thenReturn(audio(2));
        when(transport.dataUri(eq(reference), eq(ACTOR), eq(MediaKind.AUDIO), anyLong())).thenReturn(AUDIO);

        var snapshot = inputs.freeze(workflow, version(), Map.of("prompt", PROMPT, "referenceImages", List.of(IMAGE),
                "referenceAudios", List.of(reference)), ACTOR);

        assertThat(snapshot.mediaVersions()).containsExactlyEntriesOf(Map.of(AUDIO_ID, 2));
        assertThat(inputs.resolve(snapshot, ACTOR)).containsEntry("referenceAudios", List.of(AUDIO));
        when(media.get(AUDIO_ID, ACTOR)).thenReturn(audio(3));
        assertThatThrownBy(() -> inputs.resolve(snapshot, ACTOR)).isInstanceOf(MediaException.class)
                .hasMessageContaining("版本已变化");
    }

    @Test
    void trialRejectsExternalReferenceAudioUrls() throws IOException {
        var version = version();
        assertThatThrownBy(() -> inputs.freeze(workflow, version, Map.of("prompt", PROMPT,
                "referenceImages", List.of(IMAGE), "referenceAudios", List.of("https://example.com/audio.mp3")),
                ACTOR)).isInstanceOf(MediaException.class).hasMessageContaining("音频");
    }

    private ComfyUiWorkflowVersion version() throws IOException {
        try (var resource = getClass().getResourceAsStream("/comfyui/h3-reference-t8-dual-stage-2mp.json")) {
            return ComfyUiWorkflowVersion.builder().apiWorkflowJson(new String(resource.readAllBytes(),
                    StandardCharsets.UTF_8)).inputBindingsJson("{}").build();
        }
    }

    private MediaResource audio(int version) {
        return new MediaResource(AUDIO_ID, null, 1, 1L, 1L, "reference.wav",
                new MediaFacts(MediaKind.AUDIO, "audio/wav", 1024, "hash", null, null, 2500L, true),
                "READY", version, null, null, null);
    }
}
