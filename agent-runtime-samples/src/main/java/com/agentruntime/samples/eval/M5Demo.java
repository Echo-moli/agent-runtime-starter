package com.agentruntime.samples.eval;

import com.agentruntime.api.AgentPlan;
import com.agentruntime.eval.EvalCase;
import com.agentruntime.eval.EvalOutcome;
import com.agentruntime.eval.EvalReport;
import com.agentruntime.eval.EvalRunner;
import com.agentruntime.eval.FailureKind;
import com.agentruntime.eval.JudgeMode;
import com.agentruntime.exception.EvalGateFailedException;
import com.agentruntime.samples.refund.RefundAgent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * M5 验收（DESIGN.md §6 M5 / §12）：评测门禁。
 *
 * <pre>
 *   ① 20 条退款用例，基线 100% 通过，报告给出通过率 / 分类 / 单条成本
 *   ② JudgeMode.OFF：声明了 judge 的 3 条转 SKIP，通过率分母随之收缩（不虚高）
 *   ③ JudgeMode.BLOCKING：同一条 judge 判否会让构建红 —— 证明「默认不阻断」是刻意的
 *   ④ 把订单状态校验删掉（真实回退）→ 4 条工具序列失败 + 1 条成本超预算，门禁拦截
 *   ⑤ 阈值语义：0.75 的通过率在 0.9 门槛下 exitCode=1，在 0.7 门槛下放行
 * </pre>
 *
 * <p>全程内存仓储 + 同步执行 + 确定性断言，所以本 demo 跑一百次输出一致——
 * 这正是它能进 CI 的前提。
 */
public final class M5Demo {

    private static final List<String> FAILURES = new ArrayList<>();

