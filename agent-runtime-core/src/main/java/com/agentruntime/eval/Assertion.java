package com.agentruntime.eval;

/**
 * 一条确定性断言（M5）。
 *
 * <h2>为什么断言接口只返回「违规描述」而不抛异常</h2>
 * 一条 case 通常带 3–5 条断言。若第一条失败就抛异常，报告里只能看到
 * 「终态不符」，看不出「工具序列也错了」——而这两条往往同源，一起看见才能判断根因。
 * 所以这里统一用「通过返回 null，失败返回人能读懂的原因」，
 * 由 {@link EvalRunner} 收集<b>全部</b>违规再决定 verdict。
 *
 * <h2>为什么断言不用 lambda 就够，还要 {@link #kind()}</h2>
 * 分类是报告的骨架（见 {@link FailureKind}）。lambda 写不出分类，
 * 所以 {@link Expectations#satisfies} 这种通用工厂强制要求显式传 kind。
 */
@FunctionalInterface
public interface Assertion {

    /**
     * 检查一次运行。
     *
     * @return 通过返回 {@code null}；失败返回原因（含期望值与实际值）
     */
    String violation(EvalTrace trace);

    /** 失败分类；默认按「结果内容」计，具体工厂都会覆盖它。 */
    default FailureKind kind() {
        return FailureKind.RESULT_CONTENT;
    }

    /** 断言的人类可读名称，进报告。 */
    default String name() {
        return getClass().getSimpleName();
    }
}
