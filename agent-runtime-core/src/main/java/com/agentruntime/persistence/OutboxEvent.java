package com.agentruntime.persistence;

/**
 * 要投递出去的一条业务事件（Transactional Outbox 的载荷）。
 *
 * @param eventType  事件类型，如 {@code tool.executed}
 * @param aggregateId 聚合根 id，这里就是 taskId（下游按任务维度可重放）
 * @param payloadJson 已序列化的 JSON，保证落库与投递是同一份字节
 */
public record OutboxEvent(String eventType, String aggregateId, String payloadJson) {
}
