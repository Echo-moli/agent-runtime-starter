package com.agentruntime.autoconfigure;

import com.agentruntime.api.AgentEngine;
import com.agentruntime.engine.DefaultAgentEngine;
import com.agentruntime.engine.ExponentialBackoffRetry;
import com.agentruntime.engine.RetryPolicy;
import com.agentruntime.engine.ToolRegistry;
import com.agentruntime.observability.AgentTracer;
import com.agentruntime.observability.ConsoleTracer;
import com.agentruntime.observability.Metrics;
import com.agentruntime.observability.SimpleMetrics;
import com.agentruntime.persistence.IdempotencyStore;
import com.agentruntime.persistence.InMemoryIdempotencyStore;
import com.agentruntime.persistence.InMemoryTaskRepository;
import com.agentruntime.persistence.JdbcIdempotencyStore;
import com.agentruntime.persistence.JdbcTaskRepository;
import com.agentruntime.persistence.OutboxStore;
import com.agentruntime.persistence.TaskRepository;
import com.agentruntime.ops.OpsAlert;
import com.agentruntime.ops.OpsAuditLog;
import com.agentruntime.ops.OpsAuthorizer;
import com.agentruntime.ops.OpsPrincipal;
import com.agentruntime.ops.InstanceRegistry;
import com.agentruntime.ops.JdbcInstanceRegistry;
import com.agentruntime.ops.WebhookOpsAlert;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.net.URI;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 自动装配入口（M8，DESIGN §8）。
 *
 * <h2>装配出什么</h2>
 * <table border="1">
 *   <caption>bean 一览</caption>
 *   <tr><th>bean</th><th>默认实现</th><th>条件</th></tr>
 *   <tr><td>{@link ToolRegistry}</td><td>空注册表，随后由 {@link AgentToolScanner} 填充</td><td>可覆盖</td></tr>
 *   <tr><td>{@link TaskRepository}</td><td>有 {@link DataSource} → JDBC；否则内存</td><td>可覆盖</td></tr>
 *   <tr><td>{@link IdempotencyStore}</td><td>同上（JDBC / 内存）</td><td>可覆盖</td></tr>
 *   <tr><td>{@link AgentEngine}</td><td>{@link DefaultAgentEngine}，串起全部 SPI</td><td>可覆盖</td></tr>
 *   <tr><td>{@link AgentTracer}</td><td>有 OTel {@code Tracer} → OtelTracer；否则 noop / console</td><td>可覆盖</td></tr>
 *   <tr><td>{@link Metrics}</td><td>有 Micrometer {@code MeterRegistry} → 适配器；否则 SimpleMetrics</td><td>可覆盖</td></tr>
 * </table>
 *
 * <h2>三条装配原则</h2>
 * <ol>
 *   <li><b>全部 {@code @ConditionalOnMissingBean}</b>：业务方定义了同类型 bean 就完全接管，
 *       不需要 {@code @Primary}、不需要排除自动配置。这是 starter 的基本礼貌。</li>
 *   <li><b>有数据库就连数据库，没有就用内存</b>：评估阶段不该被「先装个 PG」卡住；
 *       但一旦应用里有 {@code DataSource}，默认就该持久化——内存仓储在重启后会丢掉
 *       所有 RUNNING 任务，等于把「断点续跑」这个卖点关掉了。</li>
 *   <li><b>可选依赖用 {@code @ConditionalOnClass} 隔离</b>：Micrometer / OTel 不在 classpath
 *       时这些配置类<b>连加载都不该发生</b>（否则 NoClassDefFoundError 会在启动时炸）。</li>
 * </ol>
 *
 * <h2>为什么嵌套配置类写在外部类之前（源码里看起来是「先嵌套后方法」）</h2>
 * Spring 解析配置类时，成员类先于本类的 {@code @Bean} 方法注册。所以
 * 「JDBC 版仓储」先注册，「内存版兜底」的 {@code @ConditionalOnMissingBean} 才能看到它；
 * 反过来写就会变成「内存版总是赢」。这不是风格问题，是顺序问题。
 */
@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration")
@ConditionalOnClass(AgentEngine.class)
@EnableConfigurationProperties(AgentRuntimeProperties.class)
public class AgentRuntimeAutoConfiguration {

