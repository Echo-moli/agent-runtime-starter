package com.agentruntime.samples.refund;

import java.math.BigDecimal;

/** 订单快照（示例用的最小模型，真实项目里来自订单中心）。 */
public record Order(String orderId, BigDecimal amount, String status) {

    public boolean refundable() {
        return "PAID".equals(status) || "SHIPPED".equals(status);
    }
}
