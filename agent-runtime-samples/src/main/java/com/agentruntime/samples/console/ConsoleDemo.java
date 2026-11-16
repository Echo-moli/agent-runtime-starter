package com.agentruntime.samples.console;

import com.agentruntime.api.AgentEngine;
import com.agentruntime.api.TaskResult;
import com.agentruntime.autoconfigure.EnableAgentRuntime;
import com.agentruntime.domain.TaskStatus;
import com.agentruntime.samples.db.DbSupport;
import com.agentruntime.samples.refund.OrderQueryService;
import com.agentruntime.samples.refund.RefundAgent;
import com.agentruntime.samples.refund.RefundService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.time.Duration;

/**
 * 可视化运维面板宿主（常驻服务）。
 *
 * <p>M9Demo 用随机端口、跑完即关，适合验收但不适合"打开浏览器看一眼"。
 * 这个 demo 把同一套 actuator 端点挂到固定端口，并在 {@code static/} 下放一个
 * 纯静态单页——页面与端点同源，不需要 CORS、不引入任何前端框架或 CDN。
 *
 * <p>启动时造一笔"需要人工确认"的退款：打开面板就能看到 SUSPENDED 任务，
 * 可以直接点"批准"看它真的续跑完成。这是演示数据，不是框架行为。
 *
 * <pre>
 *   bash scripts/demo-console.sh        # 推荐；自动起 PG（或 -Dagent.db=h2）
 *   # 或：
 *   java -Dagent.db=h2 -cp "&lt;classpath&gt;" com.agentruntime.samples.console.ConsoleDemo
 *
 *   浏览器打开 http://localhost:8080/agent-console.html
 * </pre>
 *
 * <p>批量恢复保持框架的默认关闭策略——面板会照实显示"未开启"，
 * 想体验才显式加 {@code -Dagent.runtime.ops.allow-bulk-recover=true}。
 */
@SpringBootApplication
@EnableAgentRuntime
public class ConsoleDemo {

    private static final Duration WAIT = Duration.ofSeconds(20);

    public static void main(String[] args) throws InterruptedException {
        System.out.println("================================================================");
        System.out.println(" Agent Runtime — 可视化运维面板");
        System.out.println("================================================================");

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ConsoleDemo.class)
                .web(WebApplicationType.SERVLET)
                .properties(
                        "spring.main.banner-mode=off",
                        "logging.level.root=WARN",
                        "logging.level.com.agentruntime=INFO",
                        "server.port=8080",
                        "management.endpoints.web.exposure.include=agentruntime,health",
                        "management.endpoint.agentruntime.show-details=always")
                .run()) {

            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            seedSampleTask(context);

            System.out.println("\n  面板地址：http://localhost:" + port + "/agent-console.html");
            System.out.println("  端点地址：http://localhost:" + port + "/actuator/agentruntime");
            System.out.println("  按 Ctrl+C 退出\n");

            // 常驻：Web 容器本身是非守护线程，这里只是把 main 挂住以便 try-with-resources
            // 在收到中断时正常关闭容器（心跳线程、只读视图缓存一并收敛）。
            Thread.currentThread().join();
        }
    }

    /** 造一笔停在人工确认的任务，让面板第一次打开就有东西可看、可操作。 */
    private static void seedSampleTask(ConfigurableApplicationContext context) {
        AgentEngine engine = context.getBean(AgentEngine.class);
        // 订单号必须在 OrderQueryService 的种子数据里，否则第一步查询就失败、走不到审批工具。
        String taskId = engine.submit(new RefundAgent("refundRequiringApproval"),
                "u_console_demo", "ORD-20261114-001");
        TaskResult result = engine.await(taskId, WAIT);
        System.out.println("  演示任务：" + taskId + "，状态=" + result.status()
                + (result.status() == TaskStatus.SUSPENDED ? "（等你在面板里批准）" : ""));
    }

    @Bean
    public DataSource dataSource() {
        return DbSupport.freshDataSource("console");
    }

    @Bean
    public OrderQueryService orderQueryService() {
        return new OrderQueryService();
    }

    @Bean
    public RefundService refundService() {
        return new RefundService();
    }
}
