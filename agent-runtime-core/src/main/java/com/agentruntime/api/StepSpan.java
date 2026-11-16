package com.agentruntime.api;

import com.agentruntime.domain.StepType;
import com.agentruntime.persistence.TaskEvent;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 一步的「span 视图」：名字、耗时、结果、花费（M4）。
 *
 * <p>它和 OTel 的 span 是同构的，但来源不同：这里是从<b>已经落库的事件</b>还原出来的，
 * 所以进程重启、收集器过期之后依然能查。{@link com.agentruntime.observability.AgentTracer}
 * 负责把同一份数据实时送给 OTel；两者不互相依赖，也不互相替代。
 *
 * <p>{@code spanId} 是确定性生成的（{@code step-0-LLM_DECISION}），不是 UUID：
 * 出问题时人能直接照着编号去库里捞，也能和日志里的 {@code stepIndex} 对上。
 */
public record StepSpan(
        String taskId,
        String spanId,
        int stepIndex,
        StepType stepType,
        String toolName,
        boolean success,
        long tokens,
        BigDecimal cost,
        long durationMs) {

    public static List<StepSpan> from(String taskId, List<TaskEvent> events) {
        List<StepSpan> spans = new ArrayList<>(events.size());
        for (TaskEvent e : events) {
            spans.add(new StepSpan(taskId, spanIdOf(e), e.stepIndex(), e.stepType(), e.toolName(),
                    e.success(), e.tokens(), e.cost(), e.durationMs()));
        }
        return List.copyOf(spans);
    }

    public static String spanIdOf(TaskEvent event) {
        return "step-" + event.stepIndex() + "-" + event.stepType();
    }

    /** span 名：工具调用用 {@code tool:<name>}，其余用小写步骤类型。 */
    public String name() {
        return stepType == StepType.TOOL_CALL && toolName != null
                ? "tool:" + toolName
                : stepType.name().toLowerCase();
    }

    /** 给控制台/日志用的一行描述。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-22s", name()))
                .append(success ? " ok   " : " FAIL ")
                .append(String.format("%6dms", durationMs));
        if (tokens > 0 || cost.signum() > 0) {
            sb.append(String.format("  %6d tok  ¥%s", tokens, cost.toPlainString()));
        }
        return sb.toString();
    }
}
