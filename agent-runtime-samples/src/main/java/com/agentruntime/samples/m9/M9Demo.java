package com.agentruntime.samples.m9;

import com.agentruntime.api.AgentEngine;
import com.agentruntime.api.TaskResult;
import com.agentruntime.autoconfigure.EnableAgentRuntime;
import com.agentruntime.samples.db.DbSupport;
import com.agentruntime.domain.TaskStatus;
import com.agentruntime.samples.refund.OrderQueryService;
import com.agentruntime.samples.refund.RefundAgent;
import com.agentruntime.samples.refund.RefundService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * M9 验收：运维控制面（真人能 curl 到的那种）。
 *
 * <pre>
 *   ① 一个需要人工确认的退款 → 任务停在 SUSPENDED，钱一分没动
 *   ② GET /actuator/agentruntime/overview    → 能看到「挂起了几笔」
 *   ③ GET /actuator/agentruntime/suspended   → 能看到「哪一笔、在等哪个工具、等了多久、为什么」
 *   ④ POST /actuator/agentruntime/resume     → 批准后工具真的执行，任务完成
 *   ⑤ GET /actuator/agentruntime/task        → 审批后能翻出完整轨迹（谁批的、批了哪步）
 *   ⑥ POST /actuator/agentruntime/recover-all → 默认拒绝，防止滚动发布时抢跑
 * </pre>
 *
 * <h2>为什么这个 demo 必须起真 Web 容器</h2>
 * 「运维能操作」这件事的验收标准是 <b>curl 得通</b>，不是「端点 bean 被创建了」。
 * 中间隔着的暴露配置（{@code management.endpoints.web.exposure.include}）、
 * 路径拼装、参数绑定（query 参数怎么映射到方法入参）、JSON 序列化——
 * 每一层都可能在 bean 装配正确的前提下出错。M4 抓 Prometheus 指标时用的是同一个理由。
 */
@SpringBootApplication
@EnableAgentRuntime
public class M9Demo {

    private static final List<String> FAILURES = new ArrayList<>();
    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final String ORDER = "ORD-20261114-001";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static HttpClient http;
    private static String baseUrl;

    public static void main(String[] args) {
        System.out.println("================================================================");
        System.out.println(" Agent Runtime M9 — 运维控制面（挂起 / 在途未知 / 恢复，真实 HTTP）");
        System.out.println("================================================================\n");

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(M9Demo.class)
                .web(WebApplicationType.SERVLET)
                .properties(
                        "spring.main.banner-mode=off",
                        "logging.level.root=WARN",
                        "logging.level.com.agentruntime=INFO",
                        "server.port=0",                                    // 随机端口，避免占用
                        "management.endpoints.web.exposure.include=agentruntime",
                        "management.endpoint.agentruntime.show-details=always")
                .run()) {

            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            baseUrl = "http://127.0.0.1:" + port + "/actuator/agentruntime";
            http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            System.out.println("控制面地址：" + baseUrl + "\n");

            String taskId = section1Suspend(context);
            section2Overview();
            section3SuspendedList(taskId);
            section4Resume(context, taskId);
            section5TaskDetail(taskId);
            section6BulkGuard();
        }

