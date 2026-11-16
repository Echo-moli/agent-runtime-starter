package com.agentruntime.api;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 一次决策（或一次工具调用）的用量与花费（DESIGN.md §4.3 的 {@code tokens}/{@code cost}）。
 *
 * <p>引擎不认识任何模型供应商，也不该认识：{@link AgentPlan#decide} 的实现知道自己调了谁、
 * 用了多少 token，就把它们连同决策一起交回来。引擎只负责<b>记到哪一步头上</b>并累加。
 *
 * <p>金额精度固定 4 位小数（与 §7 的 {@code NUMERIC(12,4)} 一致）。不要用 {@code double} 存钱，
 * 也不要指望「一步四舍五入无所谓」——一万步累加下来，差的是对账时找不回来的那几分钱。
 */
public record Usage(long inputTokens, long outputTokens, BigDecimal cost) {

    public static final Usage ZERO = new Usage(0L, 0L, BigDecimal.ZERO);

    public Usage {
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("token 数不能为负: " + inputTokens + "/" + outputTokens);
        }
        cost = cost == null ? BigDecimal.ZERO : cost.setScale(4, RoundingMode.HALF_UP);
    }

    /** 输入 + 输出。计费口径请自行区分，这里只给总量。 */
    public long totalTokens() {
        return inputTokens + outputTokens;
    }

    public boolean isEmpty() {
        return inputTokens == 0 && outputTokens == 0 && cost.signum() == 0;
    }

    public Usage plus(Usage other) {
        if (other == null) {
            return this;
        }
        return new Usage(inputTokens + other.inputTokens,
                outputTokens + other.outputTokens, cost.add(other.cost));
    }

    /**
     * 按「每百万 token 单价」算钱（供应商的常见报价口径）。
     *
     * @param pricePerMillionInput  输入单价（元 / 百万 token）
     * @param pricePerMillionOutput 输出单价（元 / 百万 token）
     */
    public static Usage priced(long inputTokens, long outputTokens,
                               BigDecimal pricePerMillionInput, BigDecimal pricePerMillionOutput) {
        BigDecimal in = BigDecimal.valueOf(inputTokens).multiply(pricePerMillionInput);
        BigDecimal out = BigDecimal.valueOf(outputTokens).multiply(pricePerMillionOutput);
        BigDecimal cost = in.add(out).divide(BigDecimal.valueOf(1_000_000L), 4, RoundingMode.HALF_UP);
        return new Usage(inputTokens, outputTokens, cost);
    }

    @Override
    public String toString() {
        return "Usage{in=" + inputTokens + ", out=" + outputTokens
                + ", tokens=" + totalTokens() + ", cost=¥" + cost + '}';
    }
}
