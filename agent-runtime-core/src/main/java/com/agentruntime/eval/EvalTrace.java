package com.agentruntime.eval;

import com.agentruntime.api.TaskResult;
import com.agentruntime.api.TaskView;
import com.agentruntime.domain.StepType;
import com.agentruntime.domain.TaskStatus;
import com.agentruntime.persistence.TaskEvent;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 一次评测运行的可断言快照（M5）。
 *
 * <h2>为什么断言只吃「事件账本」，不吃业务对象</h2>
 * 因为评测要能断言<b>过程</b>，而不只是结果。同一个「退款成功」的终态，可能是
 * 「查单 → 退款」的正确路径，也可能是「直接退款不看状态」的危险路径——后者在这次
 * 用例里碰巧也对，下次订单状态变了就是资损。{@link TaskEvent} 是引擎唯一诚实的记录，
 * 断言它既覆盖了结果也覆盖了路径，而且不要求业务方为评测额外埋点。
 *
 * <p>{@code result} / {@code error} 可能为 null（超时或引擎异常时没有结果）；
 * {@code events} 为空表示任务根本没跑起来。
 */
public record EvalTrace(
        String caseId,
        String taskId,
        TaskStatus status,
        Object result,
        String error,
        List<TaskEvent> events,
        BigDecimal totalCost,
        long totalTokens,
        long durationMs) {

    public EvalTrace {
        events = events == null ? List.of() : List.copyOf(events);
        totalCost = totalCost == null ? BigDecimal.ZERO : totalCost;
    }

    /** 正常跑完（或无结果）时从引擎视图构造。 */
    public static EvalTrace of(String caseId, TaskView view, TaskResult result, long durationMs) {
        Object payload = result == null ? null : result.result();
        String error = result == null ? null : result.error();
        if (view == null) {
            return new EvalTrace(caseId, null, TaskStatus.FAILED, payload, error,
                    List.of(), BigDecimal.ZERO, 0L, durationMs);
        }
        return new EvalTrace(caseId, view.taskId(), view.status(), payload, error,
                view.events(), view.totalCost(), view.totalTokens(), durationMs);
    }

    /** 引擎抛异常时构造（status=FAILED，events 为空，error 带异常摘要）。 */
    public static EvalTrace engineError(String caseId, String taskId, Throwable error, long durationMs) {
        String message = error == null
                ? "unknown"
                : error.getClass().getSimpleName() + ": " + error.getMessage();
        return new EvalTrace(caseId, taskId, TaskStatus.FAILED, null, message,
                List.of(), BigDecimal.ZERO, 0L, durationMs);
    }

    /** 成功的工具调用，按发生顺序。断言「序列」用它——重试中的失败尝试不算调用成功。 */
    public List<String> toolCalls() {
        List<String> names = new ArrayList<>();
        for (TaskEvent e : events) {
            if (e.stepType() == StepType.TOOL_CALL && e.success() && e.toolName() != null) {
                names.add(e.toolName());
            }
        }
        return names;
    }

    /** 工具被<b>尝试</b>的次数（含失败与重试），用于断言「重试确实发生了」。 */
    public long attempts(String toolName) {
        return events.stream()
                .filter(e -> e.stepType() == StepType.TOOL_CALL && toolName.equals(e.toolName()))
                .count();
    }

    /** 工具<b>成功</b>的次数，用于断言副作用次数（防重复扣款）。 */
    public long successes(String toolName) {
        return events.stream()
                .filter(e -> e.stepType() == StepType.TOOL_CALL
                        && e.success() && toolName.equals(e.toolName()))
                .count();
    }

    /**
     * {@code decide()} 被调用的次数，即「agent 想了几次」。
     *
     * <p>刻意把 {@link StepType#COMPLETION} 也算进来：收尾同样是一次决策、同样要计费
     * （见 {@code DecisionLoop} 里 COMPLETION 分支为什么要带 usage）。只数 {@code LLM_DECISION}
     * 会漏掉最后那次通常最贵的调用，用它做步数护栏会系统性低估。
     */
    public long decisions() {
        return events.stream()
                .filter(e -> e.stepType() == StepType.LLM_DECISION || e.stepType() == StepType.COMPLETION)
                .count();
    }

    /** 最终结果的可读文本；无结果时为空串。 */
    public String resultText() {
        return result == null ? "" : String.valueOf(result);
    }

    public String errorText() {
        return error == null ? "" : error;
    }

    /** 某一步的事件（排查用）。 */
    public Optional<TaskEvent> lastEvent() {
        return events.isEmpty() ? Optional.empty() : Optional.of(events.get(events.size() - 1));
    }

    /**
     * 紧凑的一行事件轨迹，失败时放进报告，例如
     * {@code [0:LLM_DECISION] → [0:TOOL_CALL queryOrder ✓] → ...}。
     *
     * <p>报告里只给「失败原因」不够用：看到轨迹才能立刻判断是 agent 走错了路，
     * 还是工具本身错了。
     */
    public String trajectory() {
        if (events.isEmpty()) {
            return "(无事件)";
        }
        StringBuilder sb = new StringBuilder();
        for (TaskEvent e : events) {
            if (sb.length() > 0) {
                sb.append(" → ");
            }
            sb.append('[').append(e.stepIndex()).append(':').append(e.stepType());
            if (e.toolName() != null) {
                sb.append(' ').append(e.toolName()).append(e.success() ? " ✓" : " ✗");
            }
            sb.append(']');
        }
        return sb.toString();
    }
}
