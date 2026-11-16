package com.stonewu.fusion.service.ai.comfyui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.service.ai.comfyui.client.ComfyUiJobResult;
import com.stonewu.fusion.service.ai.comfyui.client.ComfyUiNativeClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ComfyUiVoiceDesignTests {
    private static final String PROMPT_ID = "12345678-9abc-4123-8123-0123456789ab";
    private final ObjectMapper json = new ObjectMapper();
    private final ComfyUiVoiceDesign voice = new ComfyUiVoiceDesign(
            json, mock(ComfyUiNativeClient.class), new ComfyUiOutputResolver());

    @Test
    void rendersVoiceInstructionAndDialogueToTheQwenNodeWithItsOwnOutput() {
        var graph = voice.render("温和，语速偏慢", "今晚风很轻，我们再走一段吧。", "Chinese", PROMPT_ID);

        assertThat(graph.at("/1/class_type").asText()).isEqualTo("FB_Qwen3TTSVoiceDesign");
        assertThat(graph.at("/1/inputs/instruct").asText()).isEqualTo("温和，语速偏慢");
        assertThat(graph.at("/1/inputs/text").asText()).isEqualTo("今晚风很轻，我们再走一段吧。");
        assertThat(graph.at("/1/inputs/language").asText()).isEqualTo("Chinese");
        assertThat(graph.at("/1/inputs/model_choice").asText()).isEqualTo("1.7B");
        assertThat(graph.at("/1/inputs/seed").asLong()).isEqualTo(0x123456789abcL);
        assertThat(graph.at("/2/class_type").asText()).isEqualTo("SaveAudio");
        assertThat(graph.at("/2/inputs/audio/0").asText()).isEqualTo("1");
        assertThat(graph.at("/2/inputs/audio/1").asInt()).isZero();
        assertThat(graph.at("/2/inputs/filename_prefix").asText()).endsWith("/" + PROMPT_ID);
    }

    @Test
    void rejectsUnsupportedLanguageAndBlankDialogue() {
        assertThatThrownBy(() -> voice.render("低音", "我们走吧。", "Mandarin", PROMPT_ID))
                .isInstanceOf(BusinessException.class).hasMessageContaining("语言");
        assertThatThrownBy(() -> voice.render("低音", "   ", "Auto", PROMPT_ID))
                .isInstanceOf(BusinessException.class).hasMessageContaining("试听台词");
    }

    @Test
    void acceptsOnlyTheNativeSaveAudioFlacOutput() {
        var outputs = json.createObjectNode();
        outputs.putObject("1").putArray("audio").addObject()
                .put("filename", "other.flac").put("subfolder", "").put("type", "output");
        var descriptor = outputs.putObject("2").putArray("audio").addObject()
                .put("filename", "voice.flac").put("subfolder", "voice").put("type", "output");
        var job = new ComfyUiJobResult(PROMPT_ID, "completed", outputs, null, json.createObjectNode());

        assertThat(voice.output(job).filename()).isEqualTo("voice.flac");
        descriptor.put("filename", "voice.mp3");
        assertThatThrownBy(() -> voice.output(job))
                .isInstanceOf(BusinessException.class).hasMessageContaining("FLAC");
    }
}
