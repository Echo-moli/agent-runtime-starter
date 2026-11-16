package com.agentruntime.eval;

/**
 * 评测失败的分类（M5）。
 *
 * <h2>为什么失败要分类，而不是只给一句「不通过」</h2>
 * 评测报告的唯一用途是让人在 CI 红了以后<b>三十秒内知道该改哪里</b>。
 * 「3/20 失败」没有信息量；「3/20 失败，其中 2 条是工具序列不符、1 条是成本超预算」
 * 直接指向两个不同的修法。分类还是后面做「回退拦截」统计的口径：
 * 一次改动如果只让 {@link #BUDGET_EXCEEDED} 变多，说明逻辑没坏但变贵了——
 * 这是传统用例很难发现的回退类型，也正是本项目把成本归因和评测放在一起的理由。
 */
public enum FailureKind {

    /** 终态不是预期（例如该挂起却完成了）。 */
    STATUS_MISMATCH("终态不符"),
    /** 工具调用序列/次数不是预期（含漏调、多调、顺序错）。 */
    TOOL_SEQUENCE("工具序列不符"),
    /** 有副作用工具的成功执行次数 > 1，即重复扣款风险（资损级）。 */
    SIDE_EFFECT("副作用重复"),
    /** 最终结果文本里没有预期内容。 */
    RESULT_CONTENT("结果内容不符"),
    /** 成本或 token 超预算。 */
    BUDGET_EXCEEDED("成本超预算"),
    /** 决策步数超限（通常意味着 agent 在原地打转）。 */
    STEP_LIMIT("步数超限"),
    /** judge（通常是 LLM）判否。 */
    JUDGE_REJECTED("judge 判否"),
    /** judge 自己出错了（超时、限流、返回非预期格式）。 */
    JUDGE_ERROR("judge 异常"),
    /** 引擎抛异常、超时、或 session 打不开。 */
    ENGINE_ERROR("引擎异常");

    private final String label;

    FailureKind(String label) {
        this.label = label;
    }

    /** 中文短标签，用于报告。 */
    public String label() {
        return label;
    }
}
