package com.agentruntime.persistence;

import java.util.List;

/**
 * Outbox 的读取与投递标记（写入路径在 {@link IdempotencyStore#complete} 里，
 * 因为「幂等标记 + 入队」必须同事务，这是唯一能保证原子的地方）。
 */
public interface OutboxStore {

    /** 取出最多 {@code limit} 条未投递的记录，按 id 升序（保证单任务内顺序）。 */
    List<OutboxEntry> pollPending(int limit);

    void markPublished(long id);

    void markFailed(long id, String error);

    long pendingCount();
}
