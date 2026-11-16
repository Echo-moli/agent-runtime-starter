package com.agentruntime.autoconfigure;

import com.agentruntime.observability.Metrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;

import java.math.BigDecimal;
import java.util.function.Supplier;

/**
 * 把引擎指标接到 Micrometer（M8）。
 *
 * <p>{@code core} 里那套 {@code Metrics} SPI 是零依赖的（{@code SimpleMetrics} 自己拼 Prometheus 文本），
 * 到了 Spring 应用里就该交给 Micrometer——因为应用已有的 {@code /actuator/prometheus}、
 * Grafana 看板、告警规则全都挂在它上面，引擎的指标必须落到同一个注册表里，
 * 否则运维要维护两套采集。
 *
 * <h2>指标名与标签语义<b>保持与 M4 完全一致</b></h2>
 * {@code agent.tasks} / {@code agent.steps} / {@code agent.cost} / {@code agent.tasks.active}，
 * 标签 {@code status} / {@code type} / {@code tool}。换实现不换语义，
 * 这样 M4 里写好的看板与告警不需要动一行。
 *
 * <h2>金额为什么可以直接 double</h2>
 * Micrometer 的计数器是 {@code DoubleAdder}，看起来违背了「金额不要用 double」。
 * 但这里要区分两种精度诉求：
 * <ul>
 *   <li><b>账本</b>要严格到分：这是 {@code task.total_cost NUMERIC(12,4)} 的职责，
 *       由数据库在存储侧原子累加，一分不差；</li>
 *   <li><b>指标</b>要的是趋势：看板上的曲线、环比、告警阈值，误差在 1e-15 量级完全可以接受。</li>
 * </ul>
 * 把账本和指标分开，就不必为了指标去自定义一个 BigDecimal 累加器。
 * 反过来说：<b>永远不要用指标数据去做对账</b>——它本来就不是为此设计的。
 */
public final class MicrometerMetrics implements Metrics {

    private final MeterRegistry registry;

    public MicrometerMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Counter counter(String name, String... tags) {
        io.micrometer.core.instrument.Counter counter = registry.counter(name, toTags(tags));
        return new Counter() {
            @Override
            public void increment() {
                counter.increment();
            }

            @Override
            public void add(long delta) {
                counter.increment(delta);
            }

            @Override
            public void addAmount(BigDecimal amount) {
                if (amount != null) {
                    counter.increment(amount.doubleValue());
                }
            }
        };
    }

    @Override
    public Timer timer(String name, String... tags) {
        io.micrometer.core.instrument.Timer timer = registry.timer(name, toTags(tags));
        return new Timer() {
            @Override
            public Sample start() {
                io.micrometer.core.instrument.Timer.Sample sample =
                        io.micrometer.core.instrument.Timer.start(registry);
                return () -> sample.stop(timer);
            }

            /**
             * 直接记录已量过的时长。
             *
             * <p>引擎在步边界上已经量过耗时（还要写进事件），这里必须用 {@code record}
             * 而不是再包一次计时——两个计时器会有微小差异，对不上账时很难解释。
             */
            @Override
            public void record(long durationMs) {
                timer.record(java.time.Duration.ofMillis(Math.max(0, durationMs)));
            }
        };
    }

    @Override
    public void gauge(String name, Supplier<Number> supplier, String... tags) {
        Gauge.builder(name, supplier, s -> {
            Number value = s.get();
            return value == null ? Double.NaN : value.doubleValue();
        }).tags(toTags(tags)).register(registry);
    }

    /**
     * Micrometer 的 HELP 文案只能在<b>建 meter 时</b>给（{@code Meter.Builder#description}），
     * 事后无法补写，所以这里只能忽略。
     *
     * <p>这也是个提醒：{@code describe()} 在 core 的 SPI 里是可选的，
     * 真正的可读性应该来自指标名本身（{@code agent.cost} 比 {@code agent.cost.total} 好）。
     */
    @Override
    public void describe(String name, String help) {
        // 见方法注释：Micrometer 不支持事后补 description
    }

    private static Iterable<Tag> toTags(String... tags) {
        return tags == null || tags.length == 0 ? Tags.empty() : Tags.of(tags);
    }
}
