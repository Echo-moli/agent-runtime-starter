package com.agentruntime.observability;

/**
 * 一个正在进行的 span（DESIGN.md §6 M4：{@code tracer.spanBuilder(toolName)}）。
 *
 * <p>刻意做成极小的接口而不是直接依赖 {@code io.opentelemetry.api.trace.Span}：
 * {@code core} 的零依赖是 §2.1 的硬约束，而 OTel 的接入只是一层适配器
 * （把 {@code Span} 包一个 {@code OtelSpan} 即可）。同样的取舍在 M3 的幂等键表达式上已经做过一次。
 *
 * <p>用法固定为 try-with-resources：
 * <pre>{@code
 * try (Span span = tracer.startStepSpan(parent, stepIndex, StepType.TOOL_CALL, name)) {
 *     span.setAttribute("attempts", 2);
 *     ...
 * }
 * }</pre>
 * {@code end()} 必须幂等——异常路径上 close 和显式 end 可能都跑一次。
 */
public interface Span extends AutoCloseable {

    /** 同一次任务运行共享一个 traceId，便于在 OTel 里把整条链路串起来。 */
    String traceId();

    String spanId();

    /** 失败不应该影响业务：实现必须吞掉自身的异常。 */
    Span setAttribute(String key, Object value);

    Span recordException(Throwable error);

    void end();

    @Override
    default void close() {
        end();
    }

    /** 什么都不做的 span（未配置 tracer 时使用）。 */
    static Span noop() {
        return NoopTracer.NoopSpan.INSTANCE;
    }
}
