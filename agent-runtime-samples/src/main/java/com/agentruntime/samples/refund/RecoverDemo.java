package com.agentruntime.samples.refund;

import com.agentruntime.api.AgentEngine;
import com.agentruntime.api.TaskResult;
import com.agentruntime.api.TaskView;
import com.agentruntime.domain.StepType;
import com.agentruntime.domain.TaskStatus;
import com.agentruntime.engine.ConsoleStepListener;
import com.agentruntime.engine.DefaultAgentEngine;
import com.agentruntime.engine.ToolRegistry;
import com.agentruntime.exception.AgentRuntimeException;
import com.agentruntime.persistence.Task;
import com.agentruntime.persistence.TaskEvent;
import com.agentruntime.persistence.TaskRepository;
import com.agentruntime.samples.db.DbSupport;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * M2 断点续跑验收（DESIGN.md §6 M2 / §12）：
 *
 * <pre>
 * 阶段 A（crash）: 全新库提交任务 → queryOrder 完成、事件落库 → refund 工具在副作用前 halt 进程；
 * 阶段 B（recover）: 新进程打开同一个库 → 发现 RUNNING 孤儿任务 → recover 续跑 → 完成。
 * </pre>
 *
 * 运行方式（两个独立 JVM，模拟真实崩溃重启）：
 *
 * <pre>
 * java -cp "agent-runtime-core/target/classes;agent-runtime-samples/target/classes;~/.m2/.../h2-2.2.224.jar" \
 *      com.agentruntime.samples.refund.RecoverDemo crash
 * java -cp 同上 com.agentruntime.samples.refund.RecoverDemo recover
 * </pre>
 *
 * 断言（对应 §12「中途 kill 后 recover」）：
 * ① 终态 COMPLETED；② queryOrder 只执行 1 次（已完成步不重复）；③ refund 只执行 1 次；
 * ④ 事件链完整（决策/工具/完成）；⑤ currentStep == 2。
 */
public final class RecoverDemo {

