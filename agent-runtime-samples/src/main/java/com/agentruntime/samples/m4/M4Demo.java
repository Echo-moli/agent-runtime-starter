package com.agentruntime.samples.m4;

import com.agentruntime.api.AgentPlan;
import com.agentruntime.api.Context;
import com.agentruntime.api.Step;
import com.agentruntime.api.StepSpan;
import com.agentruntime.api.TaskResult;
import com.agentruntime.api.TaskView;
import com.agentruntime.domain.StepType;
import com.agentruntime.domain.TaskStatus;
import com.agentruntime.engine.ConsoleStepListener;
import com.agentruntime.engine.DefaultAgentEngine;
import com.agentruntime.engine.ToolRegistry;
import com.agentruntime.observability.CompositeTracer;
import com.agentruntime.observability.ConsoleTracer;
import com.agentruntime.observability.CostReport;
import com.agentruntime.observability.RecordingTracer;
import com.agentruntime.observability.SimpleMetrics;
import com.agentruntime.persistence.JdbcIdempotencyStore;
import com.agentruntime.persistence.Task;
import com.agentruntime.persistence.TaskEvent;
import com.agentruntime.persistence.TaskRepository;
import com.agentruntime.samples.db.DbSupport;
import com.agentruntime.samples.refund.CostAwareRefundAgent;
import com.agentruntime.samples.refund.OrderQueryService;
import com.agentruntime.samples.refund.RefundAgent;
import com.agentruntime.samples.refund.RefundService;
import com.agentruntime.util.Json;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.DoublePredicate;

/**
 * M4 验收（DESIGN.md §6 M4 / §12）：
 * <pre>
 *   ① 一次运行能看到每一步的 span 与分步成本，totalTokens/totalCost 与事件账本一致
 *   ② requireHumanConfirm 挂起 → resume：被重放的那一步<b>不再计费</b>
 *   ③ 8 个独立连接并发累加用量：原子 UPDATE，一次都不丢（读改写会丢）
 *   ④ 崩溃窗口（事件写了、汇总没加）→ reconcileUsage 按账本校正
 *   ⑤ /actuator/prometheus 真实 HTTP 抓取，指标名/值/格式都符合 Prometheus 约定
 * </pre>
 *
 * <p>用 H2 内存库（PostgreSQL 兼容模式）而非 mock：③④ 要验的正是 SQL 的原子性与账本语义，
 * 用内存 Map 模拟等于自己证明自己。异步执行器是刻意保留的——只有真的跑在线程池上，
 * 「活跃任务数」这个 gauge 才有意义。
 */
public final class M4Demo {

    private static final String ORDER_OK = "ORD-20261114-001";
    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final List<String> FAILURES = new ArrayList<>();

    /** 引擎驱动产生、应当体现在指标里的累计值（用于和 /actuator/prometheus 对账）。 */
    private static long expectedTokens;
    private static BigDecimal expectedCost = BigDecimal.ZERO;

