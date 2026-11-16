package com.agentruntime.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存版幂等 + outbox（M1/M3 演示用，不跨进程）。
 *
 * <p>「同事务」在单 JVM 里由 {@link #lock} 保证：{@link #complete} 里的 DONE 更新与 outbox 入队
 * 处于同一把锁内，不会出现只做了一半的中间态。
 */
public class InMemoryIdempotencyStore implements IdempotencyStore, OutboxStore, IdempotencyInspector {

    private final Object lock = new Object();
    private final Map<String, IdempotencyRecord> records = new ConcurrentHashMap<>();
    private final List<OutboxEntry> outbox = new ArrayList<>();
    private final AtomicLong outboxSeq = new AtomicLong();

    @Override
    public Optional<IdempotencyRecord> find(String idempotencyKey) {
        return Optional.ofNullable(records.get(idempotencyKey));
    }

    @Override
    public Claim begin(String idempotencyKey, String taskId, int stepIndex) {
        synchronized (lock) {
            IdempotencyRecord existing = records.get(idempotencyKey);
            Instant now = Instant.now();
            if (existing == null) {
                records.put(idempotencyKey, new IdempotencyRecord(
                        idempotencyKey, taskId, stepIndex, IdempotencyRecord.Status.INIT, null, now, now));
                return Claim.claimed();
            }
            return switch (existing.status()) {
                case DONE -> Claim.reused(existing);
                case INIT -> Claim.inDoubt(existing);
                case FAILED -> {
                    // 明确失败过（未产生副作用）：允许重新占位执行
                    records.put(idempotencyKey, new IdempotencyRecord(
                            idempotencyKey, taskId, stepIndex, IdempotencyRecord.Status.INIT,
                            existing.resultPayload(), existing.createdAt(), now));
                    yield Claim.claimed();
                }
            };
        }
    }

    @Override
    public List<IdempotencyRecord> findStale(java.time.Duration olderThan, int limit) {
        if (olderThan == null || limit <= 0) {
            return List.of();
        }
        Instant cutoff = Instant.now().minus(olderThan);
        return records.values().stream()
                .filter(r -> r.status() == IdempotencyRecord.Status.INIT)
                .filter(r -> r.updatedAt() != null && r.updatedAt().isBefore(cutoff))
                .sorted(java.util.Comparator.comparing(IdempotencyRecord::updatedAt))
                .limit(limit)
                .toList();
    }

    @Override
    public long countStale(java.time.Duration olderThan) {
        return findStale(olderThan, Integer.MAX_VALUE).size();
    }

    @Override
    public void complete(String idempotencyKey, String resultPayload, OutboxEvent event) {
        synchronized (lock) {
            IdempotencyRecord existing = records.get(idempotencyKey);
            Instant now = Instant.now();
            records.put(idempotencyKey, new IdempotencyRecord(
                    idempotencyKey,
                    existing == null ? null : existing.taskId(),
                    existing == null ? 0 : existing.stepIndex(),
                    IdempotencyRecord.Status.DONE,
                    resultPayload,
                    existing == null ? now : existing.createdAt(),
                    now));
            if (event != null) {
                long id = outboxSeq.incrementAndGet();
                outbox.add(new OutboxEntry(id, event.eventType(), event.aggregateId(),
                        event.payloadJson(), now, null, 0, null));
            }
        }
    }

    @Override
    public void fail(String idempotencyKey, String errorPayload) {
        synchronized (lock) {
            IdempotencyRecord existing = records.get(idempotencyKey);
            Instant now = Instant.now();
            records.put(idempotencyKey, new IdempotencyRecord(
                    idempotencyKey,
                    existing == null ? null : existing.taskId(),
                    existing == null ? 0 : existing.stepIndex(),
                    IdempotencyRecord.Status.FAILED,
                    errorPayload,
                    existing == null ? now : existing.createdAt(),
                    now));
        }
    }

    @Override
    public List<OutboxEntry> pollPending(int limit) {
        synchronized (lock) {
            return outbox.stream().filter(e -> !e.published()).limit(limit).toList();
        }
    }

    @Override
    public void markPublished(long id) {
        synchronized (lock) {
            for (int i = 0; i < outbox.size(); i++) {
                OutboxEntry e = outbox.get(i);
                if (e.id() == id) {
                    outbox.set(i, new OutboxEntry(e.id(), e.eventType(), e.aggregateId(), e.payload(),
                            e.createdAt(), Instant.now(), e.attempts() + 1, null));
                    return;
                }
            }
        }
    }

    @Override
    public void markFailed(long id, String error) {
        synchronized (lock) {
            for (int i = 0; i < outbox.size(); i++) {
                OutboxEntry e = outbox.get(i);
                if (e.id() == id) {
                    outbox.set(i, new OutboxEntry(e.id(), e.eventType(), e.aggregateId(), e.payload(),
                            e.createdAt(), null, e.attempts() + 1, error));
                    return;
                }
            }
        }
    }

    @Override
    public long pendingCount() {
        synchronized (lock) {
            return outbox.stream().filter(e -> !e.published()).count();
        }
    }
}
