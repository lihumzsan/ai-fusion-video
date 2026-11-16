package com.stonewu.fusion.service.ai.comfyui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.controller.ai.vo.comfyui.ComfyUiConnectionRespVO;
import com.stonewu.fusion.controller.ai.vo.comfyui.ComfyUiWorkflowValidationRespVO;
import com.stonewu.fusion.entity.ai.ApiConfig;
import com.stonewu.fusion.entity.ai.ComfyUiWorkflow;
import com.stonewu.fusion.entity.ai.ComfyUiWorkflowVersion;
import com.stonewu.fusion.service.ai.ApiConfigService;
import com.stonewu.fusion.service.ai.ProviderConfigurationFingerprint;
import com.stonewu.fusion.service.ai.comfyui.client.ComfyUiConnectionResult;
import com.stonewu.fusion.service.ai.comfyui.client.ComfyUiNativeClient;
import com.stonewu.fusion.service.canvas.CanvasJson;
import com.stonewu.fusion.service.generation.transport.GenerationHttpClients;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Online validation against the exact target ComfyUI instance.
 */
@Service
@RequiredArgsConstructor
public class ComfyUiWorkflowValidationService {

    private static final Set<String> MODEL_FILE_INPUTS = Set.of("vae_name", "clip_name", "unet_name", "lora_name", "model_name");

    private final ApiConfigService apiConfigService;
    private final ComfyUiWorkflowService workflowService;
    private final ComfyUiWorkflowDocumentService documentService;
    private final ComfyUiNativeClient nativeClient;
    private final CanvasJson json;

    public ComfyUiConnectionRespVO testConnection(Long apiConfigId) {
        ApiConfig apiConfig = requireComfyUiApiConfig(apiConfigId);
        ComfyUiConnectionResult result = nativeClient.testConnection(apiConfig);
        return ComfyUiConnectionRespVO.builder()
                .connected(result.connected())
                .jobsApiSupported(result.jobsApiSupported())
                .version(result.version())
                .systemStats(result.systemStats())
                .features(result.features())
                .build();
    }

    /** 预设采用目标实例公布的权重路径拼写，保存后沿用普通工作流的精确校验。 */
    public String h3RefPreset(Long apiConfigId) throws IOException {
        GenerationHttpClients.requireNoTransaction();
        ApiConfig apiConfig = requireComfyUiApiConfig(apiConfigId);
        var template = new ClassPathResource("comfyui/h3-reference-t8-dual-stage-2mp.json");
        var graph = documentService.parseApiWorkflow(template.getContentAsString(StandardCharsets.UTF_8));
        Map<String, JsonNode> definitions = new HashMap<>();
        for (var entry : graph.properties()) {
            JsonNode node = entry.getValue();
            String classType = node.path("class_type").asText();
            var inputs = (ObjectNode) node.get("inputs");
            for (String field : MODEL_FILE_INPUTS) {
                String requested = inputs.path(field).asText();
                if (!requested.contains("/")) {
                    continue;
                }
                JsonNode definition = definitions.computeIfAbsent(classType,
                        name -> nativeClient.getNodeInfo(apiConfig, name).path(name));
                String matched = modelFileChoice(choiceOptions(definition, field), requested);
                if (matched != null) {
                    inputs.put(field, matched);
                }
            }
        }
        return graph.toPrettyString();
    }

    private String modelFileChoice(JsonNode choices, String requested) {
        Set<String> matches = new LinkedHashSet<>();
        for (JsonNode choice : choices) {
            if (!choice.isTextual()) {
                continue;
            }
            String advertised = choice.textValue();
            if (requested.equals(advertised)) {
                return advertised;
            }
            if (requested.equals(advertised.replace('\\', '/'))) {
                matches.add(advertised);
            }
        }
        if (matches.size() > 1) {
            throw new BusinessException(400, "H3 REF 目标实例提供多个相同相对路径的权重选项：" + requested);
        }
        return matches.isEmpty() ? null : matches.iterator().next();
    }

