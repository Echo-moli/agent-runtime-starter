package com.agentruntime.domain;

/**
 * 一次 Agent 运行的状态机。
 *
 * <pre>
 *   PENDING ──► RUNNING ──► COMPLETED
 *                  │  │
 *                  │  └──► FAILED
 *                  ├──► SUSPENDED ──► RECOVERING ──► RUNNING
 *                  └──► RECOVERING
 * </pre>
 *
 * M1 覆盖 PENDING / RUNNING / SUSPENDED / COMPLETED / FAILED；RECOVERING 由 M2 的 recover 使用。
 */
public enum TaskStatus {
    /** 已提交，未开始。 */
    PENDING,
    /** 执行中。 */
    RUNNING,
    /** 挂起等人工（HITL）。 */
    SUSPENDED,
    /** 完成。 */
    COMPLETED,
    /** 最终失败。 */
    FAILED,
    /** 恢复中。 */
    RECOVERING
}
