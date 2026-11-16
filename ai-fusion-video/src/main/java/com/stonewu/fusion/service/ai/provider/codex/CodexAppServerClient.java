package com.stonewu.fusion.service.ai.provider.codex;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.fusion.common.BusinessException;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ToolSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static java.nio.charset.StandardCharsets.UTF_8;

/** Runs a single ephemeral Codex turn using the local desktop ChatGPT login. */
@Component
@Profile("local & !docker")
@Slf4j
public final class CodexAppServerClient {

    private static final Duration LOGIN_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration TURN_TIMEOUT = Duration.ofMinutes(5);
    private static final int MAX_TEXT_LENGTH = 1_000_000;
    private static final int MAX_PROTOCOL_LINE_LENGTH = 32 * 1024 * 1024;
    private static final String CHATGPT_API_URL = "https://chatgpt.com/backend-api/";
    private static final Set<String> CHILD_ENVIRONMENT = Set.of("HOME", "PATH", "USER", "LOGNAME", "TMPDIR", "LANG",
            "LC_ALL", "LC_CTYPE", "CODEX_HOME");
    private static final Set<String> ALLOWED_ITEMS = Set.of("userMessage", "agentMessage", "reasoning",
            "contextCompaction");
    private static final List<String> LAUNCH_ARGUMENTS = List.of(
            "-c", "model_provider=\"openai\"", "-c", "forced_login_method=\"chatgpt\"",
            "-c", "chatgpt_base_url=\"" + CHATGPT_API_URL + "\"",
            "-c", "web_search=\"disabled\"", "-c", "notify=[]", "-c", "project_doc_max_bytes=0",
            "--disable", "shell_tool", "--disable", "unified_exec", "--disable", "shell_snapshot",
            "--disable", "hooks", "--disable", "apps", "--disable", "plugins", "--disable", "image_generation",
            "--disable", "multi_agent", "--disable", "skill_search", "--disable", "skill_mcp_dependency_install",
            "--disable", "standalone_web_search", "--disable", "browser_use", "--disable", "sleep_tool",
            "--disable", "memories", "app-server", "--listen", "stdio://");
    private static final String BASE_INSTRUCTIONS = "Respond as a text-generation model using only the provided "
            + "messages and images. Do not access files, execute commands, invoke Codex tools, or take actions "
            + "outside this conversation. The host may ask you to describe a platform tool call in structured output; "
            + "the host executes such calls itself.";

    private final ObjectMapper json;
    private final String executable;
    private final Duration turnTimeout;

    @Autowired
    public CodexAppServerClient(ObjectMapper json, @Value("${fusion.codex.executable:codex}") String executable) {
        this(json, executable, TURN_TIMEOUT);
    }

    CodexAppServerClient(ObjectMapper json, String executable, Duration turnTimeout) {
        this.json = json;
        this.executable = executable;
        this.turnTimeout = turnTimeout;
    }

    /** Checks the active account without initiating login or refreshing a token. */
    public boolean isChatGptLoggedIn() {
        return run(new ChildProcess(), LOGIN_TIMEOUT, Session::isChatGptLoggedIn);
    }

    public void requireChatGptLogin() {
        requireLogin(isChatGptLoggedIn());
    }

    /** Returns the completed answer and its usage only after an accepted terminal notification. */
    public Flux<ChatResponse> stream(String modelName, List<Msg> messages) {
        return stream(modelName, messages, List.of(), null);
    }

