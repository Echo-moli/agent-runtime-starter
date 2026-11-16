package com.agentruntime.samples.eval;

import com.agentruntime.api.AgentPlan;
import com.agentruntime.api.Context;
import com.agentruntime.api.Step;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Map;

/**
 * <b>故意写坏的</b>退款 agent（M5 演示「改动后跑评测能拦截一次回退」）。
 *
 * <h2>它坏在哪，以及为什么这种坏法最常见</h2>
 * 只剩一个差别：<b>不再检查订单状态，查到单号就直接退款</b>。
 * 这是最典型的线上事故形态——
 * <ul>
 *   <li>不是崩溃，代码能跑、日志干净、happy path 全对；</li>
 *   <li>是「有人为了修『已发货订单退不了'的客诉，把状态判断删了」；</li>
 *   <li>单测大概率也过，因为原有的单测只测了 PAID。</li>
 * </ul>
 * 结果是已退款、已取消、已关闭的单子被重复打款——<b>直接资损</b>。
 * 传统做法靠人 review 那一行 diff，而 {@link RefundEvalCases} 里的 refunde 状态矩阵会在
 * CI 上把它变成 4 条红色的 {@code TOOL_SEQUENCE} 失败，还顺带暴露 1 条成本超预算。
 *
 * <p>把它做成独立类而不是给 {@code RefundAgent} 加开关，是为了让「回退」这件事
 * 在仓库里留下可见的证据：评测的价值不在于永远绿，而在于有人拿它证明过红。
 */
public class RegressedRefundAgent implements AgentPlan {

    private final String refundTool;

    public RegressedRefundAgent() {
        this("refund");
    }

    public RegressedRefundAgent(String refundTool) {
        this.refundTool = refundTool;
    }

    @Override
    public String name() {
        return "RefundAgent";
    }

    @Override
    public Step decide(Context ctx) {
        return switch (ctx.stepIndex()) {
            case 0 -> {
                String orderId = ctx.userRequest() == null ? "" : ctx.userRequest().trim();
                if (orderId.isEmpty()) {
                    // 入参校验原样保留：这次回退只删了「订单状态判断」这一行，
                    // 其余行为一模一样。回退演示必须做成最小 diff，
                    // 否则读者无法判断失败是被哪处改动引起的。
                    throw new IllegalStateException("用户请求里没有订单号");
                }
                yield Step.tool("queryOrder", Map.of("orderId", orderId));
            }
            case 1 -> {
                if (ctx.humanRejected()) {
                    yield Step.complete("人工拒绝，未执行退款");
                }
                Object last = ctx.lastToolResult().orElseThrow();
                // ★ 回退点：这里原本有 refundable() 判断，被删掉了
                yield Step.tool(refundTool, Map.of(
                        "orderId", str(field(last, "orderId")),
                        "amount", amount(field(last, "amount"))));
            }
            default -> {
                Object last = ctx.lastToolResult().orElseThrow();
                yield Step.complete("退款成功，回执号 " + str(field(last, "receiptId"))
                        + "，金额 " + amount(field(last, "amount")) + " 元");
            }
        };
    }

    private static Object field(Object source, String name) {
        if (source instanceof Map<?, ?> map) {
            return map.get(name);
        }
        try {
            Method accessor = source.getClass().getMethod(name);
            return accessor.invoke(source);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static BigDecimal amount(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return new BigDecimal(String.valueOf(value));
    }
}
