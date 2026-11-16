package com.agentruntime.api;

import com.agentruntime.domain.StepType;
import com.agentruntime.domain.TaskStatus;
import com.agentruntime.persistence.Task;
import com.agentruntime.persistence.TaskEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 任务状态 + 全链路事件的只读视图（DESIGN.md §5.3 引用但未定义，此处补全）。
 * 对应「出事能定位到底哪一步挂了」这个诉求的最小可交付形态。
 */
public record TaskView(
        String taskId,
        String agentName,
        TaskStatus status,
        int currentStep,
        List<TaskEvent> events,
        BigDecimal totalCost,
        long totalTokens,
        Instant createdAt,
        Instant updatedAt) {

    public static TaskView of(Task task, List<TaskEvent> events) {
        return new TaskView(
                task.getTaskId(),
                task.getAgentName(),
                task.getStatus(),
                task.getCurrentStep(),
                List.copyOf(events),
                task.getTotalCost(),
                task.getTotalTokens(),
                task.getCreatedAt(),
                task.getUpdatedAt());
    }

    /**
     * 步级 span 时间线（M4）：{@code getTask} 一次调用就能看到「哪一步、多久、多少钱」。
     *
     * <p>DESIGN §6 M4 的验收标准是「一次运行后 getTask 能看到每步 span 与分步成本」——
     * 这里刻意<b>不</b>要求接 OTel 才能看到，因为排查历史任务时收集器未必还在。
     */
    public List<StepSpan> spans() {
        return StepSpan.from(taskId, events);
    }

    // ------------------------------------------------------------------
    // 挂起态的三个问题（M9 运维控制面）
    // ------------------------------------------------------------------

    /**
     * 挂起时「在等哪个工具」。
     *
     * <p>运维看到的应该是「ORD-… 这笔退款在等人工批准 refund」，而不是一串 taskId。
     * 判定方式与引擎的断点重放完全同源：本步有 {@code LLM_DECISION(tool)} 但没有
     * {@code TOOL_CALL}/{@code COMPLETION} —— 这个形状统一覆盖了「等确认」「等裁定在途结果」
     * 两种挂起（见 {@code DecisionLoop#pendingStep}）。
     *
     * <p>只对 {@code SUSPENDED} 返回：{@code RUNNING} 的任务「本步没有 TOOL_CALL」可能只是
     * 正在执行，硬解释成 pending 会让运维以为卡住了。
     */
    public Optional<String> pendingTool() {
        if (status != TaskStatus.SUSPENDED) {
            return Optional.empty();
        }
        TaskEvent decision = null;
        for (TaskEvent event : events) {
            if (event.stepIndex() != currentStep) {
                continue;
            }
            if (event.stepType() == StepType.TOOL_CALL || event.stepType() == StepType.COMPLETION) {
                return Optional.empty();   // 本步已有终态：不是在等，是事件还没收尾
            }
            if (event.stepType() == StepType.LLM_DECISION && event.success()
                    && "tool".equals(event.payload().get("action"))) {
                decision = event;
            }
        }
        if (decision == null) {
            return Optional.empty();
        }
        Object toolName = decision.payload().get("toolName");
        return toolName == null ? Optional.empty() : Optional.of(String.valueOf(toolName));
    }

    /** 挂起原因（引擎写进 {@code HUMAN_REVIEW} 事件的那句话）。 */
    public Optional<String> suspendedReason() {
        return lastHumanReview()
                .map(event -> event.payload().get("reason"))
                .map(String::valueOf);
    }

    /**
     * 在途未知时被点名的幂等键。
     *
     * <p>这是整个系统里唯一「钱可能已经动了但没人知道」的状态。运维拿到这个 key 之后
     * 才能去对账系统里查那笔流水，再决定是「确实没发生（retrySideEffect）」还是
     * 「已经退过了（把结果交给 resume）」。没有它，人只能靠 taskId 猜。
     */
    public Optional<String> inDoubtKey() {
        return detailPrefix("IN_DOUBT:");
    }

    /** 是否卡在「副作用结果未知」—— 优先处理这类，别先处理普通审批。 */
    public boolean inDoubt() {
        return inDoubtKey().isPresent();
    }

    /** 是否在等 {@code requireHumanConfirm} 的确认（工具还没执行，钱一定没动）。 */
    public boolean awaitingConfirmation() {
        return detailPrefix("CONFIRM:").isPresent();
    }

    private Optional<String> detailPrefix(String prefix) {
        return lastHumanReview()
                .map(event -> event.payload().get("detail"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(detail -> detail.startsWith(prefix))
                .map(detail -> detail.substring(prefix.length()));
    }

    private Optional<TaskEvent> lastHumanReview() {
        for (int i = events.size() - 1; i >= 0; i--) {
            if (events.get(i).stepType() == StepType.HUMAN_REVIEW) {
                return Optional.of(events.get(i));
            }
        }
        return Optional.empty();
    }

    /** 总耗时：所有步的耗时之和（不含步骤之间的等待，例如等人工审批）。 */
    public long totalDurationMs() {
        long sum = 0;
        for (TaskEvent e : events) {
            sum += Math.max(0, e.durationMs());
        }
        return sum;
    }
}
