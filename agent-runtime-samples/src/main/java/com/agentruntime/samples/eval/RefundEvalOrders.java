package com.agentruntime.samples.eval;

import com.agentruntime.samples.refund.Order;

import java.math.BigDecimal;
import java.util.List;

/**
 * 评测用的订单种子（M5）：把「状态 × 金额」矩阵铺开。
 *
 * <p>覆盖 ORDER 状态机的每个出口，而不是只测 happy path。理由很直接：
 * 退款类 agent 出事故的地方几乎从来不是「该退的没退」，而是<b>「不该退的退了」</b>——
 * 已关闭、已退款、已取消、待支付的单子被重复打款。这些分支不写进评测集，
 * 就等于把最贵的那条路径交给运气。
 */
public final class RefundEvalOrders {

    private RefundEvalOrders() {
    }

    public static final String PAID = "ORD-EVAL-1001";
    public static final String PAID_SMALL = "ORD-EVAL-1002";
    public static final String SHIPPED = "ORD-EVAL-1003";
    public static final String CLOSED = "ORD-EVAL-2001";
    public static final String REFUNDED = "ORD-EVAL-2002";
    public static final String CANCELLED = "ORD-EVAL-2003";
    public static final String PENDING = "ORD-EVAL-2004";
    public static final String LARGE = "ORD-EVAL-3001";

    /** 库里不存在的单号：验证「查询失败」不会被静默当成「可退款」。 */
    public static final String MISSING = "ORD-EVAL-9999";

    public static final List<Order> ALL = List.of(
            new Order(PAID, new BigDecimal("128.00"), "PAID"),
            // 刻意用两位小数：验证金额经过 JSON 化/恢复后仍是精确的 BigDecimal，而不是 66.599999
            new Order(PAID_SMALL, new BigDecimal("66.60"), "PAID"),
            new Order(SHIPPED, new BigDecimal("39.90"), "SHIPPED"),
            new Order(CLOSED, new BigDecimal("58.50"), "CLOSED"),
            new Order(REFUNDED, new BigDecimal("88.00"), "REFUNDED"),
            new Order(CANCELLED, new BigDecimal("12.00"), "CANCELLED"),
            new Order(PENDING, new BigDecimal("100.00"), "PENDING"),
            // 大额：触发 requireHumanConfirm 的退款工具
            new Order(LARGE, new BigDecimal("5000.00"), "PAID"));
}
