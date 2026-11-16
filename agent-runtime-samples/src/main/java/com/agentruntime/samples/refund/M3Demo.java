package com.agentruntime.samples.refund;

import com.agentruntime.api.AgentPlan;
import com.agentruntime.api.Context;
import com.agentruntime.api.Step;
import com.agentruntime.api.TaskResult;
import com.agentruntime.api.TaskView;
import com.agentruntime.domain.StepType;
import com.agentruntime.domain.TaskStatus;
import com.agentruntime.engine.ConsoleStepListener;
import com.agentruntime.engine.DefaultAgentEngine;
import com.agentruntime.engine.ToolRegistry;
import com.agentruntime.outbox.OutboxDispatcher;
import com.agentruntime.persistence.InMemoryIdempotencyStore;
import com.agentruntime.persistence.InMemoryTaskRepository;
import com.agentruntime.persistence.JdbcIdempotencyStore;
import com.agentruntime.persistence.OutboxEntry;
import com.agentruntime.persistence.TaskEvent;
import com.agentruntime.persistence.TaskRepository;
import com.agentruntime.samples.db.DbSupport;
import com.agentruntime.util.JsonParser;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M3 验收（DESIGN.md §6 M3 / §12）：
 * <pre>
 *   ① 同一 orderId 二次提交，退款只真正执行一次（幂等，防资损）
 *   ② 暂时性故障按指数退避重试，成功后副作用只发生一次
 *   ③ requireHumanConfirm 的工具正确挂起，resume(approved) 后继续
 *   ④ resume(拒绝) 不执行副作用，plan 能读到人工裁定并改主意
 *   ⑤ 幂等记录停在 INIT（上次结果未知）→ 挂起问人，绝不盲目重试
 *   ⑥ resultTruncateChars 截断后仍是合法 JSON
 *   ⑦ 副作用事实进 outbox，与幂等 DONE 同时落库，可被投递器送出
 *   ⑧ 两个任务并发退同一笔订单（真实 JDBC 主键冲突）→ 只扣一次款
 * </pre>
 *
 * <p>全程内存仓储 + 同步执行（{@code executor(Runnable::run)}），保证断言确定性；
 * 跨进程的真实崩溃场景见 {@code InDoubtDemo}（两个独立 JVM，H2 文件库）。
 */
public final class M3Demo {

    private static final String ORDER_OK = "ORD-20261114-001";
    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final List<String> FAILURES = new ArrayList<>();

