package com.agentruntime.persistence;

import java.time.Instant;

/**
 * 幂等记录（DESIGN.md §4.4）。
 *
 * <p>与 DESIGN 的一处实现差异：{@code status} 用枚举而不是裸 String——
 * {@code INIT / DONE / FAILED} 三个值承担的是「副作用是否已发生」的判定责任，用字符串早晚写错。
 *
 * <p>注意 {@code idempotencyKey} 的实际组成是 {@code 工具名 + ":" + 表达式求值结果}，
 * 见 {@code IdempotencyKeys}。DESIGN §4.4 只说「业务表达式求值结果，唯一」，
 * 但 {@code queryOrder} 与 {@code refund} 常常共用 {@code #orderId} 这个表达式，
 * 不按工具名隔离会让查询结果被当成退款结果直接读出来——这是必须付的学费。
 */
public record IdempotencyRecord(
        String idempotencyKey,
        String taskId,
        int stepIndex,
        Status status,
        String resultPayload,
        Instant createdAt,
        Instant updatedAt) {

    /** {@code INIT} 表示「已开始、结果未知」，是唯一需要人工介入的状态。 */
    public enum Status {
        /** 已占位，尚未确认执行结果。 */
        INIT,
        /** 已执行且结果已缓存，再次调用直接复用。 */
        DONE,
        /** 明确失败（未产生副作用）。 */
        FAILED
    }

    public boolean done() {
        return status == Status.DONE;
    }

    public boolean inDoubt() {
        return status == Status.INIT;
    }
}
