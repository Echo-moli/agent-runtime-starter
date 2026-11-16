package com.agentruntime.eval;

/**
 * 单条 case 的三态结论（M5）。
 *
 * <p>{@link #SKIP} 与 {@link #FAIL} 必须严格区分：SKIP 表示「这条用例这次无法判定」
 * （例如声明了 judge 但处于 {@link JudgeMode#OFF}），它<b>不计入通过率分母</b>。
 * 若把 SKIP 记成 PASS，通过率会虚高；记成 FAIL，构建会因为「没配 judge」而红——
 * 两种都会让人开始怀疑评测数字，而一个不被信任的门禁等于不存在。
 */
public enum EvalVerdict {

    PASS,
    FAIL,
    SKIP
}