    /** 每个采样使用临时回合。业务工具仅作为模型输出，由 AgentScope 执行。 */
    public Flux<ChatResponse> stream(String modelName, List<Msg> messages, List<ToolSchema> tools,
                                     String reasoningEffort) {
        return Flux.<ChatResponse>create(sink -> {
            var process = new ChildProcess();
            sink.onCancel(process::stop);
            if (sink.isCancelled()) {
                return;
            }
            try {
                if (modelName == null || modelName.isBlank()) {
                    throw new CodexTextFailure(400, "请配置 Codex 模型代码");
                }
                var decision = tools.isEmpty() ? null : new CodexToolDecision(json, tools);
                var input = CodexConversation.prepare(json, messages, decision);
                ChatResponse response = run(process, turnTimeout, session -> {
                    requireLogin(session.isChatGptLoggedIn());
                    return session.complete(modelName, input, decision, reasoningEffort);
                });
                sink.next(response);
                sink.complete();
            } catch (BusinessException exception) {
                sink.error(exception);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** Owns the process, private working directory and deadline for an entire invocation. */
    private <T> T run(ChildProcess child, Duration timeout, SessionOperation<T> operation) {
        Path workspace = null;
        Disposable deadline = Schedulers.parallel().schedule(child::expire, timeout.toMillis(), TimeUnit.MILLISECONDS);
        try {
            workspace = Files.createTempDirectory("fusion-codex-", PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
            List<String> command = new ArrayList<>(LAUNCH_ARGUMENTS.size() + 1);
            command.add(executable);
            command.addAll(LAUNCH_ARGUMENTS);
            var builder = new ProcessBuilder(command).directory(workspace.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().keySet().retainAll(CHILD_ENVIRONMENT);
            Process process = child.start(builder);
            return operation.apply(new Session(process, workspace, child::beginSubmission));
        } catch (IOException exception) {
            if (child.timedOut()) {
                throw child.classify(new BusinessException(504, "本机 Codex 请求超时"));
            }
            throw child.classify(new BusinessException(502,
                    "本机 Codex App Server 无法启动或连接已中断，请检查 Codex 安装及登录状态"));
        } catch (BusinessException exception) {
            throw child.classify(exception);
        } finally {
            deadline.dispose();
            child.stop();
            child.awaitExit();
            if (workspace != null) {
                try {
                    FileSystemUtils.deleteRecursively(workspace);
                } catch (IOException exception) {
                    log.warn("无法清理本机 Codex 临时工作目录");
                }
            }
        }
    }

    private static void requireLogin(boolean loggedIn) {
        if (!loggedIn) {
            throw new CodexTextFailure(400, "请先在本机 Codex 登录 ChatGPT 账号");
        }
    }

    private static BusinessException protocolError() {
        return new BusinessException(502, "本机 Codex App Server 返回了无效协议数据");
    }

    private static String requiredString(JsonNode node) {
        if (!node.isTextual() || node.asText().isBlank()) {
            throw protocolError();
        }
        return node.asText();
    }

    private final class Session {

        private final Path workspace;
        private final BufferedReader reader;
        private final BufferedWriter writer;
        private final Runnable beginSubmission;
        private final JsonNode disabledMcp;
        private int requestId;

        private Session(Process process, Path workspace, Runnable beginSubmission) throws IOException {
            this.workspace = workspace;
            this.beginSubmission = beginSubmission;
            reader = new BufferedReader(new InputStreamReader(process.getInputStream(), UTF_8));
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), UTF_8));
            var initialize = json.createObjectNode();
            initialize.putObject("clientInfo").put("name", "ai-fusion-video").put("version", "1.0");
            request("initialize", initialize, null);
            send(json.createObjectNode().put("method", "initialized"));
            disabledMcp = readRestrictedConfiguration();
        }

        private boolean isChatGptLoggedIn() throws IOException {
            JsonNode account = request("account/read", json.createObjectNode().put("refreshToken", false), null)
                    .path("account");
            return !account.isMissingNode() && !account.isNull() && "chatgpt".equals(requiredString(account.path("type")));
        }

        private ChatResponse complete(String modelName, CodexConversation input, CodexToolDecision decision,
                                      String reasoningEffort) throws IOException {
            var start = json.createObjectNode().put("model", modelName).put("modelProvider", "openai")
                    .put("cwd", workspace.toString()).put("sandbox", "read-only").put("approvalPolicy", "never")
                    .put("ephemeral", true).put("baseInstructions", BASE_INSTRUCTIONS);
            start.putObject("config").put("chatgpt_base_url", CHATGPT_API_URL).set("mcp_servers", disabledMcp);
            if (!input.instructions().isBlank()) {
                start.put("developerInstructions", input.instructions());
            }
            String threadId = requiredString(request("thread/start", start, null).path("thread").path("id"));
            var startTurn = json.createObjectNode().put("threadId", threadId);
            startTurn.set("input", input.content());
            if (decision != null) {
                startTurn.set("outputSchema", decision.schema());
            }
            if (reasoningEffort != null && !reasoningEffort.isBlank()) {
                startTurn.put("effort", reasoningEffort);
            }
            List<JsonNode> earlyEvents = new ArrayList<>();
            beginSubmission.run();
            String turnId = requiredString(request("turn/start", startTurn, event -> {
                if (earlyEvents.size() >= 64) {
                    throw protocolError();
                }
                earlyEvents.add(event);
            }).path("turn").path("id"));
            var turn = new TurnOutput(threadId, turnId);
            for (JsonNode event : earlyEvents) {
                if (turn.consume(event)) {
                    return turn.response(decision);
                }
            }
            while (!turn.consume(receive())) {
                // The terminal event is the completion boundary of this invocation.
            }
            return turn.response(decision);
        }

        private JsonNode readRestrictedConfiguration() throws IOException {
            var params = json.createObjectNode().put("cwd", workspace.toString()).put("includeLayers", false);
            JsonNode config = request("config/read", params, null).path("config");
            String endpoint = config.path("chatgpt_base_url").asText();
            String openAiOverride = config.path("openai_base_url").asText("");
            if ((!CHATGPT_API_URL.equals(endpoint) && !(endpoint + "/").equals(CHATGPT_API_URL))
                    || !openAiOverride.isBlank()) {
                throw new CodexTextFailure(400, "本机 Codex 文本模型仅支持官方 ChatGPT 地址，请检查 Codex 端点配置");
            }
            JsonNode servers = config.path("mcp_servers");
            if (!servers.isObject()) {
                throw protocolError();
            }
            // Codex merges config tables. An empty table leaves inherited MCP entries active.
            ObjectNode overrides = json.createObjectNode();
            servers.fieldNames().forEachRemaining(name -> overrides.putObject(name).put("enabled", false));
            return overrides;
        }

        private JsonNode request(String method, ObjectNode params, Consumer<JsonNode> notifications) throws IOException {
            int id = ++requestId;
            var request = json.createObjectNode().put("id", id).put("method", method);
            request.set("params", params);
            send(request);
            while (true) {
                JsonNode response = receive();
                if (response.has("method")) {
                    verifyItem(response);
                    if (notifications != null) {
                        notifications.accept(response);
                    }
                    continue;
                }
                if (!response.path("id").isIntegralNumber() || response.path("id").asInt() != id) {
                    throw protocolError();
                }
                if (response.has("error")) {
                    throw new BusinessException(502, "本机 Codex 请求失败（" + method + "）");
                }
                if (!response.path("result").isObject()) {
                    throw protocolError();
                }
                return response.path("result");
            }
        }

        private JsonNode receive() throws IOException {
            var line = new StringBuilder();
            int next;
            while ((next = reader.read()) != '\n') {
                if (next == -1) {
                    throw new IOException("Codex stdout closed");
                }
                if (line.length() == MAX_PROTOCOL_LINE_LENGTH) {
                    throw protocolError();
                }
                line.append((char) next);
            }
            JsonNode message;
            try {
                message = json.readTree(line.toString());
            } catch (JsonProcessingException malformed) {
                throw protocolError();
            }
            if (message == null || !message.isObject()) {
                throw protocolError();
            }
            if (message.has("id") && message.has("method")) {
                throw new BusinessException(502, "本机 Codex 请求了工具或交互，文本任务已停止");
            }
            return message;
        }

        private void send(ObjectNode message) throws IOException {
            writer.write(json.writeValueAsString(message));
            writer.newLine();
            writer.flush();
        }
    }

    private static void verifyItem(JsonNode event) {
        switch (event.path("method").asText()) {
            case "item/started", "item/completed" -> {
                String type = requiredString(event.path("params").path("item").path("type"));
                if (!ALLOWED_ITEMS.contains(type)) {
                    throw new BusinessException(502, "本机 Codex 尝试使用工具，文本任务已停止");
                }
            }
            default -> { }
        }
    }

    private static final class TurnOutput {

        private final String threadId;
        private final String turnId;
        private String finalAnswer;
        private String unknownPhaseAnswer;
        private ChatUsage usage;

        private TurnOutput(String threadId, String turnId) {
            this.threadId = threadId;
            this.turnId = turnId;
        }

        private boolean consume(JsonNode event) {
            if (!event.path("method").isTextual()) {
                throw protocolError();
            }
            verifyItem(event);
            JsonNode params = event.path("params");
            if (!threadId.equals(params.path("threadId").asText())) {
                return false;
            }
            String method = event.path("method").asText();
            if ("turn/completed".equals(method)) {
                JsonNode turn = params.path("turn");
                if (!turnId.equals(requiredString(turn.path("id")))) {
                    throw protocolError();
                }
                if (!"completed".equals(requiredString(turn.path("status")))) {
                    throw new CodexTextFailure(502, "本机 Codex 未完成文本生成，请检查 Codex 账号、模型与连接状态", usage);
                }
                return true;
            }
            if (!turnId.equals(params.path("turnId").asText())) {
                return false;
            }
            if ("item/completed".equals(method) && "agentMessage".equals(params.path("item").path("type").asText())) {
                JsonNode item = params.path("item");
                JsonNode text = item.path("text");
                if (!text.isTextual()) {
                    throw protocolError();
                }
                if (text.asText().length() > MAX_TEXT_LENGTH) {
                    throw new BusinessException(502, "本机 Codex 文本输出超过大小限制");
                }
                JsonNode phase = item.path("phase");
                if ("final_answer".equals(phase.asText())) {
                    finalAnswer = text.asText();
                } else if (phase.isMissingNode() || phase.isNull()) {
                    // The native schema permits providers to leave the message phase unspecified.
                    unknownPhaseAnswer = text.asText();
                }
            } else if ("thread/tokenUsage/updated".equals(method)) {
                JsonNode total = params.path("tokenUsage").path("total");
                int inputTokens = tokenCount(total.path("inputTokens"));
                int outputTokens = tokenCount(total.path("outputTokens"));
                usage = new ChatUsage(inputTokens, outputTokens, 0d);
            }
            return false;
        }

        private ChatResponse response(CodexToolDecision decision) {
            String text = finalAnswer != null ? finalAnswer : unknownPhaseAnswer;
            if (text == null || text.isBlank()) {
                throw new CodexTextFailure(502, "本机 Codex 已结束但没有返回文本", usage);
            }
            if (decision != null) {
                return decision.response(text, usage);
            }
            return ChatResponse.builder().content(List.of(TextBlock.builder().text(text).build()))
                    .usage(usage).finishReason("stop").build();
        }

        private static int tokenCount(JsonNode node) {
            if (!node.isIntegralNumber() || !node.canConvertToInt() || node.asInt() < 0) {
                throw protocolError();
            }
            return node.asInt();
        }
    }

    private static final class ChildProcess {

        private Process process;
        private boolean stopped;
        private boolean timedOut;
        private boolean submitted;

        private synchronized Process start(ProcessBuilder builder) throws IOException {
            if (stopped) {
                throw new IOException("Codex invocation stopped");
            }
            process = builder.start();
            return process;
        }

        private synchronized boolean timedOut() {
            return timedOut;
        }

        private void beginSubmission() {
            submitted = true;
        }

        private BusinessException classify(BusinessException failure) {
            if (submitted || failure instanceof CodexTextFailure) {
                return failure;
            }
            return new CodexTextFailure(failure.getCode(), failure.getMessage());
        }

        private synchronized void expire() {
            timedOut = true;
            stop();
        }

        private synchronized void stop() {
            if (stopped) {
                return;
            }
            stopped = true;
            if (process != null) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }

        private void awaitExit() {
            if (process == null) {
                return;
            }
            try {
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    log.warn("本机 Codex 子进程未在清理期限内退出");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @FunctionalInterface
    private interface SessionOperation<T> {
        T apply(Session session) throws IOException;
    }
}
