package com.agentruntime.autoconfigure;

import com.agentruntime.domain.StepType;
import com.agentruntime.observability.AgentTracer;
import com.agentruntime.observability.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把引擎的 span 接到 OpenTelemetry API（M8）。
 *
 * <h2>为什么只依赖 {@code opentelemetry-api}，不碰 SDK</h2>
 * API 是「往哪儿写」的接口，SDK/Exporter 是「怎么写出去」的实现。业务方的应用通常
 * 已经配置好了 SDK（OTLP/Jaeger/自建网关），本 starter 只需要说清楚
 * 「我在哪一步、花了多少 token、多少钱」；一旦这里也依赖 SDK，就会出现
 * 「引入 starter 顺手改了你的 exporter 版本」这种事——这正是 DESIGN §11
 * 要求 {@code <optional>true</optional> + @ConditionalOnClass} 的原因。
 *
 * <h2>span 命名与属性</h2>
 * <pre>
 * agent.task                {agent.name, user.id, agent.status, agent.steps, agent.tokens, agent.cost}
 *   ├─ llm_decision         {step.kind, tokens, cost}
 *   └─ tool:refund          {tool.name, attempts, fromCache, truncated}
 * </pre>
 * 与 M4 的控制台 tracer 完全同名同属性，从 console 迁到 OTel 不用重学一遍。
 */
public final class OtelTracer implements AgentTracer {

    private final io.opentelemetry.api.trace.Tracer tracer;

    public OtelTracer(io.opentelemetry.api.trace.Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public Span startTaskSpan(String taskId, String agentName, String userId) {
        SpanBuilder builder = tracer.spanBuilder("agent.task")
                .setAttribute("agent.task_id", nullSafe(taskId))
                .setAttribute("agent.name", nullSafe(agentName));
        if (userId != null) {
            builder.setAttribute("user.id", userId);
        }
        return new OtelSpan(builder.startSpan());
    }

    @Override
    public Span startStepSpan(Span parent, int stepIndex, StepType stepType, String toolName) {
        String name = stepType == StepType.TOOL_CALL && toolName != null
                ? "tool:" + toolName
                : stepType.name().toLowerCase(java.util.Locale.ROOT);
        SpanBuilder builder = tracer.spanBuilder(name)
                .setAttribute("step.index", (long) stepIndex)
                .setAttribute("step.type", stepType.name());
        if (toolName != null) {
            builder.setAttribute("tool.name", toolName);
        }
        if (parent instanceof OtelSpan otelParent) {
            // OTel 1.x 的 SpanBuilder 只接受 Context 作为 parent（不接受裸 Span）。
            // 用 Context.root() 而不是 Context.current()：引擎跑在自己的线程池里，
            // 调用方线程上可能挂着无关的 ambient context，继承它会造出错误的父子关系。
            builder.setParent(io.opentelemetry.context.Context.root().with(otelParent.delegate));
        }
        return new OtelSpan(builder.startSpan());
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /** OTel {@code Span} 的薄包装；{@code end()} 必须幂等（引擎的异常路径会 close 两次）。 */
    private static final class OtelSpan implements Span {

        private final io.opentelemetry.api.trace.Span delegate;
        private final AtomicBoolean ended = new AtomicBoolean();

        private OtelSpan(io.opentelemetry.api.trace.Span delegate) {
            this.delegate = delegate;
        }

        @Override
        public String traceId() {
            SpanContext ctx = delegate.getSpanContext();
            return ctx.isValid() ? ctx.getTraceId() : "";
        }

        @Override
        public String spanId() {
            SpanContext ctx = delegate.getSpanContext();
            return ctx.isValid() ? ctx.getSpanId() : "";
        }

        @Override
        public Span setAttribute(String key, Object value) {
            if (key == null || value == null) {
                return this;
            }
            // OTel 的 setAttribute 没有 Object 重载，也只能接受基本类型；
            // 这里按实际类型分派，顺带挡掉「传了个 Map 进去」这种 OTel 会静默丢弃的情况
            if (value instanceof Number number) {
                delegate.setAttribute(key, number.doubleValue());
            } else if (value instanceof Boolean bool) {
                delegate.setAttribute(key, bool);
            } else {
                delegate.setAttribute(key, String.valueOf(value));
            }
            return this;
        }

        @Override
        public Span recordException(Throwable error) {
            if (error != null) {
                delegate.recordException(error);
                delegate.setStatus(StatusCode.ERROR, String.valueOf(error.getMessage()));
            }
            return this;
        }

        @Override
        public void end() {
            if (ended.compareAndSet(false, true)) {
                delegate.end();
            }
        }
    }
}
