package com.agentruntime.samples.refund;

import com.agentruntime.api.AgentPlan;
import com.agentruntime.api.Context;
import com.agentruntime.api.Step;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Map;

/**
 * 退款 Agent 的「大脑」。
 *
 * <p>DESIGN.md 明确把 {@code AgentPlan.decide()} 留给业务方，且引擎不感知用什么调模型。
 * 这里用最朴素的规则版实现（零 LLM 调用），这样 M1 的验收不依赖网络与 API Key；
 * 换成一次真实的模型调用时，只需把下面三个分支的判定改成读模型输出，引擎侧一行都不用动。
 *
 * <p>M2 注意：断点续跑后，上一步结果从 {@code JSONB} 还原，形态是 {@code Map} 而不是
 * {@code Order} / {@code RefundReceipt}（这是事件溯源持久化的客观事实）。
 * 因此 {@link #decide} 通过 {@link #field} 同时兼容两种形态——内存直跑拿到的是 record，
 * 崩溃恢复拿到的是 Map。
 */
public class RefundAgent implements AgentPlan {

    private final String refundTool;

    public RefundAgent() {
        this("refund");
    }

    /** 指定退款工具名，便于演示「需人工确认」的变体（{@code refundRequiringApproval}）。 */
    public RefundAgent(String refundTool) {
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
                    throw new IllegalStateException("用户请求里没有订单号");
                }
                yield Step.tool("queryOrder", Map.of("orderId", orderId));
            }
            case 1 -> {
                // M3：人工拒绝后不能再请求同一个工具（引擎会直接报错），必须给出替代决策
                if (ctx.humanRejected()) {
                    Object last = ctx.lastToolResult().orElse(null);
                    yield Step.complete("人工拒绝：订单 " + str(field(last, "orderId")) + " 的退款未执行");
                }
                Object last = ctx.lastToolResult()
                        .orElseThrow(() -> new IllegalStateException("上一步没有返回订单信息"));
                String orderId = str(field(last, "orderId"));
                String status = str(field(last, "status"));
                BigDecimal amount = amount(field(last, "amount"));
                boolean refundable = "PAID".equals(status) || "SHIPPED".equals(status);
                if (!refundable) {
                    yield Step.complete("订单 " + orderId + " 当前状态 " + status + "，不可退款");
                }
                yield Step.tool(refundTool, Map.of(
                        "orderId", orderId,
                        "amount", amount));
            }
            default -> {
                Object last = ctx.lastToolResult()
                        .orElseThrow(() -> new IllegalStateException("上一步没有返回退款回执"));
                yield Step.complete("退款成功，回执号 " + str(field(last, "receiptId"))
                        + "，金额 " + amount(field(last, "amount")) + " 元");
            }
        };
    }

    /** record 或 Map 都按字段名取值（M2 续跑后是 Map）。 */
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
