package com.stonewu.fusion.service.resource;

import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.mapper.ai.AgentRunMapper;
import com.stonewu.fusion.mapper.resource.ResourceCommandMapper;
import com.stonewu.fusion.repository.resource.ResourceReadRepository;
import com.stonewu.fusion.service.ai.run.AgentStateSessionIds;
import com.stonewu.fusion.service.ai.run.model.StartAgentRunCommand;
import com.stonewu.fusion.service.canvas.CanvasAccessPolicy;
import com.stonewu.fusion.service.canvas.model.CanvasActor;
import com.stonewu.fusion.service.resource.model.ResourceCommandContext;
import com.stonewu.fusion.service.resource.model.ResourceMutation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

import static com.stonewu.fusion.service.canvas.model.CanvasValueType.PROJECT;
import static com.stonewu.fusion.service.canvas.model.CanvasValueType.SCRIPT;

/** 使用同一项目行锁协调剧本解析、故事改编与原文写入。 */
@Component
@RequiredArgsConstructor
public class ProjectScriptParseGuard implements ResourceMutationObserver {
    private static final Set<String> SCRIPT_PIPELINES = Set.of("script_full_parse", "story_to_script");

    private final ResourceReadRepository resources;
    private final CanvasAccessPolicy access;
    private final AgentRunMapper runs;
    private final ResourceCommandMapper changes;

    /** 在创建运行记录的事务内校验项目入口的剧本版本，并限制同项目的活动解析和改编。 */
    public void admit(StartAgentRunCommand command, boolean verifySelectedScript) {
        if (!SCRIPT_PIPELINES.contains(command.agentType())) {
            return;
        }
        if (command.projectId() == null) {
            throw new BusinessException(400, "剧本任务必须指定项目");
        }
        long projectId = command.projectId();
        resources.require(PROJECT, projectId, true);
        access.requireProjectEditor(projectId, new CanvasActor(command.userId(), command.actorTeamId()));
        requireIdle(projectId);
        if (verifySelectedScript) {
            boolean continuation = command.stateSessionCandidate().equals(
                    AgentStateSessionIds.recoveryGeneration(command.conversationId(), command.agentType(), command.runId()));
            if (continuation) {
                requireUnchangedSource(command, projectId);
            } else {
                requireSelectedVersion(command, projectId);
            }
        }
    }

    /** 原文修改和剧本删除已持有项目锁；解析和改编期间拒绝这些写入。 */
    @Override
    public void validateAndApply(ResourceMutation mutation, ResourceCommandContext context) {
        for (var change : mutation.changes()) {
            if (change.resourceType() == SCRIPT && (change.deleted() || change.changedFields().contains("rawContent")
                    || change.changedFields().contains("deleted"))) {
                requireIdle(change.projectId());
            }
        }
    }

    private void requireSelectedVersion(StartAgentRunCommand command, long projectId) {
        var values = command.kernelSnapshot().payload().promptVariables();
        long scriptId;
        int version;
        try {
            scriptId = Long.parseLong(values.get("scriptId"));
            version = Integer.parseInt(values.get("scriptVersion"));
        } catch (NumberFormatException invalid) {
            throw new BusinessException(400, "请提供有效的剧本 ID 和版本");
        }
        if (scriptId <= 0 || version < 0) {
            throw new BusinessException(400, "请提供有效的剧本 ID 和版本");
        }
        long latestId = resources.lockScriptForProject(projectId)
                .orElseThrow(() -> new BusinessException(409, "项目剧本已变化，请刷新后重试"));
        if (latestId != scriptId || resources.require(SCRIPT, scriptId, true).version() != version) {
            throw new BusinessException(409, "项目剧本已变化，请刷新后重试");
        }
    }

    /** 任务会更新结构版本；续跑只核对原文相关的已提交修改。 */
    private void requireUnchangedSource(StartAgentRunCommand command, long projectId) {
        var previous = runs.selectLatestRootByConversation(command.conversationId());
        var scriptId = resources.lockScriptForProject(projectId);
        if (previous == null || scriptId.isEmpty()
                || !scriptId.get().toString().equals(command.kernelSnapshot().payload().promptVariables().get("scriptId"))
                || changes.scriptSourceChangedAfter(scriptId.get(), previous.getStartedAt())) {
            throw new BusinessException(409, "剧本原文已变化或无法确认，请从项目页重新发起任务");
        }
    }

    private void requireIdle(long projectId) {
        // 项目锁串行化受理；此处只读运行状态，避免与先锁 Agent Run 的工具写入反向加锁。
        if (runs.hasActiveProjectScriptParse(projectId)) {
            throw new BusinessException(409, "项目已有剧本解析或改编任务正在运行，请等待完成或停止后再试");
        }
    }
}