    private static final Log log = LogFactory.getLog(AgentRuntimeAutoConfiguration.class);

    // ------------------------------------------------------------------
    // 持久化：有 DataSource 走 JDBC，没有走内存
    // ------------------------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(DataSource.class)
    static class JdbcPersistenceConfiguration {

        @Bean
        @ConditionalOnMissingBean(TaskRepository.class)
        TaskRepository agentTaskRepository(DataSource dataSource) {
            return new JdbcTaskRepository(dataSource);
        }

        /**
         * 返回<b>具体类型</b>而不是 {@code IdempotencyStore} 接口。
         *
         * <p>原因：这个对象同时实现 {@link IdempotencyStore} 与 {@link OutboxStore}。
         * 若再单独声明一个 {@code OutboxStore} bean 指向同一个对象，容器里就会出现
         * <b>两个 bean 定义都匹配 IdempotencyStore</b>，按类型注入直接
         * {@code NoUniqueBeanDefinitionException}。一个 bean 定义、多个接口，
         * 才是「同一个对象两种角色」在 Spring 里的正确表达。
         */
        @Bean
        @ConditionalOnMissingBean(IdempotencyStore.class)
        JdbcIdempotencyStore agentIdempotencyStore(DataSource dataSource) {
            return new JdbcIdempotencyStore(dataSource);
        }

        @Bean
        @ConditionalOnMissingBean
        // schema-init 默认开；flyway.enabled=true 时让路，避免两套机制同时建表。
        // 用表达式而非两个 @ConditionalOnProperty：后者不可重复标注在同一方法上。
        @ConditionalOnExpression("${agent.runtime.schema-init:true} and !${agent.runtime.flyway.enabled:false}")
        AgentSchemaInitializer agentSchemaInitializer(DataSource dataSource) {
            return new AgentSchemaInitializer(dataSource);
        }

