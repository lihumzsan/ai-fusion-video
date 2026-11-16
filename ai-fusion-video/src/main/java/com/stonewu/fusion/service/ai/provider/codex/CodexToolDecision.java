package com.stonewu.fusion.service.ai.provider.codex;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ToolSchema;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 转换 Codex 提议的工具调用，平台运行时仍负责执行、权限检查和记录。 */
final class CodexToolDecision {
    private static final TypeReference<Map<String, Object>> ARGUMENTS = new TypeReference<>() { };

    private final ObjectMapper json;
    private final ObjectReader reader;
    private final Set<String> allowed = new HashSet<>();
    private final ArrayNode descriptions;
    private final ObjectNode schema;

    CodexToolDecision(ObjectMapper json, List<ToolSchema> tools) {
        this.json = json;
        reader = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature());
        descriptions = json.createArrayNode();
        ObjectNode envelope = objectSchema("text", "calls");
        var fields = envelope.putObject("properties");
        fields.putObject("text").put("type", "string");
        ObjectNode call = objectSchema("name", "argumentsJson");
        fields.putObject("calls").put("type", "array").set("items", call);
        var callFields = call.putObject("properties");
        var names = callFields.putObject("name").put("type", "string").putArray("enum");
        callFields.putObject("argumentsJson").put("type", "string");
        for (ToolSchema tool : tools) {
            if (!allowed.add(tool.getName())) {
                throw new IllegalArgumentException("Codex 模型工具名称重复：" + tool.getName());
            }
            names.add(tool.getName());
            descriptions.addObject().put("name", tool.getName()).put("description", tool.getDescription())
                    .set("parameters", json.valueToTree(tool.getParameters()));
        }
        schema = envelope;
    }

    String instructions() {
        return """

                The platform can execute the tools listed in the input. Request a tool only via the calls array
                with its exact name and an argumentsJson string containing a JSON object matching its parameters.
                Do not invent tool results. If one call depends on another's result, wait for that result before
                requesting the dependent call. Use an empty calls array for a final answer. The text field is shown
                to the user and may be empty while requesting tools.
                """;
    }

    ObjectNode schema() {
        return schema;
    }

    ArrayNode descriptions() {
        return descriptions;
    }

    /** 先验证完整的回复，再向 AgentScope 提交全部工具提议。 */
    ChatResponse response(String answer, ChatUsage usage) {
        try {
            JsonNode payload = reader.readTree(answer);
            if (payload == null || !payload.isObject() || payload.size() != 2 || !payload.path("text").isTextual()
                    || !payload.path("calls").isArray()) {
                throw invalidDecision(usage);
            }
            var calls = payload.path("calls");
            String text = payload.path("text").textValue();
            List<ContentBlock> blocks = new ArrayList<>();
            if (!text.isBlank()) {
                blocks.add(TextBlock.builder().text(text).build());
            }
            for (JsonNode call : calls) {
                JsonNode name = call.path("name");
                JsonNode arguments = call.path("argumentsJson");
                if (!call.isObject() || call.size() != 2 || !name.isTextual() || !allowed.contains(name.textValue())
                        || !arguments.isTextual()) {
                    throw invalidDecision(usage);
                }
                JsonNode parsed = reader.readTree(arguments.textValue());
                if (parsed == null || !parsed.isObject()) {
                    throw invalidDecision(usage);
                }
                blocks.add(ToolUseBlock.builder().id("call_" + UUID.randomUUID()).name(name.textValue())
                        .input(json.convertValue(parsed, ARGUMENTS)).content(arguments.textValue()).build());
            }
            if (blocks.isEmpty()) {
                throw invalidDecision(usage);
            }
            return ChatResponse.builder().content(blocks).usage(usage)
                    .finishReason(calls.isEmpty() ? "stop" : "tool_calls").build();
        } catch (JsonProcessingException malformed) {
            throw invalidDecision(usage);
        }
    }

    private ObjectNode objectSchema(String... fields) {
        var object = json.createObjectNode().put("type", "object").put("additionalProperties", false);
        var required = object.putArray("required");
        for (String field : fields) {
            required.add(field);
        }
        return object;
    }

    private CodexTextFailure invalidDecision(ChatUsage usage) {
        return new CodexTextFailure(502, "Codex 返回的文本或工具参数格式无效，本轮工具未执行", usage);
    }
}
