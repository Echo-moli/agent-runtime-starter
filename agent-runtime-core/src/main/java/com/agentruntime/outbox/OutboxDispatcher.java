package com.agentruntime.outbox;

import com.agentruntime.persistence.OutboxEntry;
import com.agentruntime.persistence.OutboxStore;

import java.util.List;
import java.util.function.Consumer;

/**
 * Outbox 投递器（最小可用版）。
 *
 * <p>真实生产里这一步通常是 Debezium 读 WAL / CDC 直接推 MQ，这里只做「轮询 + 标记」的等价物，
 * 目的是把语义讲清楚：
 * <ul>
 *   <li>投递是 <b>at-least-once</b>——handler 成功后 {@code markPublished}，失败则 {@code markFailed}
 *       留下 attempts 与错误，下轮继续；</li>
 *   <li>所以消费方必须按 {@code eventType + aggregateId} 幂等——这是 outbox 模式的固有契约，
 *       不是实现偷懒。</li>
 * </ul>
 *
 * <p>不内置定时线程：由调用方（Spring 的 {@code @Scheduled}，或示例里的显式调用）驱动，
 * 免得框架替业务决定线程模型——DESIGN §1.3 的非目标之一就是「不接管运行时」。
 */
public final class OutboxDispatcher {

    private final OutboxStore store;
    private final Consumer<OutboxEntry> handler;

    public OutboxDispatcher(OutboxStore store, Consumer<OutboxEntry> handler) {
        this.store = store;
        this.handler = handler;
    }

    /**
     * 投递一轮。
     *
     * @return 成功投递的条数
     */
    public int dispatchOnce(int limit) {
        List<OutboxEntry> pending = store.pollPending(limit);
        int published = 0;
        for (OutboxEntry entry : pending) {
            try {
                handler.accept(entry);
                store.markPublished(entry.id());
                published++;
            } catch (RuntimeException e) {
                store.markFailed(entry.id(), e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }
        return published;
    }
}
