package com.agentruntime.observability;

import java.math.BigDecimal;
import java.util.function.Supplier;

/** 默认指标实现：全部空转（不配就零开销）。 */
public final class NoopMetrics implements Metrics {

    public static final NoopMetrics INSTANCE = new NoopMetrics();

    private NoopMetrics() {
    }

    private static final Counter COUNTER = new Counter() {
        @Override
        public void increment() {
        }

        @Override
        public void add(long delta) {
        }

        @Override
        public void addAmount(BigDecimal amount) {
        }
    };

    private static final Timer TIMER = new Timer() {
        @Override
        public Sample start() {
            return () -> {
            };
        }

        @Override
        public void record(long durationMs) {
        }
    };

    @Override
    public Counter counter(String name, String... tags) {
        return COUNTER;
    }

    @Override
    public Timer timer(String name, String... tags) {
        return TIMER;
    }

    @Override
    public void gauge(String name, Supplier<Number> supplier, String... tags) {
    }
}
