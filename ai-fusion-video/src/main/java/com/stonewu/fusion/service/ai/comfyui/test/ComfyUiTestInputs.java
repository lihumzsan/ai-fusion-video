package com.stonewu.fusion.service.ai.comfyui.test;

import com.stonewu.fusion.entity.ai.ComfyUiWorkflow;
import com.stonewu.fusion.entity.ai.ComfyUiWorkflowVersion;
import com.stonewu.fusion.service.ai.comfyui.ComfyUiInputBinding;
import com.stonewu.fusion.service.ai.comfyui.ComfyUiH3RefProfile;
import com.stonewu.fusion.service.ai.comfyui.ComfyUiInputResourceService;
import com.stonewu.fusion.service.ai.comfyui.ComfyUiWorkflowDocumentService;
import com.stonewu.fusion.service.ai.comfyui.ComfyUiWorkflowRenderer;
import com.stonewu.fusion.service.canvas.model.CanvasActor;
import com.stonewu.fusion.service.media.MediaInputTransport;
import com.stonewu.fusion.service.media.MediaReference;
import com.stonewu.fusion.service.media.MediaResourceQueries;
import com.stonewu.fusion.service.media.model.MediaException;
import com.stonewu.fusion.service.media.model.MediaKind;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 根据工作流校验试运行输入，并在冻结后按媒体版本读取提交所需的内容。 */
@Component
@RequiredArgsConstructor
public class ComfyUiTestInputs {
    private final ComfyUiWorkflowDocumentService documents;
    private final ComfyUiWorkflowRenderer renderer;
    private final MediaResourceQueries media;
    private final MediaInputTransport transport;

    public ComfyUiTestSnapshot freeze(ComfyUiWorkflow workflow, ComfyUiWorkflowVersion version,
                                      Map<String, Object> inputs, CanvasActor actor) {
        boolean h3 = ComfyUiH3RefProfile.matches(workflow.getModelType(), version, documents);
        var bindings = h3 ? List.<ComfyUiInputBinding>of() : bindings(workflow, version);
        var known = h3 ? ComfyUiH3RefProfile.INPUT_FIELDS
                : bindings.stream().map(ComfyUiInputBinding::businessField).collect(Collectors.toSet());
        if (!known.containsAll(inputs.keySet())) {
            throw MediaException.invalid("试运行输入含未绑定字段，请刷新工作流版本");
        }
        renderer.render(workflow.getModelType(), version, inputs);
        Map<String, Integer> versions = new LinkedHashMap<>();
        uploadFields(h3, bindings).forEach((field, kind) -> {
            for (String source : sources(inputs.get(field), kind)) {
                if (!MediaReference.managed(source)) {
                    continue;
                }
                var resource = media.get(MediaReference.id(source), actor);
                if (resource.content().kind() != kind) {
                    throw MediaException.invalid("试运行的参考素材类型与输入字段不符");
                }
                versions.put(resource.id(), resource.version());
            }
        });
        return new ComfyUiTestSnapshot(1, workflow, version, inputs, Map.copyOf(versions));
    }

    public Map<String, Object> resolve(ComfyUiTestSnapshot snapshot, CanvasActor actor) {
        Map<String, Object> result = new LinkedHashMap<>(snapshot.inputs());
        boolean h3 = ComfyUiH3RefProfile.matches(snapshot.workflow().getModelType(), snapshot.version(), documents);
        var bindings = h3 ? List.<ComfyUiInputBinding>of() : bindings(snapshot.workflow(), snapshot.version());
        uploadFields(h3, bindings).forEach((field, kind) -> {
            if (!result.containsKey(field)) {
                return;
            }
            result.put(field, sources(result.get(field), kind).stream().map(source -> {
                if (!MediaReference.managed(source)) {
                    return source;
                }
                String id = MediaReference.id(source);
                var resource = media.get(id, actor);
                if (!Integer.valueOf(resource.version()).equals(snapshot.mediaVersions().get(id))) {
                    throw new MediaException(409, "COMFY_TEST_MEDIA_CHANGED", "试运行引用的输入媒体版本已变化");
                }
                long limit = kind == MediaKind.AUDIO ? ComfyUiInputResourceService.MAX_H3_REFERENCE_AUDIO_BYTES
                        : ComfyUiInputResourceService.MAX_IMAGE_BYTES;
                return transport.dataUri(source, actor, kind, limit);
            }).toList());
        });
        return result;
    }

    private List<ComfyUiInputBinding> bindings(ComfyUiWorkflow workflow, ComfyUiWorkflowVersion version) {
        return documents.parseInputBindings(workflow.getModelType(), version.getApiWorkflowJson(),
                version.getInputBindingsJson());
    }

    private Map<String, MediaKind> uploadFields(boolean h3, List<ComfyUiInputBinding> bindings) {
        if (h3) {
            return Map.of("referenceImages", MediaKind.IMAGE, "referenceAudios", MediaKind.AUDIO);
        }
        if (bindings.stream().anyMatch(binding -> binding.valueType().equals("uploaded_video") || binding.valueType()
                .equals("uploaded_audio"))) {
            throw MediaException.invalid("当前 ComfyUI Native API 尚不支持视频或音频上传绑定");
        }
        return bindings.stream().filter(binding -> binding.valueType().equals("uploaded_image"))
                .map(ComfyUiInputBinding::businessField).distinct()
                .collect(Collectors.toMap(field -> field, field -> MediaKind.IMAGE));
    }

    private List<String> sources(Object value, MediaKind kind) {
        if (value == null) {
            return List.of();
        }
        var values = value instanceof List<?> list ? list : List.of(value);
        if (values.size() > 64) {
            throw MediaException.invalid("单个试运行输入集合不能超过 64 项");
        }
        return values.stream().map(item -> {
            if (kind == MediaKind.AUDIO) {
                if (item instanceof String source && MediaReference.managed(source)) {
                    return source;
                }
                throw MediaException.invalid("参考音频须上传为已授权的托管媒体");
            }
            if (!(item instanceof String source) || !(MediaReference.managed(source) || source.startsWith("https://")
                    || source.startsWith("http://") || source.startsWith("data:image/"))) {
                throw MediaException.invalid("图片输入须使用已授权媒体、图片 Data URI 或 HTTP(S) 地址");
            }
            return source;
        }).toList();
    }
}
