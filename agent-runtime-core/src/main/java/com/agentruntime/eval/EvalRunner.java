package com.agentruntime.eval;

import com.agentruntime.api.TaskResult;
import com.agentruntime.api.TaskView;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 批量跑评测用例并出报告（M5）。
 *
 * <pre>{@code
 * EvalReport report = EvalRunner.builder(fixture)
 *         .judgeMode(JudgeMode.ADVISORY)
 *         .timeout(Duration.ofSeconds(30))
 *         .build()
 *         .run("refund-regression", cases);
 *
 * System.out.println(report.render());
 * report.assertGate(1.0, cases.size());   // CI：低于 100% 直接抛异常
 * }</pre>
 *
 * <h2>为什么逐条串行跑</h2>
 * 评测的产出是「是否回退」，它必须可复现。并行会引入共享资源争用（同一 H2 连接池、
 * 同一 mock 服务里的计数器），让偶发失败与真实回退混在一起——而评测报告一旦出现
 * 「不确定的红」，它就会被人忽略。真要快，先让夹具各用独立资源，再开并行开关，
 * 不能反过来用并行掩盖串行时的确定性。
 *
 * <h2>三条硬规矩</h2>
 * <ol>
 *   <li><b>每条 case 一套夹具</b>，跑完立刻关：状态不串味。</li>
 *   <li><b>断言全收集，不短路</b>：一条 case 多个维度同时坏了，报告要一次说清。</li>
 *   <li><b>judge 不默认阻断</b>：见 {@link JudgeMode}，默认 {@link JudgeMode#ADVISORY}。</li>
 * </ol>
 */
public final class EvalRunner {

    private final EvalFixture fixture;
    private final Judge judge;
    private final JudgeMode judgeMode;
    private final Duration timeout;
    private final Consumer<EvalOutcome> onOutcome;

    private EvalRunner(Builder b) {
        this.fixture = b.fixture;
        this.judge = b.judge == null ? Judge.NONE : b.judge;
        this.judgeMode = b.judgeMode;
        this.timeout = b.timeout;
        this.onOutcome = b.onOutcome;
    }

    public static Builder builder(EvalFixture fixture) {
        return new Builder(fixture);
    }

    public EvalReport run(List<EvalCase> cases) {
        return run("eval", cases);
    }

    /** 跑一批用例；{@code label} 进报告标题（例如 {@code refund-regression}）。 */
    public EvalReport run(String label, List<EvalCase> cases) {
        Objects.requireNonNull(cases, "cases");
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("评测用例为空：空报告的通过率永远是 100%，"
                    + "这比没有评测更危险——它会让人误以为有覆盖");
        }
        long start = System.nanoTime();
        List<EvalOutcome> outcomes = new ArrayList<>(cases.size());
        for (EvalCase evalCase : cases) {
            EvalOutcome outcome = runOne(evalCase);
            outcomes.add(outcome);
            if (onOutcome != null) {
                onOutcome.accept(outcome);
            }
        }
        long durationMs = (System.nanoTime() - start) / 1_000_000L;
        return EvalReport.of(label, outcomes, durationMs);
    }

    // ---------------------------------------------------------------- 单条

    private EvalOutcome runOne(EvalCase evalCase) {
        long start = System.nanoTime();
        String taskId = null;
        try (EvalFixture.Session session = fixture.open(evalCase)) {
            if (session == null || session.engine() == null || session.plan() == null) {
                return fail(evalCase, FailureKind.ENGINE_ERROR,
                        "夹具返回了空 session（engine/plan 为 null）", null, start);
            }
            String userId = session.userId() == null ? evalCase.userId() : session.userId();
            taskId = session.engine().submit(session.plan(), userId, evalCase.request());
            TaskResult result = session.engine().await(taskId, timeout);
            TaskView view = session.engine().getTask(taskId);
            EvalTrace trace = EvalTrace.of(evalCase.id(), view, result, elapsedMs(start));
            if (result == null) {
                return fail(evalCase, FailureKind.ENGINE_ERROR,
                        "await 超时（" + timeout.toMillis() + "ms）未拿到结果，任务可能卡住",
                        trace, start);
            }
            return judgeAndAssert(evalCase, trace, start);
        } catch (RuntimeException e) {
            // 单条 case 炸掉不该让整批没有报告：记成 ENGINE_ERROR 继续跑。
            // Error（OOM 等）不在这里吞掉——那是环境问题，评测结论已无意义。
            EvalTrace trace = EvalTrace.engineError(evalCase.id(), taskId, e, elapsedMs(start));
            return fail(evalCase, FailureKind.ENGINE_ERROR,
                    "引擎抛异常：" + e.getClass().getSimpleName() + ": " + e.getMessage(), trace, start);
        }
    }

    /** 断言 + judge → verdict。断言全收集，不短路。 */
    private EvalOutcome judgeAndAssert(EvalCase evalCase, EvalTrace trace, long start) {
        List<String> violations = new ArrayList<>();
        FailureKind kind = null;
        for (Assertion assertion : evalCase.assertions()) {
            String violation;
            try {
                violation = assertion.violation(trace);
            } catch (RuntimeException e) {
                violation = "断言自身抛异常：" + e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            if (violation != null) {
                if (kind == null) {
                    kind = assertion.kind();
                }
                violations.add("[" + assertion.kind().name() + "] " + assertion.name() + " → " + violation);
            }
        }

        String judgeNote = null;
        if (evalCase.requiresJudge() && judgeMode != JudgeMode.OFF) {
            try {
                Judge.Verdict verdict = judge.judge(evalCase, trace);
                if (verdict == null) {
                    judgeNote = "judge 返回 null（按未通过处理）";
                    verdict = Judge.Verdict.reject("judge 返回 null");
                }
                boolean blocking = judgeMode == JudgeMode.BLOCKING;
                if (verdict.passed()) {
                    judgeNote = "pass：" + verdict.reason();
                } else if (blocking) {
                    judgeNote = "reject（阻断）：" + verdict.reason();
                    if (kind == null) {
                        kind = FailureKind.JUDGE_REJECTED;
                    }
                    violations.add("[JUDGE_REJECTED] " + evalCase.judgeRubric() + " → " + verdict.reason());
                } else {
                    // ADVISORY：只记录，不影响 verdict——这是刻意的，见 JudgeMode 的说明
                    judgeNote = "reject（仅参考，不阻断）：" + verdict.reason();
                }
            } catch (RuntimeException e) {
                if (judgeMode == JudgeMode.BLOCKING) {
                    if (kind == null) {
                        kind = FailureKind.JUDGE_ERROR;
                    }
                    violations.add("[JUDGE_ERROR] judge 抛异常 → " + e.getClass().getSimpleName()
                            + ": " + e.getMessage());
                    judgeNote = "error：" + e.getMessage();
                } else {
                    judgeNote = "error（仅参考，不阻断）：" + e.getMessage();
                }
            }
        }

        long elapsed = elapsedMs(start);
        if (!violations.isEmpty()) {
            return new EvalOutcome(evalCase.id(), EvalVerdict.FAIL, kind,
                    String.join("；", violations), trace.taskId(), trace.totalCost(),
                    trace.totalTokens(), elapsed, trace.trajectory(), judgeNote);
        }
        if (evalCase.requiresJudge() && judgeMode == JudgeMode.OFF) {
            return new EvalOutcome(evalCase.id(), EvalVerdict.SKIP, null,
                    "声明了 judge，但 JudgeMode=OFF：确定性断言通过，语义评判本次未执行（不计入通过率）",
                    trace.taskId(), trace.totalCost(), trace.totalTokens(), elapsed,
                    trace.trajectory(), null);
        }
        return new EvalOutcome(evalCase.id(), EvalVerdict.PASS, null, null, trace.taskId(),
                trace.totalCost(), trace.totalTokens(), elapsed, trace.trajectory(), judgeNote);
    }

    private EvalOutcome fail(EvalCase evalCase, FailureKind kind, String message,
                             EvalTrace trace, long start) {
        return new EvalOutcome(evalCase.id(), EvalVerdict.FAIL, kind, message,
                trace == null ? null : trace.taskId(),
                trace == null ? null : trace.totalCost(),
                trace == null ? 0L : trace.totalTokens(),
                elapsedMs(start),
                trace == null ? null : trace.trajectory(),
                null);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    // ---------------------------------------------------------------- builder

    public static final class Builder {

        private final EvalFixture fixture;
        private Judge judge;
        private JudgeMode judgeMode = JudgeMode.ADVISORY;
        private Duration timeout = Duration.ofSeconds(60);
        private Consumer<EvalOutcome> onOutcome;

        private Builder(EvalFixture fixture) {
            this.fixture = Objects.requireNonNull(fixture, "fixture");
        }

        /** 接自己的模型/评分服务；不配则声明了 judge 的 case 在 OFF 下跳过。 */
        public Builder judge(Judge judge) {
            this.judge = judge;
            return this;
        }

        /** 默认 {@link JudgeMode#ADVISORY}：判否只记录，不红构建。 */
        public Builder judgeMode(JudgeMode judgeMode) {
            this.judgeMode = Objects.requireNonNull(judgeMode, "judgeMode");
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /** 逐条回调，用于 CI 实时打印进度。 */
        public Builder onOutcome(Consumer<EvalOutcome> onOutcome) {
            this.onOutcome = onOutcome;
            return this;
        }

        public EvalRunner build() {
            return new EvalRunner(this);
        }
    }
}