    public static void main(String[] args) {
        System.out.println("================================================================");
        System.out.println(" Agent Runtime M5 — 评测门禁（确定性断言进 CI，judge 只做参考）");
        System.out.println("================================================================\n");

        List<EvalCase> cases = RefundEvalCases.all();
        System.out.printf("用例集：%d 条（退款状态矩阵 %d 条 + 异常输入 + 人工闸门 + 成本 + 账本）%n%n",
                cases.size(), 7);

        EvalReport baseline = run(false, JudgeMode.ADVISORY);
        section1Baseline(baseline, cases);
        section2JudgeOff();
        section3JudgeBlocking();
        EvalReport regressed = section4Regression();
        section5Threshold(regressed);

        System.out.println("\n================================================================");
        if (FAILURES.isEmpty()) {
            System.out.println(" M5 验收通过：" + cases.size() + " 条用例基线全绿，回退被拦截 "
                    + regressed.failed() + " 条");
        } else {
            System.out.println(" M5 验收失败 " + FAILURES.size() + " 项：");
            FAILURES.forEach(f -> System.out.println("   - " + f));
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- ①

    private static void section1Baseline(EvalReport report, List<EvalCase> cases) {
        System.out.println("[1/5] 基线：正常版 RefundAgent（ADVISORY judge）");
        System.out.println(report.render());

        check("20 条用例全部跑完（实际 " + report.total() + "）", report.total() == cases.size());
        check("基线 0 失败（实际 " + report.failed() + "）", report.failed() == 0);
        check("基线通过率 100%（实际 " + pct(report.passRate()) + "）", report.passRate() == 1.0);
        check("基线门禁在 100% 门槛下放行", report.gate(1.0));
        check("报告带得出总成本与总 token（" + money(report.totalCost()) + " 元 / "
                + report.totalTokens() + " tok）",
                report.totalCost().signum() > 0 && report.totalTokens() > 0);

        Optional<EvalOutcome> judged = report.outcomes().stream()
                .filter(o -> "EVAL-16-judge-polite-tone".equals(o.caseId()))
                .findFirst();
        check("EVAL-16 的 judge 判否只记为「仅参考」，verdict 仍是 PASS",
                judged.isPresent() && judged.get().passed()
                        && judged.get().judgeNote().contains("仅参考"));
        System.out.println("      judge 旁注：" + judged.map(EvalOutcome::judgeNote).orElse("-"));
        System.out.println();
    }

    // ---------------------------------------------------------------- ②

    private static void section2JudgeOff() {
        System.out.println("[2/5] JudgeMode.OFF：judge 用例转 SKIP，不计入通过率分母");
        EvalReport report = run(false, JudgeMode.OFF);
        System.out.println("      总数 " + report.total() + "，通过 " + report.passed()
                + "，跳过 " + report.skipped() + "，通过率 " + pct(report.passRate())
                + "（分母 " + report.evaluated() + "）");
        check("3 条 judge 用例被跳过（实际 " + report.skipped() + "）", report.skipped() == 3);
        check("分母收缩为 17（实际 " + report.evaluated() + "）", report.evaluated() == 17);
        check("跳过不计入分母：通过率仍是 100%", report.passRate() == 1.0);
        // 反面教材：如果人工把 judge 全删了凑绿，至少 assertGate 的 minCases 还能兜住
        boolean rejected = false;
        try {
            report.assertGate(1.0, 20);
        } catch (EvalGateFailedException e) {
            rejected = true;
            System.out.println("      minCases 护栏：" + e.getMessage());
        }
        check("用例被大量跳过时 assertGate(minCases) 会拒绝通过", rejected);
        System.out.println();
    }

    // ---------------------------------------------------------------- ③

    private static void section3JudgeBlocking() {
        System.out.println("[3/5] JudgeMode.BLOCKING：把 judge 当门禁的后果");
        EvalReport report = run(false, JudgeMode.BLOCKING);
        System.out.println("      通过 " + report.passed() + " / " + report.total()
                + "，通过率 " + pct(report.passRate()) + "，失败分类 " + report.failureSummary());
        check("judge 判否变成 1 条 FAIL（实际 " + report.failed() + "）", report.failed() == 1);
        check("失败分类是 JUDGE_REJECTED",
                report.failureBreakdown().getOrDefault(FailureKind.JUDGE_REJECTED, 0L) == 1L);
        boolean blocked = false;
        try {
            report.assertGate(1.0, 20);
        } catch (EvalGateFailedException e) {
            blocked = true;
            System.out.println("      门禁消息：" + e.getMessage());
        }
        check("同一条 judge 在 BLOCKING 下会让构建红（这就是默认不用它的原因）", blocked);
        System.out.println();
    }

    // ---------------------------------------------------------------- ④

    private static EvalReport section4Regression() {
        System.out.println("[4/5] 回退拦截：删掉订单状态校验（RegressedRefundAgent）");
        EvalReport report = run(true, JudgeMode.ADVISORY);
        System.out.println(report.render());

        Map<FailureKind, Long> breakdown = report.failureBreakdown();
        check("回退被拦截 5 条（实际 " + report.failed() + "）", report.failed() == 5);
        check("4 条工具序列不符——不可退款订单被真的退了",
                breakdown.getOrDefault(FailureKind.TOOL_SEQUENCE, 0L) == 4L);
        check("1 条成本超预算——同一 case 多绕一步就变贵，传统单测看不见",
                breakdown.getOrDefault(FailureKind.BUDGET_EXCEEDED, 0L) == 1L);
        check("通过率跌到 75%（实际 " + pct(report.passRate()) + "）", report.passRate() == 0.75);

        List<String> failedIds = report.failures().stream().map(EvalOutcome::caseId).toList();
        check("失败的是 4 条 not-refundable + 1 条成本用例：" + failedIds,
                failedIds.size() == 5
                        && failedIds.stream().filter(id -> id.contains("not-refundable")).count() == 4);

        boolean blocked = false;
        try {
            report.assertGate(0.9, 20);
        } catch (EvalGateFailedException e) {
            blocked = true;
            System.out.println("      门禁消息：" + e.getMessage());
        }
        check("门槛 0.9 时回退被拦下", blocked);
        return report;
    }

    // ---------------------------------------------------------------- ⑤

    private static void section5Threshold(EvalReport regressed) {
        System.out.println("\n[5/5] 阈值语义：门禁是「可调的线」，不是「全绿或全红」");
        System.out.printf("      通过率 %.1f%%：gate(0.9)=%s，gate(0.7)=%s，exitCode(0.9)=%d%n",
                regressed.passRate() * 100, regressed.gate(0.9), regressed.gate(0.7),
                regressed.exitCode(0.9));
        check("0.75 < 0.9 → 不放行", !regressed.gate(0.9));
        check("0.75 ≥ 0.7 → 放行", regressed.gate(0.7));
        check("exitCode(0.9) = 1（CI 直接拿它当退出码）", regressed.exitCode(0.9) == 1);
        check("exitCode(0.7) = 0", regressed.exitCode(0.7) == 0);
        System.out.println("      注意：放行不等于合格。0.7 的门槛意味着允许 3 条资损类回退上线，"
                + "所以生产项目应把它设成 1.0。");
    }

    // ---------------------------------------------------------------- 基础设施

    /** 每批评测都用全新夹具，避免上一条用例的幂等记录影响下一条。 */
    private static EvalReport run(boolean regressed, JudgeMode judgeMode) {
        return EvalRunner.builder(new RefundEvalFixture(
                        RefundEvalOrders.ALL, evalCase -> planFor(evalCase, regressed)))
                .judge(new LexicalJudge())
                .judgeMode(judgeMode)
                .timeout(Duration.ofSeconds(30))
                .build()
                .run(regressed ? "regressed" : "baseline", RefundEvalCases.all());
    }

    /** 大额用例换用需审批的退款工具——这本身就是一条该被评测覆盖的接线差异。 */
    private static AgentPlan planFor(EvalCase evalCase, boolean regressed) {
        String refundTool = evalCase.tags().contains(RefundEvalCases.TAG_APPROVAL)
                ? "refundRequiringApproval"
                : "refund";
        return regressed ? new RegressedRefundAgent(refundTool) : new RefundAgent(refundTool);
    }

    private static void check(String name, boolean condition) {
        System.out.println((condition ? "   ✓ " : "   ✗ ") + name);
        if (!condition) {
            FAILURES.add(name);
        }
    }

    private static String pct(double rate) {
        return String.format("%.1f%%", rate * 100);
    }

    private static String money(java.math.BigDecimal value) {
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }
}
