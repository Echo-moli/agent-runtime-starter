package com.agentruntime.samples.refund;

import com.agentruntime.annotation.AgentTool;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 订单查询服务（mock）：故意保留真实业务里「单号不存在就抛业务异常」的行为。 */
public class OrderQueryService {

    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    /** 人为延迟（M4 演示用）：让 span 的耗时有可读的数值，默认 0 不影响既有 demo。 */
    private long latencyMs;

    public OrderQueryService() {
        orders.put("ORD-20261114-001", new Order("ORD-20261114-001", new BigDecimal("128.00"), "PAID"));
        orders.put("ORD-20261114-002", new Order("ORD-20261114-002", new BigDecimal("58.50"), "CLOSED"));
    }

    /**
     * 指定订单集合（M5 评测用）。
     *
     * <p>评测需要把「订单状态 × 金额」的矩阵铺开，而默认的两条种子数据只覆盖 PAID/CLOSED。
     * 用构造器注入而不是给每个 case 改数据库，是为了让评测夹具保持「每条 case 一套干净环境」——
     * 上一条用例改过的状态不会串到下一条。
     */
    public OrderQueryService(java.util.Collection<Order> seed) {
        for (Order order : seed) {
            orders.put(order.orderId(), order);
        }
    }

    /** 每次查询模拟这么多毫秒的网络往返。 */
    public OrderQueryService withLatency(long latencyMs) {
        this.latencyMs = Math.max(0, latencyMs);
        return this;
    }

    @AgentTool(name = "queryOrder", idempotencyKey = "#orderId")
    public Order queryOrder(String orderId) {
        SampleLatency.sleep(latencyMs);
        Order order = orders.get(orderId);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在: " + orderId);
        }
        return order;
    }

    /**
     * 故意返回一个大字符串，用来验证 {@code resultTruncateChars} 的截断形态是否仍是合法 JSON。
     *
     * <p>不设 {@code idempotencyKey}：这是纯查询，重试安全，与退款不是一类东西。
     */
    @AgentTool(name = "auditLog", resultTruncateChars = 120)
    public String auditLog(String orderId) {
        StringBuilder sb = new StringBuilder("订单 ").append(orderId).append(" 的操作日志：");
        for (int i = 1; i <= 200; i++) {
            sb.append("[").append(i).append("] 状态流转 OK; ");
        }
        return sb.toString();
    }
}
