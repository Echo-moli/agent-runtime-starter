package com.agentruntime.engine;

import com.agentruntime.exception.HumanConfirmRequiredException;
import com.agentruntime.exception.MaxStepsExceededException;
import com.agentruntime.exception.ToolNotFoundException;
import com.agentruntime.exception.ToolRegistrationException;

/**
 * 默认重试策略：指数退避 + 业务异常不重试。
 *
 * <p>分类原则（比退避算法本身重要得多）：
 * <ul>
 *   <li><b>业务拒绝</b>（{@link IllegalArgumentException} / {@link IllegalStateException} /
 *       {@link UnsupportedOperationException}）：参数错了或业务规则不允许，重试不会改变结论，
 *       只会把 1 秒的失败拖成 3 秒；</li>
 *   <li><b>配置错误</b>（{@link ToolNotFoundException} / {@link ToolRegistrationException} /
 *       {@link MaxStepsExceededException} / {@link HumanConfirmRequiredException}）：
 *       重试同样无意义；</li>
 *   <li><b>其余</b>（网络、超时、DB 连接）：交给退避重试。</li>
 * </ul>
 *
 * <p>退避公式：{@code baseDelayMs * 2^(attempt-1)}，上限 {@code maxDelayMs}。
 * 有上限很重要——没有上限的指数退避就是「等到用户挂电话」。
 */
public final class ExponentialBackoffRetry implements RetryPolicy {

    private final long maxDelayMs;

    public ExponentialBackoffRetry() {
        this(10_000L);
    }

    public ExponentialBackoffRetry(long maxDelayMs) {
        this.maxDelayMs = maxDelayMs;
    }

    @Override
    public boolean retryable(Throwable error) {
        Throwable root = rootCause(error);
        return !(root instanceof IllegalArgumentException)
                && !(root instanceof IllegalStateException)
                && !(root instanceof UnsupportedOperationException)
                && !(root instanceof NullPointerException)
                && !(root instanceof ToolNotFoundException)
                && !(root instanceof ToolRegistrationException)
                && !(root instanceof MaxStepsExceededException)
                && !(root instanceof HumanConfirmRequiredException)
                && !(root instanceof com.agentruntime.exception.IdempotencyInDoubtException);
    }

    @Override
    public long delayMs(int attempt, long baseDelayMs) {
        if (attempt <= 0 || baseDelayMs <= 0) {
            return 0;
        }
        long factor = 1L << Math.min(attempt - 1, 20); // 2^(attempt-1)，先夹住指数防溢出
        if (baseDelayMs > maxDelayMs / factor) {
            return maxDelayMs; // 乘法前判上界，避免溢出成负数
        }
        return Math.min(maxDelayMs, baseDelayMs * factor);
    }

    /** 取最内层原因：工具异常常被 {@code ToolInvocationException} 包了一层。 */
    public static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
