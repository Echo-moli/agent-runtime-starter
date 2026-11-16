package com.agentruntime.engine;

import com.agentruntime.exception.HumanConfirmRequiredException;
import com.agentruntime.exception.IdempotencyInDoubtException;
import com.agentruntime.exception.ToolNotFoundException;
import com.agentruntime.exception.TransientFailureException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/** 重试分类：业务拒绝/配置错误绝不重试，基础设施抖动才退避；退避有上限不溢出。 */
class ExponentialBackoffRetryTest {

    private final RetryPolicy retry = new ExponentialBackoffRetry(1_000L);

    @Test
    void 业务与配置异常不重试() {
        assertFalse(retry.retryable(new IllegalArgumentException("参数错")));
        assertFalse(retry.retryable(new IllegalStateException("业务不允许")));
        assertFalse(retry.retryable(new ToolNotFoundException("没这个工具")));
        assertFalse(retry.retryable(new HumanConfirmRequiredException("refund", "等人")));
        assertFalse(retry.retryable(new IdempotencyInDoubtException("k", "t")));
    }

    @Test
    void 基础设施异常可重试_且识别最内层原因() {
        assertTrue(retry.retryable(new IOException("网络断")));
        assertTrue(retry.retryable(new TimeoutException("超时")));
        assertTrue(retry.retryable(new RuntimeException("包装", new IOException("根因"))));
    }

    @Test
    void 显式声明的瞬时故障可重试() {
        assertTrue(retry.retryable(new TransientFailureException("业务说没发生")));
    }

    @Test
    void 退避指数增长但不超过上限_且大attempt不溢出() {
        assertEquals(0L, retry.delayMs(0, 100));
        assertEquals(100L, retry.delayMs(1, 100));
        assertEquals(200L, retry.delayMs(2, 100));
        assertEquals(800L, retry.delayMs(4, 100));
        assertEquals(1_000L, retry.delayMs(5, 100));      // 1600 被夹到上限
        assertEquals(1_000L, retry.delayMs(100, 100));    // 指数再大也不溢出
        assertEquals(0L, retry.delayMs(3, 0));            // base=0 不延迟
    }
}
