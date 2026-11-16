package com.stonewu.fusion.service.ai.provider;

import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.controller.ai.vo.RemoteModelVO;
import com.stonewu.fusion.service.ai.provider.codex.CodexAppServerClient;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.stonewu.fusion.service.ai.model.AiModelMetadata.CODEX;

/** 通过本机 ChatGPT 登录为平台文本模块提供 Codex 模型。 */
@Component
@Profile("local & !docker")
@RequiredArgsConstructor
public class CodexAiProvider implements AiProvider {

    private final CodexAppServerClient client;

    @Override
    public boolean supports(String platform) {
        return CODEX.equals(platform);
    }

    @Override
    public ChatModel createChatModel(AiProviderContext context) {
        throw new BusinessException(400, "本机 Codex 文本调用需通过 AgentScope 模型接口");
    }

    /** 单次请求在提交所用的会话中检查登录，其他用法仍在创建模型时检查。 */
    @Override
    public ChatModelBase createAgentScopeModel(AiProviderContext context) {
        if (!Integer.valueOf(1).equals(context.getModel().getModelType())) {
            throw new BusinessException(400, "本机 Codex 只支持文本模型");
        }
        if (!context.isSingleRequest()) {
            client.requireChatGptLogin();
        }
        return new CodexTextModel(context, client);
    }

    @Override
    public List<RemoteModelVO> listRemoteModels(AiProviderContext context) {
        throw new BusinessException(400, "请手动填写 Codex 模型标识");
    }

    private static final class CodexTextModel extends ChatModelBase {
        private final String modelName;
        private final CodexAppServerClient client;
        private final boolean singleRequest;
        private final String reasoningEffort;
        private final AtomicBoolean submitted = new AtomicBoolean();

        private CodexTextModel(AiProviderContext context, CodexAppServerClient client) {
            modelName = context.getModelName();
            singleRequest = context.isSingleRequest();
            reasoningEffort = context.getConfig().get("reasoningEffort") instanceof String effort ? effort : null;
            this.client = client;
            if (context.getModel().getContextWindow() != null) {
                setContextWindowSize(context.getModel().getContextWindow());
            }
        }

        @Override
        protected Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.defer(() -> {
                if (singleRequest && !submitted.compareAndSet(false, true)) {
                    return Flux.error(new IllegalStateException("此 Codex 请求已提交，不能自动重发"));
                }
                String effort = options != null && options.getReasoningEffort() != null
                        ? options.getReasoningEffort() : reasoningEffort;
                return client.stream(modelName, messages, tools == null ? List.of() : tools, effort);
            });
        }

        @Override
        public String getModelName() {
            return modelName;
        }
    }
}