    public static void main(String[] args) throws Exception {
        System.out.println("================================================================");
        System.out.println(" Agent Runtime M4 — span / 分步成本归因 / Prometheus 指标");
        System.out.println("================================================================\n");

        String url = DbSupport.memUrl("agent-runtime-m4");
        TaskRepository repo = DbSupport.openRepository(url);
        JdbcIdempotencyStore idempotency = DbSupport.openIdempotencyStore(url);

        RecordingTracer recording = new RecordingTracer();
        CompositeTracer tracer = new CompositeTracer(new ConsoleTracer(false), recording);
        SimpleMetrics metrics = new SimpleMetrics();

        ToolRegistry registry = new ToolRegistry()
                .register(new OrderQueryService().withLatency(12))
                .register(new RefundService().withLatency(35));

        try (DefaultAgentEngine engine = DefaultAgentEngine.builder(repo, registry)
                .idempotencyStore(idempotency)
                .tracer(tracer)
                .metrics(metrics)
                .listener(new ConsoleStepListener(false))
                .build()) {

            happyPath(engine, repo, recording);
            approvalAndResume(engine, repo);
            concurrentUsageIsAtomic(url, repo);
            driftReconciliation(engine, repo);
            scrapePrometheus(engine, repo, metrics);
        }

        summary();
        if (!FAILURES.isEmpty()) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------
    // ① 正常路径：span 树 + 分步成本
    // ------------------------------------------------------------------

    private static void happyPath(DefaultAgentEngine engine, TaskRepository repo, RecordingTracer recording) {
        section("① 一次真实运行：每步 span、分步成本、总数对账");
        recording.reset();

        CostAwareRefundAgent plan = new CostAwareRefundAgent("RefundAgent", new RefundAgent(),
                CostAwareRefundAgent.PriceList.DEMO);
        String taskId = engine.submit(plan, "u_10086", ORDER_OK);
        TaskResult result = engine.await(taskId, WAIT);
        TaskView view = engine.getTask(taskId);

        System.out.println("\n 步级时间线（从库里的 task_event 还原，不需要 OTel 收集器）");
        for (StepSpan span : view.spans()) {
            System.out.println("   " + span.describe());
        }

        CostReport report = CostReport.from(view);
        System.out.println("\n 分步成本：");
        System.out.print(indent(report.toText()));

        long tokensFromEvents = view.events().stream().mapToLong(TaskEvent::tokens).sum();
        BigDecimal costFromEvents = view.events().stream()
                .map(TaskEvent::cost).reduce(BigDecimal.ZERO, BigDecimal::add);

        check("任务终态 COMPLETED", result.status() == TaskStatus.COMPLETED);
        check("5 条事件（2 决策 + 2 工具 + 1 完成），实际 " + view.events().size(),
                view.events().size() == 5);
        check("totalTokens(" + view.totalTokens() + ") == 事件之和(" + tokensFromEvents + ")",
                view.totalTokens() == tokensFromEvents);
        check("totalCost(" + view.totalCost() + ") == 事件之和(" + costFromEvents + ")",
                view.totalCost().compareTo(costFromEvents) == 0);
        check("totalCost > 0（真的归因到钱，而不是记了 0）", view.totalCost().signum() > 0);

        Task stored = repo.findById(taskId).orElseThrow();
        check("库里的累计与视图一致（不是只在内存里对）",
                stored.getTotalTokens() == view.totalTokens()
                        && stored.getTotalCost().compareTo(view.totalCost()) == 0);

        StepSpan querySpan = spanOf(view, "tool:queryOrder");
        StepSpan refundSpan = spanOf(view, "tool:refund");
        check("queryOrder span 耗时 >= 12ms（实测 " + querySpan.durationMs() + "ms）",
                querySpan.durationMs() >= 12);
        check("refund span 耗时 >= 35ms（实测 " + refundSpan.durationMs() + "ms）",
                refundSpan.durationMs() >= 35);
        check("两步工具耗时之和 <= 总耗时（时间线自洽）",
                querySpan.durationMs() + refundSpan.durationMs() <= view.totalDurationMs());

        CostReport.Line priciest = report.mostExpensiveStep();
        check("最贵的一步不是工具调用（钱花在模型上，不在工具上），最贵="
                        + (priciest == null ? "无" : priciest.stepType() + "@step" + priciest.stepIndex()),
                priciest != null && priciest.stepType() != StepType.TOOL_CALL);

        BigDecimal attributed = report.costByTool().values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        check("按工具/类型的归因合计回总花费（¥" + attributed.toPlainString() + "）",
                attributed.compareTo(view.totalCost()) == 0);

        long chargedSteps = view.events().stream().filter(e -> e.tokens() > 0).count();
        check("3 个决策步都有计费（含最后一步生成答案），实际 " + chargedSteps, chargedSteps == 3);

        // ---- tracer 断言：埋点有没有真的发出去 ----
        check("根 span 恰好 1 个", recording.byName("agent.task").size() == 1);
        check("决策 span 3 个（每次 decide 一个，含收尾），实际 " + recording.byName("llm_decision").size(),
                recording.byName("llm_decision").size() == 3);
        List<RecordingTracer.SpanRecord> refundSpans = recording.byName("tool:refund");
        check("工具 span 1 个（tool:refund）", refundSpans.size() == 1);
        if (!refundSpans.isEmpty()) {
            RecordingTracer.SpanRecord span = refundSpans.get(0);
            check("refund span 带 attempts=1 / fromCache=false",
                    Integer.valueOf(1).equals(span.attribute("attempts"))
                            && Boolean.FALSE.equals(span.attribute("fromCache")));
        }
        RecordingTracer.SpanRecord root = recording.byName("agent.task").get(0);
        check("根 span 带 agent.cost 属性（" + root.attribute("agent.cost") + "）",
                root.attribute("agent.cost") != null);
        check("所有步 span 的父 span 都指向根 span（链路没断）",
                recording.records().stream()
                        .filter(r -> !r.name().equals("agent.task"))
                        .allMatch(r -> root.spanId().equals(r.parentSpanId())));

        expectedTokens += view.totalTokens();
        expectedCost = expectedCost.add(view.totalCost());
    }

    // ------------------------------------------------------------------
    // ② 挂起 → 批准 → 续跑：不重复计费
    // ------------------------------------------------------------------

    private static void approvalAndResume(DefaultAgentEngine engine, TaskRepository repo) {
        section("② 挂起 → 人工批准 → 续跑：重放的那一步没有重新计费");

        CostAwareRefundAgent plan = new CostAwareRefundAgent("RefundAgentApproval",
                new RefundAgent("refundRequiringApproval"), CostAwareRefundAgent.PriceList.DEMO);
        String taskId = engine.submit(plan, "u_10086", ORDER_OK);

        TaskResult suspended = engine.await(taskId, WAIT);
        TaskView before = engine.getTask(taskId);
        long tokensAtSuspend = before.totalTokens();
        long chargedAtSuspend = countChargedSteps(before);

        check("先挂起等人工（状态 " + suspended.status() + "）",
                suspended.status() == TaskStatus.SUSPENDED);
        check("挂起时已为 2 次决策付费（" + chargedAtSuspend + " 次，"
                + tokensAtSuspend + " tokens）", chargedAtSuspend == 2 && tokensAtSuspend > 0);

        engine.resume(taskId, Map.of("approved", true, "note", "大额退款已核对，同意执行"));
        TaskResult done = engine.await(taskId, WAIT);
        TaskView after = engine.getTask(taskId);

        long charged = countChargedSteps(after);
        long tokensFromEvents = after.events().stream().mapToLong(TaskEvent::tokens).sum();
        System.out.println("   挂起前 " + tokensAtSuspend + " tokens → 续跑后 "
                + after.totalTokens() + " tokens，计费步数 " + charged);

        check("续跑后 COMPLETED", done.status() == TaskStatus.COMPLETED);
        check("计费步数 3（step0/1/2），重放的第 1 步没有重新决策，实际 " + charged,
                charged == 3);
        check("totalTokens(" + after.totalTokens() + ") == 3 次决策之和(" + tokensFromEvents + ")",
                after.totalTokens() == tokensFromEvents);
        check("续跑后金额增加（收尾决策确实计费了）",
                after.totalCost().compareTo(before.totalCost()) > 0);

        expectedTokens += after.totalTokens();
        expectedCost = expectedCost.add(after.totalCost());
    }

    // ------------------------------------------------------------------
    // ③ 并发累加：原子 UPDATE
    // ------------------------------------------------------------------

    private static void concurrentUsageIsAtomic(String url, TaskRepository repo)
            throws InterruptedException, java.util.concurrent.ExecutionException {
        section("③ 8 个独立连接并发累加：原子 UPDATE，一次都不丢");

        String taskId = "cost-probe-concurrency";
        repo.save(new Task(taskId, "CostProbe", "u_sys", "usage-probe"));

        int threads = 8;
        int perThread = 50;
        long tokensPerCall = 100L;
        BigDecimal costPerCall = new BigDecimal("0.0001");

        // 先建好连接，避免把建表 DDL 也算进并发窗口
        List<TaskRepository> connections = new ArrayList<>(threads);
        for (int i = 0; i < threads; i++) {
            connections.add(DbSupport.openRepository(url));
        }

        CountDownLatch startGun = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            TaskRepository connection = connections.get(i);
            futures.add(pool.submit(() -> {
                startGun.await();
                for (int k = 0; k < perThread; k++) {
                    connection.addUsage(taskId, tokensPerCall, costPerCall);
                }
                return null;
            }));
        }
        startGun.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        pool.shutdown();

        long expectedTokenTotal = (long) threads * perThread * tokensPerCall;
        BigDecimal expectedCostTotal = costPerCall.multiply(
                BigDecimal.valueOf((long) threads * perThread));

        Task probe = repo.findById(taskId).orElseThrow();
        check("并发 " + (threads * perThread) + " 次累加后 token 精确（期望 "
                        + expectedTokenTotal + "，实际 " + probe.getTotalTokens() + "）",
                probe.getTotalTokens() == expectedTokenTotal);
        check("金额精确（期望 " + expectedCostTotal.toPlainString() + "，实际 "
                        + probe.getTotalCost().toPlainString() + "）",
                probe.getTotalCost().compareTo(expectedCostTotal) == 0);
    }

