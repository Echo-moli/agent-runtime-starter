package com.agentruntime.autoconfigure;

import com.agentruntime.domain.TaskStatus;
import com.agentruntime.engine.DefaultAgentEngine;
import com.agentruntime.persistence.TaskRepository;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;

/**
 * Agent 运行时健康探针（P1 可运维）。
 *
 * <h2>检查什么</h2>
 * <ul>
 *   <li><b>存活（liveness）</b>：引擎没有在停机中——停机的实例不该再接流量；</li>
 *   <li><b>就绪（readiness）</b>：数据源能连通（若有），且租约过期的孤儿任务没有堆积——
 *       堆积说明没有实例在接管，任务正在静默卡住。</li>
 * </ul>
 *
 * <p>刻意不做「挂起数 > 0 就 DOWN」：挂起是业务正常态（等人审批），把健康探针
 * 和业务等待绑在一起会让 K8s 反复重启那些只是在等财务审批的任务。挂起数只作为 detail 上报。
 *
 * <p>暴露在 Actuator 的 {@code /actuator/health/agentRuntime}；
 * 需要把 {@code management.endpoint.health.show-details} 配成 {@code always} 才看得到 detail。
 */
public final class AgentRuntimeHealthIndicator implements HealthIndicator {

    private final DefaultAgentEngine engine;
    private final TaskRepository repository;
    private final DataSource dataSource;
    private final Duration leaseTtl;
    /** 孤儿数超过这个阈值就报 DOWN（默认 0：任何过期孤儿都值得 attention）。 */
    private final int orphanThreshold;

    public AgentRuntimeHealthIndicator(DefaultAgentEngine engine, TaskRepository repository,
                                       DataSource dataSource, Duration leaseTtl, int orphanThreshold) {
        this.engine = engine;
        this.repository = repository;
        this.dataSource = dataSource;
        this.leaseTtl = leaseTtl == null ? Duration.ofMinutes(5) : leaseTtl;
        this.orphanThreshold = Math.max(0, orphanThreshold);
    }

    @Override
    public Health health() {
        java.util.List<String> failures = new java.util.ArrayList<>();
        java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();

        if (engine != null && engine.isShuttingDown()) {
            failures.add("引擎正在优雅停机，不再接收新任务");
        }

        if (dataSource != null) {
            try (Connection connection = dataSource.getConnection()) {
                if (!connection.isValid(2)) {
                    throw new IllegalStateException("连接无效");
                }
                details.put("database", "UP");
            } catch (Exception e) {
                failures.add("数据源不可连: " + e.getMessage());
                details.put("database", "DOWN");
            }
        } else {
            details.put("database", "IN_MEMORY");
        }

        long orphans = countOrphans();
        details.put("orphanTasks", orphans);
        details.put("suspendedTasks", count(TaskStatus.SUSPENDED));
        details.put("leaseTtl", leaseTtl.toString());
        if (engine != null) {
            details.put("cachedResults", engine.cachedResultCount());
        }
        if (orphans > orphanThreshold) {
            failures.add("租约过期孤儿任务堆积: " + orphans
                    + "（阈值 " + orphanThreshold + "），没有实例在接管");
        }

        if (failures.isEmpty()) {
            return Health.up().withDetails(details).build();
        }
        details.put("reason", String.join("; ", failures));
        return Health.down().withDetails(details).build();
    }

    private long countOrphans() {
        Instant staleBefore = Instant.now().minus(leaseTtl);
        long count = 0;
        for (TaskStatus status : new TaskStatus[]{TaskStatus.RUNNING, TaskStatus.RECOVERING,
                TaskStatus.PENDING}) {
            for (var task : repository.findByStatus(status, 10_000)) {
                if (task.isLeaseExpired(staleBefore)) {
                    count++;
                }
            }
        }
        return count;
    }

    private long count(TaskStatus status) {
        return repository.countByStatus(status);
    }
}
