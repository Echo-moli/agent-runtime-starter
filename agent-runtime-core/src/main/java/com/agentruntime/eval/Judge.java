package com.agentruntime.eval;

/**
 * 语义评判器 SPI（M5）——「结果像不像人话」由业务方接自己的模型。
 *
 * <h2>为什么 core 里只留接口，不内置任何调模型的实现</h2>
 * 这是 DESIGN §2.1「core 零 LLM 依赖」的直接推论，也是这个项目对企业的价值所在：
 * 客户已经采购了某家的模型网关，不想为了跑评测再引入一套 SDK 和它带来的版本冲突。
 * 所以 judge 就是一个函数式接口——用原生 {@code HttpClient} 写、用 Spring AI 写、
 * 用内部评分服务写，都只是实现这一个方法的事。
 *
 * <h2>约定</h2>
 * <ul>
 *   <li>实现方<b>不要</b>抛异常来表达「判否」：判否返回 {@link Verdict#reject}；
 *       抛异常会被记成 {@link FailureKind#JUDGE_ERROR}——它表示「评测本身坏了」，
 *       与「业务结果不合格」是两件事，混在一起会让人误判。</li>
 *   <li>实现方应当是幂等的、无副作用的：评测可能重跑，judge 不能因为重跑而改变外部状态。</li>
 * </ul>
 */
@FunctionalInterface
public interface Judge {

    /**
     * 评判一次运行。
     *
     * @param evalCase 用例（含 request 与 rubric）
     * @param trace    这次运行的全部事实
     * @return 判定结果；不要返回 {@code null}
     */
    Verdict judge(EvalCase evalCase, EvalTrace trace);

    /** 不评判的兜底 judge：声明了 judge 的 case 在 ADVISORY 下全部记为 SKIP。 */
    Judge NONE = (evalCase, trace) -> Verdict.skip("未配置 judge");

    /**
     * 判定结果。
     *
     * @param passed 是否通过
     * @param reason 理由（进入报告，失败时必须有）
     * @param score  0..1 的分数，供趋势统计；不关心时填 1 或 0
     */
    record Verdict(boolean passed, String reason, double score) {

        public static Verdict pass(String reason) {
            return new Verdict(true, reason, 1.0);
        }

        public static Verdict reject(String reason) {
            return new Verdict(false, reason, 0.0);
        }

        public static Verdict reject(String reason, double score) {
            return new Verdict(false, reason, score);
        }

        /** 本次不评判（例如 rubric 不适用于该 case）。 */
        public static Verdict skip(String reason) {
            return new Verdict(true, reason, Double.NaN);
        }
    }
}
