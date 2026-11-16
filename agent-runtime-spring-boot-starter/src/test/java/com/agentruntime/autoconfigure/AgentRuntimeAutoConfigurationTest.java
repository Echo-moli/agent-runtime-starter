package com.agentruntime.autoconfigure;

import com.agentruntime.annotation.AgentTool;
import com.agentruntime.api.AgentEngine;
import com.agentruntime.api.AgentPlan;
import com.agentruntime.api.Context;
import com.agentruntime.api.Step;
import com.agentruntime.api.TaskResult;
import com.agentruntime.domain.InDoubtPolicy;
import com.agentruntime.domain.TaskStatus;
import com.agentruntime.engine.DefaultAgentEngine;
import com.agentruntime.engine.ToolRegistry;
import com.agentruntime.observability.AgentTracer;
import com.agentruntime.observability.Metrics;
import com.agentruntime.observability.SimpleMetrics;
import com.agentruntime.persistence.IdempotencyStore;
import com.agentruntime.persistence.InMemoryIdempotencyStore;
import com.agentruntime.persistence.InMemoryTaskRepository;
import com.agentruntime.persistence.JdbcIdempotencyStore;
import com.agentruntime.persistence.JdbcTaskRepository;
import com.agentruntime.persistence.OutboxStore;
import com.agentruntime.persistence.TaskRepository;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自动装配的行为断言（M8）。
 *
 * <h2>为什么这些用例必须存在</h2>
 * 自动装配最容易出的事故不是「编译不过」，而是<b>静默装配错了</b>：
 * 有 DataSource 却用了内存仓储（重启丢任务）、用户自定义的 bean 被覆盖、
 * 可选依赖缺席时启动直接炸 {@code NoClassDefFoundError}。
 * 这些都不会在编译期暴露，只能靠「把容器真的起起来，然后检查里面到底有什么」。
 *
 * <p>{@link ApplicationContextRunner} 是 Spring Boot 官方为这件事提供的工具：
 * 它不起 Web 容器、不加一堆无关的自动配置，只装配你指定的配置类并断言结果，
 * 比 {@code @SpringBootTest} 快一个数量级，也比它更聚焦。
 */
class AgentRuntimeAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentRuntimeAutoConfiguration.class));

    @Test
    @DisplayName("没有 DataSource：全部退化为内存实现，引擎照样能用")
    void fallsBackToInMemoryWithoutDataSource() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(TaskRepository.class);
            assertThat(context.getBean(TaskRepository.class)).isInstanceOf(InMemoryTaskRepository.class);
            assertThat(context.getBean(IdempotencyStore.class)).isInstanceOf(InMemoryIdempotencyStore.class);
            assertThat(context.getBean(AgentEngine.class)).isInstanceOf(DefaultAgentEngine.class);
            assertThat(context.getBean(AgentTracer.class)).isSameAs(AgentTracer.noop());
            assertThat(context.getBean(Metrics.class)).isInstanceOf(SimpleMetrics.class);

            // outbox 必须与幂等记录是同一个对象，否则「DONE 与 outbox 同事务」这条不变量就断了
            assertThat(context.getBean(OutboxStore.class))
                    .isSameAs(context.getBean(IdempotencyStore.class));
            assertThat(context).hasNotFailed();
        });
    }

    @Test
    @DisplayName("有 DataSource：切到 JDBC 实现，并且启动时把表建好")
    void switchesToJdbcWhenDataSourcePresent() {
        runner.withBean(DataSource.class, AgentRuntimeAutoConfigurationTest::h2DataSource)
                .run(context -> {
                    assertThat(context.getBean(TaskRepository.class)).isInstanceOf(JdbcTaskRepository.class);
                    assertThat(context.getBean(IdempotencyStore.class)).isInstanceOf(JdbcIdempotencyStore.class);
                    assertThat(context.getBean(OutboxStore.class))
                            .isSameAs(context.getBean(IdempotencyStore.class));

                    // 真实查一下表在不在——「初始化器 bean 存在」不等于「DDL 真的执行成功」。
                    // 逐表 SELECT 而不是查 information_schema：后者的表名大小写在 H2/PostgreSQL
                    // 两种方言下不一致，断言会因为大小写而假失败。
                    try (var connection = context.getBean(DataSource.class).getConnection()) {
                        for (String table : new String[]{"task", "task_event",
                                "idempotency_record", "outbox"}) {
                            try (var rs = connection.createStatement()
                                    .executeQuery("SELECT COUNT(*) FROM " + table)) {
                                assertThat(rs.next()).as("表 " + table + " 应已被创建").isTrue();
                            }
                        }
                    }
                    assertThat(context).hasNotFailed();
                });
    }

    @Test
    @DisplayName("schema-init=false：不建表（交给 Flyway 的场景）")
    void schemaInitCanBeDisabled() {
        runner.withBean(DataSource.class, AgentRuntimeAutoConfigurationTest::h2DataSource)
                .withPropertyValues("agent.runtime.schema-init=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AgentSchemaInitializer.class);
                    // 表没建，但容器仍然起得来——引擎要到第一次写库时才会报错，这是刻意的
                    assertThat(context).hasNotFailed();
                });
    }

    @Test
    @DisplayName("业务方定义的 bean 优先，自动装配让路")
    void userBeansWin() {
        TaskRepository custom = new InMemoryTaskRepository();
        AgentEngine customEngine = new DefaultAgentEngineBuilderProbe();

        runner.withBean(TaskRepository.class, () -> custom)
                .withBean(AgentEngine.class, () -> customEngine)
                .run(context -> {
                    assertThat(context.getBean(TaskRepository.class)).isSameAs(custom);
                    assertThat(context.getBean(AgentEngine.class)).isSameAs(customEngine);
                });
    }

    @Test
    @DisplayName("配置项真的绑到了引擎上（max-steps / in-doubt-policy）")
    void propertiesAreBound() {
        runner.withPropertyValues(
                        "agent.runtime.max-steps=3",
                        "agent.runtime.in-doubt-policy=FAIL",
                        "agent.runtime.executor-threads=2")
                .run(context -> {
                    AgentRuntimeProperties props = context.getBean(AgentRuntimeProperties.class);
                    assertThat(props.maxSteps()).isEqualTo(3);
                    assertThat(props.inDoubtPolicy()).isEqualTo(InDoubtPolicy.FAIL);
                    assertThat(props.effectiveThreads()).isEqualTo(2);
                });
    }

    @Test
    @DisplayName("max-steps 生效：3 步上限会让绕不出来的 agent 失败")
    void maxStepsIsEnforced() {
        runner.withBean(CalculatorTools.class, CalculatorTools::new)
                .withPropertyValues("agent.runtime.max-steps=3")
                .run(context -> {
                    AgentEngine engine = context.getBean(AgentEngine.class);
                    String taskId = engine.submit(new NeverEndingPlan(), "u1", "x");
                    TaskResult result = engine.await(taskId, Duration.ofSeconds(10));
                    assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
                    assertThat(result.error()).contains("上限 3");
                });
    }

    @Test
    @DisplayName("@AgentTool bean 被自动注册，并且能被引擎真的调起来")
    void toolsAreScannedAndInvocable() {
        runner.withBean(CalculatorTools.class, CalculatorTools::new)
                .run(context -> {
                    ToolRegistry registry = context.getBean(ToolRegistry.class);
                    assertThat(registry.names()).contains("add", "multiply");

                    AgentEngine engine = context.getBean(AgentEngine.class);
                    String taskId = engine.submit(new AddPlan(), "u1", "2+3");
                    TaskResult result = engine.await(taskId, Duration.ofSeconds(10));
                    assertThat(result.status()).isEqualTo(TaskStatus.COMPLETED);
                    assertThat(String.valueOf(result.result())).contains("5");
                });
    }

    @Test
    @DisplayName("AutoConfiguration.imports 指向正确的类（打包错误会静默失效）")
    void autoConfigurationImportsIsPackaged() throws Exception {
        try (var in = getClass().getResourceAsStream(
                "/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
            assertThat(in).as("imports 文件必须存在，否则 Spring Boot 根本不会加载本 starter")
                    .isNotNull();
            String content = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(content).contains(AgentRuntimeAutoConfiguration.class.getName());
            // 缺末尾换行时 Spring 会静默丢掉最后一条配置，所以文件必须以换行结尾
            assertThat(content).endsWith("\n");
        }
    }

    @Test
    @DisplayName("Flyway 开启后：版本化脚本真的建了表，且幂等 DDL 让路")
    void flywayMigrationRunsAndDisablesLegacyInitializer() {
        runner.withBean(DataSource.class, AgentRuntimeAutoConfigurationTest::h2DataSource)
                .withPropertyValues("agent.runtime.flyway.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(AgentSchemaInitializer.class);
                    assertThat(context).hasSingleBean(AgentFlywayInitializer.class);
                    // Flyway 的迁移历史表 + 四张业务表都要在
                    try (var connection = context.getBean(DataSource.class).getConnection()) {
                        // Flyway 历史表是小写标识符，H2 里必须加引号精确匹配；
                        // 业务表由 CREATE TABLE task 建成大写，用裸名让 H2 自动大写化匹配。
                        for (String table : new String[]{"\"agent_runtime_schema_history\"",
                                "task", "task_event", "idempotency_record", "outbox"}) {
                            try (var rs = connection.createStatement()
                                    .executeQuery("SELECT COUNT(*) FROM " + table)) {
                                assertThat(rs.next()).as("Flyway 应已建表 " + table).isTrue();
                            }
                        }
                    }
                });
    }

    @Test
    @DisplayName("spring-configuration-metadata.json 已打包，覆盖全部可调项（IDE 自动提示）")
    void configurationMetadataIsPackaged() throws Exception {
        try (var in = getClass().getResourceAsStream(
                "/META-INF/spring-configuration-metadata.json")) {
            assertThat(in).as("配置元数据必须存在，否则 IDE 里 agent.runtime.* 没有自动提示")
                    .isNotNull();
            String json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            for (String key : new String[]{"agent.runtime.max-steps", "agent.runtime.lease-ttl",
                    "agent.runtime.queue-capacity", "agent.runtime.max-results",
                    "agent.runtime.shutdown-timeout-ms", "agent.runtime.ops.strict-auth"}) {
                assertThat(json).as("元数据缺少 " + key).contains(key);
            }
        }
    }

    // ---------------------------------------------------------------- 夹具

    private static DataSource h2DataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:agent-runtime-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        return dataSource;
    }

    /** 计数器，验证「自动装配没有覆盖业务方提供的引擎」。 */
    private static final class DefaultAgentEngineBuilderProbe implements AgentEngine {
        @Override
        public String submit(AgentPlan plan, String userId, String userRequest) {
            return "probe";
        }

        @Override
        public TaskResult await(String taskId, Duration timeout) {
            return new TaskResult(taskId, TaskStatus.COMPLETED, null, null, null, 0);
        }

        @Override
        public void recover(String taskId) {
        }

        @Override
        public void resume(String taskId, Map<String, Object> humanInput) {
        }

        @Override
        public com.agentruntime.api.TaskView getTask(String taskId) {
            return null;
        }
    }

    /** 声明在类上的 @AgentTool——扫描器必须能在（可能的）代理类上找到它们。 */
    static class CalculatorTools {

        @AgentTool(name = "add")
        public int add(int a, int b) {
            return a + b;
        }

        @AgentTool(name = "multiply")
        public int multiply(int a, int b) {
            return a * b;
        }
    }

    /** 两步就完成的 plan。 */
    static class AddPlan implements AgentPlan {
        @Override
        public Step decide(Context ctx) {
            return switch (ctx.stepIndex()) {
                case 0 -> Step.tool("add", Map.of("a", 2, "b", 3));
                default -> Step.complete("2+3=" + ctx.lastToolResult().orElse("?"));
            };
        }
    }

    /** 永不结束：用来验证 max-steps 护栏。 */
    static class NeverEndingPlan implements AgentPlan {
        @Override
        public Step decide(Context ctx) {
            return Step.tool("add", Map.of("a", 1, "b", 1));
        }
    }
}