    // ------------------------------------------------------------------
    // ④ 崩溃窗口：按事件账本校正
    // ------------------------------------------------------------------

    private static void driftReconciliation(DefaultAgentEngine engine, TaskRepository repo) {
        section("④ 崩溃窗口（事件已写、汇总未加）→ 按账本校正");

        String taskId = "cost-probe-drift";
        repo.save(new Task(taskId, "CrashWindowProbe", "u_sys", "drift-probe"));

        // 现场还原：两步的事件已经落库（账本上有 500 token / ¥0.0100），
        // 但随后的 addUsage 还没执行就崩了，所以汇总列仍是 0。
        repo.appendEvent(TaskEvent.draft(taskId, 0, StepType.LLM_DECISION, null,
                Map.of("action", "tool", "toolName", "queryOrder"), true,
                120L, new BigDecimal("0.0008"), 9L));
        repo.appendEvent(TaskEvent.draft(taskId, 1, StepType.LLM_DECISION, null,
                Map.of("action", "tool", "toolName", "refund"), true,
                380L, new BigDecimal("0.0092"), 11L));

        Task before = repo.findById(taskId).orElseThrow();
        check("崩溃现场如预期：事件里有 500 token，汇总却是 0", before.getTotalTokens() == 0L);

        long delta = engine.reconcileUsage(taskId);
        Task after = repo.findById(taskId).orElseThrow();

        check("检出 500 token 的漂移（实际 " + delta + "）", delta == 500L);
        check("token 汇总被校正为账本之和（" + after.getTotalTokens() + "）",
                after.getTotalTokens() == 500L);
        check("金额汇总被校正为账本之和（" + after.getTotalCost().toPlainString() + "）",
                after.getTotalCost().compareTo(new BigDecimal("0.0100")) == 0);
        check("再次校正应无差额（幂等）", engine.reconcileUsage(taskId) == 0L);
    }

