package com.agentruntime.observability;

import com.agentruntime.domain.StepType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 span 打成缩进的树（控制台版 tracing，M4 demo 用）。
 *
 * <p>它是「可观测性不是只有接收集器一条路」的证明：一个下午就能读懂 span 结构，
 * 而不用先起 Jaeger。生产当然应该走 {@link AgentTracer} 的 OTel 适配器，但本地调试时
 * 打开这个比翻 JSON 快得多。
 */
public final class ConsoleTracer implements AgentTracer {

    private static final String RESET = "\u001B[0m";
    private static final String DIM = "\u001B[2m";
    private static final String CYAN = "\u001B[36m";
    private static final String GREEN = "\u001B[32m";
    private static final String RED = "\u001B[31m";
    private static final String YELLOW = "\u001B[33m";

    private final boolean color;
    private long seq;

    public ConsoleTracer() {
        this(true);
    }

    public ConsoleTracer(boolean color) {
        this.color = color;
    }

    @Override
    public synchronized Span startTaskSpan(String taskId, String agentName, String userId) {
        String traceId = "trace-" + (++seq);
        return new ConsoleSpan(traceId, traceId, "agent.task " + agentName, 0);
    }

    @Override
    public Span startStepSpan(Span parent, int stepIndex, StepType stepType, String toolName) {
        String traceId = parent == null ? "trace-?" : parent.traceId();
        String parentId = parent == null ? traceId : parent.spanId();
        int depth = parent instanceof ConsoleSpan cs ? cs.depth + 1 : 1;
        String name = stepType == StepType.TOOL_CALL && toolName != null
                ? "tool:" + toolName
                : stepType.name().toLowerCase();
        return new ConsoleSpan(traceId, parentId + "/" + stepIndex + "-" + name, name, depth);
    }

    private final class ConsoleSpan implements Span {

        private final String traceId;
        private final String spanId;
        private final String name;
        private final int depth;
        private final long startNanos = System.nanoTime();
        private final Map<String, Object> attributes = new LinkedHashMap<>();
        private String error;
        private boolean ended;

        ConsoleSpan(String traceId, String spanId, String name, int depth) {
            this.traceId = traceId;
            this.spanId = spanId;
            this.name = name;
            this.depth = depth;
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
        public void end() {
            if (ended) {
                return;
            }
            ended = true;
            long ms = (System.nanoTime() - startNanos) / 1_000_000;
            StringBuilder sb = new StringBuilder();
            sb.append("  ".repeat(depth)).append(p(depth == 0 ? CYAN : DIM)).append("● ").append(p(RESET))
                    .append(name);
            if (error != null) {
                sb.append(' ').append(p(RED)).append("✗").append(p(RESET));
            } else {
                sb.append(' ').append(p(GREEN)).append("✓").append(p(RESET));
            }
            sb.append(' ').append(p(YELLOW)).append(ms).append("ms").append(p(RESET));
            if (!attributes.isEmpty()) {
                sb.append(' ').append(p(DIM)).append(attributes).append(p(RESET));
            }
            if (error != null) {
                sb.append(' ').append(p(RED)).append(error).append(p(RESET));
            }
            System.out.println(sb);
        }

        private String p(String code) {
            return color ? code : "";
        }
    }
}
