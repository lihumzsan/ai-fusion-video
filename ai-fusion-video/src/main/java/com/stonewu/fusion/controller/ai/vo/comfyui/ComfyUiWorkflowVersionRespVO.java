package com.stonewu.fusion.controller.ai.vo.comfyui;

import lombok.Data;

import java.time.LocalDateTime;

/** 已保存的 ComfyUI 工作流版本及其动态输入类型。 */
@Data
public class ComfyUiWorkflowVersionRespVO {

    private Long id;
    private Long workflowId;
    private Integer versionNo;
    private String uiWorkflowJson;
    private String apiWorkflowJson;
    private String inputBindingsJson;
    private String outputBindingsJson;
    private String requiredNodesJson;
    private boolean h3Ref;
    private String workflowHash;
    private Integer validationStatus;
    private String validationMessage;
    private Integer testStatus;
    private String testMessage;
    private LocalDateTime lastTestTime;
    private String lastTestRunId;
    private Boolean published;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
