package com.agentruntime.samples.m8;

import com.agentruntime.api.AgentEngine;
import com.agentruntime.api.TaskResult;
import com.agentruntime.api.TaskView;
import com.agentruntime.autoconfigure.AgentRuntimeProperties;
import com.agentruntime.autoconfigure.EnableAgentRuntime;
import com.agentruntime.samples.db.DbSupport;
import com.agentruntime.domain.TaskStatus;
import com.agentruntime.engine.DefaultAgentEngine;
import com.agentruntime.engine.ToolRegistry;
import com.agentruntime.observability.AgentTracer;
import com.agentruntime.observability.Metrics;
import com.agentruntime.persistence.IdempotencyStore;
import com.agentruntime.persistence.OutboxStore;
import com.agentruntime.persistence.TaskRepository;
import com.agentruntime.samples.refund.OrderQueryService;
import com.agentruntime.samples.refund.RefundAgent;
import com.agentruntime.samples.refund.RefundService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * M8 验收（DESIGN.md §8）：自动装配。
 *
 * <pre>
 *   ① 一个 {@code @SpringBootApplication} + 一个 {@code @AgentTool} bean，引擎自动就位
 *   ② 容器里有 DataSource → 仓储/幂等自动切到 JDBC，并建好 4 张表（连库、建表、装配都没写一行代码）
 *   ③ {@code @AgentTool} bean 被自动扫描注册（4 个工具），且能被引擎真的调起来
 *   ④ 完整跑一笔退款：决策→查单→退款→完成，事件账本落库
 *   ⑤ 同订单第二次提交命中幂等：真实扣款仍只有 1 次（经 Spring 装配后不变量依旧成立）
 *   ⑥ {@code agent.runtime.*} 配置项真的绑到了引擎上
 * </pre>
 *
 * <p>跑法：
 * <pre>
 *   mvn -q -pl agent-runtime-samples install
 *   java -cp "agent-runtime-core/target/classes;agent-runtime-spring-boot-starter/target/classes;\
 * agent-runtime-samples/target/classes;$(cat cp.txt)" com.agentruntime.samples.m8.M8Demo
 * </pre>
 * 依赖较多，实际用 {@code mvn -pl agent-runtime-samples exec:java -Dexec.mainClass=...} 更方便。
 */
@SpringBootApplication
@EnableAgentRuntime
public class M8Demo {

    private static final List<String> FAILURES = new ArrayList<>();
    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final String ORDER = "ORD-20261114-001";

    public static void main(String[] args) {
        System.out.println("================================================================");
        System.out.println(" Agent Runtime M8 — Spring Boot 自动装配（一个依赖 + 一个注解）");
        System.out.println("================================================================\n");

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(M8Demo.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.main.banner-mode=off",
                        "logging.level.root=WARN",
                        "logging.level.com.agentruntime=INFO",
                        "agent.runtime.max-steps=10",
                        "agent.runtime.tracer=CONSOLE")
                .run()) {

            section1Wiring(context);
            section2ToolScanning(context);
            section3EndToEnd(context);
            section4Idempotency(context);
            section5Persistence(context);
        }