    private static final String ORDER_OK = "ORD-20261114-001";
    private static final List<String> FAILURES = new ArrayList<>();

    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0] : "usage";
        switch (mode) {
            case "crash" -> crashPhase();
            case "recover" -> recoverPhase();
            default -> {
                System.err.println("用法: RecoverDemo <crash|recover>");
                System.exit(2);
            }
        }
    }

    // ------------------------------------------------------------------
    // 阶段 A：跑一半，halt
    // ------------------------------------------------------------------

    private static void crashPhase() {
        System.out.println("================================================================");
        System.out.println(" M2 阶段 A：提交任务并在 refund 副作用前模拟进程崩溃");
        System.out.println("================================================================\n");

        DbSupport.resetDatabase();
        TaskRepository repository = DbSupport.openRepository();

        ToolRegistry registry = new ToolRegistry()
                .register(new OrderQueryService())
                .register(new RefundService(RefundService.Fault.HALT_BEFORE_SIDE_EFFECT));

        try (DefaultAgentEngine engine = DefaultAgentEngine.builder(repository, registry)
                .listener(new ConsoleStepListener(true))
                .build()) {
            engine.registerPlan(new RefundAgent());
            String taskId = engine.submit(new RefundAgent(), "u_10086", ORDER_OK);
            System.out.printf("%n[phase-A] taskId=%s 已提交（异步执行），等待进程被 halt...%n", taskId);

            try {
                // 正常情况下 worker 线程会在 refund 工具里 halt 整个进程；
                // 若 15 秒还没崩，说明演示出了问题。
                engine.await(taskId, Duration.ofSeconds(15));
                System.err.println("[phase-A] 任务居然正常完成了——halt 没有触发，演示失败");
                System.exit(1);
            } catch (AgentRuntimeException e) {
                System.err.println("[phase-A] 任务未在预期内崩溃: " + e.getMessage());
                System.exit(1);
            }
        }
    }

    // ------------------------------------------------------------------
    // 阶段 B：新进程，恢复
    // ------------------------------------------------------------------

    private static void recoverPhase() {
        System.out.println("================================================================");
        System.out.println(" M2 阶段 B：新进程打开同一数据库，恢复孤儿任务");
        System.out.println("================================================================\n");

        DbSupport.clearStaleLocks();
        TaskRepository repository = DbSupport.openRepository();

        // 先看崩溃现场：库里留下了什么
        List<Task> orphans = repository.findAll().stream()
                .filter(t -> t.getStatus() == TaskStatus.RUNNING || t.getStatus() == TaskStatus.RECOVERING)
                .toList();
        System.out.printf("[phase-B] 发现孤儿任务 %d 个%n", orphans.size());
        for (Task orphan : orphans) {
            List<TaskEvent> events = repository.findEvents(orphan.getTaskId());
            System.out.printf("[phase-B] taskId=%s status=%s currentStep=%d 已落库事件 %d 条%n",
                    orphan.getTaskId(), orphan.getStatus(), orphan.getCurrentStep(), events.size());
            for (TaskEvent e : events) {
                System.out.printf("  #%d step=%d %-12s %-10s success=%s%n",
                        e.id(), e.stepIndex(), e.stepType(),
                        e.toolName() == null ? "-" : e.toolName(), e.success());
            }
        }
        System.out.println();

        RefundService refundService = new RefundService(RefundService.Fault.NONE);
        ToolRegistry registry = new ToolRegistry()
                .register(new OrderQueryService())
                .register(refundService);

        try (DefaultAgentEngine engine = DefaultAgentEngine.builder(repository, registry)
                .listener(new ConsoleStepListener(true))
                .build()) {
            engine.registerPlan(new RefundAgent());

            // M10：安全默认下，心跳还新鲜的任务<b>不会</b>被接管——哪怕它其实就是崩溃现场。
            // 这一行就是为了把这件事演出来：0 不是 bug，是护栏在起作用。
            int safeDefault = engine.recoverAllInterrupted().size();
            System.out.printf("%n[phase-B] 安全默认（等租约过期）接管了 %d 个任务 —— 崩溃刚发生，"
                    + "心跳还是新鲜的，所以按规矩不能抢%n", safeDefault);
            check("安全默认不抢新鲜租约", safeDefault == 0);

            // 单实例「崩了马上重启」的场景：新进程能确定老进程已死（编排系统告诉它的），
            // 所以这里可以合法地 force，不必白等一个 TTL。
            System.out.printf("[phase-B] 单实例重启：force 接管本机遗留租约%n");
            List<String> recovered = engine.recoverAllInterrupted(true);
            System.out.printf("[phase-B] 已认领并续跑 %d 个任务%n%n", recovered.size());
            check("崩溃现场存在可恢复任务", !recovered.isEmpty());

            for (String taskId : recovered) {
                TaskResult result = engine.await(taskId, Duration.ofSeconds(15));
                TaskView view = engine.getTask(taskId);
                dumpEvents(view);

                check("任务终态为 COMPLETED", result.status() == TaskStatus.COMPLETED);
                check("结论包含回执号", String.valueOf(result.result()).contains("回执号"));
                check("currentStep == 2（queryOrder + refund）", view.currentStep() == 2);

                long queryOrderCalls = countToolCalls(view, "queryOrder");
                long refundCalls = countToolCalls(view, "refund");
                long completions = view.events().stream()
                        .filter(e -> e.stepType() == StepType.COMPLETION).count();
                long decisions = view.events().stream()
                        .filter(e -> e.stepType() == StepType.LLM_DECISION).count();

                check("queryOrder 只执行 1 次（实际 " + queryOrderCalls + "）——已完成步不重复", queryOrderCalls == 1);
                check("refund 只执行 1 次（实际 " + refundCalls + "）", refundCalls == 1);
                check("LLM_DECISION 只有 2 条（实际 " + decisions + "）——断点决策不重算",
                        decisions == 2);
                check("本进程真实副作用只发生 1 次（实际 " + refundService.refundExecutions() + "）",
                        refundService.refundExecutions() == 1);
                check("事件链包含 COMPLETION（实际 " + completions + "）", completions == 1);
            }
        }

        System.out.println();
        if (FAILURES.isEmpty()) {
            System.out.println("✅ M2 验收通过：崩溃后 recover 从断点续跑，已完成步未重复执行，副作用只发生一次。");
        } else {
            System.out.println("❌ M2 验收失败：");
            FAILURES.forEach(f -> System.out.println("   - " + f));
            System.exit(1);
        }
    }

    private static long countToolCalls(TaskView view, String toolName) {
        return view.events().stream()
                .filter(e -> e.stepType() == StepType.TOOL_CALL)
                .filter(e -> toolName.equals(e.toolName()))
                .count();
    }

    private static void dumpEvents(TaskView view) {
        System.out.println("  ── 恢复后完整事件链路 ──");
        for (TaskEvent e : view.events()) {
            System.out.printf("  #%d step=%d %-12s %-10s success=%s%n",
                    e.id(), e.stepIndex(), e.stepType(),
                    e.toolName() == null ? "-" : e.toolName(), e.success());
        }
        System.out.println();
    }

    private static void check(String name, boolean ok) {
        System.out.printf("   %s %s%n", ok ? "✓" : "✗", name);
        if (!ok) {
            FAILURES.add(name);
        }
    }

    private RecoverDemo() {
    }
}
