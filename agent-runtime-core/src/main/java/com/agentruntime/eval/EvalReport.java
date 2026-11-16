package com.agentruntime.eval;

import com.agentruntime.exception.EvalGateFailedException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 一次批量评测的结果（M5）：通过率 + 失败分类 + 成本 + 门禁判定。
 *
 * <h2>为什么报告要带成本，而不只是通过率</h2>
 * 因为「质量」和「花费」在 agent 里是同一条曲线的两端：把 {@code decide()} 里的
 * prompt 加长、把重试次数调高，通过率常常会涨，账单涨得更快。传统单测看不见后者，
 * 于是团队会在某个月发现成本翻倍却找不到是哪次提交干的。这里每条 case 都带成本，
 * 门禁可以同时锁「不低于 X%」和「不超过 Y 元」——这才是「成本可控」落到实处。
 */
public record EvalReport(String label, List<EvalOutcome> outcomes, long durationMs) {

    public EvalReport {
        outcomes = List.copyOf(outcomes);
    }

    public static EvalReport of(String label, List<EvalOutcome> outcomes, long durationMs) {
        return new EvalReport(label, outcomes, durationMs);
    }

    public int total() {
        return outcomes.size();
    }

    public long passed() {
        return outcomes.stream().filter(EvalOutcome::passed).count();
    }

    public long failed() {
        return outcomes.stream().filter(EvalOutcome::failed).count();
    }

    public long skipped() {
        return outcomes.stream().filter(EvalOutcome::skipped).count();
    }

    /** 计入分母的条数（= 总数 − 跳过）。 */
    public long evaluated() {
        return total() - skipped();
    }

    /**
     * 通过率 = 通过 / 计入分母的条数。
     *
     * <p>分母为 0（全部跳过）时返回 {@code 1.0} 并<b>不</b>报错——但 {@link #assertGate}
     * 会额外检查「至少跑了一条」，否则一个把用例全跳过的人会拿到满分。
     */
    public double passRate() {
        long evaluated = evaluated();
        return evaluated == 0 ? 1.0 : (double) passed() / evaluated;
    }

    public List<EvalOutcome> failures() {
        return outcomes.stream().filter(EvalOutcome::failed).collect(Collectors.toList());
    }

    /** 失败按分类计数（按枚举顺序，便于对比历次报告）。 */
    public Map<FailureKind, Long> failureBreakdown() {
        Map<FailureKind, Long> counts = new EnumMap<>(FailureKind.class);
        for (EvalOutcome outcome : outcomes) {
            if (outcome.failed() && outcome.kind() != null) {
                counts.merge(outcome.kind(), 1L, Long::sum);
            }
        }
        return counts;
    }

    public BigDecimal totalCost() {
        BigDecimal sum = BigDecimal.ZERO;
        for (EvalOutcome outcome : outcomes) {
            sum = sum.add(outcome.cost());
        }
        return sum;
    }

    public long totalTokens() {
        return outcomes.stream().mapToLong(EvalOutcome::tokens).sum();
    }

    /** 通过率是否达到门槛（0..1）。 */
    public boolean gate(double minPassRate) {
        return passRate() >= minPassRate;
    }

    /**
     * 门禁断言：不达标就抛 {@link EvalGateFailedException}，message 里点名失败的 case。
     *
     * <p>message 特意列出 caseId 与分类：CI 日志里第一屏就该能看出「是哪几条、坏在哪一维」，
     * 而不是让人从 300 行构建输出里翻。
     */
    public void assertGate(double minPassRate, int minCases) {
        if (evaluated() < minCases) {
            throw new EvalGateFailedException(
                    "评测只跑了 " + evaluated() + " 条（要求至少 " + minCases + " 条）："
                            + "用例被大量跳过时通过率没有意义", passRate(), minPassRate);
        }
        if (gate(minPassRate)) {
            return;
        }
        String detail = failures().stream()
                .map(o -> o.caseId() + "[" + (o.kind() == null ? "-" : o.kind().name()) + "]")
                .collect(Collectors.joining(", "));
        throw new EvalGateFailedException(
                String.format("评测通过率 %.1f%% 低于门槛 %.1f%%；失败 %d 条：%s",
                        passRate() * 100, minPassRate * 100, failed(), detail),
                passRate(), minPassRate);
    }

    /** 0 = 达标，1 = 不达标（给 {@code main} 直接当退出码用）。 */
    public int exitCode(double minPassRate) {
        return gate(minPassRate) ? 0 : 1;
    }

