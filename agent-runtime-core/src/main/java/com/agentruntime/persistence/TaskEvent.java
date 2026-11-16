package com.agentruntime.persistence;

import com.agentruntime.domain.StepType;
import com.agentruntime.util.Json;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 事件溯源的单条记录（DESIGN.md §4.3）。
 *
 * <p>与 DESIGN 的一处实现约定：{@code payload} 在 M1 用 {@code Map} 承载（内存仓库直接持有对象，
 * 便于示例 `decide()` 读上一步结果），落库时由 M2 统一序列化为 {@code JSONB}；
 * {@link #payloadJson()} 给出与 DDL 中 {@code payload JSONB} 等价的视图。
 *
 * <p>M4 增加 {@link #durationMs}：这一步本身耗了多久。
 *
 * <h2>为什么时长要落库，而不是只交给 OTel 收集器</h2>
 * span 通常只活在收集器里，而「上周那笔退款为什么慢」是三天后才会问的问题——
 * 那时收集器早过了保留期，但任务事件还在库里。{@code durationMs} 让
 * {@link com.agentruntime.api.TaskView#spans()} 在<b>进程重启之后</b>依然能给出完整的步级时间线，
 * 不依赖任何外部系统。导出到 OTel 是锦上添花，落库的这条才是兜底。
 */
public record TaskEvent(
        long id,
        String taskId,
        int stepIndex,
        StepType stepType,
        String toolName,
        Map<String, Object> payload,
        boolean success,
        long tokens,
        BigDecimal cost,
        long durationMs,
        Instant createdAt) {

    public TaskEvent {
        payload = payload == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        cost = cost == null ? BigDecimal.ZERO : cost;
    }

    /** 静态工厂：仓库分配 id 前使用，{@code id=0} 表示尚未落库。 */
    public static TaskEvent draft(String taskId, int stepIndex, StepType stepType,
                                  String toolName, Map<String, Object> payload, boolean success) {
        return draft(taskId, stepIndex, stepType, toolName, payload, success, 0L, BigDecimal.ZERO, 0L);
    }

    /** 带用量与耗时的工厂（M4）：{@code tokens}/{@code cost} 是<b>这一步</b>的花费，不是累计。 */
    public static TaskEvent draft(String taskId, int stepIndex, StepType stepType, String toolName,
                                  Map<String, Object> payload, boolean success,
                                  long tokens, BigDecimal cost, long durationMs) {
        return new TaskEvent(0L, taskId, stepIndex, stepType, toolName, payload,
                success, tokens, cost, durationMs, Instant.now());
    }

    public TaskEvent withId(long newId) {
        return new TaskEvent(newId, taskId, stepIndex, stepType, toolName, payload,
                success, tokens, cost, durationMs, createdAt);
    }

    public String payloadJson() {
        return Json.write(payload);
    }
}
