package com.agentruntime.samples.refund;

import com.agentruntime.api.AgentEngine;
import com.agentruntime.api.TaskResult;
import com.agentruntime.api.TaskView;
import com.agentruntime.domain.StepType;
import com.agentruntime.domain.TaskStatus;
import com.agentruntime.engine.ConsoleStepListener;
import com.agentruntime.engine.DefaultAgentEngine;
import com.agentruntime.engine.ToolRegistry;
import com.agentruntime.persistence.InMemoryTaskRepository;
import com.agentruntime.persistence.TaskEvent;
import com.agentruntime.persistence.TaskRepository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * M1 最小可跑闭环（DESIGN.md §6 M1 验收）：
 * 决策 → 调工具 → 完成，内存仓库、同步执行、每步打日志。
 *
 * <p>三个场景分别覆盖：正常退款、工具抛业务异常（能否定位到哪一步挂了）、业务判定为不可退。
 */
public final class RefundAgentDemo {

    private static final String ORDER_OK = "ORD-20261114-001";
    private static final String ORDER_CLOSED = "ORD-20261114-002";
    private static final String ORDER_MISSING = "ORD-NOT-EXIST";

    private static final List<String> FAILURES = new ArrayList<>();

    public static void main(String[] args) {
        System.out.println("================================================================");
        System.out.println(" Agent Runtime M1 — 最小闭环演示（内存仓库 / 同步执行 / 无幂等）");
        System.out.println("================================================================\n");

        TaskRepository repository = new InMemoryTaskRepository();
        RefundService refundService = new RefundService();
        ToolRegistry registry = new ToolRegistry()
                .register(new OrderQueryService())
                .register(refundService);
        AgentEngine engine = DefaultAgentEngine.builder(repository, registry)
                .listener(new ConsoleStepListener(true))
                .maxSteps(10)
                .build();

        System.out.printf("已注册工具: %s%n%n", registry.names());

        // ---------- 场景 1：正常退款 ----------
        section("场景 1：正常退款 " + ORDER_OK);
        String taskId = engine.submit(new RefundAgent(), "u_10086", ORDER_OK);
        TaskResult result = engine.await(taskId, Duration.ofSeconds(10));
        dumpEvents(engine.getTask(taskId));

        check("任务终态为 COMPLETED", result.status() == TaskStatus.COMPLETED);
        check("结论包含回执号", String.valueOf(result.result()).contains("回执号"));
        check("步数为 2（queryOrder + refund）", engine.getTask(taskId).currentStep() == 2);
        check("事件数 ≥ 5（2 决策 + 2 工具 + 1 完成）", engine.getTask(taskId).events().size() >= 5);
        check("真实退款只执行 1 次", refundService.refundExecutions() == 1);

        // ---------- 场景 2：工具抛业务异常 ----------
        section("场景 2：订单不存在 " + ORDER_MISSING);
        String failTaskId = engine.submit(new RefundAgent(), "u_10086", ORDER_MISSING);
        TaskResult failResult = engine.await(failTaskId, Duration.ofSeconds(10));
        dumpEvents(engine.getTask(failTaskId));

        check("任务终态为 FAILED", failResult.status() == TaskStatus.FAILED);
        check("失败原因指向 queryOrder",
                failResult.error() != null && failResult.error().contains("queryOrder"));
        check("留下了 success=false 的工具事件", hasFailedToolEvent(engine.getTask(failTaskId)));
        check("失败场景没有触发真实退款", refundService.refundExecutions() == 1);

        // ---------- 场景 3：业务判定不可退 ----------
        section("场景 3：不可退订单 " + ORDER_CLOSED);
        String closedTaskId = engine.submit(new RefundAgent(), "u_10086", ORDER_CLOSED);
        TaskResult closedResult = engine.await(closedTaskId, Duration.ofSeconds(10));
        dumpEvents(engine.getTask(closedTaskId));

        check("任务终态为 COMPLETED（业务判定，不是故障）",
                closedResult.status() == TaskStatus.COMPLETED);
        check("结论说明不可退款", String.valueOf(closedResult.result()).contains("不可退款"));
        check("未调用 refund 工具", refundService.refundExecutions() == 1);

        // ---------- 汇总 ----------
        System.out.println();
        if (FAILURES.isEmpty()) {
            System.out.println("✅ M1 验收通过：决策→工具→完成闭环成立，事件可定位每一步，失败可归因。");
        } else {
            System.out.println("❌ M1 验收失败：");
            FAILURES.forEach(f -> System.out.println("   - " + f));
            System.exit(1);
        }
    }

    private static boolean hasFailedToolEvent(TaskView view) {
        return view.events().stream()
                .anyMatch(e -> e.stepType() == StepType.TOOL_CALL && !e.success());
    }

    private static void dumpEvents(TaskView view) {
        System.out.println("  ── 事件链路 ──");
        for (TaskEvent e : view.events()) {
            System.out.printf("  #%d step=%d %-12s %-12s success=%s%n",
                    e.id(), e.stepIndex(), e.stepType(),
                    e.toolName() == null ? "-" : e.toolName(), e.success());
        }
        System.out.println();
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

    private RefundAgentDemo() {
    }
}
