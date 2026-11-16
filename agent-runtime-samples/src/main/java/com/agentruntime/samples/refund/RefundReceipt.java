package com.agentruntime.samples.refund;

import java.math.BigDecimal;
import java.time.Instant;

/** 退款回执。 */
public record RefundReceipt(String receiptId, String orderId, BigDecimal amount, Instant at) {
}
