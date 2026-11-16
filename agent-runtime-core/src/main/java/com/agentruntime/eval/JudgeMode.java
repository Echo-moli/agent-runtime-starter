package com.agentruntime.eval;

/**
 * judge（通常是 LLM）结果对构建的影响方式（M5）。
 *
 * <h2>这个枚举是整个评测模块最重要的设计决定</h2>
 * DESIGN §6 M5 写的是「确定性断言 or LLM-judge 表达式」。听起来两者对等，
 * 但放进 CI 就完全不同了：LLM 的输出受温度、模型版本、供应商限流影响，
 * 同一次 commit 重跑可能 19/20 也可能 20/20。一旦拿它当通过率分母，
 * 构建就会<b>随机变红</b>——第一次大家还看日志，第三次大家就开始骂评测、然后加
 * {@code -DskipEval}。评测从此名存实亡，而且没人会承认是自己关掉的。
 *
 * <p>所以这里的默认是 {@link #ADVISORY}：
 * <ul>
 *   <li>确定性断言决定红绿（可重放，谁跑都一样）；</li>
 *   <li>judge 只产出<b>参考信号</b>，进报告、可趋势化，但不阻断；</li>
 *   <li>真要阻断，必须显式选 {@link #BLOCKING}，并且由人选一个稳定的模型/温度。</li>
 * </ul>
 * 换句话说：门禁只允许可证明的东西进来，不确定的东西只能作为情报。
 */
public enum JudgeMode {

    /** 不跑 judge；声明了 judge 的 case 记为 SKIP（不计入通过率分母）。 */
    OFF,

    /**
     * 跑 judge，结果写进报告但不影响 verdict（默认）。
     *
     * <p>适合：模型刚换版本、prompt 刚改，想先看趋势而不是被红叉打断。
     */
    ADVISORY,

    /** judge 判否即 FAIL。只建议用于人工挑过 case、且固定了模型与温度的回归集。 */
    BLOCKING
}
