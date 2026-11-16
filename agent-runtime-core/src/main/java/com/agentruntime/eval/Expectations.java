package com.agentruntime.eval;

import com.agentruntime.domain.TaskStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 确定性断言工厂（M5）。
 *
 * <h2>为什么断言只允许「可确定性重放」的谓词</h2>
 * 评测要进 CI，就必须满足一条硬要求：<b>同样的代码，跑一百次结论一样</b>。
 * 因此这里的断言全部只读引擎落库的事实（终态、事件账本、成本累计），
 * 不含时间、不含随机、不含模型输出。凡是「像不像人话」这类判断，
 * 一律走 {@link Judge} 并且默认<b>不阻断</b>构建——理由见 {@link JudgeMode}。
 */
public final class Expectations {

    private Expectations() {
    }

    /** 终态等于期望。 */
    public static Assertion status(TaskStatus expected) {
        return new StatusIs(expected);
    }

    /** 终态属于给定集合之一（同一个 case 允许多种正确结局时用）。 */
    public static Assertion statusIn(TaskStatus... allowed) {
        return new StatusIn(allowed.clone());
    }

    /**
     * 成功的工具调用序列<b>完全等于</b>期望序列。
     *
     * <p>这是最有价值的一条：它同时锁住「有没有漏调」「有没有多调」「顺序对不对」。
     * 「直接退款不看订单状态」这类回退，终态可能依然正确，只有序列能抓出来。
     */
    public static Assertion tools(String... expected) {
        return new ToolsExactly(expected.clone());
    }

    /** 工具调用序列<b>以</b>给定前缀开始（后面允许多跑几步时用）。 */
    public static Assertion startsWithTools(String... prefix) {
        return new ToolsPrefix(prefix.clone());
    }

    /** 某工具成功执行恰好 {@code times} 次。 */
    public static Assertion toolCount(String toolName, int times) {
        return new ToolCount(toolName, times);
    }

    /**
     * 有副作用的工具最多成功一次——防重复扣款不变量。
     *
     * <p>单独成一个工厂而不是写 {@code toolCount(x,1)}，是因为它表达的是
     * <b>安全性质</b>而不是业务期望：任何退款/扣款/发货类工具都该被这条锁死，
     * 失败分类也必须是资损级的 {@link FailureKind#SIDE_EFFECT}，不能被平均进普通用例失败里。
     */
    public static Assertion atMostOnce(String... tools) {
        return new AtMostOnce(tools.clone());
    }

    /** 这些工具<b>从未成功执行</b>（例如不可退款订单绝不能调 refund）。 */
    public static Assertion neverCalled(String... tools) {
        return new NeverCalled(tools.clone());
    }

    /** 结果文本包含全部片段。 */
    public static Assertion resultContains(String... fragments) {
        return new ResultContains(fragments.clone());
    }

    /** 错误信息包含全部片段（失败路径的 case 用）。 */
    public static Assertion errorContains(String... fragments) {
        return new ErrorContains(fragments.clone());
    }

    /** 总成本不超过预算（元）。 */
    public static Assertion maxCost(BigDecimal budget) {
        return new MaxCost(budget);
    }

    /** 总成本不超过预算，字符串写法便于在 case 表里写 {@code "0.02"}。 */
    public static Assertion maxCost(String budget) {
        return new MaxCost(new BigDecimal(budget));
    }

    /** 总 token 不超过预算。 */
    public static Assertion maxTokens(long budget) {
        return new MaxTokens(budget);
    }

    /** 决策步数不超过 {@code limit}（agent 原地打转的护栏）。 */
    public static Assertion maxDecisions(int limit) {
        return new MaxDecisions(limit);
    }

    /** 通用断言出口：内置工厂表达不了的长尾检查走这里，必须显式给分类。 */
    public static Assertion satisfies(String name, FailureKind kind, Predicate<EvalTrace> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return new Assertion() {
            @Override
            public String violation(EvalTrace trace) {
                return predicate.test(trace) ? null : name + " 未满足（实际轨迹：" + trace.trajectory() + "）";
            }

            @Override
            public FailureKind kind() {
                return kind;
            }

            @Override
            public String name() {
                return name;
            }
        };
    }

    // ---------------------------------------------------------------- 实现

    private record StatusIs(TaskStatus expected) implements Assertion {
        @Override
        public String violation(EvalTrace trace) {
            if (Objects.equals(trace.status(), expected)) {
                return null;
            }
            return "终态期望 " + expected + "，实际 " + trace.status()
                    + (trace.error() == null ? "" : "（error=" + trace.error() + "）");
        }

        @Override
        public FailureKind kind() {
            return FailureKind.STATUS_MISMATCH;
        }

        @Override
        public String name() {
            return "status=" + expected;
        }
    }

    private record StatusIn(List<TaskStatus> allowed) implements Assertion {
        StatusIn(TaskStatus[] allowed) {
            this(Arrays.asList(allowed));
        }

        @Override
        public String violation(EvalTrace trace) {
            return allowed.contains(trace.status())
                    ? null
                    : "终态期望属于 " + allowed + "，实际 " + trace.status();
        }

        @Override
        public FailureKind kind() {
            return FailureKind.STATUS_MISMATCH;
        }

        @Override
        public String name() {
            return "status in " + allowed;
        }
    }

    private record ToolsExactly(List<String> expected) implements Assertion {
        ToolsExactly(String[] expected) {
            this(List.of(expected));
        }

        @Override
        public String violation(EvalTrace trace) {
            List<String> actual = trace.toolCalls();
            if (actual.equals(expected)) {
                return null;
            }
            return "工具序列期望 " + expected + "，实际 " + actual;
        }

