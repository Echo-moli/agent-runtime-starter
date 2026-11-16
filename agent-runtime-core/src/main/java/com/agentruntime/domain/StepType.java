package com.agentruntime.domain;

/** 事件类型。一个 stepIndex 通常对应一条 LLM_DECISION + 一条 TOOL_CALL。 */
public enum StepType {
    /** 决策步。 */
    LLM_DECISION,
    /** 工具调用步。 */
    TOOL_CALL,
    /** 人工介入步。 */
    HUMAN_REVIEW,
    /** 完成步。 */
    COMPLETION
}
