package com.agentruntime.engine;

import com.agentruntime.api.AgentEngine;
import com.agentruntime.api.AgentPlan;
import com.agentruntime.api.Context;
import com.agentruntime.api.Step;
import com.agentruntime.api.TaskResult;
import com.agentruntime.domain.StepType;
import com.agentruntime.domain.TaskStatus;
import com.agentruntime.persistence.InMemoryTaskRepository;
import com.agentruntime.persistence.Task;
import com.agentruntime.persistence.TaskEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 断点重放：库里已有 LLM_DECISION(tool) 但没有 TOOL_CALL 时，
 * 决策循环必须直接从事件还原这一步，绝不重调 decide()（模型调用是最贵的一步）。
 */
class DecisionLoopReplayTest {

    private static final AtomicInteger toolCalls = new AtomicInteger();

    /** 一个带幂等键的工具，记录真实调用次数。 */
    public static class RefundTools {
        @com.agentruntime.annotation.AgentTool(name = "refund", idempotencyKey = "#orderId")
        public Map<String, Object> refund(String orderId) {
            toolCalls.incrementAndGet();
            return Map.of("receipt", "R-" + orderId);
        }
    }

    private static DecisionLoop loop(InMemoryTaskRepository repo, ToolRegistry registry) {
        return new DecisionLoop(repo, registry, new ContextAssembler(),
                new AgentEngine.StepListener() {
                },
                new ToolExecutor(null, RetryPolicy.none(), null, null),
                com.agentruntime.observability.AgentTracer.noop(),
                com.agentruntime.observability.Metrics.noop(), 10, null);
    }

    @Test
    void 已有决策事件时跳过decide且工具恰好执行一次() {
        toolCalls.set(0);
        InMemoryTaskRepository repo = new InMemoryTaskRepository();
        ToolRegistry registry = new ToolRegistry().register(new RefundTools());

        Task task = new Task("t-replay", "ReplayAgent", "u", "退 ORD-1");
        task.setStatus(TaskStatus.RUNNING);
        task.setOwner("instance-A", Instant.now());
        repo.save(task);

        // 模拟崩溃现场：第 0 步的决策已落库，但工具还没执行
        repo.appendEvent(TaskEvent.draft("t-replay", 0, StepType.LLM_DECISION, null,
                Map.of("action", "tool", "toolName", "refund",
                        "args", Map.of("orderId", "ORD-1")), true));

        List<Integer> decidedAtSteps = new java.util.ArrayList<>();
        // 重放第 0 步后进入第 1 步：plan 第一次被调应当发生在 stepIndex=1。
        // 若第 0 步被错误地重算，这里会是 0——那是重复计费的铁证。
        AgentPlan plan = ctx -> {
            decidedAtSteps.add(ctx.stepIndex());
            return Step.complete("done");
        };

        TaskResult result = loop(repo, registry).run(task, plan);

        assertEquals(List.of(1), decidedAtSteps,
                "第 0 步必须从事件重放；decide() 第一次被调用应在第 1 步");
        assertEquals(1, toolCalls.get(), "被重放的工具必须真的执行一次");
        assertEquals(TaskStatus.COMPLETED, result.status());
        assertEquals(1, task.getCurrentStep());
        List<TaskEvent> events = repo.findEvents("t-replay");
        assertEquals(StepType.TOOL_CALL, events.get(events.size() - 2).stepType());
    }

    @Test
    void 已有完成事件时直接收尾不重跑决策() {
        InMemoryTaskRepository repo = new InMemoryTaskRepository();
        ToolRegistry registry = new ToolRegistry();

        Task task = new Task("t-done", "ReplayAgent", "u", "req");
        task.setStatus(TaskStatus.RUNNING);
        task.setOwner("instance-A", Instant.now());
        repo.save(task);
        repo.appendEvent(TaskEvent.draft("t-done", 0, StepType.COMPLETION, null,
                Map.of("result", "已完成"), true));

        AtomicInteger decisions = new AtomicInteger();
        AgentPlan plan = ctx -> {
            decisions.incrementAndGet();
            return Step.complete("不该走到这");
        };

        TaskResult result = loop(repo, registry).run(task, plan);
        assertEquals(0, decisions.get());
        assertEquals(TaskStatus.COMPLETED, result.status());
        assertEquals("已完成", result.result());
    }
}
