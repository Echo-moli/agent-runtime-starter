package com.agentruntime.autoconfigure;

import com.agentruntime.domain.InDoubtPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 自动装配的可调参数（M8），前缀 {@code agent.runtime}。
 *
 * <pre>
 * agent:
 *   runtime:
 *     max-steps: 25              # 单任务最多决策几步（防原地打转）
 *     executor-threads: 0        # 0 = 按 CPU 核数自动（min 2）
 *     retry-enabled: true        # false = 完全不重试（纯查询类也不退避）
 *     retry-max-delay-ms: 0      # 0 = 用引擎默认上限
 *     in-doubt-policy: SUSPEND_FOR_HUMAN   # 在途未知时：挂起 / 重试 / 失败
 *     schema-init: true          # 启动时建表（IF NOT EXISTS，可重复执行）
 *     flyway:
 *       enabled: false           # true=用 Flyway 版本化迁移替代 schema-init（需引 flyway-core）
 *     metrics-enabled: true
 *     tracer: NONE               # NONE / CONSOLE
 *     lease-ttl: 5m              # 心跳停多久算死；批量恢复据此判孤儿（必须大于最慢的一步）
 *     instance-id: (自动)         # 写进 task.owner_id，多实例排查时对得上机器
 *     queue-capacity: 200         # 执行器有界队列容量；满了用 CallerRuns 背压（防无界 OOM）
 *     max-results: 10000          # 结果缓存上限；超了淘汰最早的已完成任务
 *     shutdown-timeout-ms: 10000  # 优雅停机最多等在途任务多久
 *     ops:
 *       allow-bulk-recover: false      # 是否允许批量恢复（默认关闭，防滚动发布抢跑）
 *       stale-in-doubt-after: 15m      # INIT 停留多久算「超期未决」
 *       max-list-size: 50              # 端点一次最多返回多少条
 *       strict-auth: false             # true=运维端点用 ownerOrAdmin 鉴权（需注入 OpsPrincipal）
 *       audit-jdbc: false              # true=审计写入 ops_audit 表（需有 DataSource 且表已建）
 * </pre>
 *
 * <h2>为什么用 record 而不是 JavaBean</h2>
 * 这些值在启动后不该再变，record 天然不可变；Spring Boot 3 支持 record 的构造器绑定。
 * 代价是「没配」与「配成默认值」在 record 里长得一样——所以下面用
 * 「0 / null 表示未配置」的约定，并在紧凑构造器里统一落默认值，
 * 免得每个使用点都要写一次 {@code != null ? x : DEFAULT}。
 *
 * <h2>为什么 inDoubtPolicy 可以被配置，而默认必须挂起</h2>
 * 在途未知（钱可能已经退了、但记录停在 INIT）时，任何自动动作都是在赌钱。
 * 默认 {@link InDoubtPolicy#SUSPEND_FOR_HUMAN}；允许配成 RETRY 是给
 * 「幂等做得足够好、愿意用重试换吞吐」的团队开的口子，但那必须是他们<b>明确选择</b>的。
 */
@Validated
@ConfigurationProperties("agent.runtime")
public record AgentRuntimeProperties(
        @Positive(message = "agent.runtime.max-steps 必须 > 0") int maxSteps,
        @Min(value = 0, message = "agent.runtime.executor-threads 不能为负") int executorThreads,
        @Min(value = 0, message = "agent.runtime.retry-max-delay-ms 不能为负") long retryMaxDelayMs,
        Boolean retryEnabled,
        Boolean schemaInit,
        Boolean metricsEnabled,
        InDoubtPolicy inDoubtPolicy,
        TracerMode tracer,
        @Valid Ops ops,
        @NotNull(message = "agent.runtime.lease-ttl 不能为空") Duration leaseTtl,
        String instanceId,
        @Positive(message = "agent.runtime.queue-capacity 必须 > 0") Integer queueCapacity,
        @Positive(message = "agent.runtime.max-results 必须 > 0") Integer maxResults,
        @Positive(message = "agent.runtime.shutdown-timeout-ms 必须 > 0") Long shutdownTimeoutMs,
        @Valid Flyway flyway) {

    /** 与引擎的默认值保持一致，避免「配了 starter 反而行为变了」。 */
    public static final int DEFAULT_MAX_STEPS = 25;

    public AgentRuntimeProperties {
        if (maxSteps <= 0) {
            maxSteps = DEFAULT_MAX_STEPS;
        }
        if (executorThreads < 0) {
            executorThreads = 0;
        }
        if (retryMaxDelayMs < 0) {
            retryMaxDelayMs = 0;
        }
        if (retryEnabled == null) {
            retryEnabled = Boolean.TRUE;
        }
        if (schemaInit == null) {
            schemaInit = Boolean.TRUE;
        }
        if (metricsEnabled == null) {
            metricsEnabled = Boolean.TRUE;
        }
        if (inDoubtPolicy == null) {
            inDoubtPolicy = InDoubtPolicy.SUSPEND_FOR_HUMAN;
        }
        if (tracer == null) {
            tracer = TracerMode.NONE;
        }
        if (ops == null) {
            ops = new Ops(null, null, 0, null, null);
        }
        if (leaseTtl == null || leaseTtl.isZero() || leaseTtl.isNegative()) {
            leaseTtl = Duration.ofMinutes(5);
        }
        if (instanceId != null && instanceId.isBlank()) {
            instanceId = null;
        }
        if (queueCapacity == null) {
            queueCapacity = DEFAULT_QUEUE_CAPACITY;
        }
        if (maxResults == null) {
            maxResults = DEFAULT_MAX_RESULTS;
        }
        if (shutdownTimeoutMs == null) {
            shutdownTimeoutMs = DEFAULT_SHUTDOWN_TIMEOUT_MS;
        }
        if (flyway == null) {
            flyway = new Flyway(null);
        }
    }

    /** Flyway 版本化迁移开关（P1）。与 schema-init 互斥：开启时自动关闭幂等 DDL。 */
    public record Flyway(Boolean enabled) {
        public Flyway {
            if (enabled == null) {
                enabled = Boolean.FALSE;
            }
        }

        public boolean isEnabled() {
            return Boolean.TRUE.equals(enabled);
        }
    }

    public static final int DEFAULT_QUEUE_CAPACITY = 200;
    public static final int DEFAULT_MAX_RESULTS = 10_000;
    public static final long DEFAULT_SHUTDOWN_TIMEOUT_MS = 10_000L;

    /** 实际线程数：未配置时按 CPU 自动（至少 2，保证一个任务能阻塞等另一个不被饿死）。 */
    public int effectiveThreads() {
        return executorThreads > 0
                ? executorThreads
                : Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    }

    /**
     * 运维控制面参数（M9）。
     *
     * @param allowBulkRecover   是否允许「一键恢复所有孤儿任务」。默认 {@code false}——
     *                           滚动发布期间新实例若自动去抢，会把还在跑的任务抢过来重放，
     *                           而这一步没有任何人确认过
     * @param staleInDoubtAfter  幂等记录停在 {@code INIT} 多久算「超期未决」，默认 15 分钟
     *                          （覆盖一次慢网关调用 + 余量）。这类记录是<b>唯一</b>
     *                          「钱可能动了但没人知道」的状态，值得单独告警
     * @param maxListSize        端点一次最多返回多少条，默认 50
     * @param strictAuth         true = 用 ownerOrAdmin 策略鉴权（匿名变更会被拒），默认 false 保持兼容
     */
    public record Ops(Boolean allowBulkRecover, Duration staleInDoubtAfter, int maxListSize,
                      Boolean strictAuth, Boolean auditJdbc) {

        public static final Duration DEFAULT_STALE_AFTER = Duration.ofMinutes(15);
        public static final int DEFAULT_MAX_LIST_SIZE = 50;

        public Ops {
            if (allowBulkRecover == null) {
                allowBulkRecover = Boolean.FALSE;
            }
            if (staleInDoubtAfter == null || staleInDoubtAfter.isNegative() || staleInDoubtAfter.isZero()) {
                staleInDoubtAfter = DEFAULT_STALE_AFTER;
            }
            if (maxListSize <= 0) {
                maxListSize = DEFAULT_MAX_LIST_SIZE;
            }
            if (strictAuth == null) {
                strictAuth = Boolean.FALSE;
            }
            if (auditJdbc == null) {
                auditJdbc = Boolean.FALSE;
            }
        }
    }

    /** 链路追踪输出。 */
    public enum TracerMode {
        /** 不输出（默认；埋点仍然在工作，只是没有消费方）。 */
        NONE,
        /** 打到控制台，排查时用。 */
        CONSOLE
    }
}
