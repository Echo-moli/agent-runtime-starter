package com.agentruntime.observability;

import com.agentruntime.api.StepSpan;
import com.agentruntime.api.TaskView;
import com.agentruntime.domain.StepType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分步成本报表（M4）：把「这次运行花了多少钱、花在哪一步、花在哪个工具上」算清楚。
 *
 * <p>DESIGN §4 把成本归因列为三大缺口之一，因为多数框架只在最后给一个总数——
 * 而真正要回答的问题是「这次超预算，是模型多轮自我纠正，还是某个工具被反复重试打爆了」。
 * 所以报表必须到步、到工具，且能看出<b>重试造成的那部分</b>。
 */
public record CostReport(String taskId, long totalTokens, BigDecimal totalCost,
                         List<Line> lines, Map<String, BigDecimal> costByTool,
                         Map<String, Long> tokensByTool) {

    /** 一步的花费。{@code durationMs} 与金额同一行，是为了看「贵」和「慢」是不是同一处。 */
    public record Line(int stepIndex, StepType stepType, String toolName, String spanName,
                       boolean success, long tokens, BigDecimal cost, long durationMs) {
    }

    public static CostReport from(TaskView view) {
        return from(view.taskId(), view.spans(), view.totalTokens(), view.totalCost());
    }

    public static CostReport from(String taskId, List<StepSpan> spans, long totalTokens, BigDecimal totalCost) {
        List<Line> lines = new ArrayList<>(spans.size());
        Map<String, BigDecimal> byTool = new LinkedHashMap<>();
        Map<String, Long> tokensByTool = new LinkedHashMap<>();
        for (StepSpan span : spans) {
            String bucket = span.stepType() == StepType.TOOL_CALL && span.toolName() != null
                    ? span.toolName()
                    : span.stepType().name();
            lines.add(new Line(span.stepIndex(), span.stepType(), span.toolName(), span.name(),
                    span.success(), span.tokens(), span.cost(), span.durationMs()));
            byTool.merge(bucket, span.cost(), BigDecimal::add);
            tokensByTool.merge(bucket, span.tokens(), Long::sum);
        }
        return new CostReport(taskId, totalTokens, totalCost, List.copyOf(lines),
                Collections.unmodifiableMap(byTool), Collections.unmodifiableMap(tokensByTool));
    }

    /** 单步最贵的那一步（没有花费时为 null）。 */
    public Line mostExpensiveStep() {
        Line max = null;
        for (Line line : lines) {
            if (line.cost().signum() > 0 && (max == null || line.cost().compareTo(max.cost()) > 0)) {
                max = line;
            }
        }
        return max;
    }

    /** 人看的表格。 */
    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-4s %-14s %-16s %-8s %8s %10s %8s%n",
                "step", "type", "tool", "result", "tokens", "cost(¥)", "ms"));
        sb.append("-".repeat(76)).append('\n');
        for (Line line : lines) {
            sb.append(String.format("%-4d %-14s %-16s %-8s %8d %10s %8d%n",
                    line.stepIndex(), line.stepType(),
                    line.toolName() == null ? "-" : line.toolName(),
                    line.success() ? "ok" : "FAIL",
                    line.tokens(), line.cost().toPlainString(), line.durationMs()));
        }
        sb.append("-".repeat(76)).append('\n');
        sb.append(String.format("%-4s %-14s %-16s %-8s %8d %10s%n",
                "", "TOTAL", "", "", totalTokens, totalCost.toPlainString()));
        if (!costByTool.isEmpty()) {
            sb.append("按工具/类型归因: ");
            List<String> parts = new ArrayList<>();
            costByTool.forEach((k, v) -> parts.add(k + "=¥" + v.toPlainString()));
            sb.append(String.join(", ", parts)).append('\n');
        }
        return sb.toString();
    }
}