    /** 在线核对目标实例的节点、模型选项与专用工作流端口。 */
    public ComfyUiWorkflowValidationRespVO validateVersion(Long versionId) {
        ComfyUiWorkflowVersion version = workflowService.requireVersion(versionId);
        ComfyUiWorkflow workflow = workflowService.requireWorkflow(version.getWorkflowId());
        ApiConfig apiConfig = requireComfyUiApiConfig(workflow.getApiConfigId());
        String apiHash = ProviderConfigurationFingerprint.of(apiConfig, json);
        try {
            nativeClient.testConnection(apiConfig);
            ObjectNode apiWorkflow = documentService.parseApiWorkflow(version.getApiWorkflowJson());
            boolean h3Ref = workflow.getModelType() == 3 && ComfyUiH3RefProfile.matches(version, apiWorkflow);
            Set<String> missingClasses = new LinkedHashSet<>();
            List<String> invalidModelInputs = new ArrayList<>();
            Map<String, JsonNode> definitionCache = new HashMap<>();
            Iterator<Map.Entry<String, JsonNode>> nodes = apiWorkflow.fields();
            int checkedNodes = 0;
            while (nodes.hasNext()) {
                Map.Entry<String, JsonNode> entry = nodes.next();
                checkedNodes++;
                String nodeId = entry.getKey();
                JsonNode workflowNode = entry.getValue();
                String classType = workflowNode.path("class_type").asText();
                JsonNode definition = definitionCache.computeIfAbsent(classType,
                        ignored -> nativeClient.getNodeInfo(apiConfig, classType).path(classType));
                if (definition.isMissingNode() || !definition.isObject()) {
                    missingClasses.add(classType);
                    continue;
                }
                validateChoiceInputs(nodeId, workflowNode.path("inputs"), definition, invalidModelInputs, h3Ref);
            }
            if (h3Ref && !missingClasses.contains(ComfyUiH3RefProfile.CONDITIONING_CLASS)) {
                ComfyUiH3RefProfile.validateReferenceSlots(
                        definitionCache.get(ComfyUiH3RefProfile.CONDITIONING_CLASS), invalidModelInputs);
            }
            boolean valid = missingClasses.isEmpty() && invalidModelInputs.isEmpty();
            String message = valid
                    ? "已在目标 ComfyUI 校验 " + checkedNodes + " 个节点"
                    : buildFailureMessage(missingClasses, invalidModelInputs);
            workflowService.recordValidation(versionId, version.getWorkflowHash(), apiHash, valid, message);
            return ComfyUiWorkflowValidationRespVO.builder()
                    .valid(valid)
                    .checkedNodeCount(checkedNodes)
                    .missingNodeClasses(List.copyOf(missingClasses))
                    .invalidModelInputs(List.copyOf(invalidModelInputs))
                    .message(message)
                    .build();
        } catch (BusinessException error) {
            workflowService.recordValidation(versionId, version.getWorkflowHash(), apiHash, false, error.getMessage());
            throw error;
        }
    }

    private void validateChoiceInputs(String nodeId,
                                      JsonNode workflowInputs,
                                      JsonNode definition,
                                      List<String> invalidInputs,
                                      boolean h3Ref) {
        Iterator<Map.Entry<String, JsonNode>> inputs = workflowInputs.fields();
        while (inputs.hasNext()) {
            Map.Entry<String, JsonNode> input = inputs.next();
            if (!input.getValue().isTextual()) {
                continue;
            }
            // H3 的加载节点只是模板，运行时文件名由实际上传的参考素材替换。
            if (h3Ref && ComfyUiH3RefProfile.isReferencePlaceholder(nodeId, input.getKey())) {
                continue;
            }
            JsonNode choices = choiceOptions(definition, input.getKey());
            if (!choices.isArray()) {
                continue;
            }
            boolean allowed = false;
            for (JsonNode choice : choices) {
                if (choice.isTextual() && choice.asText().equals(input.getValue().asText())) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                invalidInputs.add(nodeId + "." + input.getKey() + "=" + input.getValue().asText());
            }
        }
    }

    private JsonNode choiceOptions(JsonNode definition, String field) {
        JsonNode definitions = definition.path("input");
        JsonNode input = definitions.path("required").path(field);
        if (input.isMissingNode()) {
            input = definitions.path("optional").path(field);
        }
        return input.path(0);
    }

    private String buildFailureMessage(Set<String> missingClasses, List<String> invalidInputs) {
        List<String> parts = new ArrayList<>();
        if (!missingClasses.isEmpty()) {
            parts.add("缺少节点: " + String.join(", ", missingClasses));
        }
        if (!invalidInputs.isEmpty()) {
            parts.add("目标实例不存在模型/选项: " + String.join(", ", invalidInputs));
        }
        return String.join("；", parts);
    }

    private ApiConfig requireComfyUiApiConfig(Long id) {
        ApiConfig apiConfig = id == null ? null : apiConfigService.getById(id);
        if (apiConfig == null) {
            throw new BusinessException(404, "API 配置不存在");
        }
        if (!ComfyUiWorkflowService.PLATFORM.equalsIgnoreCase(apiConfig.getPlatform())) {
            throw new BusinessException(400, "API 配置不是 ComfyUI 平台");
        }
        if (!Integer.valueOf(1).equals(apiConfig.getStatus())) {
            throw new BusinessException(400, "ComfyUI API 配置已禁用");
        }
        return apiConfig;
    }
}
