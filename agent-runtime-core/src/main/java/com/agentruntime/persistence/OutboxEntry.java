package com.agentruntime.persistence;

import java.time.Instant;

/**
 * Outbox 表的一行（DESIGN.md §7 缺失的那张表，M3 补上）。
 *
 * <p>它存在的理由：真实副作用（调用下游退款接口）和「幂等标记 / 事件记录」不在同一个事务里——
 * 下游是 HTTP，数据库是本地。要么接受不一致，要么用 Outbox 把「已经发生的事实」先落到本地事务，
 * 再由投递器去送。后者是唯一能保证「不丢」的做法。
 */
public record OutboxEntry(
        long id,
        String eventType,
        String aggregateId,
        String payload,
        Instant createdAt,
        Instant publishedAt,
        int attempts,
        String lastError) {

    public boolean published() {
        return publishedAt != null;
    }
}
