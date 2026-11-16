package com.stonewu.fusion.service.ai.provider;

import com.stonewu.fusion.service.ai.provider.codex.CodexAppServerClient;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 提供本机 Codex 接入及 ChatGPT 登录状态。 */
@Service
@RequiredArgsConstructor
public class CodexStatusService {
    private final ObjectProvider<CodexAppServerClient> clientProvider;

    /** 只返回状态，不读取或返回账号身份及凭据。 */
    @Transactional(propagation = Propagation.NEVER)
    public CodexStatus status() {
        var client = clientProvider.getIfAvailable();
        return new CodexStatus(client != null, client != null && client.isChatGptLoggedIn());
    }

    /** 接入能力和当前登录状态。 */
    public record CodexStatus(boolean available, boolean signedIn) {
    }
}
