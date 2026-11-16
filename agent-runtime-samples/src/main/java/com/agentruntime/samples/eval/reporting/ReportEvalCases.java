package com.agentruntime.samples.eval.reporting;

import com.agentruntime.domain.TaskStatus;
import com.agentruntime.eval.EvalCase;

import java.util.List;

/**
 * 报表域评测用例（P2 第二业务域）。
 *
 * <p>和退款域刻意不同：这里的失败是「越权导出」而不是「重复打款」，
 * 断言重点是<b>工具序列、权限边界、成本与账本</b>。同一套 {@code EvalRunner} 不改一行
 * 就能跑——这才是 M5 评测框架泛化能力的实证。
 */
public final class ReportEvalCases {

    private ReportEvalCases() {
    }

    public static List<EvalCase> all() {
        return List.of(
                EvalCase.of("REPORT-01-owner-export", "R-001:alice")
                        .tag("happy")
                        .expectStatus(TaskStatus.COMPLETED)
                        .expectTools("queryReport", "exportReport")
                        .expectResultContains("已导出", "日销售汇总")
                        .expectAtMostOnce("exportReport")
                        .build(),

                EvalCase.of("REPORT-02-other-owner-denied", "R-001:bob")
                        .tag("authz")
                        .expectStatus(TaskStatus.FAILED)
                        // 查询成功被记账本；导出抛业务异常，不产生成功的 TOOL_CALL 事件
                        .expectTools("queryReport")
                        .expectErrorContains("无权导出")
                        .expectNeverCalled("exportReport")
                        .build(),

                EvalCase.of("REPORT-03-blank-report-id", "")
                        .tag("error-path")
                        .expectStatus(TaskStatus.FAILED)
                        .expectErrorContains("报表号")
                        .expectTools()
                        .expectNeverCalled("exportReport")
                        .build(),

                EvalCase.of("REPORT-04-missing-report", "R-999:alice")
                        .tag("error-path")
                        .expectStatus(TaskStatus.FAILED)
                        .expectErrorContains("报表不存在")
                        .expectNeverCalled("exportReport")
                        .build(),

                // 刻意不写「成本上限」用例：报表域没有套 CostAwareAgent，token/成本恒为 0，
                // 那种断言只会永远通过、给报告凑分母（RefundEvalCases 的纪律第 2 条）。
                // 换成一条真正有区分度的：报表的 owner 才能导出（bob 导出 R-002 成功）。
                EvalCase.of("REPORT-05-second-owner-export", "R-002:bob")
                        .tag("authz")
                        .expectStatus(TaskStatus.COMPLETED)
                        .expectTools("queryReport", "exportReport")
                        .expectResultContains("已导出", "退款分析")
                        .expectAtMostOnce("exportReport")
                        .build(),

                EvalCase.of("REPORT-06-ledger-shape", "R-001:alice")
                        .tag("contract")
                        .expectStatus(TaskStatus.COMPLETED)
                        .expectTools("queryReport", "exportReport")
                        .expectAtMostOnce("queryReport")
                        .expectAtMostOnce("exportReport")
                        .build()
        );
    }
}