    // ------------------------------------------------------------------
    // ⑤ /actuator/prometheus
    // ------------------------------------------------------------------

    private static void scrapePrometheus(DefaultAgentEngine engine, TaskRepository repo,
                                         SimpleMetrics metrics) throws Exception {
        section("⑤ /actuator/prometheus 真实 HTTP 抓取");

        try (ActuatorEndpoint endpoint = ActuatorEndpoint.start(0, metrics,
                () -> agentJson(engine, repo))) {
            System.out.println("   抓取 " + endpoint.prometheusUrl());

            HttpResponse<String> response = httpGet(endpoint.prometheusUrl());
            String body = response.body();

            check("HTTP 200", response.statusCode() == 200);
            check("Content-Type 是 Prometheus 文本（实际 "
                            + response.headers().firstValue("content-type").orElse("-") + "）",
                    response.headers().firstValue("content-type").orElse("").startsWith("text/plain"));
            check("含 # TYPE agent_steps_total counter",
                    body.contains("# TYPE agent_steps_total counter"));
            check("含带标签的样本 agent_steps_total{step_type=\"TOOL_CALL\",success=\"true\"}",
                    body.contains("agent_steps_total{step_type=\"TOOL_CALL\",success=\"true\"}"));
            check("含 agent_cost_total / agent_tokens_total / agent_tasks_active",
                    body.contains("agent_cost_total ")
                            && body.contains("agent_tokens_total ")
                            && body.contains("agent_tasks_active "));
            check("含 summary 计时（agent_step_duration_seconds_count）",
                    body.contains("agent_step_duration_seconds_count"));
            check("没有 *_total_total 这种双后缀名字", !body.contains("_total_total"));

            double promCost = metricValue(body, "agent_cost_total");
            double promTokens = metricValue(body, "agent_tokens_total");
            System.out.println("   指标对账：agent_cost_total=" + promCost
                    + "（期望 " + expectedCost.toPlainString() + "）"
                    + "，agent_tokens_total=" + promTokens + "（期望 " + expectedTokens + "）");
            check("agent_cost_total 与引擎驱动的累计花费一致",
                    Math.abs(promCost - expectedCost.doubleValue()) < 1e-6);
            check("agent_tokens_total 与引擎驱动的累计 token 一致",
                    Math.abs(promTokens - expectedTokens) < 1e-6);
            check("agent_step_duration_seconds_count > 0（真的记了耗时）",
                    metricValue(body, "agent_step_duration_seconds_count") > 0);

            // gauge：必须能看到「抓取那一刻」正在跑的任务
            AgentPlan slow = new AgentPlan() {
                @Override
                public String name() {
                    return "SlowAgent";
                }

                @Override
                public Step decide(Context ctx) {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return Step.complete("slow done");
                }
            };
            String slowTaskId = engine.submit(slow, "u_sys", "slow");
            double active = pollMetric(endpoint.prometheusUrl(), "agent_tasks_active", v -> v >= 1, 3000);
            check("运行中 agent_tasks_active >= 1（实测 " + active + "）", active >= 1);
            engine.await(slowTaskId, WAIT);

            double idle = metricValue(httpGet(endpoint.prometheusUrl()).body(), "agent_tasks_active");
            check("全部结束后 agent_tasks_active 归零（实测 " + idle + "）", idle == 0);

            // 另一个端点：给人工控制台用的 JSON 概览
            HttpResponse<String> agent = httpGet(endpoint.agentUrl());
            check("/actuator/agent 返回 200 且是 JSON",
                    agent.statusCode() == 200
                            && agent.body().startsWith("{")
                            && agent.body().contains("suspended"));
            System.out.println("   " + agent.body());

            System.out.println("\n   —— Prometheus 文本片段 ——");
            body.lines().filter(line -> line.startsWith("agent_") || line.startsWith("# TYPE agent_"))
                    .limit(14)
                    .forEach(line -> System.out.println("   " + line));
        }
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    private static String agentJson(DefaultAgentEngine engine, TaskRepository repo) {
        long tokens = 0;
        BigDecimal cost = BigDecimal.ZERO;
        for (Task task : repo.findAll()) {
            tokens += task.getTotalTokens();
            cost = cost.add(task.getTotalCost() == null ? BigDecimal.ZERO : task.getTotalCost());
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("tasks", repo.findAll().size());
        json.put("suspended", engine.suspended().size());
        json.put("totalTokens", tokens);
        json.put("totalCost", cost);
        return Json.write(json);
    }

    private static HttpResponse<String> httpGet(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** 从 Prometheus 文本里取某个指标的值（优先无标签的样本）。 */
    private static double metricValue(String body, String name) {
        double labeled = Double.NaN;
        for (String line : body.split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int space = line.lastIndexOf(' ');
            if (space < 0) {
                continue;
            }
            String key = line.substring(0, space);
            if (key.equals(name)) {
                return parse(line.substring(space + 1));
            }
            if (key.startsWith(name + "{")) {
                labeled = parse(line.substring(space + 1));
            }
        }
        return labeled;
    }

    private static double parse(String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** 轮询直到指标满足条件（gauge 只在抓取瞬间有意义，固定 sleep 会 flaky）。 */
    private static double pollMetric(String url, String name, DoublePredicate done, long timeoutMs)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        double last = Double.NaN;
        while (System.currentTimeMillis() < deadline) {
            last = metricValue(httpGet(url).body(), name);
            if (done.test(last)) {
                return last;
            }
            Thread.sleep(10);
        }
        return last;
    }

    private static StepSpan spanOf(TaskView view, String name) {
        return view.spans().stream().filter(s -> name.equals(s.name())).findFirst()
                .orElseThrow(() -> new IllegalStateException("没有 " + name + " 的 span: " + view.spans()));
    }

    private static long countChargedSteps(TaskView view) {
        return view.events().stream().filter(e -> e.tokens() > 0).count();
    }

    private static String indent(String text) {
        return text.lines().map(line -> "   " + line + "\n").reduce("", String::concat);
    }

    private static void section(String title) {
        System.out.println("\n────────────────────────────────────────────────────────────");
        System.out.println(" " + title);
        System.out.println("────────────────────────────────────────────────────────────");
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            System.out.println("   ✓ " + name);
        } else {
            System.out.println("   ✗ " + name);
            FAILURES.add(name);
        }
    }

    private static void summary() {
        System.out.println("\n================================================================");
        if (FAILURES.isEmpty()) {
            System.out.println(" ✅ M4 验收通过");
        } else {
            System.out.println(" ❌ M4 验收失败 " + FAILURES.size() + " 项：");
            FAILURES.forEach(f -> System.out.println("    - " + f));
        }
        System.out.println("================================================================");
    }
}
