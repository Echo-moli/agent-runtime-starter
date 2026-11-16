package com.agentruntime.api;

import com.agentruntime.domain.StepType;
import com.agentruntime.persistence.TaskEvent;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 交给 {@link AgentPlan#decide(Context)} 的上下文（DESIGN.md §5.2 未给出定义，此处补全）。
 *
 * <p>只读部分：任务标识、用户原始请求、当前步、已完成事件。
 * 可写部分：{@link #memory()}，业务方可在决策过程中暂存中间量，引擎不解释其内容。
 */
public final class Context {

    private final String taskId;
    private final String agentName;
    private final String userId;
    private final String userRequest;
    private final int stepIndex;
    private final Map<String, Object> memory;
    private final List<TaskEvent> events;

    public Context(String taskId, String agentName, String userId, String userRequest,
                   int stepIndex, Map<String, Object> memory, List<TaskEvent> events) {
        this.taskId = taskId;
        this.agentName = agentName;
        this.userId = userId;
        this.userRequest = userRequest;
        this.stepIndex = stepIndex;
        this.memory = memory;
        this.events = Collections.unmodifiableList(events);
    }

    public String taskId() {
        return taskId;
    }

    public String agentName() {
        return agentName;
    }

    public String userId() {
        return userId;
    }

    /** 用户原始输入。 */
    public String userRequest() {
        return userRequest;
    }

    /** 已完成的步数，等于下一步的序号。 */
    public int stepIndex() {
        return stepIndex;
    }

    /** 可变工作记忆，随任务存续。 */
    public Map<String, Object> memory() {
        return memory;
    }

    /** 至今为止的全部事件，按时间顺序。 */
    public List<TaskEvent> events() {
        return events;
    }

    /** 最近一次成功工具调用的返回值，便于 plan 串联上一步结果。 */
    public Optional<Object> lastToolResult() {
        for (int i = events.size() - 1; i >= 0; i--) {
            TaskEvent e = events.get(i);
            if (e.stepType() == StepType.TOOL_CALL && e.success()) {
                return Optional.ofNullable(e.payload().get("result"));
            }
        }
        return Optional.empty();
    }

    /** 最近一次失败工具调用的错误描述。 */
    public Optional<String> lastToolError() {
        for (int i = events.size() - 1; i >= 0; i--) {
            TaskEvent e = events.get(i);
            if (e.stepType() == StepType.TOOL_CALL && !e.success()) {
                Object err = e.payload().get("error");
                return Optional.ofNullable(err == null ? null : err.toString());
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------
    // 人工介入（M3）
    // ------------------------------------------------------------------

    /**
     * 当前步的人工作答（只在 resume 之后、且本步确实挂起过时存在）。
     *
     * <p>plan 必须用它决定「批准后继续」还是「拒绝后改主意」——引擎不会替业务方解释
     * 「拒绝」是什么意思（可能是终止、可能是换个方案、也可能是转人工工单）。
     */
    public Optional<HumanAnswer> humanAnswer() {
        for (int i = events.size() - 1; i >= 0; i--) {
            TaskEvent e = events.get(i);
            if (e.stepIndex() != stepIndex || e.stepType() != StepType.HUMAN_REVIEW) {
                continue;
            }
            Map<String, Object> p = e.payload();
            if (!Boolean.TRUE.equals(p.get("answer"))) {
                continue;
            }
            return Optional.of(new HumanAnswer(
                    !Boolean.FALSE.equals(p.get("approved")),
                    p.get("note") == null ? null : String.valueOf(p.get("note")),
                    Boolean.TRUE.equals(p.get("retrySideEffect")),
                    p.get("result"),
                    stepIndex));
        }
        return Optional.empty();
    }

    /** 人工是否明确拒绝了当前步。 */
    public boolean humanRejected() {
        return humanAnswer().map(a -> !a.approved()).orElse(false);
    }

    /** 人工是否批准了当前步。 */
    public boolean humanApproved() {
        return humanAnswer().map(HumanAnswer::approved).orElse(false);
    }

    /** 一次人工作答。 */
    public record HumanAnswer(boolean approved, String note, boolean retrySideEffect,
                              Object result, int stepIndex) {
    }

    @Override
    public String toString() {
        return "Context{task=" + taskId + ", agent=" + agentName + ", step=" + stepIndex
                + ", events=" + events.size() + '}';
    }
}
