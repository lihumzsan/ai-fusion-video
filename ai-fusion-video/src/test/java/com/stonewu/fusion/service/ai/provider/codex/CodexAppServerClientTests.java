package com.stonewu.fusion.service.ai.provider.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.service.ai.agentscope.message.AgentModelCallFailure;
import com.stonewu.fusion.service.ai.agentscope.message.TrackedAgentChatModel;
import com.stonewu.fusion.service.ai.provider.AiProviderContext;
import com.stonewu.fusion.service.ai.provider.CodexAiProvider;
import com.stonewu.fusion.service.ai.run.model.AgentModelRequests;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CodexAppServerClientTests {

    @TempDir
    private Path temp;

    @Test
    void sendsPreviousAssistantAndUserMessagesWithTheirRoles() throws IOException {
        var codex = fixture("chatgpt", """
                case "$request" in *'assistant'*'earlier response'*'user'*'current request'*) ;; *) exit 21 ;; esac
                printf '%s\\n' '{"method":"item/completed","params":{"threadId":"thread-1","turnId":"turn-1","item":{"type":"agentMessage","phase":"final_answer","text":"Related reply"}}}'
                printf '%s\\n' '{"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"completed"}}}'
                """);
        var prior = Msg.builder().role(MsgRole.ASSISTANT).textContent("earlier response").build();
        var next = Msg.builder().role(MsgRole.USER).textContent("current request").build();

        StepVerifier.create(codex.stream("fixture-model", List.of(user(), prior, next)))
                .assertNext(response -> assertThat(response.getContent()).singleElement()
                        .extracting(block -> ((TextBlock) block).getText()).isEqualTo("Related reply"))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void returnsAnAgentScopeToolCallAndSendsItsResultOnTheNextRequest() throws IOException {
        var tool = ToolSchema.builder().name("lookup_color").description("Read the requested color")
                .parameters(Map.of("type", "object", "properties", Map.of("item", Map.of("type", "string")))).build();
        var codex = fixture("chatgpt", decision("""
                {"text":"","calls":[{"name":"lookup_color","argumentsJson":"{\\"item\\":\\"badge\\"}"}]}
                """, "case \"$request\" in *'lookup_color'*'\"outputSchema\"'*'\"effort\":\"low\"'*) ;; *) exit 22 ;; esac"));
        var messages = List.of(user());
        var response = codex.stream("fixture-model", messages, List.of(tool), "low").single()
                .block(Duration.ofSeconds(10));
        assertThat(response.getFinishReason()).isEqualTo("tool_calls");
        assertThat(response.getContent()).hasSize(1);
        var call = (ToolUseBlock) response.getContent().getFirst();
        assertThat(call.getName()).isEqualTo("lookup_color");
        assertThat(call.getInput()).containsExactlyEntriesOf(Map.of("item", "badge"));
        assertThat(call.getId()).startsWith("call_");

        var assistant = Msg.builder().role(MsgRole.ASSISTANT).content(call).build();
        var result = Msg.builder().role(MsgRole.TOOL)
                .content(ToolResultBlock.text("sky blue").withIdAndName(call.getId(), call.getName())).build();
        var followup = fixture("chatgpt", decision("""
                {"text":"The badge is sky blue.","calls":[]}
                """, "case \"$request\" in *'tool_call'*'" + call.getId() + "'*'tool_result'*'" + call.getId()
                + "'*'sky blue'*) ;; *) exit 23 ;; esac"));

        StepVerifier.create(followup.stream("fixture-model", List.of(user(), assistant, result), List.of(tool), null))
                .assertNext(next -> {
                    assertThat(next.getContent()).singleElement()
                            .extracting(block -> ((TextBlock) block).getText()).isEqualTo("The badge is sky blue.");
                    assertThat(next.getFinishReason()).isEqualTo("stop");
                })
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void rejectsTheEntireDecisionWhenAnyToolIsNotAvailable() throws IOException {
        var tool = ToolSchema.builder().name("lookup_color").description("Read the requested color")
                .parameters(Map.of("type", "object")).build();
        var codex = fixture("chatgpt", decision("""
                {"text":"","calls":[{"name":"lookup_color","argumentsJson":"{}"},
                {"name":"another_tool","argumentsJson":"{}"}]}
                """, ""));

        StepVerifier.create(codex.stream("fixture-model", List.of(user()), List.of(tool), null))
                .expectErrorMatches(error -> error instanceof CodexTextFailure
                        && error.getMessage().contains("本轮工具未执行"))
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void returnsOnlyTheCompletedFinalAnswer() throws IOException {
        var codex = fixture("chatgpt", """
                printf '%s\\n' '{"method":"item/completed","params":{"threadId":"thread-1","turnId":"turn-1","item":{"id":"progress","type":"agentMessage","phase":"commentary","text":"Checking"}}}'
                printf '%s\\n' '{"method":"item/completed","params":{"threadId":"thread-1","turnId":"turn-1","item":{"id":"final","type":"agentMessage","phase":"final_answer","text":"OK"}}}'
                printf '%s\\n' '{"method":"thread/tokenUsage/updated","params":{"threadId":"thread-1","turnId":"turn-1","tokenUsage":{"total":{"inputTokens":17,"outputTokens":9,"totalTokens":26}}}}'
                printf '%s\\n' '{"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"completed"}}}'
                """);
        var instruction = Msg.builder().role(MsgRole.SYSTEM).textContent("只回复 OK").build();
        var image = ImageBlock.builder().source(Base64Source.builder().mediaType("image/png")
                .data("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a52cAAAAASUVORK5CYII=")
                .build()).build();
        var user = Msg.builder().role(MsgRole.USER).content(List.of(text(), image)).build();

        StepVerifier.create(codex.stream("fixture-model", List.of(instruction, user)))
                .assertNext(response -> {
                    assertThat(response.getContent()).singleElement()
                            .extracting(block -> ((TextBlock) block).getText()).isEqualTo("OK");
                    assertThat(response.getFinishReason()).isEqualTo("stop");
                    assertThat(response.getUsage().getTotalTokens()).isEqualTo(26);
                })
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void standaloneStatusReportsNonChatGptAccount() throws IOException {
        var codex = fixture("apiKey", "");

        assertThat(codex.isChatGptLoggedIn()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void singleRequestChecksAccountInTheSubmittingSession(boolean signedIn) throws IOException {
        var codex = fixture(signedIn ? "chatgpt" : "apiKey", """
                printf '%s\\n' '{"method":"item/completed","params":{"threadId":"thread-1","turnId":"turn-1","item":{"type":"agentMessage","phase":"final_answer","text":"OK"}}}'
                printf '%s\\n' '{"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"completed"}}}'
                """);
        var context = AiProviderContext.builder().model(AiModel.builder().modelType(1).build())
                .modelName("fixture-model").singleRequest(true).build();
        var model = new CodexAiProvider(codex).createAgentScopeModel(context);
        var response = StepVerifier.create(model.stream(List.of(user()), List.of(), null));
        if (signedIn) {
            response.assertNext(result -> assertThat(result.getContent()).singleElement()
                            .extracting(block -> ((TextBlock) block).getText()).isEqualTo("OK"))
                    .expectComplete().verify(Duration.ofSeconds(10));
            assertThat(Files.readAllLines(temp.resolve("requests.log")))
                    .containsExactly("account/read", "thread/start", "turn/start");
        } else {
            response.expectErrorMatches(error -> error instanceof CodexTextFailure && error.getMessage().contains("ChatGPT"))
                    .verify(Duration.ofSeconds(10));
            assertThat(Files.readAllLines(temp.resolve("requests.log"))).containsExactly("account/read");
        }
    }

    @Test
    void rejectsExternalImageBeforeSpawningRuntime() {
        var codex = new CodexAppServerClient(new ObjectMapper(), "invalid-codex-executable", Duration.ofSeconds(5));
        var image = ImageBlock.builder().source(URLSource.builder().url("https://example.invalid/image.png")
                .mimeType("image/png").build()).build();
        var message = Msg.builder().role(MsgRole.USER).content(List.of(text(), image)).build();

        StepVerifier.create(codex.stream("fixture-model", List.of(message)))
                .expectErrorMatches(error -> error instanceof CodexTextFailure && error.getMessage().contains("Base64"))
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void refusesToolExecutionEvenIfTurnWouldReportSuccess() throws IOException {
        var codex = fixture("chatgpt", """
                printf '%s\\n' '{"method":"item/started","params":{"threadId":"thread-1","turnId":"turn-1","item":{"type":"commandExecution","id":"tool-1"}}}'
                printf '%s\\n' '{"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"completed"}}}'
                """);

        StepVerifier.create(codex.stream("fixture-model", List.of(user())))
                .expectErrorMatches(error -> error instanceof BusinessException && error.getMessage().contains("工具"))
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void doesNotSignalCompletionForFailedTurn() throws IOException {
        var codex = fixture("chatgpt", """
                printf '%s\\n' '{"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"failed","error":{"message":"private data"}}}}'
                """);

        StepVerifier.create(codex.stream("fixture-model", List.of(user())))
                .expectErrorMatches(error -> error instanceof CodexTextFailure && !error.getMessage().contains("private data"))
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void doesNotTreatCommentaryAsACompletedAnswer() throws IOException {
        var codex = fixture("chatgpt", """
                printf '%s\\n' '{"method":"item/completed","params":{"threadId":"thread-1","turnId":"turn-1","item":{"id":"progress","type":"agentMessage","phase":"commentary","text":"Checking"}}}'
                printf '%s\\n' '{"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"completed"}}}'
                """);

        StepVerifier.create(codex.stream("fixture-model", List.of(user())))
                .expectErrorMatches(error -> error instanceof CodexTextFailure && error.getMessage().contains("没有返回文本"))
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void rejectsNonOfficialChatGptEndpointBeforeReadingAccount() throws IOException {
        var codex = fixture("chatgpt", "https://example.invalid/custom", "");

        assertThatThrownBy(codex::isChatGptLoggedIn).isInstanceOf(CodexTextFailure.class)
                .hasMessageContaining("官方")
                .hasMessageNotContaining("example.invalid");
    }

    @Test
    void interruptedTransportAfterSubmissionDoesNotReportKnownFailure() throws IOException {
        var codex = fixture("chatgpt", "");

        StepVerifier.create(codex.stream("fixture-model", List.of(user())))
                .expectErrorMatches(error -> error instanceof BusinessException && !(error instanceof CodexTextFailure))
                .verify(Duration.ofSeconds(10));
    }

    @ParameterizedTest
    @ValueSource(strings = {"disconnect", "failed", "failed-with-usage", "invalid-decision-with-usage"})
    void preservesObservedUsageAndDistinguishesUnconfirmedCodexSubmissions(String scenario) throws IOException {
        String failure = """
                printf '%s\\n' '{"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"failed"}}}'
                """;
        String usage = """
                printf '%s\\n' '{"method":"thread/tokenUsage/updated","params":{"threadId":"thread-1","turnId":"turn-1","tokenUsage":{"total":{"inputTokens":21,"outputTokens":13}}}}'
                """;
        String output = switch (scenario) {
            case "disconnect" -> usage;
            case "failed" -> failure;
            case "failed-with-usage" -> usage + failure;
            case "invalid-decision-with-usage" -> usage + decision("""
                    {"text":"","calls":[{"name":"lookup_color","argumentsJson":"{}"},
                    {"name":"lookup_color","argumentsJson":"[]"}]}
                    """, "");
            default -> throw new AssertionError(scenario);
        };
        List<ToolSchema> tools = scenario.equals("invalid-decision-with-usage")
                ? List.of(ToolSchema.builder().name("lookup_color").description("Read the requested color")
                .parameters(Map.of("type", "object")).build()) : List.of();
        var codex = fixture("chatgpt", output);
        var delegate = new ChatModelBase() {
            @Override
            public String getModelName() {
                return "fixture-model";
            }

            @Override
            protected Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return codex.stream(getModelName(), messages, tools, null);
            }
        };
        var model = AiModel.builder().id(7L).build();
        var ledger = mock(AgentModelRequests.class);
        var claim = new AgentModelRequests.Claim("request", "run", "worker", 1);
        when(ledger.admit(anyString(), eq(model), nullable(RuntimeContext.class), eq("fingerprint")))
                .thenReturn(Optional.of(claim));

        try (var tracked = new TrackedAgentChatModel(delegate, () -> {
            throw new AssertionError("Codex must not be resubmitted");
        }, model, ledger, "fingerprint")) {
            StepVerifier.create(tracked.stream(List.of(user()), tools, null))
                    .expectErrorSatisfies(error -> assertThat(error)
                            .isInstanceOfSatisfying(AgentModelCallFailure.class, modelFailure -> {
                                assertThat(modelFailure.retryable()).isFalse();
                                assertThat(modelFailure.getCause()).isInstanceOf(BusinessException.class);
                                assertThat(modelFailure.getCause() instanceof CodexTextFailure)
                                        .isEqualTo(!scenario.equals("disconnect"));
                            }))
                    .verify(Duration.ofSeconds(10));
        }

        verify(ledger).admit(anyString(), eq(model), nullable(RuntimeContext.class), eq("fingerprint"));
        switch (scenario) {
            case "disconnect" -> verify(ledger).uncertain(claim, false);
            case "failed" -> verify(ledger).failed(claim);
            default -> verify(ledger).completed(eq(claim), argThat(observed -> observed != null
                    && observed.getInputTokens() == 21 && observed.getOutputTokens() == 13));
        }
        verifyNoMoreInteractions(ledger);
    }

    @Test
    void timesOutAndStopsAnUnresponsiveProcess() throws IOException {
        var script = temp.resolve("codex");
        var pidFile = temp.resolve("fixture.pid");
        Files.writeString(script, """
                #!/bin/sh
                printf '%%s' "$$" > '%s'
                exec sleep 60
                """.formatted(pidFile));
        assertThat(script.toFile().setExecutable(true, true)).isTrue();
        var codex = new CodexAppServerClient(new ObjectMapper(), script.toString(), Duration.ofMillis(600));

        StepVerifier.create(codex.stream("fixture-model", List.of(user())))
                .expectErrorMatches(error -> error instanceof BusinessException business && business.getCode() == 504)
                .verify(Duration.ofSeconds(10));

        long pid = Long.parseLong(Files.readString(pidFile));
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    private Msg user() {
        return Msg.builder().role(MsgRole.USER).content(text()).build();
    }

    private TextBlock text() {
        return TextBlock.builder().text("Write a short answer").build();
    }

    private String decision(String payload, String check) {
        var completed = new ObjectMapper().createObjectNode().put("method", "item/completed");
        completed.putObject("params").put("threadId", "thread-1").put("turnId", "turn-1")
                .putObject("item").put("type", "agentMessage").put("phase", "final_answer").put("text", payload);
        return check + "\nprintf '%s\\n' '" + completed
                + "' '{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"thread-1\","
                + "\"turn\":{\"id\":\"turn-1\",\"status\":\"completed\"}}}'\n";
    }

    private CodexAppServerClient fixture(String accountType, String output) throws IOException {
        return fixture(accountType, "https://chatgpt.com/backend-api/", output);
    }

    private CodexAppServerClient fixture(String accountType, String endpoint, String output) throws IOException {
        var script = temp.resolve("codex");
        Files.writeString(script, """
                #!/bin/sh
                set -eu
                log='%s'
                IFS= read -r request
                case "$request" in *'"method":"initialize"'*) ;; *) exit 11 ;; esac
                printf '%%s\\n' '{"id":1,"result":{}}'
                IFS= read -r request
                case "$request" in *'"method":"initialized"'*) ;; *) exit 12 ;; esac
                IFS= read -r request
                case "$request" in *'"method":"config/read"'*'"includeLayers":false'*) ;; *) exit 19 ;; esac
                printf '%%s\\n' '{"id":2,"result":{"config":{"chatgpt_base_url":"%s","mcp_servers":{"fixture":{"enabled":true,"command":"unused"}}}}}'
                IFS= read -r request || exit 0
                case "$request" in *'"method":"account/read"'*'"refreshToken":false'*) ;; *) exit 13 ;; esac
                printf '%%s\\n' 'account/read' >> "$log"
                printf '%%s\\n' '{"id":3,"result":{"account":{"type":"%s"},"requiresOpenaiAuth":true}}'
                IFS= read -r request || exit 0
                case "$request" in *'"method":"thread/start"'*) ;; *) exit 14 ;; esac
                printf '%%s\\n' 'thread/start' >> "$log"
                case "$request" in *'"ephemeral":true'*) ;; *) exit 15 ;; esac
                case "$request" in *'"sandbox":"read-only"'*) ;; *) exit 16 ;; esac
                case "$request" in *'"approvalPolicy":"never"'*) ;; *) exit 17 ;; esac
                case "$request" in *'"mcp_servers":{"fixture":{"enabled":false}}'*) ;; *) exit 20 ;; esac
                printf '%%s\\n' '{"id":4,"result":{"thread":{"id":"thread-1"}}}'
                IFS= read -r request
                case "$request" in *'"method":"turn/start"'*'"threadId":"thread-1"'*) ;; *) exit 18 ;; esac
                printf '%%s\\n' 'turn/start' >> "$log"
                printf '%%s\\n' '{"id":5,"result":{"turn":{"id":"turn-1"}}}'
                %s
                """.formatted(temp.resolve("requests.log"), endpoint, accountType, output));
        assertThat(script.toFile().setExecutable(true, true)).isTrue();
        return new CodexAppServerClient(new ObjectMapper(), script.toString(), Duration.ofSeconds(10));
    }
}
