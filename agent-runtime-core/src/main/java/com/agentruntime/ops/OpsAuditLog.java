package com.agentruntime.ops;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 运维审计日志（P0 安全）。
 *
 * <p>危险操作不能只有 {@code log.warn}——日志会被轮转掉，事故复盘时需要的是<b>可查询、
 * 不可抵赖</b>的记录。这个 SPI 让审计落到哪由使用方决定：内存（测试）、数据库、日志平台。
 *
 * <p>core 自带一个线程安全的内存实现供测试与小规模使用；生产接 JDBC / 日志平台只需实现本接口。
 */
public interface OpsAuditLog {

    /** 记录一条运维操作（无论成功还是被拒绝）。 */
    void record(OpsAuditEvent event);

    /** 最近的记录（测试与小控制台用；返回空表示不支持查询）。 */
    default List<OpsAuditEvent> recent(int limit) {
        return List.of();
    }

    static OpsAuditLog noop() {
        return event -> {
        };
    }

    /** 有界内存实现：默认保留最近 1000 条，避免审计本身成为内存泄漏点。 */
    static OpsAuditLog inMemory(int capacity) {
        return new InMemoryOpsAuditLog(capacity);
    }

    /** 一条审计记录。decision 为 null 表示「未走鉴权」（兼容模式）。 */
    record OpsAuditEvent(Instant at, OpsAction action, String taskId, String ownerUserId,
                         OpsPrincipal principal, String note, boolean allowed, String reason) {
    }

    final class InMemoryOpsAuditLog implements OpsAuditLog {
        private final int capacity;
        private final List<OpsAuditEvent> events = Collections.synchronizedList(new ArrayList<>());

        InMemoryOpsAuditLog(int capacity) {
            this.capacity = Math.max(1, capacity);
        }

        @Override
        public void record(OpsAuditEvent event) {
            synchronized (events) {
                events.add(event);
                while (events.size() > capacity) {
                    events.remove(0);
                }
            }
        }

        @Override
        public List<OpsAuditEvent> recent(int limit) {
            synchronized (events) {
                int n = Math.min(Math.max(0, limit), events.size());
                return List.copyOf(events.subList(events.size() - n, events.size()));
            }
        }
    }
}
