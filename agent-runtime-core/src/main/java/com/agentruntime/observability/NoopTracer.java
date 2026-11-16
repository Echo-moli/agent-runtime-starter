package com.agentruntime.observability;

import com.agentruntime.domain.StepType;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 默认 tracer：不产生任何输出（M1–M3 的行为保持不变）。
 *
 * <p>「不配就没开销」是可观测性组件的基本礼貌——很多框架默认开着全量埋点，
 * 结果压测时一半时间花在自己身上。
 */
public final class NoopTracer implements AgentTracer {

    public static final NoopTracer INSTANCE = new NoopTracer();

    private final AtomicLong seq = new AtomicLong();

    private NoopTracer() {
    }

    @Override
    public Span startTaskSpan(String taskId, String agentName, String userId) {
        // 仍然发号：日志里偶尔需要「本次运行的 traceId」做串联，代价可以忽略
        seq.incrementAndGet();
        return NoopSpan.INSTANCE;
    }

    @Override
    public Span startStepSpan(Span parent, int stepIndex, StepType stepType, String toolName) {
        return NoopSpan.INSTANCE;
    }

    static final class NoopSpan implements Span {

        static final NoopSpan INSTANCE = new NoopSpan();

        @Override
        public String traceId() {
            return "00000000000000000000000000000000";
        }

        @Override
        public String spanId() {
            return "0000000000000000";
        }

        @Override
        public Span setAttribute(String key, Object value) {
            return this;
        }

        @Override
        public Span recordException(Throwable error) {
            return this;
        }

        @Override
        public void end() {
        }
    }
}
