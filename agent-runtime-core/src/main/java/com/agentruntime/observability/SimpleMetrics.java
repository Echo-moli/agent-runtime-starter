package com.agentruntime.observability;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * 零依赖的指标实现，自带 Prometheus 文本导出（M4）。
 *
 * <p>生产环境用 Micrometer + {@code micrometer-registry-prometheus} 即可，
 * 但 {@code core} 的零依赖是硬约束，而「能被 /actuator/prometheus 抓走」这件事本身
 * 只依赖一段文本格式——所以这里自己实现了它。demo 用 JDK 的 {@code HttpServer}
 * 把 {@link #prometheusText()} 挂到 {@code /actuator/prometheus} 上，验收是真实 HTTP 抓取。
 *
 * <h2>三个实现细节，都是踩过才知道的</h2>
 * <ul>
 *   <li><b>金额用整数微元存</b>（1e-6 元）：{@code double} 累加 0.0001 一万次会漂，
 *       而对账差一分钱就够写事故报告了。渲染时再转成小数。</li>
 *   <li><b>计数器后缀 {@code _total} 由导出格式补</b>，内部名不要带 total，否则出现
 *       {@code agent_cost_total_total}。</li>
 *   <li><b>输出按名字排序</b>：抓取结果要能 diff。顺序随机的输出会让「这版和上版指标有啥变化」
 *       变成人工肉眼看 300 行。</li>
 * </ul>
 */
public final class SimpleMetrics implements Metrics {

    private enum Kind {COUNTER, SUMMARY, GAUGE}

    private final Map<String, Series> series = new ConcurrentHashMap<>();
    private final Map<String, Kind> kinds = new ConcurrentHashMap<>();
    private final Map<String, String> helps = new ConcurrentHashMap<>();

    @Override
    public void describe(String name, String help) {
        if (help != null && !help.isBlank()) {
            helps.put(name, help);
        }
    }

    @Override
    public Counter counter(String name, String... tags) {
        Series s = series(name, Kind.COUNTER, tags);
        return new Counter() {
            @Override
            public void increment() {
                s.addMicros(1_000_000L);
            }

            @Override
            public void add(long delta) {
                s.addMicros(delta * 1_000_000L);
            }

            @Override
            public void addAmount(BigDecimal amount) {
                if (amount == null || amount.signum() == 0) {
                    return;
                }
                s.addMicros(amount.movePointRight(6).setScale(0, RoundingMode.HALF_UP).longValueExact());
            }
        };
    }

    @Override
    public Timer timer(String name, String... tags) {
        Series s = series(name, Kind.SUMMARY, tags);
        return new Timer() {
            @Override
            public Sample start() {
                return new Sample() {
                    private final long startNanos = System.nanoTime();
                    private boolean stopped;

                    @Override
                    public void stop() {
                        if (stopped) {
                            return;
                        }
                        stopped = true;
                        s.observe(System.nanoTime() - startNanos);
                    }
                };
            }

            @Override
            public void record(long durationMs) {
                s.observe(durationMs * 1_000_000L);
            }
        };
    }

    @Override
    public void gauge(String name, Supplier<Number> supplier, String... tags) {
        series(name, Kind.GAUGE, tags).gauge = supplier;
    }

    /**
     * 当前累计值（测试断言用；不存在的指标返回 0）。
     *
     * <p>带标签的指标请传完整标签，例如 {@code value("agent.steps", "step_type", "TOOL_CALL",
     * "success", "true")}。
     */
    public double value(String name, String... tags) {
        Series s = series.get(key(name, tags));
        return s == null ? 0d : s.read();
    }

    /** Prometheus 文本格式（{@code text/plain; version=0.0.4}）。 */
    public String prometheusText() {
        Map<String, List<Series>> byName = new TreeMap<>();
        for (Series s : series.values()) {
            byName.computeIfAbsent(s.name, k -> new ArrayList<>()).add(s);
        }
        StringBuilder out = new StringBuilder(512);
        for (Map.Entry<String, List<Series>> entry : byName.entrySet()) {
            String name = entry.getKey();
            Kind kind = kinds.getOrDefault(name, Kind.COUNTER);
            String help = helps.getOrDefault(name, name);
            String prom = promName(name, kind);
            out.append("# HELP ").append(prom).append(' ').append(escapeHelp(help)).append('\n');
            out.append("# TYPE ").append(prom).append(' ').append(typeName(kind)).append('\n');
            entry.getValue().stream()
                    .sorted((a, b) -> a.labels.compareTo(b.labels))   // 稳定输出，方便 diff
                    .forEach(s -> s.render(out, prom, kind));
        }
        return out.toString();
    }

    // ------------------------------------------------------------------

    private Series series(String name, Kind kind, String... tags) {
        if (tags.length % 2 != 0) {
            throw new IllegalArgumentException("标签必须是 k,v 成对出现: " + name + " " + List.of(tags));
        }
        kinds.putIfAbsent(name, kind);
        return series.computeIfAbsent(key(name, tags), k -> new Series(name, labelString(tags)));
    }

    private static String key(String name, String... tags) {
        return name + labelString(tags);
    }

    private static String labelString(String... tags) {
        if (tags.length == 0) {
            return "{}";
        }
        Map<String, String> sorted = new TreeMap<>();
        for (int i = 0; i + 1 < tags.length; i += 2) {
            sorted.put(tags[i], tags[i + 1] == null ? "" : tags[i + 1]);
        }
        StringBuilder sb = new StringBuilder("{");
        sorted.forEach((k, v) -> {
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append(k).append("=\"").append(escapeLabel(v)).append('"');
        });
        return sb.append('}').toString();
    }

    private static String promName(String name, Kind kind) {
        String base = name.replace('.', '_').replace('-', '_');
        if (kind == Kind.COUNTER && !base.endsWith("_total")) {
            return base + "_total";
        }
        return base;
    }

    private static String typeName(Kind kind) {
        return switch (kind) {
            case COUNTER -> "counter";
            case SUMMARY -> "summary";
            case GAUGE -> "gauge";
        };
    }

    private static String escapeLabel(String v) {
        return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static String escapeHelp(String v) {
        return v.replace("\\", "\\\\").replace("\n", "\\n");
    }

    /** 整数微元 → 小数文本（整数时不带小数尾巴）。 */
    private static String decimal(long micros) {
        if (micros % 1_000_000L == 0) {
            return Long.toString(micros / 1_000_000L);
        }
        return BigDecimal.valueOf(micros, 6).stripTrailingZeros().toPlainString();
    }

    private static final class Series {

        final String name;
        final String labels;
        /** 计数器：整数微元（金额 1e-6 元；普通计数 1 个 = 1e6）。 */
        final LongAdder micros = new LongAdder();
        final LongAdder sumNanos = new LongAdder();
        final LongAdder count = new LongAdder();
        volatile Supplier<Number> gauge;

        Series(String name, String labels) {
            this.name = name;
            this.labels = labels;
        }

        void addMicros(long delta) {
            micros.add(delta);
        }

        void observe(long nanos) {
            sumNanos.add(nanos);
            count.increment();
        }

        double read() {
            return micros.sum() / 1_000_000d;
        }

        void render(StringBuilder out, String promName, Kind kind) {
            String tag = "{}".equals(labels) ? "" : labels;
            switch (kind) {
                case COUNTER -> out.append(promName).append(tag).append(' ')
                        .append(decimal(micros.sum())).append('\n');
                case SUMMARY -> {
                    BigDecimal seconds = BigDecimal.valueOf(sumNanos.sum(), 9)
                            .setScale(6, RoundingMode.HALF_UP);
                    out.append(promName).append("_seconds_sum").append(tag).append(' ')
                            .append(seconds.toPlainString()).append('\n');
                    out.append(promName).append("_seconds_count").append(tag).append(' ')
                            .append(count.sum()).append('\n');
                }
                case GAUGE -> {
                    Number n = readGauge();
                    out.append(promName).append(tag).append(' ').append(number(n)).append('\n');
                }
            }
        }

        private Number readGauge() {
            Supplier<Number> s = gauge;
            if (s == null) {
                return 0;
            }
            try {
                Number n = s.get();
                return n == null ? 0 : n;
            } catch (RuntimeException e) {
                // 抓取过程绝不能因为某个 supplier 抛错而整体失败
                return -1;
            }
        }

        private static String number(Number n) {
            if (n instanceof Double || n instanceof Float) {
                return BigDecimal.valueOf(n.doubleValue()).stripTrailingZeros().toPlainString();
            }
            return n.toString();
        }
    }

    /** 便于 demo 打印的调试快照。 */
    public Map<String, Double> snapshot() {
        Map<String, Double> snapshot = new LinkedHashMap<>();
        series.forEach((k, s) -> snapshot.put(s.name + s.labels, s.read()));
        return snapshot;
    }
}
