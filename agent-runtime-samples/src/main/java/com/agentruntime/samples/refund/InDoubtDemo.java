package com.agentruntime.samples.refund;

import com.agentruntime.api.TaskResult;
import com.agentruntime.api.TaskView;
import com.agentruntime.domain.StepType;
import com.agentruntime.domain.TaskStatus;
import com.agentruntime.engine.ConsoleStepListener;
import com.agentruntime.engine.DefaultAgentEngine;
import com.agentruntime.engine.ToolRegistry;
import com.agentruntime.exception.AgentRuntimeException;
import com.agentruntime.persistence.IdempotencyRecord;
import com.agentruntime.persistence.JdbcIdempotencyStore;
import com.agentruntime.persistence.Task;
import com.agentruntime.persistence.TaskEvent;
import com.agentruntime.persistence.TaskRepository;
import com.agentruntime.samples.db.DbSupport;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * M3 最难也最值钱的一条验收：<b>副作用已发生、幂等记录没写完，进程猝死</b>。
 *
 * <pre>
 * 阶段 A（crash）  : 提交任务 → queryOrder 成功 → 退款接口扣款成功 → 还没写 DONE 就 halt(17)
 * 阶段 B（recover）: 新进程打开同一个库 → 续跑 → 幂等记录是 INIT → 不猜、不重试，挂起等人
 * 阶段 B（resume） : 人查了账，确认「钱已经退了」，把流水号交给系统 → 任务完成，全程只扣一次款
 * </pre>
 *
 * <p>这是 at-least-once 与 at-most-once 的分界线所在：
 * M2 的崩溃（副作用之前）可以自动重试；M3 的崩溃（副作用之后）<b>必须</b>停下来问人。
 * 两者的数据库现场一模一样（都是 {@code INIT}），所以框架无从区分——只有人的账本能区分。
 *
 * <pre>
 * 运行（两个独立 JVM）：
 *   java -cp "...;h2.jar" com.agentruntime.samples.refund.InDoubtDemo crash
 *   java -cp 同上        com.agentruntime.samples.refund.InDoubtDemo recover
 * </pre>
 */
public final class InDoubtDemo {

