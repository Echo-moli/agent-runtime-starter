package com.agentruntime.observability;

import com.agentruntime.domain.StepType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 把 span 记在内存里，供测试断言（M4 demo 用它证明「真的建了 span，属性也对」）。
 *
 * <p>断言 span 的价值在于：埋点最典型的故障不是崩溃，而是<b>悄悄少了一半</b>——
 * 异常分支提前 return、异步线程丢了 context，日志上看一切正常，线上却拼不出链路。
 * 把 span 变成可断言的集合，这类问题在 CI 就会红。
 */
public final class RecordingTracer implements AgentTracer {

    /** 一个结束的 span 的快照。 */
    public record SpanRecord(String traceId, String spanId, String parentSpanId, String name,
                             int stepIndex, StepType stepType, String toolName,
                             Map<String, Object> attributes, long durationMs, String error) {

        public Object attribute(String key) {
            return attributes.get(key);
        }

        public boolean failed() {
            return error != null;
        }
    }

    private final List<SpanRecord> records = new CopyOnWriteArrayList<>();
    private final AtomicLong seq = new AtomicLong();

    @Override
    public Span startTaskSpan(String taskId, String agentName, String userId) {
        String traceId = "trace-" + seq.incrementAndGet();
        return new RecordingSpan(traceId, traceId, null, "agent.task", -1, null, null);
    }

    @Override
    public Span startStepSpan(Span parent, int stepIndex, StepType stepType, String toolName) {
        String traceId = parent == null ? "trace-orphan" : parent.traceId();
        String parentId = parent == null ? null : parent.spanId();
        String name = stepType == StepType.TOOL_CALL && toolName != null
                ? "tool:" + toolName
                : stepType.name().toLowerCase();
        return new RecordingSpan(traceId, traceId + "/" + seq.incrementAndGet(), parentId,
                name, stepIndex, stepType, toolName);
    }

    /** 已结束的 span，按结束顺序。 */
    public List<SpanRecord> records() {
        return List.copyOf(records);
    }

    /** 按名字过滤（例如只看 {@code tool:refund}）。 */
    public List<SpanRecord> byName(String name) {
        return records.stream().filter(r -> r.name().equals(name)).toList();
    }

    public void reset() {
        records.clear();
    }

    private final class RecordingSpan implements Span {

        private final String traceId;
        private final String spanId;
        private final String parentSpanId;
        private final String name;
        private final int stepIndex;
        private final StepType stepType;
        private final String toolName;
        private final long startNanos = System.nanoTime();
        private final Map<String, Object> attributes = new LinkedHashMap<>();
        private String error;
        private boolean ended;

        RecordingSpan(String traceId, String spanId, String parentSpanId, String name,
                      int stepIndex, StepType stepType, String toolName) {
            this.traceId = traceId;
            this.spanId = spanId;
            this.parentSpanId = parentSpanId;
            this.name = name;
            this.stepIndex = stepIndex;
            this.stepType = stepType;
            this.toolName = toolName;
        }

        @Override
        public String traceId() {
            return traceId;
        }

        @Override
        public String spanId() {
            return spanId;
        }

        @Override
        public Span setAttribute(String key, Object value) {
            if (key != null && value != null) {
                attributes.put(key, value);
            }
            return this;
        }

        @Override
        public Span recordException(Throwable t) {
            this.error = t == null ? "unknown" : t.getClass().getSimpleName() + ": " + t.getMessage();
            return this;
        }

        @Override
        public synchronized void end() {
            if (ended) {
                return;
            }
            ended = true;
            records.add(new SpanRecord(traceId, spanId, parentSpanId, name, stepIndex, stepType,
                    toolName, Map.copyOf(attributes), (System.nanoTime() - startNanos) / 1_000_000,
                    error));
        }
    }
}