        /**
         * Flyway 版本化迁移（P1）：仅当 classpath 有 flyway-core 且显式开启时启用。
         * 与 {@link AgentSchemaInitializer} 互斥（见上方条件）。
         */
        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnClass(name = "org.flywaydb.core.Flyway")
        @ConditionalOnProperty(prefix = "agent.runtime.flyway", name = "enabled", havingValue = "true")
        AgentFlywayInitializer agentFlywayInitializer(DataSource dataSource) {
            return new AgentFlywayInitializer(dataSource);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(DataSource.class)
    static class InMemoryPersistenceConfiguration {

        @Bean
        @ConditionalOnMissingBean(TaskRepository.class)
        TaskRepository agentTaskRepository() {
            return new InMemoryTaskRepository();
        }

        /** 同 JDBC 版：一个 bean 定义，同时承担幂等记录与 outbox 两个角色。 */
        @Bean
        @ConditionalOnMissingBean(IdempotencyStore.class)
        InMemoryIdempotencyStore agentIdempotencyStore() {
            return new InMemoryIdempotencyStore();
        }
    }

    // ------------------------------------------------------------------
    // 可观测性适配器（可选依赖，先注册；本类里的兜底在其后）
    // ------------------------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    @ConditionalOnBean(type = "io.micrometer.core.instrument.MeterRegistry")
    static class MicrometerConfiguration {

        @Bean
        @ConditionalOnMissingBean(Metrics.class)
        Metrics agentMetrics(io.micrometer.core.instrument.MeterRegistry registry) {
            return new MicrometerMetrics(registry);
        }
    }

    /**
     * 运维控制面（M9）：只有引了 actuator 的应用才会有这个端点。
     *
     * <p>两个条件：actuator 在 classpath 上（{@code @ConditionalOnClass} 用类名字符串，
     * 免得没引 actuator 时配置类本身就加载失败）、且没被配置关掉。
     *
     * <p><b>刻意不加 {@code @ConditionalOnBean(AgentEngine.class)}</b>：嵌套配置类的条件
     * 在本类 {@code @Bean} 方法注册<b>之前</b>求值，那时引擎的 bean 定义还不存在，
     * 条件恒为假——端点会无声消失。这正是本文档反复强调的那个顺序陷阱。
     *
     * <p>端点 bean 存在 ≠ 端点被暴露：还需要
     * {@code management.endpoints.web.exposure.include=agentruntime}。
     * 这是有意的——「能操作」与「能被访问」是两道独立的门。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
    @ConditionalOnProperty(prefix = "management.endpoint.agentruntime", name = "enabled",
            matchIfMissing = true)
    static class OpsEndpointConfiguration {

        /**
         * 安全 SPI 全部走 {@link ObjectProvider}：业务方注入 {@link OpsAuthorizer} /
         * {@link OpsAuditLog} / {@link OpsAlert} / {@link OpsPrincipal} 即自动接管，
         * 不注入则用 {@link #opsAuthorizer()} 等默认 bean（放行 + 内存审计 + 无告警）。
         */
        @Bean
        @ConditionalOnMissingBean
        AgentRuntimeEndpoint agentRuntimeEndpoint(AgentEngine engine,
                                                  ObjectProvider<DefaultAgentEngine> bulkCapable,
                                                  TaskRepository repository,
                                                  ObjectProvider<com.agentruntime.persistence.IdempotencyInspector> inspector,
                                                  AgentRuntimeProperties properties,
                                                  ObjectProvider<OpsAuthorizer> authorizer,
                                                  ObjectProvider<OpsAuditLog> audit,
                                                  ObjectProvider<OpsAlert> alert,
                                                  ObjectProvider<OpsPrincipal> principal) {
            return new AgentRuntimeEndpoint(engine, bulkCapable, repository, inspector, properties,
                    authorizer, audit, alert, principal);
        }

        /** 默认鉴权：放行（兼容旧行为）；企业部署应提供 ownerOrAdmin() 或自定义 bean。 */
        @Bean
        @ConditionalOnMissingBean(OpsAuthorizer.class)
        OpsAuthorizer opsAuthorizer(AgentRuntimeProperties properties) {
            if (properties.ops().strictAuth()) {
                log.warn("[agent-runtime] 已启用 strict-auth：运维端点默认拒绝匿名变更操作，"
                        + "请注入 OpsPrincipal bean 提供操作者身份");
                return OpsAuthorizer.ownerOrAdmin();
            }
            return OpsAuthorizer.permissive();
        }

        /**
         * 审计日志：默认有界内存（最近 1000 条可查）；{@code audit-jdbc=true} 且有 DataSource 时落库。
         * JDBC 模式要求 {@code ops_audit} 表已建（schema-init 或 Flyway 都会建）。
         */
        @Bean
        @ConditionalOnMissingBean(OpsAuditLog.class)
        OpsAuditLog opsAuditLog(AgentRuntimeProperties properties, ObjectProvider<DataSource> dataSource) {
            if (properties.ops().auditJdbc()) {
                DataSource ds = dataSource.getIfAvailable();
                if (ds != null) {
                    return new com.agentruntime.ops.JdbcOpsAuditLog(ds);
                }
                log.warn("[agent-runtime] audit-jdbc=true 但没有 DataSource，审计退化为内存实现");
            }
            return OpsAuditLog.inMemory(1000);
        }

        /**
         * 告警出口：默认 NOOP；配了 {@code agent.runtime.ops.alert-webhook-url} 才真正发出去。
         * 单一 bean 按配置分流，比两个互斥的 @ConditionalOnProperty 少一个装配竞态。
         */
        // destroyMethod 用 Spring 默认的 inferred：WebhookOpsAlert 有 close() 会被调用，
        // NOOP lambda 没有则安全跳过（显式写 "close" 会让 NOOP 启动时报 Invalid destruction signature）。
        @Bean
        @ConditionalOnMissingBean(OpsAlert.class)
        OpsAlert opsAlert(@Value("${agent.runtime.ops.alert-webhook-url:}") String webhookUrl) {
            if (webhookUrl == null || webhookUrl.isBlank()) {
                return OpsAlert.NOOP;
            }
            return new WebhookOpsAlert(URI.create(webhookUrl));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.opentelemetry.api.trace.Tracer")
    @ConditionalOnBean(type = "io.opentelemetry.api.trace.Tracer")
    static class OpenTelemetryConfiguration {

        @Bean
        @ConditionalOnMissingBean(AgentTracer.class)
        AgentTracer agentTracer(io.opentelemetry.api.trace.Tracer tracer) {
            return new OtelTracer(tracer);
        }
    }

    // ------------------------------------------------------------------
    // 兜底实现
    // ------------------------------------------------------------------

    @Bean
    @ConditionalOnMissingBean
    ToolRegistry agentToolRegistry() {
        return new ToolRegistry();
    }

    /** 注入工具 bean；必须在注册表就绪之后，故用 {@code SmartInitializingSingleton}。 */
    @Bean
    @ConditionalOnMissingBean
    AgentToolScanner agentToolScanner(ConfigurableApplicationContext context, ToolRegistry registry) {
        return new AgentToolScanner(context, registry);
    }

    @Bean
    @ConditionalOnMissingBean(AgentTracer.class)
    AgentTracer agentTracer(AgentRuntimeProperties properties) {
        return properties.tracer() == AgentRuntimeProperties.TracerMode.CONSOLE
                ? new ConsoleTracer()
                : AgentTracer.noop();
    }

    /**
     * 指标兜底：没配 Micrometer 就用 core 自带的实现。
     *
     * <p>为什么不默认 noop：没有指标的引擎在线上就是黑盒，
     * 而 {@link SimpleMetrics} 的开销只是几个 {@code LongAdder}，
     * 「默认关掉可观测性」省下的那点 CPU 换来的是「出事时什么都看不到」。
     */
    @Bean
    @ConditionalOnMissingBean(Metrics.class)
    Metrics agentMetrics(AgentRuntimeProperties properties) {
        return Boolean.FALSE.equals(properties.metricsEnabled())
                ? Metrics.noop()
                : new SimpleMetrics();
    }

    /**
     * 启动期校验：幂等存储必须同时能承接 outbox（M3 的核心不变量）。
     *
     * <h2>为什么不在这里再声明一个 {@code OutboxStore} bean</h2>
     * 内存版与 JDBC 版的 store 都同时实现两个接口，容器里本来就有「一个 bean、两种角色」。
     * 再声明一个指向同一对象的 {@code OutboxStore} bean，会让按类型注入
     * {@link IdempotencyStore} 时撞上两个 bean 定义（{@code NoUniqueBeanDefinitionException}）——
     * 这正是第一版自动装配测试抓到的真实问题。
     *
     * <p>所以校验放在这里：业务方自定义的 store 若没实现 {@link OutboxStore}，
     * 「DONE 标记与 outbox 事件同事务」这条不变量就无处落地。这里报 warn 而不是让应用起不来——
     * outbox 只影响下游投递，不影响引擎本身跑完任务；让人看见原因，比让人看见启动失败更有用。
     */
    private static void verifyOutboxCapability(IdempotencyStore store) {
        if (store == null) {
            log.warn("[agent-runtime] 没有 IdempotencyStore：所有工具的幂等语义被关闭"
                    + "（带 idempotencyKey 的写操作会重复执行，请勿用于生产）");
            return;
        }
        if (!(store instanceof OutboxStore)) {
            log.warn("[agent-runtime] 自定义 IdempotencyStore 没有实现 OutboxStore: "
                    + store.getClass().getName()
                    + "；副作用事实不会进 outbox，下游收不到 tool.executed 事件");
        }
    }

    /**
     * 引擎线程池。
     *
     * <p>不用应用里已有的 {@code Executor}：那通常是给异步任务/HTTP 用的，
     * agent 的每一步都可能阻塞几秒（等模型、等网关），共用会互相拖死。
     * 线程是 daemon，且 bean 的 {@code destroyMethod=shutdown} 保证容器关闭时收干净——
     * 非 daemon 线程会让 JVM 退不掉，这是集成测试里最常见的「mvn 卡住」原因。
     */
    @Bean(name = "agentRuntimeExecutor", destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "agentRuntimeExecutor")
    ExecutorService agentRuntimeExecutor(AgentRuntimeProperties properties) {
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "agent-runtime-worker-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        int threads = properties.effectiveThreads();
        // 有界队列 + CallerRunsPolicy（P0 稳定性）：下游慢下来时提交线程自己扛住，
        // 形成背压而不是把任务堆进无界队列直到 OOM。
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.queueCapacity()), factory,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * 健康探针（P1）：用 {@link ObjectProvider} 拿引擎而不是 {@code @ConditionalOnBean}——
     * 后者在配置类里的求值顺序不可靠（本项目反复踩过的「条件恒假」陷阱）。
     * 引擎不是 DefaultAgentEngine（业务方自定义）时返回 null，Spring 会跳过该 bean。
     */
    @Bean
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
    @ConditionalOnMissingBean(name = "agentRuntimeHealthIndicator")
    AgentRuntimeHealthIndicator agentRuntimeHealthIndicator(
            ObjectProvider<DefaultAgentEngine> engine, TaskRepository repository,
            ObjectProvider<DataSource> dataSource, AgentRuntimeProperties properties) {
        DefaultAgentEngine impl = engine.getIfAvailable();
        if (impl == null) {
            return null;   // 自定义引擎：不提供本探针，让业务方自己决定健康语义
        }
        return new AgentRuntimeHealthIndicator(impl, repository, dataSource.getIfAvailable(),
                properties.leaseTtl(), 0);
    }

    /**
     * 实例注册表：默认内存实现（单实例够用）。
     * 多副本必须设 {@code agent.runtime.ops.registry-jdbc=true} 换成 JDBC 共享版，
     * 否则每个实例都只看得见自己报到，注册表退化成永远返回 false 的摆设。
     */
    @Bean
    @ConditionalOnMissingBean(InstanceRegistry.class)
    @ConditionalOnProperty(name = "agent.runtime.ops.registry-jdbc", havingValue = "false", matchIfMissing = true)
    InstanceRegistry agentInstanceRegistry() {
        return new InstanceRegistry.InMemory();
    }

    /** 跨实例共享注册表：大家往同一张表报到，恢复决策才有第二个独立信号。 */
    @Bean
    @ConditionalOnMissingBean(InstanceRegistry.class)
    @ConditionalOnProperty(name = "agent.runtime.ops.registry-jdbc", havingValue = "true")
    JdbcInstanceRegistry agentJdbcInstanceRegistry(DataSource dataSource) {
        return new JdbcInstanceRegistry(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean(AgentEngine.class)
    DefaultAgentEngine agentEngine(TaskRepository repository,
                                   ToolRegistry registry,
                                   AgentRuntimeProperties properties,
                                   ObjectProvider<IdempotencyStore> stores,
                                   ObjectProvider<AgentEngine.StepListener> listeners,
                                   AgentTracer tracer,
                                   Metrics metrics,
                                   InstanceRegistry instanceRegistry,
                                   ObjectProvider<OpsAlert> alerts,
                                   @Qualifier("agentRuntimeExecutor") ExecutorService agentRuntimeExecutor) {
        RetryPolicy retryPolicy = Boolean.FALSE.equals(properties.retryEnabled())
                ? RetryPolicy.none()
                : properties.retryMaxDelayMs() > 0
                        ? new ExponentialBackoffRetry(properties.retryMaxDelayMs())
                        : RetryPolicy.exponential();

        IdempotencyStore store = stores.getIfAvailable();
        verifyOutboxCapability(store);

        List<AgentEngine.StepListener> ordered = listeners.orderedStream().toList();
        return DefaultAgentEngine.builder(repository, registry)
                .maxSteps(properties.maxSteps())
                .executor(agentRuntimeExecutor)
                .listener(CompositeStepListener.of(ordered))
                .idempotencyStore(store)
                .retryPolicy(retryPolicy)
                .inDoubtPolicy(properties.inDoubtPolicy())
                .tracer(tracer)
                .metrics(metrics)
                .instanceId(properties.instanceId())
                .leaseTtl(properties.leaseTtl())
                .maxResults(properties.maxResults())
                .shutdownTimeoutMs(properties.shutdownTimeoutMs())
                .instanceRegistry(instanceRegistry)
                .alert(alerts.getIfAvailable(() -> OpsAlert.NOOP))
                .build();
    }
}