        @Override
        public FailureKind kind() {
            return FailureKind.TOOL_SEQUENCE;
        }

        @Override
        public String name() {
            return "tools=" + expected;
        }
    }

    private record ToolsPrefix(List<String> prefix) implements Assertion {
        ToolsPrefix(String[] prefix) {
            this(List.of(prefix));
        }

        @Override
        public String violation(EvalTrace trace) {
            List<String> actual = trace.toolCalls();
            if (actual.size() < prefix.size() || !actual.subList(0, prefix.size()).equals(prefix)) {
                return "工具序列期望以 " + prefix + " 开始，实际 " + actual;
            }
            return null;
        }

        @Override
        public FailureKind kind() {
            return FailureKind.TOOL_SEQUENCE;
        }

        @Override
        public String name() {
            return "tools^=" + prefix;
        }
    }

    private record ToolCount(String toolName, int times) implements Assertion {
        @Override
        public String violation(EvalTrace trace) {
            long actual = trace.successes(toolName);
            if (actual == times) {
                return null;
            }
            return toolName + " 成功次数期望 " + times + "，实际 " + actual
                    + "（含失败尝试共 " + trace.attempts(toolName) + " 次）";
        }

        @Override
        public FailureKind kind() {
            return FailureKind.TOOL_SEQUENCE;
        }

        @Override
        public String name() {
            return toolName + "×" + times;
        }
    }

    private record AtMostOnce(List<String> tools) implements Assertion {
        AtMostOnce(String[] tools) {
            this(List.of(tools));
        }

        @Override
        public String violation(EvalTrace trace) {
            List<String> duplicated = new ArrayList<>();
            for (String tool : tools) {
                if (trace.successes(tool) > 1) {
                    duplicated.add(tool + "×" + trace.successes(tool));
                }
            }
            return duplicated.isEmpty()
                    ? null
                    : "副作用重复执行（资损风险）：" + duplicated;
        }

        @Override
        public FailureKind kind() {
            return FailureKind.SIDE_EFFECT;
        }

        @Override
        public String name() {
            return "atMostOnce" + tools;
        }
    }

    private record NeverCalled(List<String> tools) implements Assertion {
        NeverCalled(String[] tools) {
            this(List.of(tools));
        }

        @Override
        public String violation(EvalTrace trace) {
            List<String> hit = tools.stream()
                    .filter(t -> trace.successes(t) > 0)
                    .collect(Collectors.toList());
            return hit.isEmpty() ? null : "这些工具不该被调用却调用了：" + hit;
        }

        @Override
        public FailureKind kind() {
            return FailureKind.SIDE_EFFECT;
        }

        @Override
        public String name() {
            return "never" + tools;
        }
    }

    private record ResultContains(List<String> fragments) implements Assertion {
        ResultContains(String[] fragments) {
            this(List.of(fragments));
        }

        @Override
        public String violation(EvalTrace trace) {
            String text = trace.resultText();
            List<String> missing = fragments.stream()
                    .filter(f -> !text.contains(f))
                    .collect(Collectors.toList());
            return missing.isEmpty() ? null : "结果文本缺少 " + missing + "，实际为「" + text + "」";
        }

        @Override
        public FailureKind kind() {
            return FailureKind.RESULT_CONTENT;
        }

        @Override
        public String name() {
            return "result∋" + fragments;
        }
    }

    private record ErrorContains(List<String> fragments) implements Assertion {
        ErrorContains(String[] fragments) {
            this(List.of(fragments));
        }

        @Override
        public String violation(EvalTrace trace) {
            String text = trace.errorText();
            List<String> missing = fragments.stream()
                    .filter(f -> !text.contains(f))
                    .collect(Collectors.toList());
            return missing.isEmpty() ? null : "错误信息缺少 " + missing + "，实际为「" + text + "」";
        }

        @Override
        public FailureKind kind() {
            return FailureKind.ENGINE_ERROR;
        }

        @Override
        public String name() {
            return "error∋" + fragments;
        }
    }

    private record MaxCost(BigDecimal budget) implements Assertion {
        @Override
        public String violation(EvalTrace trace) {
            if (trace.totalCost().compareTo(budget) <= 0) {
                return null;
            }
            return "总成本 " + trace.totalCost().stripTrailingZeros().toPlainString()
                    + " 元超预算 " + budget.stripTrailingZeros().toPlainString() + " 元";
        }

        @Override
        public FailureKind kind() {
            return FailureKind.BUDGET_EXCEEDED;
        }

        @Override
        public String name() {
            return "cost≤" + budget.stripTrailingZeros().toPlainString();
        }
    }

    private record MaxTokens(long budget) implements Assertion {
        @Override
        public String violation(EvalTrace trace) {
            return trace.totalTokens() <= budget
                    ? null
                    : "总 token " + trace.totalTokens() + " 超预算 " + budget;
        }

        @Override
        public FailureKind kind() {
            return FailureKind.BUDGET_EXCEEDED;
        }

        @Override
        public String name() {
            return "tokens≤" + budget;
        }
    }

    private record MaxDecisions(int limit) implements Assertion {
        @Override
        public String violation(EvalTrace trace) {
            long actual = trace.decisions();
            return actual <= limit ? null : "决策步数 " + actual + " 超限 " + limit;
        }

        @Override
        public FailureKind kind() {
            return FailureKind.STEP_LIMIT;
        }

        @Override
        public String name() {
            return "decisions≤" + limit;
        }
    }
}