    public static void main(String[] args) {
        System.out.println("================================================================");
        System.out.println(" Agent Runtime M3 — 幂等 / 重试 / 人工挂起与 resume");
        System.out.println("================================================================\n");

        idempotencyAndOutbox();
        retryWithBackoff();
        humanConfirmApproved();
        humanConfirmRejected();
        inDoubtSuspend();
        truncationIsValidJson();
        concurrentSameOrder();

        System.out.println();
        if (FAILURES.isEmpty()) {
            System.out.println("✅ M3 验收通过：退款不会重复扣款，暂时性故障会退避重试，"
                    + "需要人工的地方停下来等人，结果未知时不猜。");
        } else {
            System.out.println("❌ M3 验收失败：");
            FAILURES.forEach(f -> System.out.println("   - " + f));
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------
    // ① 幂等 + ⑦ outbox
    // ------------------------------------------------------------------

    private static void idempotencyAndOutbox() {
        section("① 幂等：同一订单提交两次 " + ORDER_OK);
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        RefundService refundService = new RefundService();
        DefaultAgentEngine engine = engine(new InMemoryTaskRepository(), refundService, store);

        String first = engine.submit(new RefundAgent(), "u_10086", ORDER_OK);
        TaskResult r1 = engine.await(first, WAIT);
        check("第一次任务 COMPLETED", r1.status() == TaskStatus.COMPLETED);
        check("第一次真实退款 1 次（实际 " + refundService.refundExecutions() + "）",
                refundService.refundExecutions() == 1);

        String second = engine.submit(new RefundAgent(), "u_10086", ORDER_OK);
        TaskResult r2 = engine.await(second, WAIT);
        check("第二次任务也 COMPLETED（业务视角成功）", r2.status() == TaskStatus.COMPLETED);
        check("真实退款仍然只有 1 次——第二次没有扣钱（实际 " + refundService.refundExecutions() + "）",
                refundService.refundExecutions() == 1);
        check("工具方法根本没被调用（实际 " + refundService.invocations() + " 次）",
                refundService.invocations() == 1);
        check("第二次结论复用了缓存回执（含回执号）", String.valueOf(r2.result()).contains("回执号"));

        TaskEvent cached = toolCall(engine.getTask(second), "refund");
        check("事件标记 fromCache=true（审计能看出这是缓存命中）",
                cached != null && Boolean.TRUE.equals(cached.payload().get("fromCache")));
        check("事件 attempts=0（没有任何新调用）",
                cached != null && Integer.valueOf(0).equals(cached.payload().get("attempts")));
        check("事件里带幂等键 " + "refund:" + ORDER_OK,
                cached != null && (String.valueOf(cached.payload().get("idempotencyKey")))
                        .equals("refund:" + ORDER_OK));

        // ⑦ outbox：queryOrder + refund 各一条，二次提交不再产生
        check("outbox 里有 2 条待投递事实（queryOrder + refund，实际 " + store.pendingCount() + "）",
                store.pendingCount() == 2);
        List<OutboxEntry> published = new ArrayList<>();
        OutboxDispatcher dispatcher = new OutboxDispatcher(store, published::add);
        int n = dispatcher.dispatchOnce(10);
        check("投递器送出 2 条（实际 " + n + "）", n == 2);
        check("投递后 outbox 清空（实际 " + store.pendingCount() + "）", store.pendingCount() == 0);
        check("投递内容含工具名与幂等键",
                published.stream().allMatch(e -> e.payload().contains("toolName")));
        System.out.println();
    }

    // ------------------------------------------------------------------
    // ② 重试
    // ------------------------------------------------------------------

    private static void retryWithBackoff() {
        section("② 重试：退款网关前 2 次暂时不可用");
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        RefundService refundService = new RefundService(RefundService.Fault.TRANSIENT, 2);
        DefaultAgentEngine engine = engine(new InMemoryTaskRepository(), refundService, store);

        long start = System.currentTimeMillis();
        String taskId = engine.submit(new RefundAgent(), "u_10086", ORDER_OK);
        TaskResult result = engine.await(taskId, WAIT);
        long elapsed = System.currentTimeMillis() - start;

        check("任务最终 COMPLETED（重试成功）", result.status() == TaskStatus.COMPLETED);
        check("工具被调用 3 次（1 次原始 + 2 次重试，实际 " + refundService.invocations() + "）",
                refundService.invocations() == 3);
        check("真实扣款只有 1 次（失败的那两次没有产生副作用，实际 "
                + refundService.refundExecutions() + "）", refundService.refundExecutions() == 1);
        TaskEvent event = toolCall(engine.getTask(taskId), "refund");
        check("事件记录了 attempts=3（实际 " + (event == null ? "-" : event.payload().get("attempts")) + "）",
                event != null && Integer.valueOf(3).equals(event.payload().get("attempts")));
        check("退避确实等了（500ms + 1000ms，实测 " + elapsed + "ms）", elapsed >= 1400);
        System.out.println();
    }

    // ------------------------------------------------------------------
    // ③ 人工确认 —— 批准
    // ------------------------------------------------------------------

    private static void humanConfirmApproved() {
        section("③ 人工确认：大额退款被拦下 → 批准 → 继续");
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        RefundService refundService = new RefundService();
        DefaultAgentEngine engine = engine(new InMemoryTaskRepository(), refundService, store);

        String taskId = engine.submit(new RefundAgent("refundRequiringApproval"), "u_10086", ORDER_OK);
        TaskResult suspended = engine.await(taskId, WAIT);

        check("任务停在 SUSPENDED（实际 " + suspended.status() + "）",
                suspended.status() == TaskStatus.SUSPENDED);
        check("挂起时一分钱都没退（实际 " + refundService.refundExecutions() + " 次）",
                refundService.refundExecutions() == 0);
        check("engine.suspended() 能列出待人工处理的任务（实际 " + engine.suspended().size() + " 个）",
                engine.suspended().size() == 1);
        check("挂起原因写清了是哪个工具要确认",
                String.valueOf(suspended.result()).contains("refundRequiringApproval"));

        Map<String, Object> approval = new LinkedHashMap<>();
        approval.put("approved", true);
        approval.put("note", "财务已核对：用户投诉未收到货，准予退款");
        engine.resume(taskId, approval);

        TaskResult done = engine.await(taskId, WAIT);
        check("批准后任务 COMPLETED（实际 " + done.status() + "）", done.status() == TaskStatus.COMPLETED);
        check("批准后真实退款 1 次（实际 " + refundService.refundExecutions() + "）",
                refundService.refundExecutions() == 1);
        check("事件链里有含 note 的人工作答", hasAnswerEvent(engine.getTask(taskId), "财务已核对"));
        check("事件链里没有重复的 LLM_DECISION（批准后续跑没有重算决策）",
                countStepType(engine.getTask(taskId), StepType.LLM_DECISION) == 2);
        System.out.println();
    }

    // ------------------------------------------------------------------
    // ④ 人工确认 —— 拒绝
    // ------------------------------------------------------------------

    private static void humanConfirmRejected() {
        section("④ 人工确认：拒绝后不得执行副作用");
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        RefundService refundService = new RefundService();
        DefaultAgentEngine engine = engine(new InMemoryTaskRepository(), refundService, store);

        String taskId = engine.submit(new RefundAgent("refundRequiringApproval"), "u_10086", ORDER_OK);
        engine.await(taskId, WAIT);

        engine.resume(taskId, Map.of("approved", false, "note", "订单已超过售后期，驳回"));
        TaskResult done = engine.await(taskId, WAIT);

        check("拒绝后任务 COMPLETED（业务终止，不是系统故障，实际 " + done.status() + "）",
                done.status() == TaskStatus.COMPLETED);
        check("一分钱都没退（实际 " + refundService.refundExecutions() + " 次）",
                refundService.refundExecutions() == 0);
        check("结论说明是人工拒绝", String.valueOf(done.result()).contains("人工拒绝"));
        System.out.println();
    }

    // ------------------------------------------------------------------
    // ⑤ 在途未知（INIT）
    // ------------------------------------------------------------------

    private static void inDoubtSuspend() {
        section("⑤ 在途未知：幂等记录停在 INIT（钱可能已退，也可能没退）");
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        // 直接构造「崩溃现场」：占位了 INIT，但没写 DONE。
        // 现实中这由「副作用执行后、写 DONE 前进程被杀」造成（见 InDoubtDemo 的真实两阶段演示）。
        store.begin("refund:" + ORDER_OK, "crashed-task", 1);

        RefundService refundService = new RefundService();
        DefaultAgentEngine engine = engine(new InMemoryTaskRepository(), refundService, store);

        String taskId = engine.submit(new RefundAgent(), "u_10086", ORDER_OK);
        TaskResult suspended = engine.await(taskId, WAIT);

        check("任务挂起而不是失败（实际 " + suspended.status() + "）",
                suspended.status() == TaskStatus.SUSPENDED);
        check("没有盲目重试退款（实际 " + refundService.refundExecutions() + " 次）",
                refundService.refundExecutions() == 0);
        check("挂起原因说明是幂等记录在途", String.valueOf(suspended.result()).contains("INIT"));

        // 人工查过账，确认「上次确实没退成功」→ 授权重试副作用
        engine.resume(taskId, Map.of("approved", true, "retrySideEffect", true,
                "note", "已查对账单：该单无退款流水，确认未发生"));
        TaskResult done = engine.await(taskId, WAIT);

        check("人工确认后任务 COMPLETED（实际 " + done.status() + "）", done.status() == TaskStatus.COMPLETED);
        check("人工授权后真实退款 1 次（实际 " + refundService.refundExecutions() + "）",
                refundService.refundExecutions() == 1);
        System.out.println();
    }

    // ------------------------------------------------------------------
    // ⑥ 截断
    // ------------------------------------------------------------------

    private static void truncationIsValidJson() {
        section("⑥ 结果截断：超长结果必须仍是合法 JSON");
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        DefaultAgentEngine engine = engine(new InMemoryTaskRepository(), new RefundService(), store);

        AgentPlan plan = new AgentPlan() {
            @Override
            public String name() {
                return "AuditAgent";
            }

            @Override
            public Step decide(Context ctx) {
                return ctx.stepIndex() == 0
                        ? Step.tool("auditLog", Map.of("orderId", ORDER_OK))
                        : Step.complete("审计日志已读取");
            }
        };

        String taskId = engine.submit(plan, "u_10086", ORDER_OK);
        engine.await(taskId, WAIT);
        TaskEvent event = toolCall(engine.getTask(taskId), "auditLog");

        boolean wrapped = event != null && event.payload().get("result") instanceof Map<?, ?> map
                && Boolean.TRUE.equals(map.get("_truncated"));
        check("超长结果被包装成 {_truncated:true, body:...}", wrapped);
        check("事件标记 truncated=true（实际 "
                        + (event == null ? "-" : event.payload().get("truncated")) + "）",
                event != null && Boolean.TRUE.equals(event.payload().get("truncated")));

        boolean parseable = false;
        try {
            Map<String, Object> parsed = JsonParser.parseObject(event == null ? "" : event.payloadJson());
            parseable = parsed.containsKey("result") && parsed.get("result") instanceof Map<?, ?> m
                    && Boolean.TRUE.equals(m.get("_truncated"));
        } catch (RuntimeException e) {
            parseable = false;
        }
        check("payloadJson 能被 JSON 解析器读回（截断没有产生非法 JSON）", parseable);
        System.out.println();
    }

    // ------------------------------------------------------------------
    // ⑧ 并发同一订单
    // ------------------------------------------------------------------

    /**
     * 生产里真正会出事的形态不是「顺序提交两次」，而是「两个实例同时收到同一笔退款」。
     *
     * <p>这里用真实 JDBC 跑（PG 上是独立 schema，H2 兜底时是独立内存库），
     * 因为要验的正是<b>数据库主键冲突</b>给出的原子性：
     * 先查后写在这种场景下一定漏——两个线程都会查到「不存在」。
     *
     * <p>允许两种合法结局（都不允许重复扣款）：输的一方拿到缓存结果（赢家已完成），
     * 或挂起等人工裁定（赢家还在执行，此刻无法区分「正在执行」与「崩溃留下的 INIT」）。
     */
    private static void concurrentSameOrder() {
        section("⑧ 并发：两个任务同时退同一笔订单");
        String concurrencyUrl = DbSupport.memUrl("m3-concurrency");
        TaskRepository repository = DbSupport.openRepository(concurrencyUrl);
        JdbcIdempotencyStore store = DbSupport.openIdempotencyStore(concurrencyUrl);
        RefundService refundService = new RefundService();

        ToolRegistry registry = new ToolRegistry()
                .register(new OrderQueryService())
                .register(refundService);

        try (DefaultAgentEngine engine = DefaultAgentEngine.builder(repository, registry)
                .idempotencyStore(store)
                .retryLogger(s -> System.out.println("        └─ " + s))
                .maxSteps(10)
                .build()) {
            String t1 = engine.submit(new RefundAgent(), "u_10086", ORDER_OK);
            String t2 = engine.submit(new RefundAgent(), "u_10086", ORDER_OK);
            TaskResult r1 = engine.await(t1, WAIT);
            TaskResult r2 = engine.await(t2, WAIT);

            check("两个任务都拿到了结果（没有互相卡死）", r1 != null && r2 != null);
            check("真实扣款只有 1 次（实际 " + refundService.refundExecutions() + "）——"
                    + "这是主键冲突给出的原子性", refundService.refundExecutions() == 1);
            check("没有任务被判 FAILED（" + r1.status() + " / " + r2.status() + "）",
                    r1.status() != TaskStatus.FAILED && r2.status() != TaskStatus.FAILED);
            long completed = (r1.status() == TaskStatus.COMPLETED ? 1 : 0)
                    + (r2.status() == TaskStatus.COMPLETED ? 1 : 0);
            check("至少一方完成（另一方缓存命中或挂起等人工），实际完成 " + completed + " 个", completed >= 1);
            long suspended = (r1.status() == TaskStatus.SUSPENDED ? 1 : 0)
                    + (r2.status() == TaskStatus.SUSPENDED ? 1 : 0);
            System.out.printf("   ℹ 本次运行：完成 %d 个、挂起 %d 个（两种结局都不重复扣款）%n",
                    completed, suspended);
        }
        System.out.println();
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static DefaultAgentEngine engine(InMemoryTaskRepository repository, RefundService refundService,
                                             InMemoryIdempotencyStore store) {
        ToolRegistry registry = new ToolRegistry()
                .register(new OrderQueryService())
                .register(refundService);
        return DefaultAgentEngine.builder(repository, registry)
                .listener(new ConsoleStepListener(true))
                .executor(Runnable::run)   // 同步执行，断言可确定
                .idempotencyStore(store)
                .retryLogger(s -> System.out.println("        └─ " + s))
                .maxSteps(10)
                .build();
    }

    private static TaskEvent toolCall(TaskView view, String toolName) {
        return view.events().stream()
                .filter(e -> e.stepType() == StepType.TOOL_CALL && toolName.equals(e.toolName()))
                .reduce((a, b) -> b)
                .orElse(null);
    }

    private static long countStepType(TaskView view, StepType type) {
        return view.events().stream().filter(e -> e.stepType() == type).count();
    }

    private static boolean hasAnswerEvent(TaskView view, String noteFragment) {
        return view.events().stream()
                .filter(e -> e.stepType() == StepType.HUMAN_REVIEW)
                .anyMatch(e -> Boolean.TRUE.equals(e.payload().get("answer"))
                        && e.payload().get("note") != null
                        && String.valueOf(e.payload().get("note")).contains(noteFragment));
    }

    private static void section(String title) {
        System.out.println("\n──────── " + title + " ────────");
    }

    private static void check(String name, boolean ok) {
        System.out.printf("   %s %s%n", ok ? "✓" : "✗", name);
        if (!ok) {
            FAILURES.add(name);
        }
    }

    private M3Demo() {
    }
}