        System.out.println("\n================================================================");
        if (FAILURES.isEmpty()) {
            System.out.println(" ✅ M9 验收通过：挂起任务可被发现、被批准、被续跑，批量操作有护栏。");
        } else {
            System.out.println(" ❌ M9 验收失败 " + FAILURES.size() + " 项：");
            FAILURES.forEach(f -> System.out.println("   - " + f));
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 夹具

    @Bean
    public DataSource dataSource() {
        // M11：默认 PostgreSQL；每次运行用独立 schema，互不干扰、也不用手工清库。
        // 没装 PG 时加 -Dagent.db=h2 走 H2 离线兜底（随机内存库）。
        return DbSupport.freshDataSource("m9");
    }

    @Bean
    public OrderQueryService orderQueryService() {
        return new OrderQueryService();
    }

    @Bean
    public RefundService refundService() {
        return new RefundService();
    }

    // ---------------------------------------------------------------- ①

    private static String section1Suspend(ConfigurableApplicationContext context) {
        System.out.println("[1/6] 大额退款需要人工确认：任务会停在 SUSPENDED");
        AgentEngine engine = context.getBean(AgentEngine.class);
        RefundService refundService = context.getBean(RefundService.class);

        // 用带审批的工具：引擎在「调工具之前」就停下，工具方法自己完全不知道被拦过
        String taskId = engine.submit(new RefundAgent("refundRequiringApproval"), "u_10086", ORDER);
        TaskResult result = engine.await(taskId, WAIT);
        System.out.println("      taskId=" + taskId + "，终态=" + result.status()
                + "，真实扣款次数=" + refundService.refundExecutions());
        check("任务挂起而不是失败", result.status() == TaskStatus.SUSPENDED);
        check("挂起时钱一分没动（扣款 0 次）", refundService.refundExecutions() == 0);
        return taskId;
    }

    // ---------------------------------------------------------------- ②

    private static void section2Overview() {
        System.out.println("\n[2/6] GET overview：挂起了几笔？");
        JsonNode body = get("/overview");
        System.out.println("      " + body);
        check("HTTP 200 且返回 tasks 计数", body.has("tasks"));
        check("SUSPENDED 计数 = 1", body.at("/tasks/SUSPENDED").asLong() == 1L);
        check("超期未决可巡检（0 笔）", body.get("staleInDoubt").asLong() == 0L);
        check("批量恢复默认关闭", !body.get("bulkRecoverAllowed").asBoolean());
    }

    // ---------------------------------------------------------------- ③

    private static void section3SuspendedList(String taskId) {
        System.out.println("\n[3/6] GET suspended：哪一笔、在等什么、等了多久？");
        JsonNode body = get("/suspended");
        JsonNode item = body.at("/items/0");
        System.out.println("      " + item);
        check("清单里有这一笔", body.get("count").asLong() == 1L
                && taskId.equals(item.get("taskId").asText()));
        check("说清了在等哪个工具", "refundRequiringApproval".equals(item.get("pendingTool").asText()));
        check("标明这是「等确认」而不是「在途未知」",
                item.get("awaitingConfirmation").asBoolean() && !item.get("inDoubt").asBoolean());
        check("给了人类可读的原因", String.valueOf(item.get("reason")).contains("需要人工确认"));
        check("给了已等待秒数（运维判断优先级用）", item.has("waitedSeconds"));
    }

    // ---------------------------------------------------------------- ④

    private static void section4Resume(ConfigurableApplicationContext context, String taskId) {
        System.out.println("\n[4/6] POST resume：批准后工具才真的执行");
        JsonNode body = post("/resume/" + taskId, "{\"note\":\"财务已核对发票\"}");
        System.out.println("      " + body);
        check("端点返回成功", body.get("ok").asBoolean());

        // 恢复是异步的：用引擎自己 await，证明「端点触发 → 引擎真的续跑完了」
        AgentEngine engine = context.getBean(AgentEngine.class);
        TaskResult done = engine.await(taskId, WAIT);
        RefundService refundService = context.getBean(RefundService.class);
        System.out.println("      终态=" + done.status() + "，真实扣款次数=" + refundService.refundExecutions());
        check("任务完成", done.status() == TaskStatus.COMPLETED);
        check("批准后恰好执行 1 次退款", refundService.refundExecutions() == 1);
    }

    // ---------------------------------------------------------------- ⑤

    private static void section5TaskDetail(String taskId) {
        System.out.println("\n[5/6] GET task：事后能翻出完整轨迹");
        JsonNode body = get("/task/" + taskId);
        System.out.println("      status=" + body.get("status").asText()
                + "，trajectory=" + body.get("trajectory").asText());
        check("终态是 COMPLETED", "COMPLETED".equals(body.get("status").asText()));
        check("轨迹里有「决策→工具→完成」", body.get("trajectory").asText().contains("COMPLETION"));
        check("挂起信息已清空（不再是待处理）", body.get("pendingTool").isNull());
        check("步级 span 可离线还原", body.get("spans").size() >= 2);
    }

    // ---------------------------------------------------------------- ⑥

    private static void section6BulkGuard() {
        System.out.println("\n[6/6] POST recover-all：批量恢复的护栏");
        JsonNode body = post("/recover-all/dry", "{\"note\":\"巡检验证\"}");
        System.out.println("      " + body);
        check("默认拒绝批量恢复", !body.get("ok").asBoolean());
        check("拒绝原因指向具体配置项",
                String.valueOf(body.get("error")).contains("allow-bulk-recover"));

        JsonNode unknown = post("/resume/nope", "{\"note\":\"\"}");
        check("操作不存在的任务返回原因而不是 500", !unknown.get("ok").asBoolean());

        // M10：孤儿视图 —— 批量恢复到底会动谁，先看清楚再动手
        JsonNode orphans = get("/orphans");
        System.out.println("      " + orphans);
        check("没有心跳过期的孤儿任务", orphans.get("count").asLong() == 0L);
        check("孤儿视图给出了租约 TTL", orphans.has("leaseTtl"));
    }

    // ---------------------------------------------------------------- HTTP

    private static JsonNode get(String path) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET());
    }

    private static JsonNode post(String path, String jsonBody) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody)));
    }

    private static JsonNode send(HttpRequest.Builder builder) {
        try {
            HttpResponse<String> response = http.send(
                    builder.timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
            }
            return JSON.readTree(response.body());
        } catch (Exception e) {
            throw new IllegalStateException("请求失败: " + e.getMessage(), e);
        }
    }

    private static void check(String name, boolean condition) {
        System.out.println((condition ? "   ✓ " : "   ✗ ") + name);
        if (!condition) {
            FAILURES.add(name);
        }
    }
}