    private static final String ORDER_OK = "ORD-20261114-001";
    private static final String IDEM_KEY = "refund:" + ORDER_OK;
    private static final List<String> FAILURES = new ArrayList<>();

    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0] : "usage";
        switch (mode) {
            case "crash" -> crashPhase();
            case "recover" -> recoverPhase();
            default -> {
                System.err.println("用法: InDoubtDemo <crash|recover>");
                System.exit(2);
            }
        }
    }

    // ------------------------------------------------------------------
    // 阶段 A：副作用之后 halt
    // ------------------------------------------------------------------

    private static void crashPhase() {
        System.out.println("================================================================");
        System.out.println(" M3 阶段 A：退款扣款成功后、写幂等 DONE 之前进程猝死");
        System.out.println("================================================================\n");

        DbSupport.resetDatabase();
        TaskRepository repository = DbSupport.openRepository();
        JdbcIdempotencyStore store = DbSupport.openIdempotencyStore();

        ToolRegistry registry = new ToolRegistry()
                .register(new OrderQueryService())
                .register(new RefundService(RefundService.Fault.HALT_AFTER_SIDE_EFFECT));

        try (DefaultAgentEngine engine = DefaultAgentEngine.builder(repository, registry)
                .listener(new ConsoleStepListener(true))
                .idempotencyStore(store)
                .build()) {
            engine.registerPlan(new RefundAgent());
            String taskId = engine.submit(new RefundAgent(), "u_10086", ORDER_OK);
            System.out.printf("%n[phase-A] taskId=%s 已提交，等待进程在扣款后被 halt...%n", taskId);
            try {
                engine.await(taskId, Duration.ofSeconds(20));
                System.err.println("[phase-A] 任务居然正常完成了——halt 没触发，演示失败");
                System.exit(1);
            } catch (AgentRuntimeException e) {
                System.err.println("[phase-A] 未按预期崩溃: " + e.getMessage());
                System.exit(1);
            }
        }
    }

    // ------------------------------------------------------------------
    // 阶段 B：恢复 → 挂起 → 人工作答
    // ------------------------------------------------------------------

    private static void recoverPhase() {
        System.out.println("================================================================");
        System.out.println(" M3 阶段 B：新进程续跑——结果未知，必须问人");
        System.out.println("================================================================\n");

        DbSupport.clearStaleLocks();
        TaskRepository repository = DbSupport.openRepository();
        JdbcIdempotencyStore store = DbSupport.openIdempotencyStore();

        // ---------- 崩溃现场 ----------
        List<Task> orphans = repository.findAll().stream()
                .filter(t -> t.getStatus() == TaskStatus.RUNNING || t.getStatus() == TaskStatus.RECOVERING)
                .toList();
        System.out.printf("[phase-B] 孤儿任务 %d 个%n", orphans.size());
        check("崩溃现场存在可恢复任务", !orphans.isEmpty());

        Optional<IdempotencyRecord> crashed = store.find(IDEM_KEY);
        System.out.printf("[phase-B] 幂等记录 %s → status=%s，result=%s%n", IDEM_KEY,
                crashed.map(r -> r.status().name()).orElse("不存在"),
                crashed.map(IdempotencyRecord::resultPayload).orElse("-"));
        check("崩溃后幂等记录停在 INIT（副作用是否发生未知）",
                crashed.isPresent() && crashed.get().inDoubt());
        check("崩溃前 queryOrder 的 outbox 事实已落库（" + store.pendingCount() + " 条待投递）",
                store.pendingCount() == 1);

        long refundBefore = countToolCalls(repository, "refund");
        check("崩溃时 task_event 里没有任何 refund 事件（扣款发生了但没记下来）", refundBefore == 0);

        RefundService refundService = new RefundService(RefundService.Fault.NONE);
        ToolRegistry registry = new ToolRegistry()
                .register(new OrderQueryService())
                .register(refundService);

        try (DefaultAgentEngine engine = DefaultAgentEngine.builder(repository, registry)
                .listener(new ConsoleStepListener(true))
                .idempotencyStore(store)
                .build()) {
            engine.registerPlan(new RefundAgent());

            // M10：安全默认下心跳还新鲜，批量恢复不能抢（哪怕它就是崩溃现场）。
            int safeDefault = engine.recoverAllInterrupted().size();
            System.out.printf("%n[phase-B] 安全默认（等租约过期）接管了 %d 个任务 —— "
                    + "崩溃刚发生、心跳新鲜，按规矩不能抢%n", safeDefault);
            check("安全默认不抢新鲜租约", safeDefault == 0);

            // 单实例「崩了马上重启」：新进程能确定老进程已死，故合法 force
            System.out.printf("[phase-B] 单实例重启：force 接管本机遗留租约%n");
            List<String> recovered = engine.recoverAllInterrupted(true);
            System.out.printf("[phase-B] 认领并续跑 %d 个任务%n%n", recovered.size());
            check("续跑了 1 个任务", recovered.size() == 1);
            if (recovered.isEmpty()) {
                finish();
                return;
            }
            String taskId = recovered.get(0);
            TaskResult afterRecover = engine.await(taskId, Duration.ofSeconds(20));

            // ---------- 核心断言：不猜 ----------
            check("恢复后任务是 SUSPENDED 而不是 COMPLETED/FAILED（实际 " + afterRecover.status() + "）",
                    afterRecover.status() == TaskStatus.SUSPENDED);
            check("恢复过程中没有第二次扣款（实际 " + refundService.refundExecutions() + " 次）",
                    refundService.refundExecutions() == 0);
            check("挂起原因说明了幂等记录在途", String.valueOf(afterRecover.result()).contains("INIT"));

            // ---------- 人工作答：查账确认钱已退，给回流水号 ----------
            Map<String, Object> ledger = new LinkedHashMap<>();
            ledger.put("receiptId", "RFD-FROM-LEDGER-001");
            ledger.put("orderId", ORDER_OK);
            ledger.put("amount", new BigDecimal("128.00"));
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("approved", true);
            answer.put("note", "对账单显示该单已于 15:52 退款成功，流水 RFD-FROM-LEDGER-001");
            answer.put("result", ledger);

            engine.resume(taskId, answer);
            TaskResult done = engine.await(taskId, Duration.ofSeconds(20));
            TaskView view = engine.getTask(taskId);
            dumpEvents(view);

            check("人工作答后任务 COMPLETED（实际 " + done.status() + "）",
                    done.status() == TaskStatus.COMPLETED);
            check("仍未发生第二次扣款（实际 " + refundService.refundExecutions() + " 次）",
                    refundService.refundExecutions() == 0);
            check("最终结论采用人工提供的流水号（" + done.result() + "）",
                    String.valueOf(done.result()).contains("RFD-FROM-LEDGER-001"));

            // ---------- 落库状态 ----------
            Optional<IdempotencyRecord> resolved = store.find(IDEM_KEY);
            check("幂等记录已从 INIT 迁移为 DONE（实际 "
                            + resolved.map(r -> r.status().name()).orElse("-") + "）",
                    resolved.isPresent() && resolved.get().done());
            check("DONE 的 result_payload 是人工给的结果",
                    resolved.map(IdempotencyRecord::resultPayload).orElse("").contains("RFD-FROM-LEDGER-001"));
            check("outbox 新增了这条已发生事实（共 " + store.pendingCount() + " 条待投递）",
                    store.pendingCount() == 2);
        }
        finish();
    }

    private static long countToolCalls(TaskRepository repository, String toolName) {
        return repository.findAll().stream()
                .flatMap(t -> repository.findEvents(t.getTaskId()).stream())
                .filter(e -> e.stepType() == StepType.TOOL_CALL && toolName.equals(e.toolName()))
                .count();
    }

    private static void dumpEvents(TaskView view) {
        System.out.println("  ── 恢复后完整事件链路 ──");
        for (TaskEvent e : view.events()) {
            System.out.printf("  #%d step=%d %-12s %-24s success=%s%n",
                    e.id(), e.stepIndex(), e.stepType(),
                    e.toolName() == null ? "-" : e.toolName(), e.success());
        }
        System.out.println();
    }

    private static void finish() {
        System.out.println();
        if (FAILURES.isEmpty()) {
            System.out.println("✅ M3 在途未知验收通过：扣款成功但记录未落盘的崩溃，"
                    + "恢复后没有盲目重试，而是挂起等人；人工给出账本结果后完成，全程只扣一次款。");
        } else {
            System.out.println("❌ M3 在途未知验收失败：");
            FAILURES.forEach(f -> System.out.println("   - " + f));
            System.exit(1);
        }
    }

    private static void check(String name, boolean ok) {
        System.out.printf("   %s %s%n", ok ? "✓" : "✗", name);
        if (!ok) {
            FAILURES.add(name);
        }
    }

    private InDoubtDemo() {
    }
}
