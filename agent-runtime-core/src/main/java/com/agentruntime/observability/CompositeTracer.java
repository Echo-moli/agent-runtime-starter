package com.agentruntime.observability;

import com.agentruntime.domain.StepType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 同时喂给多个 tracer（M4）：一个打到控制台给人看，一个留着给测试断言，一个送 OTel。
 *
 * <p>真实项目里这几乎是必需的能力——上线时既想要收集器里的数据，也想要本地日志；
 * 而「在引擎里加个 if 判断有几个 tracer」会把可观测性逻辑污染进决策循环。
 */
public final class CompositeTracer implements AgentTracer {

    private final List<AgentTracer> tracers;

    public CompositeTracer(AgentTracer... tracers) {
        if (tracers == null || tracers.length == 0) {
            throw new IllegalArgumentException("至少需要一个 tracer");
        }
        List<AgentTracer> list = new ArrayList<>(tracers.length);
        for (AgentTracer t : tracers) {
            if (t != null) {
                list.add(t);
            }
        }
        this.tracers = List.copyOf(list);
    }

    @Override
    public Span startTaskSpan(String taskId, String agentName, String userId) {
        List<Span> spans = new ArrayList<>(tracers.size());
        for (AgentTracer tracer : tracers) {
            spans.add(tracer.startTaskSpan(taskId, agentName, userId));
        }
        return new CompositeSpan(spans);
    }

    @Override
    public Span startStepSpan(Span parent, int stepIndex, StepType stepType, String toolName) {
        List<Span> parents = parent instanceof CompositeSpan cs
                ? cs.spans
                : Collections.nCopies(tracers.size(), parent);
        List<Span> spans = new ArrayList<>(tracers.size());
        for (int i = 0; i < tracers.size(); i++) {
            spans.add(tracers.get(i).startStepSpan(parents.get(i), stepIndex, stepType, toolName));
        }
        return new CompositeSpan(spans);
    }

    @Override
    public void shutdown() {
        tracers.forEach(AgentTracer::shutdown);
    }

    private record CompositeSpan(List<Span> spans) implements Span {

        @Override
        public String traceId() {
            return spans.get(0).traceId();
        }

        @Override
        public String spanId() {
            return spans.get(0).spanId();
        }

        @Override
        public Span setAttribute(String key, Object value) {
            spans.forEach(s -> s.setAttribute(key, value));
            return this;
        }

        @Override
        public Span recordException(Throwable error) {
            spans.forEach(s -> s.recordException(error));
            return this;
        }

        @Override
        public void end() {
            spans.forEach(Span::end);   // 各实现自己的 end() 幂等，这里不必再去重
        }
    }
}
