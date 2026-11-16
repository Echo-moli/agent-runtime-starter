package com.agentruntime.samples.eval;

import com.agentruntime.api.AgentPlan;
import com.agentruntime.eval.EvalCase;
import com.agentruntime.eval.EvalReport;
import com.agentruntime.eval.EvalRunner;
import com.agentruntime.eval.JudgeMode;
import com.agentruntime.exception.EvalGateFailedException;
import com.agentruntime.samples.eval.reporting.ReportAgent;
import com.agentruntime.samples.eval.reporting.ReportEvalCases;
import com.agentruntime.samples.refund.RefundAgent;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * CI 门禁入口（M5）：通过率低于阈值就让构建失败。
 *
 * <p>由 {@code agent-runtime-samples/pom.xml} 里的 {@code exec-maven-plugin} 在 {@code verify}
 * 阶段调用：
 *
 * <pre>
 *   mvn verify                        # 用默认门槛 1.0 跑
 *   mvn verify -Deval.threshold=0.9   # 放宽/收紧
 *   mvn verify -Deval.skip=true       # 紧急跳过（且请在 PR 里说明原因）
 * </pre>
 *
 * <h2>为什么这里是「另一个 main」，而不是让 M5Demo 兼做门禁</h2>
 * demo 的职责是<b>证明机制有效</b>，它包括「故意跑一个坏版本让门禁红」这一步；
 * 门禁的职责是<b>判断这次提交能不能进主干</b>，它看到的必须只有正常版本。
 * 两者混在一个入口里，早晚会出现「为了把 demo 跑完而放行」的改动——
 * 门禁一旦有了破例的口子，就只剩形式。
 */
public final class EvalGate {

    /** 默认门槛：确定性断言必须 100% 通过。 */
    private static final double DEFAULT_THRESHOLD = 1.0;

    public static void main(String[] args) {
        double threshold = threshold(args);
        double maxCost = decimal(System.getProperty("eval.maxCost"), 0.30);

        System.out.println("================================================================");
        System.out.println(" M5 评测门禁 · 门槛 " + String.format("%.0f%%", threshold * 100)
                + " · 成本上限 " + maxCost + " 元/批");
        System.out.println("================================================================\n");

        EvalReport report = EvalRunner.builder(new RefundEvalFixture(
                        RefundEvalOrders.ALL, EvalGate::planFor))
                // judge 只做参考：CI 的红绿只由可重放的确定性断言决定（见 JudgeMode）
                .judge(new LexicalJudge())
                .judgeMode(JudgeMode.ADVISORY)
                .timeout(Duration.ofSeconds(30))
                .build()
                .run("ci-gate", RefundEvalCases.all());

        System.out.println(report.render());
        writeMarkdown(report);

        try {
            report.assertGate(threshold, RefundEvalCases.all().size());
        } catch (EvalGateFailedException e) {
            gateFailed(e.getMessage());
        }

        BigDecimal cost = report.totalCost();
        if (cost.compareTo(BigDecimal.valueOf(maxCost)) > 0) {
            // 成本也是门禁：通过率绿但账单翻倍，同样是回退
            gateFailed("本批评测花费 " + cost.stripTrailingZeros().toPlainString()
                    + " 元，超过上限 " + maxCost + " 元");
        }

        System.out.println("[评测门禁] 退款域通过：通过率 "
                + String.format("%.1f%%", report.passRate() * 100)
                + "，花费 " + cost.stripTrailingZeros().toPlainString() + " 元");

        // P2 第二业务域：同一套 EvalRunner 换一套 fixture + cases 就能跑，
        // 证明评测框架与退款业务解耦；门禁对两个域一视同仁。
        runReportingDomain(threshold);
    }

    private static void runReportingDomain(double threshold) {
        System.out.println("\n[评测门禁] 第二业务域：报表查询（验证 EvalRunner 泛化）");
        EvalReport report = EvalRunner.builder(new ReportAgent.Fixture())
                .judgeMode(JudgeMode.OFF)   // 报表域没有 judge 用例，关掉避免分母噪声
                .timeout(Duration.ofSeconds(30))
                .build()
                .run("ci-gate-reporting", ReportEvalCases.all());
        System.out.println(report.render());
        try {
            report.assertGate(threshold, ReportEvalCases.all().size());
        } catch (EvalGateFailedException e) {
            gateFailed("报表域：" + e.getMessage());
        }
        System.out.println("[评测门禁] 报表域通过：通过率 "
                + String.format("%.1f%%", report.passRate() * 100));
    }

    /**
     * 把 Markdown 版报告落到 {@code target/}，作为构建产物归档。
     *
     * <p>为什么要落盘而不是只打日志：CI 的日志会随构建记录过期，而「这个月通过率和
     * 单条成本怎么变的」是要跨时间看的。每次 verify 留一份，趋势就不用再猜。
     * 路径可用 {@code -Deval.report=...} 覆盖。
     */
    private static void writeMarkdown(EvalReport report) {
        Path path = Path.of(System.getProperty("eval.report", "target/eval-report.md"));
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, report.toMarkdown(), java.nio.charset.StandardCharsets.UTF_8);
            System.out.println("[评测门禁] Markdown 报告：" + path.toAbsolutePath());
        } catch (IOException e) {
            // 归档失败不该让门禁失败：门禁的结论是红绿，不是产物完整性
            System.err.println("[评测门禁] 报告落盘失败（忽略）：" + e.getMessage());
        }
    }

    /**
     * 失败时<b>不</b>调 {@code System.exit}。
     *
     * <p>{@code exec:java} 与 Maven 跑在同一个 JVM 里，直接退出会绕过 Maven 的收尾与报告，
     * CI 上看到的是一个没有上下文的进程退出码；抛异常则让插件干净地报 BUILD FAILURE，
     * 单独用 {@code java -cp ... EvalGate} 跑时未捕获异常同样是 exit code 1 —— 两种用法都对。
     */
    private static void gateFailed(String message) {
        System.err.println("[评测门禁] 失败：" + message);
        System.err.println("[评测门禁] 提示：先看上面的「失败明细」——轨迹里有 agent 实际走过的每一步。");
        throw new IllegalStateException("评测门禁失败：" + message);
    }

    private static AgentPlan planFor(EvalCase evalCase) {
        String refundTool = evalCase.tags().contains(RefundEvalCases.TAG_APPROVAL)
                ? "refundRequiringApproval"
                : "refund";
        return new RefundAgent(refundTool);
    }

    private static double threshold(String[] args) {
        String raw = args.length > 0 ? args[0] : System.getProperty("eval.threshold");
        if (raw == null || raw.isBlank()) {
            return DEFAULT_THRESHOLD;
        }
        double value = Double.parseDouble(raw);
        if (value < 0 || value > 1) {
            throw new IllegalArgumentException("eval.threshold 必须在 0..1 之间，实际 " + value);
        }
        return value;
    }

    private static double decimal(String raw, double fallback) {
        return raw == null || raw.isBlank() ? fallback : Double.parseDouble(raw);
    }
}
