package com.agentruntime.persistence;

import java.util.Optional;

/**
 * 幂等记录存取口（DESIGN.md §4.4 / §6 M3）。
 *
 * <p>核心是一个 CAS 式的 {@link #begin}：同一 {@code idempotencyKey} 并发进来时，
 * 只有一个人拿到 {@link Claim.Outcome#CLAIMED}，其余人要么复用结果、要么面对「结果未知」。
 *
 * <p>为什么必须 CAS 而不是「先查后写」：两个实例同时查都会看到「不存在」，
 * 然后各自退款一次——这正是要防的资损。JDBC 实现靠主键冲突做原子判定，不依赖先查。
 */
public interface IdempotencyStore {

    Optional<IdempotencyRecord> find(String idempotencyKey);

    /**
     * 尝试占位执行权。
     *
     * @return 三态结论，绝不返回 null
     */
    Claim begin(String idempotencyKey, String taskId, int stepIndex);

    /**
     * 执行成功：缓存结果，并把「副作用已发生」这条事实入队到 outbox。
     *
     * <p><b>实现约束（DESIGN §7 的 Transactional Outbox 要求）</b>：
     * {@code idempotency_record} 的 DONE 更新与 outbox 的 INSERT 必须在同一个数据库事务里提交。
     * 否则会出现「标记说做过了、事件却没发出去」的静默丢失。
     */
    void complete(String idempotencyKey, String resultPayload, OutboxEvent event);

    /** 无 outbox 需求时的便捷重载。 */
    default void complete(String idempotencyKey, String resultPayload) {
        complete(idempotencyKey, resultPayload, null);
    }

    /** 执行明确失败（未产生副作用），允许后续重新占位。 */
    void fail(String idempotencyKey, String errorPayload);

    /** 一次性占位结论。 */
    record Claim(Outcome outcome, IdempotencyRecord existing) {

        public enum Outcome {
            /** 本调用者获得执行权，必须真正执行副作用。 */
            CLAIMED,
            /** 已有 DONE 记录：直接复用缓存结果，绝不重复执行。 */
            REUSED,
            /** 已有 INIT 记录：副作用是否发生未知，交给 {@code InDoubtPolicy}。 */
            IN_DOUBT
        }

        public static Claim claimed() {
            return new Claim(Outcome.CLAIMED, null);
        }

        public static Claim reused(IdempotencyRecord record) {
            return new Claim(Outcome.REUSED, record);
        }

        public static Claim inDoubt(IdempotencyRecord record) {
            return new Claim(Outcome.IN_DOUBT, record);
        }
    }
}
