package com.agentruntime.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 决策结果：下一步做什么（DESIGN.md §5.2）。
 *
 * <p>与 DESIGN 的差异有两处补齐：
 * <ul>
 *   <li>{@code Map} 补成 {@code Map<String, Object>}，否则实现者无法知道 key 的类型约定；</li>
 *   <li>M4 加 {@link #usage()}：这次决策花了多少 token / 多少钱。
 *       引擎不认识模型供应商，用量只能由 plan 自己报上来，否则成本归因永远只能靠"事后反推"。</li>
 * </ul>
 */
public final class Step {

    public enum Kind {
        /** 调用一个 {@code @AgentTool} 方法。 */
        TOOL,
        /** 请求人工介入，任务转入 SUSPENDED。 */
        HUMAN_REVIEW,
        /** 结束任务。 */
        COMPLETE
    }

    private final Kind kind;
    private final String toolName;
    private final Map<String, Object> args;
    private final String reason;
    private final Object result;
    private final Usage usage;

    private Step(Kind kind, String toolName, Map<String, Object> args, String reason, Object result,
                 Usage usage) {
        this.kind = kind;
        this.toolName = toolName;
        this.args = args;
        this.reason = reason;
        this.result = result;
        this.usage = usage == null ? Usage.ZERO : usage;
    }

    public static Step tool(String toolName, Map<String, ?> args) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName 不能为空");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        if (args != null) {
            copy.putAll(args);
        }
        return new Step(Kind.TOOL, toolName, Collections.unmodifiableMap(copy), null, null, null);
    }

    public static Step humanReview(String reason) {
        return new Step(Kind.HUMAN_REVIEW, null, Map.of(), reason == null ? "" : reason, null, null);
    }

    public static Step complete(Object result) {
        return new Step(Kind.COMPLETE, null, Map.of(), null, result, null);
    }

    /**
     * 附上兑现这次决策所花的用量（M4，链式写法）。
     *
     * <pre>{@code
     * Usage usage = Usage.priced(prompt.tokens(), reply.tokens(), IN_PRICE, OUT_PRICE);
     * return Step.tool("refund", args).withUsage(usage);
     * }</pre>
     */
    public Step withUsage(Usage usage) {
        return new Step(kind, toolName, args, reason, result, usage);
    }

    public Kind kind() {
        return kind;
    }

    public String toolName() {
        return toolName;
    }

    public Map<String, Object> args() {
        return args;
    }

    public String reason() {
        return reason;
    }

    public Object result() {
        return result;
    }

    /** 这次决策的用量；plan 不报就是 {@link Usage#ZERO}。 */
    public Usage usage() {
        return usage;
    }

    @Override
    public String toString() {
        return switch (kind) {
            case TOOL -> "Step.tool(" + toolName + ", " + args + ')';
            case HUMAN_REVIEW -> "Step.humanReview(" + reason + ')';
            case COMPLETE -> "Step.complete(" + result + ')';
        };
    }
}
