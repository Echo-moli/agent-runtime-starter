package com.agentruntime.api;

import com.agentruntime.domain.TaskStatus;

import java.math.BigDecimal;

/**
 * 一次运行的最终结果（DESIGN.md §5.3 引用但未定义，此处补全）。
 *
 * @param taskId      任务标识
 * @param status      终态；M1 可能是 COMPLETED / SUSPENDED / FAILED
 * @param result      {@link Step#complete(Object)} 携带的返回值
 * @param error       失败原因；成功时为 null
 * @param totalCost   累计成本（元），M4 才有非零值
 * @param totalTokens 累计 token，M4 才有非零值
 */
public record TaskResult(
        String taskId,
        TaskStatus status,
        Object result,
        String error,
        BigDecimal totalCost,
        long totalTokens) {
}
