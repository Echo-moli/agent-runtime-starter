package com.agentruntime.persistence;

import java.time.Duration;
import java.util.List;

/**
 * 幂等记录的巡检入口（M9）。
 *
 * <h2>为什么单独一个接口，而不是往 {@link IdempotencyStore} 里加方法</h2>
 * {@code IdempotencyStore} 是执行路径上的 SPI：它被引擎每步调用，改动它等于让所有自定义实现
 * 都要跟着改（Java 接口加方法会打断所有实现者，除非给默认实现——而默认实现返回空列表，
 * 会让「巡检结果是 0」与「实现不支持巡检」在监控上长得一模一样，这是最坏的静默失败）。
 *
 * <p>巡检是<b>只读的旁路能力</b>：给运维看「哪些在途未知已经放了太久，该去查账了」。
 * 所以它单独成接口、用 {@code ObjectProvider} 可选注入——没有实现就不显示这块信息，
 * 而不是假装巡检过了、一切正常。
 *
 * <h2>为什么只查 INIT，不自动清理</h2>
 * {@code INIT} 的语义是「这个副作用可能已经发生了，但我们不知道」。自动清理等于
 * 「假装没发生过」，下一次重试就是多退一笔钱。所以这里只提供<b>观测</b>：
 * 列出超期记录，由人或者对账系统去确认，确认后再走 {@code resume(retrySideEffect/result)}。
 * 一个会产生资损的自动化，不如一个人工确认的列表。
 */
public interface IdempotencyInspector {

    /**
     * 处于 {@code INIT} 且最后更新早于 {@code olderThan} 之前的记录。
     *
     * <p>按 {@code updatedAt} 升序（最老的排前面）：运维先看最久的那几笔。
     *
     * @param olderThan 多久没动过算「超期」；建议 10–30 分钟（覆盖一次慢网关调用 + 余量）
     * @param limit     最多返回多少条（端点不该一次吐出十万行）
     */
    List<IdempotencyRecord> findStale(Duration olderThan, int limit);

    /** 超期记录条数（用于告警阈值）。 */
    long countStale(Duration olderThan);
}