        System.out.println("\n================================================================");
        if (FAILURES.isEmpty()) {
            System.out.println(" ✅ M8 验收通过：自动装配把引擎、仓储、幂等、工具、指标全部就位。");
        } else {
            System.out.println(" ❌ M8 验收失败 " + FAILURES.size() + " 项：");
            FAILURES.forEach(f -> System.out.println("   - " + f));
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 装配

    /** 业务方唯一需要写的「配置」：一个 DataSource。引擎相关的一切由 starter 决定。 */
    @Bean
    public DataSource dataSource() {
        // M11：默认 PostgreSQL；每次运行用独立 schema，互不干扰、也不用手工清库。
        // 没装 PG 时加 -Dagent.db=h2 走 H2 离线兜底（随机内存库）。
        return DbSupport.freshDataSource("m8");
    }

    /** 工具 bean：@AgentTool 注解本身没有任何 Spring 依赖，这里只是普通 bean。 */
    @Bean
    public OrderQueryService orderQueryService() {
        return new OrderQueryService();
    }

    @Bean
    public RefundService refundService() {
        return new RefundService();
    }

    // ---------------------------------------------------------------- 断言

    private static void section1Wiring(ConfigurableApplicationContext context) {
        System.out.println("[1/5] 容器里被自动装配出了什么");
        AgentRuntimeProperties props = context.getBean(AgentRuntimeProperties.class);
        TaskRepository repository = context.getBean(TaskRepository.class);
        IdempotencyStore store = context.getBean(IdempotencyStore.class);
        AgentEngine engine = context.getBean(AgentEngine.class);
        AgentTracer tracer = context.getBean(AgentTracer.class);
        Metrics metrics = context.getBean(Metrics.class);

        System.out.println("      TaskRepository  = " + repository.getClass().getSimpleName());
        System.out.println("      IdempotencyStore= " + store.getClass().getSimpleName());
        System.out.println("      AgentEngine     = " + engine.getClass().getSimpleName()
                + "（maxSteps=" + props.maxSteps() + "，inDoubt=" + props.inDoubtPolicy() + "）");
        System.out.println("      AgentTracer     = " + tracer.getClass().getSimpleName());
        System.out.println("      Metrics         = " + metrics.getClass().getSimpleName());

        check("有 DataSource → 仓储走 JDBC", repository.getClass().getSimpleName().contains("Jdbc"));
        check("有 DataSource → 幂等记录走 JDBC", store.getClass().getSimpleName().contains("Jdbc"));
        check("同一个 store 也承担 outbox 角色", store instanceof OutboxStore);
        check("引擎是 DefaultAgentEngine", engine instanceof DefaultAgentEngine);
        check("tracer 按 agent.runtime.tracer=CONSOLE 配上了", tracer != AgentTracer.noop());
        check("配置项绑到了引擎（max-steps=10）", props.maxSteps() == 10);
        check("AgentEngine 只有一个 bean（@EnableAgentRuntime 没有重复注册）",
                context.getBeanNamesForType(AgentEngine.class).length == 1);
        System.out.println();
    }

    private static void section2ToolScanning(ConfigurableApplicationContext context) {
        System.out.println("[2/5] @AgentTool bean 自动扫描");
        ToolRegistry registry = context.getBean(ToolRegistry.class);
        System.out.println("      已注册工具：" + registry.names());
        check("扫到 queryOrder / refund / refundRequiringApproval / auditLog 共 4 个",
                registry.names().containsAll(
                        List.of("queryOrder", "refund", "refundRequiringApproval", "auditLog")));
        System.out.println();
    }

    private static void section3EndToEnd(ConfigurableApplicationContext context) {
        System.out.println("[3/5] 端到端跑一笔退款（决策→查单→退款→完成）");
        AgentEngine engine = context.getBean(AgentEngine.class);
        String taskId = engine.submit(new RefundAgent(), "u_10086", ORDER);
        TaskResult result = engine.await(taskId, WAIT);
        TaskView view = engine.getTask(taskId);

        System.out.println("      终态=" + result.status() + "，事件=" + view.events().size()
                + " 条，结果=" + result.result());
        check("任务完成", result.status() == TaskStatus.COMPLETED);
        check("结果里有退款回执", String.valueOf(result.result()).contains("退款成功"));
        check("事件账本完整（2 决策 + 2 工具 + 1 完成 = 5）", view.events().size() == 5);
        check("步级时间线可离线还原（spans 非空）", !view.spans().isEmpty());
        System.out.println();
    }

    private static void section4Idempotency(ConfigurableApplicationContext context) {
        System.out.println("[4/5] 同一订单再提交一次：幂等仍然生效");
        AgentEngine engine = context.getBean(AgentEngine.class);
        RefundService refundService = context.getBean(RefundService.class);

        String second = engine.submit(new RefundAgent(), "u_10086", ORDER);
        TaskResult secondResult = engine.await(second, WAIT);
        System.out.println("      第二次终态=" + secondResult.status()
                + "，本进程真实扣款次数=" + refundService.refundExecutions());
        check("第二次也正常完成", secondResult.status() == TaskStatus.COMPLETED);
        check("真实扣款只有 1 次（幂等命中缓存）", refundService.refundExecutions() == 1);
        System.out.println();
    }

    private static void section5Persistence(ConfigurableApplicationContext context) {
        System.out.println("[5/5] 数据真的落到了库里（建表由 starter 在启动时完成）");
        DataSource dataSource = context.getBean(DataSource.class);
        long tasks = count(dataSource, "SELECT COUNT(*) FROM task");
        long events = count(dataSource, "SELECT COUNT(*) FROM task_event");
        long doneKeys = count(dataSource,
                "SELECT COUNT(*) FROM idempotency_record WHERE status='DONE'");
        long outbox = count(dataSource, "SELECT COUNT(*) FROM outbox");
        System.out.println("      task=" + tasks + "，task_event=" + events
                + "，幂等 DONE=" + doneKeys + "，outbox=" + outbox);
        check("两次提交 → 2 条 task", tasks == 2);
        check("事件已落库（两次各 5 条，共 " + events + " 条）", events == 10);
        check("幂等记录有 DONE（queryOrder 与 refund 各一条 key）", doneKeys >= 2);
        check("outbox 有事件待投递（副作用事实不丢）", outbox >= 1);
        System.out.println();
    }

    private static long count(DataSource dataSource, String sql) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1L;
        } catch (Exception e) {
            throw new IllegalStateException("查询失败: " + sql, e);
        }
    }

    private static void check(String name, boolean condition) {
        System.out.println((condition ? "   ✓ " : "   ✗ ") + name);
        if (!condition) {
            FAILURES.add(name);
        }
    }
}
