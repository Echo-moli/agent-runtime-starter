package com.agentruntime.eval;

import java.math.BigDecimal;

/**
 * 一条 case 的运行结论（M5）。
 *
 * @param caseId     用例标识
 * @param verdict    三态结论
 * @param kind       失败分类；PASS/SKIP 时为 {@code null}
 * @param message    失败原因 / SKIP 原因；PASS 时为 {@code null}
 * @param taskId     这次运行的任务号（拿着它去 {@code getTask} 能翻出全部事件）
 * @param cost       这条 case 花掉的钱（元），用于成本报表
 * @param tokens     这条 case 花掉的 token
 * @param durationMs 这条 case 的墙钟耗时（含引擎启动/关闭）
 * @param trajectory 事件轨迹的一行摘要，失败时必须带上（否则报告只有结论没有证据）
 * @param judgeNote  judge 的旁注（仅在跑了 judge 时有值）
 */
public record EvalOutcome(
        String caseId,
        EvalVerdict verdict,
        FailureKind kind,
        String message,
        String taskId,
        BigDecimal cost,
        long tokens,
        long durationMs,
        String trajectory,
        String judgeNote) {

    public EvalOutcome {
        cost = cost == null ? BigDecimal.ZERO : cost;
    }

    public boolean failed() {
        return verdict == EvalVerdict.FAIL;
    }

    public boolean skipped() {
        return verdict == EvalVerdict.SKIP;
    }

    public boolean passed() {
        return verdict == EvalVerdict.PASS;
    }
}
