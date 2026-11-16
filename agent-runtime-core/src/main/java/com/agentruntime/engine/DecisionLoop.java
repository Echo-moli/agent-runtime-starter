package com.agentruntime.engine;

import com.agentruntime.api.AgentEngine;
import com.agentruntime.api.AgentPlan;
import com.agentruntime.api.Context;
import com.agentruntime.api.Step;
import com.agentruntime.api.TaskResult;
import com.agentruntime.api.TaskView;
import com.agentruntime.api.Usage;
import com.agentruntime.domain.StepType;
import com.agentruntime.domain.TaskStatus;
import com.agentruntime.exception.AgentRuntimeException;
import com.agentruntime.exception.HumanConfirmRequiredException;
import com.agentruntime.exception.IdempotencyInDoubtException;
import com.agentruntime.exception.LeaseLostException;
import com.agentruntime.exception.MaxStepsExceededException;
import com.agentruntime.observability.AgentTracer;
import com.agentruntime.observability.Metrics;
import com.agentruntime.observability.Span;
import com.agentruntime.persistence.Task;
import com.agentruntime.persistence.TaskEvent;
import com.agentruntime.persistence.TaskRepository;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 决策循环（DESIGN.md §3 的 2→5 步）：组装上下文 → 决策 → 执行工具 → 记录事件 → 判定终态或继续。
 *
 * <h2>M3 让它变复杂的三件事，以及统一的解法</h2>
 * M2 的断点（决策已落库、工具未执行）和 M3 的人工确认（决策已落库、等人工）在事件层是<b>同一个形状</b>：
 * 「本步有一条 LLM_DECISION(tool)，但没有 TOOL_CALL」。所以只需要一个 {@link #pendingStep}，
 * 崩溃恢复和 resume 走同一条重放路径，不必写两套逻辑——两套逻辑的结局通常是一套先腐坏。
 *
 * <h2>挂起为什么不抛异常</h2>
 * DESIGN §6 M3 的写法是「写 SUSPENDED + 事件，抛 {@code HumanConfirmRequiredException}」。
 * 在事务语义下这样会把刚写的挂起状态一起回滚（异常 = rollback），任务变成
 * 「既没挂起也没继续」的幽灵。本实现改为<b>写完即返回</b>：挂起是一条正常结果，不是错误。
 * {@link HumanConfirmRequiredException} 仍被识别，业务方在工具内部主动抛它也能正确挂起。
 *
 * <h2>M4：埋点为什么加在这个循环里</h2>
 * 一步的边界只有引擎知道——一次「步」包含决策 + 工具 + 重试退避，从外面看到的是黑盒。
 * 所以 span、耗时、用量都在这里采集，但只吐给 {@link AgentTracer} / {@link Metrics}：
 * 引擎自己不解释、不聚合、不上报。
 */
public final class DecisionLoop {

    private final TaskRepository repository;
    private final ToolRegistry registry;
    private final ContextAssembler assembler;
    private final AgentEngine.StepListener listener;
    private final ToolExecutor toolExecutor;
    private final AgentTracer tracer;
    private final Metrics metrics;
    private final int maxSteps;
    /**
     * 本实例标识（M10.1）。非空时，所有状态写入都会带上「我是谁 + 我以为的状态」，
     * 用 CAS 落地；{@code null} 表示不做租约校验（直接构造 DecisionLoop 的老用法）。
     */
    private final String ownerId;

    public DecisionLoop(TaskRepository repository, ToolRegistry registry, ContextAssembler assembler,
                        AgentEngine.StepListener listener, ToolExecutor toolExecutor,
                        AgentTracer tracer, Metrics metrics, int maxSteps, String ownerId) {
        this.repository = repository;
        this.registry = registry;
        this.assembler = assembler;
        this.listener = listener;
        this.toolExecutor = toolExecutor;
        this.tracer = tracer == null ? AgentTracer.noop() : tracer;
        this.metrics = metrics == null ? Metrics.noop() : metrics;
        this.maxSteps = maxSteps;
        this.ownerId = ownerId;
    }

    public TaskResult run(Task task, AgentPlan plan) {
        boolean resumed = task.getStatus() == TaskStatus.RECOVERING;
        int startStep = task.getCurrentStep();

        // 第一写要把 PENDING/RECOVERING 改成 RUNNING，所以期望状态得用改动前的值。
        // 注意它必须落在下面的 try 里：CAS 失败同样要按「租约丢了」处理，
        // 而不是把 LeaseLostException 抛给 schedule() 变成一个「执行异常」。
        TaskStatus persisted = task.getStatus();
        task.setStatus(TaskStatus.RUNNING);

        Span taskSpan = tracer.startTaskSpan(task.getTaskId(), plan.name(), task.getUserId());
        taskSpan.setAttribute("agent.name", plan.name())
                .setAttribute("agent.resumed", resumed)
                .setAttribute("agent.start_step", startStep);
        listener.onTaskStart(TaskView.of(task, repository.findEvents(task.getTaskId())));

        try {
            persist(task, persisted);
            while (true) {
                if (task.getCurrentStep() >= maxSteps) {
                    throw new MaxStepsExceededException(maxSteps);
                }
                // 每步开始前确认租约还在自己手上（M10.1）。放在这里而不是每步结束时，
                // 是为了在「写事件」之前就停下——账本里不留另一个主人的半截步骤。
                assertStillOwner(task);
                List<TaskEvent> events = repository.findEvents(task.getTaskId());

                // 已在库里有 COMPLETION：说明上次崩在「写完终态事件、还没改任务状态」之间。
                // 直接收尾，不要重跑 decide()——否则最后一步的决策会被再收一次费，
                // 而且用户会看到两条完成事件。
                TaskEvent finished = completionAt(events, task.getCurrentStep());
                if (finished != null) {
                    task.setStatus(TaskStatus.COMPLETED);
                    persist(task, TaskStatus.RUNNING);
                    listener.onCompleted(TaskView.of(task, repository.findEvents(task.getTaskId())));
                    return result(task, TaskStatus.COMPLETED, finished.payload().get("result"), null);
                }

                // 断点重放：决策已落库但工具没执行（崩溃窗口 / 等人工确认 / 等人工裁定在途结果）
                Step replay = pendingStep(events, task.getCurrentStep());
                Step step;
                long decideMs = 0L;
                Usage decisionUsage = Usage.ZERO;

                if (replay != null) {
                    step = replay;
                    taskSpan.setAttribute("agent.replayed_step", task.getCurrentStep());
                } else {
                    Context context = assembler.assemble(task, events);
                    Span decideSpan = tracer.startStepSpan(taskSpan, task.getCurrentStep(),
                            StepType.LLM_DECISION, null);
                    long began = System.nanoTime();
                    try {
                        step = plan.decide(context);
                        decideMs = elapsedMs(began);
                        if (step == null) {
                            throw new AgentRuntimeException("AgentPlan.decide() 返回 null，plan=" + plan.name());
                        }
                        decisionUsage = step.usage();
                        decideSpan.setAttribute("step.kind", step.kind().name())
                                .setAttribute("tokens", decisionUsage.totalTokens())
                                .setAttribute("cost", decisionUsage.cost().toPlainString());
                    } catch (RuntimeException e) {
                        decideSpan.recordException(e);
                        throw e;
                    } finally {
                        decideSpan.end();
                    }
                }

                switch (step.kind()) {
                    case TOOL -> {
                        TaskResult suspended = handleTool(task, taskSpan, step, replay != null, events,
                                decideMs, decisionUsage);
                        if (suspended != null) {
                            return suspended;
                        }
                    }
                    case HUMAN_REVIEW -> {
                        return suspend(task, step.reason(), true, null);
                    }
                    case COMPLETE -> {
                        // 收尾决策同样是一次 LLM 调用，同样要计费。
                        // 早先这里传的是 Usage.ZERO，结果「最后一步生成答案」那笔钱永远不入账——
                        // 而那通常是整条链路里最贵的一次调用（上下文最长、输出最长）。
                        record(task, StepType.COMPLETION, null,
                                payload("result", step.result()), true, decideMs, decisionUsage);
                        applyUsage(task, decisionUsage);
                        task.setStatus(TaskStatus.COMPLETED);
                        persist(task, TaskStatus.RUNNING);
                        listener.onCompleted(TaskView.of(task, repository.findEvents(task.getTaskId())));
                        return result(task, TaskStatus.COMPLETED, step.result(), null);
                    }
                }
            }
        } catch (LeaseLostException e) {
            // 租约被别人接管了：什么都不写，任务归新主人。
            // 这里<b>不能</b>标 FAILED——那会让新持有者看到「我刚认领的任务失败了」。
            metrics.counter("agent.lease.aborted").increment();
            return result(task, TaskStatus.RECOVERING, null, e.getMessage());
        } catch (IdempotencyInDoubtException e) {
            // 兜底：即便异常从工具层穿到这里，也按「挂起」处理，绝不记成失败
            return suspend(task, e.getMessage(), true, "IN_DOUBT:" + e.idempotencyKey());
        } catch (RuntimeException e) {
            task.setStatus(TaskStatus.FAILED);
            // 这里用「尽力写」：已经要结束了，CAS 失败说明租约也没了，不值得再抛一个异常淹掉真因
            persistQuietly(task, TaskStatus.RUNNING);
            String error = e.getMessage() == null ? e.toString() : e.getMessage();
            listener.onFailed(TaskView.of(task, repository.findEvents(task.getTaskId())), error);
            return result(task, TaskStatus.FAILED, null, error);
        } finally {
            taskSpan.setAttribute("agent.status", task.getStatus().name())
                    .setAttribute("agent.steps", task.getCurrentStep())
                    .setAttribute("agent.tokens", task.getTotalTokens())
                    .setAttribute("agent.cost", String.valueOf(task.getTotalCost()));
            taskSpan.end();
        }
    }

    // ------------------------------------------------------------------
    // 工具步
    // ------------------------------------------------------------------

    /** @return 需要挂起时返回结果；继续执行返回 null */
    private TaskResult handleTool(Task task, Span taskSpan, Step step, boolean replay,
                                  List<TaskEvent> events, long decideMs, Usage decisionUsage) {
        int stepIndex = task.getCurrentStep();
        String toolName = step.toolName();

        if (!replay) {
            // 决策事件先写（这是账本），再累加用量（这是物化汇总）。
            // 两者之间若崩溃，汇总会少算一步；recover/resume 的 reconcileUsage 会按事件校正。
            record(task, StepType.LLM_DECISION, null, payload(
                    "action", "tool",
                    "toolName", toolName,
                    "args", step.args()), true, decideMs, decisionUsage);
            applyUsage(task, decisionUsage);
        }

        ToolRegistry.ToolHandle handle = registry.require(toolName);

        // ---- 人工确认门禁：在调工具之前判定 ----
        boolean approved = hasAnswer(events, stepIndex, toolName, true);
        boolean rejected = hasAnswer(events, stepIndex, toolName, false);
        if (handle.meta().requireHumanConfirm() && !approved) {
            if (rejected) {
                throw new AgentRuntimeException("人工已拒绝执行工具 " + toolName
                        + "，但 plan 仍然请求了它（step=" + stepIndex + "）。"
                        + "decide() 必须读取 Context.humanApproved() 并给出别的决策");
            }
            return suspend(task, "工具 " + toolName + " 需要人工确认后才能执行（requireHumanConfirm=true）",
                    true, "CONFIRM:" + toolName);
        }

        ToolExecutor.Hint hint = hintOf(events, stepIndex);
        Span toolSpan = tracer.startStepSpan(taskSpan, stepIndex, StepType.TOOL_CALL, toolName);
        toolSpan.setAttribute("tool.name", toolName).setAttribute("step.index", stepIndex);
        ToolExecutor.Execution execution;
        long began = System.nanoTime();
        long toolMs;
        try {
            execution = toolExecutor.execute(handle, step.args(), task.getTaskId(), stepIndex, hint);
            toolMs = elapsedMs(began);
            toolSpan.setAttribute("attempts", execution.attempts())
                    .setAttribute("fromCache", execution.fromCache())
                    .setAttribute("fromHuman", execution.fromHuman())
                    .setAttribute("truncated", execution.truncated());
        } catch (IdempotencyInDoubtException | HumanConfirmRequiredException e) {
            toolSpan.recordException(e).setAttribute("inDoubt", true);
            String detail = e instanceof IdempotencyInDoubtException d
                    ? "IN_DOUBT:" + d.idempotencyKey()
                    : "CONFIRM:" + toolName;
            return suspend(task, e.getMessage(), true, detail);
        } catch (RuntimeException e) {
            toolSpan.recordException(e);
            record(task, StepType.TOOL_CALL, toolName,
                    payload("args", step.args(), "error", describe(e)), false,
                    elapsedMs(began), Usage.ZERO);
            throw e;
        } finally {
            toolSpan.end();
        }

        record(task, StepType.TOOL_CALL, toolName, payload(
                "args", step.args(),
                "result", execution.output(),
                "attempts", execution.attempts(),
                "fromCache", execution.fromCache(),
                "fromHuman", execution.fromHuman(),
                "idempotencyKey", execution.idempotencyKey(),
                "truncated", execution.truncated()), true,
                toolMs, Usage.ZERO);

        task.setCurrentStep(stepIndex + 1);
        persist(task, TaskStatus.RUNNING);
        return null;
    }

    // ------------------------------------------------------------------
    // 事件解析
    // ------------------------------------------------------------------

    /**
     * 本步是否有「已决策、未执行」的工具调用。
     *
     * <p>三种情况都归到这里：进程崩溃（M2）、等人工确认（M3 门禁）、等人工裁定在途结果（INIT）。
     * 唯一例外是「人工已拒绝」——那条决策视为作废，让 plan 重新决定。
     */
    @SuppressWarnings("unchecked")
    private static Step pendingStep(List<TaskEvent> events, int stepIndex) {
        TaskEvent decision = null;
        for (TaskEvent e : events) {
            if (e.stepIndex() != stepIndex) {
                continue;
            }
            if (e.stepType() == StepType.TOOL_CALL || e.stepType() == StepType.COMPLETION) {
                return null; // 本步已有终态
            }
            if (e.stepType() == StepType.LLM_DECISION && e.success()
                    && "tool".equals(e.payload().get("action"))) {
                decision = e;
            }
        }
        if (decision == null) {
            return null;
        }
        String toolName = String.valueOf(decision.payload().get("toolName"));
        if (hasAnswer(events, stepIndex, toolName, false)) {
            return null; // 被人工拒绝 → 作废，plan 重新决策
        }
        Object args = decision.payload().get("args");
        return Step.tool(toolName, args instanceof Map ? (Map<String, Object>) args : Map.of());
    }

    /** 本步是否已有 COMPLETION 事件（崩溃窗口：事件已写、状态未改）。 */
    private static TaskEvent completionAt(List<TaskEvent> events, int stepIndex) {
        for (TaskEvent e : events) {
            if (e.stepIndex() == stepIndex && e.stepType() == StepType.COMPLETION) {
                return e;
            }
        }
        return null;
    }

    /** 该步是否有针对某工具的有效人工作答（只看 answer=true 的事件）。 */
    private static boolean hasAnswer(List<TaskEvent> events, int stepIndex, String toolName, boolean approved) {
        for (TaskEvent e : events) {
            if (e.stepIndex() != stepIndex || e.stepType() != StepType.HUMAN_REVIEW) {
                continue;
            }
            Map<String, Object> p = e.payload();
            if (!Boolean.TRUE.equals(p.get("answer"))) {
                continue;
            }
            boolean sameTool = toolName == null || p.get("toolName") == null
                    || toolName.equals(p.get("toolName"));
            boolean sameDecision = Boolean.valueOf(approved).equals(p.get("approved"));
            if (sameTool && sameDecision) {
                return true;
            }
        }
        return false;
    }

    /** 人工是否给了「重试副作用」或「直接给结果」的指令。 */
    private static ToolExecutor.Hint hintOf(List<TaskEvent> events, int stepIndex) {
        for (int i = events.size() - 1; i >= 0; i--) {
            TaskEvent e = events.get(i);
            if (e.stepIndex() != stepIndex || e.stepType() != StepType.HUMAN_REVIEW) {
                continue;
            }
            Map<String, Object> p = e.payload();
            if (!Boolean.TRUE.equals(p.get("answer"))) {
                continue;
            }
            if (p.containsKey("result")) {
                return ToolExecutor.Hint.givenResult(p.get("result"));
            }
            if (Boolean.TRUE.equals(p.get("retrySideEffect"))) {
                return ToolExecutor.Hint.forceRetry();
            }
        }
        return ToolExecutor.Hint.NONE;
    }

    // ------------------------------------------------------------------
    // 记录与终态
    // ------------------------------------------------------------------

    private TaskResult suspend(Task task, String reason, boolean needsConfirm, String detail) {
        Map<String, Object> payload = payload("reason", reason, "needsConfirm", needsConfirm, "awaiting", true);
        if (detail != null) {
            payload.put("detail", detail);
        }
        record(task, StepType.HUMAN_REVIEW, null, payload, true, 0L, Usage.ZERO);
        task.setStatus(TaskStatus.SUSPENDED);
        persist(task, TaskStatus.RUNNING);
        listener.onSuspended(TaskView.of(task, repository.findEvents(task.getTaskId())));
        return result(task, TaskStatus.SUSPENDED, reason, null);
    }

    private TaskEvent record(Task task, StepType type, String toolName, Map<String, Object> payload,
                             boolean success, long durationMs, Usage usage) {
        Usage u = usage == null ? Usage.ZERO : usage;
        long ms = Math.max(0, durationMs);
        // M10.2：事件也带所有权校验。丢了租约的实例不允许往别人的账本里追加步骤——
        // 那种事件事后无法解释（既不是新主人的操作，也不是它重放的操作）。
        TaskEvent event = repository.appendEventIfOwned(TaskEvent.draft(
                task.getTaskId(), task.getCurrentStep(), type, toolName, payload, success,
                u.totalTokens(), u.cost(), ms), ownerId);
        if (event == null) {
            throw new LeaseLostException(task.getTaskId(), ownerId);
        }
        listener.onEvent(event);

        metrics.counter("agent.steps",
                "step_type", type.name(),
                "success", String.valueOf(success)).increment();
        if (ms > 0) {
            metrics.timer("agent.step.duration", "step_type", type.name()).record(ms);
        }
        return event;
    }

    /**
     * 把一步的用量累加到任务上。
     *
     * <p>只在<b>真实决策</b>时调用：断点重放没有再调模型，重放时累加就是凭空多收一次费。
     */
    private void applyUsage(Task task, Usage usage) {
        if (usage == null || usage.isEmpty()) {
            return;
        }
        TaskRepository.UsageTotal total = repository.addUsage(
                task.getTaskId(), usage.totalTokens(), usage.cost());
        task.setTotalTokens(total.tokens());
        task.setTotalCost(total.cost());

        metrics.counter("agent.tokens").add(usage.totalTokens());
        metrics.counter("agent.cost").addAmount(usage.cost());
    }

    /**
     * 带租约的保存（M10.1）：{@code ownerId} 为空时退化为普通 save。
     *
     * @param expected 调用方认为数据库里<b>当前</b>的状态（CAS 条件）；
     *                 写入的是 {@code task} 上的<b>新</b>状态
     */
    private void persist(Task task, TaskStatus expected) {
        if (ownerId == null) {
            repository.save(task);
            return;
        }
        if (!repository.saveIfOwned(task, ownerId, expected)) {
            throw new LeaseLostException(task.getTaskId(), ownerId);
        }
    }

    private void persistQuietly(Task task, TaskStatus expected) {
        if (ownerId == null) {
            repository.save(task);
            return;
        }
        try {
            repository.saveIfOwned(task, ownerId, expected);
        } catch (RuntimeException ignored) {
            // 收尾失败不再抛出：原始异常才是要报告的根因
        }
    }

    /**
     * 每步开始前的租约校验。
     *
     * <p>代价是一次 SELECT（每步一次）。换来的是：账本里不会出现「另一个主人写的半截步骤」——
     * 那种数据事后没人能解释清楚，而对账最喜欢的就是这种解释不清的中间态。
     *
     * <p>任务已不存在（被清理）时同样视为失去租约。
     */
    private void assertStillOwner(Task task) {
        if (ownerId == null) {
            return;
        }
        boolean mine = repository.findById(task.getTaskId())
                .map(current -> ownerId.equals(current.getOwnerId()))
                .orElse(false);
        if (!mine) {
            throw new LeaseLostException(task.getTaskId(), ownerId);
        }
    }

    private TaskResult result(Task task, TaskStatus status, Object result, String error) {
        return new TaskResult(task.getTaskId(), status, result, error,
                task.getTotalCost() == null ? BigDecimal.ZERO : task.getTotalCost(),
                task.getTotalTokens());
    }

    /** 允许 null value 的 payload 构造器（Map.of 不允许 null）。 */
    private static Map<String, Object> payload(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    private static long elapsedMs(long beganNanos) {
        return (System.nanoTime() - beganNanos) / 1_000_000;
    }

    private static String describe(Throwable e) {
        String msg = e.getMessage();
        return msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg;
    }

    /** 供引擎 resume 时把那一步正在等确认的工具名写进人工作答事件。 */
    static Optional<String> pendingToolName(List<TaskEvent> events, int stepIndex) {
        Step step = pendingStep(events, stepIndex);
        return step == null ? Optional.empty() : Optional.ofNullable(step.toolName());
    }
}