    // ---------------------------------------------------------------- 渲染

    /** 控制台可读报告。 */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("评测报告 · ").append(label).append('\n');
        sb.append("----------------------------------------------------------------\n");
        int idWidth = Math.max(8, outcomes.stream().mapToInt(o -> o.caseId().length()).max().orElse(8));
        for (EvalOutcome o : outcomes) {
            sb.append(String.format("%-5s %-" + idWidth + "s %6dms  %10s  %7dtok",
                    o.verdict(), o.caseId(), o.durationMs(), money(o.cost()), o.tokens()));
            if (o.judgeNote() != null && !o.judgeNote().isBlank()) {
                sb.append("  [judge] ").append(o.judgeNote());
            }
            sb.append('\n');
        }
        sb.append("----------------------------------------------------------------\n");
        sb.append(String.format("合计 %d 条：通过 %d / 失败 %d / 跳过 %d · 通过率 %.1f%% · "
                        + "总成本 %s 元 · 总 token %d · 墙钟 %dms%n",
                total(), passed(), failed(), skipped(), passRate() * 100,
                money(totalCost()), totalTokens(), durationMs));
        Map<FailureKind, Long> breakdown = failureBreakdown();
        if (!breakdown.isEmpty()) {
            sb.append("失败分类：");
            sb.append(breakdown.entrySet().stream()
                    .map(e -> e.getKey().name() + "(" + e.getKey().label() + ")×" + e.getValue())
                    .collect(Collectors.joining("，")));
            sb.append('\n');
        }
        appendDetails(sb);
        return sb.toString();
    }

    private void appendDetails(StringBuilder sb) {
        List<EvalOutcome> failures = failures();
        if (!failures.isEmpty()) {
            sb.append("\n失败明细\n");
            int i = 1;
            for (EvalOutcome o : failures) {
                sb.append(String.format("  %d) %s [%s]%n", i++, o.caseId(),
                        o.kind() == null ? "-" : o.kind().name()));
                sb.append("     原因：").append(o.message()).append('\n');
                if (o.trajectory() != null) {
                    sb.append("     轨迹：").append(o.trajectory()).append('\n');
                }
                if (o.taskId() != null) {
                    sb.append("     taskId：").append(o.taskId()).append('\n');
                }
            }
        }
        List<EvalOutcome> skipped = outcomes.stream().filter(EvalOutcome::skipped).toList();
        if (!skipped.isEmpty()) {
            sb.append("\n跳过（不计入通过率）\n");
            for (EvalOutcome o : skipped) {
                sb.append("  - ").append(o.caseId()).append("：").append(o.message()).append('\n');
            }
        }
    }

    /** Markdown 版，便于贴进 PR 评论或按次归档做趋势。 */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("### 评测报告 · ").append(label).append("\n\n");
        sb.append("| 结果 | case | 分类 | 成本(元) | token | 耗时(ms) |\n");
        sb.append("| --- | --- | --- | --- | --- | --- |\n");
        for (EvalOutcome o : outcomes) {
            sb.append("| ").append(o.verdict())
                    .append(" | ").append(o.caseId())
                    .append(" | ").append(o.kind() == null ? "-" : o.kind().name())
                    .append(" | ").append(money(o.cost()))
                    .append(" | ").append(o.tokens())
                    .append(" | ").append(o.durationMs())
                    .append(" |\n");
        }
        sb.append("\n**通过率 ").append(String.format("%.1f%%", passRate() * 100))
                .append("（").append(passed()).append('/').append(evaluated()).append("）")
                .append("，跳过 ").append(skipped())
                .append("，总成本 ").append(money(totalCost())).append(" 元")
                .append("，总 token ").append(totalTokens()).append("**\n");
        for (EvalOutcome o : failures()) {
            sb.append("\n- ❌ `").append(o.caseId()).append("` [")
                    .append(o.kind() == null ? "-" : o.kind().name()).append("] ")
                    .append(o.message());
        }
        return sb.append('\n').toString();
    }

    /** 报告里不带无意义的尾零：{@code 0.0031} 而不是 {@code 0.003100}。 */
    private static String money(BigDecimal value) {
        if (value == null || value.signum() == 0) {
            return "0";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    /** 按分类汇总的简表，demo 断言用。 */
    public List<String> failureSummary() {
        List<String> lines = new ArrayList<>();
        failureBreakdown().forEach((kind, count) ->
                lines.add(kind.name() + "×" + count + "：" + kind.label()));
        return lines;
    }
}
