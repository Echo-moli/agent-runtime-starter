package com.agentruntime.engine;

/**
 * 重试策略（DESIGN §6 M3 的 Resilience4j 替代实现）。
 *
 * <p>为什么不用 Resilience4j：{@code core} 零第三方依赖是 DESIGN §2.1 的硬约束，
 * 而这里需要的只是「指数退避 + 异常分类」两件事，一百行能写清楚；
 * 等 M8 做自动装配时，再提供一个 {@code Resilience4jRetryPolicy} 适配器即可（接口已留好）。
 *
 * <p>更重要的一件是 {@link #retryable}：无脑重试比不重试更危险。
 * 「订单不存在」重试三次只是浪费 1.5 秒；「退款接口超时」重试三次是退三笔钱。
 */
public interface RetryPolicy {

    /** 该异常是否值得重试。判断依据是异常的<b>语义</b>，不是它的类型是否 RuntimeException。 */
    boolean retryable(Throwable error);

    /**
     * 第 {@code attempt} 次失败后等待多久（毫秒）。
     *
     * @param attempt    已失败的次数，从 1 开始
     * @param baseDelayMs 退避基准，来自 {@code @AgentTool.retryBaseDelayMs}
     */
    long delayMs(int attempt, long baseDelayMs);

    /** 不重试。 */
    static RetryPolicy none() {
        return new RetryPolicy() {
            @Override
            public boolean retryable(Throwable error) {
                return false;
            }

            @Override
            public long delayMs(int attempt, long baseDelayMs) {
                return 0;
            }
        };
    }

    /** 默认策略：指数退避，业务校验类异常不重试。 */
    static RetryPolicy exponential() {
        return new ExponentialBackoffRetry();
    }
}
