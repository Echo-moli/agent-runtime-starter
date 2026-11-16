package com.agentruntime.eval;

import com.agentruntime.domain.TaskStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 一条评测用例（M5）：输入 + 期望。
 *
 * <pre>{@code
 * EvalCase.of("REFUND-003", "ORD-20261114-001")
 *         .tag("happy-path")
 *         .expectStatus(TaskStatus.COMPLETED)
 *         .expectTools("queryOrder", "refund")
 *         .expectResultContains("退款成功")
 *         .expectAtMostOnce("refund")
 *         .expectMaxCost("0.02")
 *         .build();
 * }</pre>
 *
 * <h2>为什么 case 里的期望是「断言列表」而不是一个期望对象</h2>
 * 真实业务的「正确」是多维的：终态对、路径对、钱数对、不超过预算。写成列表后
 * 报告能明确指出<b>哪一维</b>坏了（见 {@link FailureKind}）；写成单个期望对象，
 * 只能得到「不符」，然后人去翻日志。此外列表天然可组合：公共不变量
 * （比如 {@code atMostOnce("refund")}）可以抽出来给所有 case 复用。
 *
 * <h2>关于 judge</h2>
 * 需要「语义像不像人话」的 case 用 {@link Builder#judge(String)} 声明，
 * 但它<b>不会</b>成为确定性断言，是否阻断构建由 {@link JudgeMode} 决定。
 * 这条界限是本模块最重要的设计：把不确定的东西排除在门禁之外。
 */
public final class EvalCase {

    private final String id;
    private final String request;
    private final String userId;
    private final List<String> tags;
    private final List<Assertion> assertions;
    private final String judgeRubric;

    private EvalCase(Builder b) {
        this.id = b.id;
        this.request = b.request;
        this.userId = b.userId;
        this.tags = List.copyOf(b.tags);
        this.assertions = List.copyOf(b.assertions);
        this.judgeRubric = b.judgeRubric;
    }

    public static Builder of(String id, String request) {
        return new Builder(id, request);
    }

    public String id() {
        return id;
    }

    public String request() {
        return request;
    }

    public String userId() {
        return userId;
    }

    public List<String> tags() {
        return tags;
    }

    public List<Assertion> assertions() {
        return assertions;
    }

    /** 需要 judge 时的评分标准；{@code null} 表示这条 case 不需要 judge。 */
    public String judgeRubric() {
        return judgeRubric;
    }

    public boolean requiresJudge() {
        return judgeRubric != null;
    }

    @Override
    public String toString() {
        return "EvalCase[" + id + " ← " + request + ", " + assertions.size() + " 条断言"
                + (requiresJudge() ? " + judge" : "") + ']';
    }

    /** 链式构造器；{@link #build()} 时校验「至少一条期望」。 */
    public static final class Builder {

        private final String id;
        private final String request;
        private String userId = "eval-user";
        private final List<String> tags = new ArrayList<>();
        private final List<Assertion> assertions = new ArrayList<>();
        private String judgeRubric;

        private Builder(String id, String request) {
            this.id = id;
            this.request = request;
        }

        /**
         * 提交时的请求串。刻意保持 String：DESIGN §5.1 的
         * {@code engine.submit(plan, userId, userRequest)} 只收字符串，
         * 评测不该要求业务方为它改接口。
         */
        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder tag(String... tags) {
            this.tags.addAll(List.of(tags));
            return this;
        }

        public Builder expect(Assertion assertion) {
            this.assertions.add(Objects.requireNonNull(assertion, "assertion"));
            return this;
        }

        public Builder expectStatus(TaskStatus status) {
            return expect(Expectations.status(status));
        }

        public Builder expectStatusIn(TaskStatus... allowed) {
            return expect(Expectations.statusIn(allowed));
        }

        public Builder expectTools(String... tools) {
            return expect(Expectations.tools(tools));
        }

        public Builder expectStartsWithTools(String... prefix) {
            return expect(Expectations.startsWithTools(prefix));
        }

        public Builder expectToolCount(String toolName, int times) {
            return expect(Expectations.toolCount(toolName, times));
        }

        public Builder expectAtMostOnce(String... tools) {
            return expect(Expectations.atMostOnce(tools));
        }

        public Builder expectNeverCalled(String... tools) {
            return expect(Expectations.neverCalled(tools));
        }

        public Builder expectResultContains(String... fragments) {
            return expect(Expectations.resultContains(fragments));
        }

        public Builder expectErrorContains(String... fragments) {
            return expect(Expectations.errorContains(fragments));
        }

        public Builder expectMaxCost(String budget) {
            return expect(Expectations.maxCost(budget));
        }

        public Builder expectMaxCost(BigDecimal budget) {
            return expect(Expectations.maxCost(budget));
        }

        public Builder expectMaxTokens(long budget) {
            return expect(Expectations.maxTokens(budget));
        }

        public Builder expectMaxDecisions(int limit) {
            return expect(Expectations.maxDecisions(limit));
        }

        /** 声明这条 case 需要语义评判；{@code rubric} 是给 judge 的评分标准。 */
        public Builder judge(String rubric) {
            this.judgeRubric = Objects.requireNonNull(rubric, "rubric");
            return this;
        }

        public EvalCase build() {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("EvalCase.id 不能为空");
            }
            if (request == null) {
                throw new IllegalArgumentException("EvalCase[" + id + "].request 不能为 null");
            }
            if (assertions.isEmpty()) {
                throw new IllegalArgumentException(
                        "EvalCase[" + id + "] 没有任何期望：一条不检查任何东西的用例等于没写，"
                                + "它只会把通过率抬高");
            }
            return new EvalCase(this);
        }
    }
}
