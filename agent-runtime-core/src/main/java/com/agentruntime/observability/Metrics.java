package com.agentruntime.observability;

import java.math.BigDecimal;
import java.util.function.Supplier;

/**
 * 指标出口（DESIGN.md §6 M4：{@code agent.tasks.active} / {@code agent.steps.total} / {@code agent.cost.total}）。
 *
 * <h2>名字里不要自带 total</h2>
 * 指标名用 Micrometer 风格的点分隔（{@code agent.cost}），导出成 Prometheus 时计数器的后缀
 * {@code _total} 由导出格式补（变成 {@code agent_cost_total}）。如果内部名字已经叫
 * {@code agent.cost.total}，导出来就是 {@code agent_cost_total_total}——线上真的见过这种名字。
 *
 * <p>标签用 {k, v, k, v} 变参传入，成对校验。实现必须容忍高并发调用（引擎每步都会调）。
 */
public interface Metrics {

    /** 单调递增计数。{@code addAmount} 用于金额，避免用 double 累加钱。 */
    interface Counter {
        void increment();

        void add(long delta);

        void addAmount(BigDecimal amount);
    }

    /** 计时器；用 try-with-resources 包住要测的代码，或直接 {@link #record(long)} 已知时长。 */
    interface Timer {
        Sample start();

        /**
         * 直接记录一个已经量过的时长。
         *
         * <p>引擎在步边界上已经量过耗时（还要写进事件），再包一层 try-with-resources 就是重复计时；
         * 而且两个计时器会有微小差异，对不上账时很难解释。
         */
        void record(long durationMs);

        interface Sample extends AutoCloseable {
            void stop();

            @Override
            default void close() {
                stop();
            }
        }
    }

    Counter counter(String name, String... tags);

    Timer timer(String name, String... tags);

    /** 注册一个回调式 gauge（如当前活跃任务数）；重复注册同名同标签会覆盖。 */
    void gauge(String name, Supplier<Number> supplier, String... tags);

    /** 给指标补一句 HELP 文案（可选；不调就用名字兜底）。 */
    default void describe(String name, String help) {
    }

    static Metrics noop() {
        return NoopMetrics.INSTANCE;
    }
}
