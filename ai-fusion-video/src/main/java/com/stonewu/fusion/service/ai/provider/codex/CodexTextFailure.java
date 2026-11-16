package com.stonewu.fusion.service.ai.provider.codex;

import com.stonewu.fusion.common.BusinessException;
import io.agentscope.core.model.ChatUsage;

/** A confirmed Codex text failure whose message is safe to display. */
public final class CodexTextFailure extends BusinessException {
    private final ChatUsage observedUsage;

    CodexTextFailure(int code, String message) {
        this(code, message, null);
    }

    CodexTextFailure(int code, String message, ChatUsage observedUsage) {
        super(code, message);
        this.observedUsage = observedUsage;
    }

    public ChatUsage observedUsage() {
        return observedUsage;
    }
}
