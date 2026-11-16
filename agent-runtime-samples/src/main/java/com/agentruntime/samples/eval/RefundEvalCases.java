package com.agentruntime.samples.eval;

import com.agentruntime.domain.StepType;
import com.agentruntime.domain.TaskStatus;
import com.agentruntime.eval.EvalCase;
import com.agentruntime.eval.EvalTrace;
import com.agentruntime.eval.Expectations;
import com.agentruntime.eval.FailureKind;

import java.util.List;

import static com.agentruntime.samples.eval.RefundEvalOrders.CANCELLED;
import static com.agentruntime.samples.eval.RefundEvalOrders.CLOSED;
import static com.agentruntime.samples.eval.RefundEvalOrders.LARGE;
import static com.agentruntime.samples.eval.RefundEvalOrders.MISSING;
import static com.agentruntime.samples.eval.RefundEvalOrders.PAID;
import static com.agentruntime.samples.eval.RefundEvalOrders.PAID_SMALL;
import static com.agentruntime.samples.eval.RefundEvalOrders.PENDING;
import static com.agentruntime.samples.eval.RefundEvalOrders.REFUNDED;
import static com.agentruntime.samples.eval.RefundEvalOrders.SHIPPED;

/**
 * 退款 agent 的 20 条评测用例（M5，DESIGN §6 M5 的验收要求）。
 *
 * <h2>这批用例想拦住什么</h2>
 * <table border="1">
 *   <caption>用例覆盖矩阵</caption>
 *   <tr><th>组</th><th>用例</th><th>拦的回退类型</th></tr>
 *   <tr><td>应退</td><td>EVAL-01/02/03</td><td>happy path 坏了、漏调退款、金额精度丢失</td></tr>
 *   <tr><td>不应退</td><td>EVAL-04/05/06/07</td><td><b>重复打款（资损）</b>——状态校验被删</td></tr>
 *   <tr><td>异常输入</td><td>EVAL-08/09/10</td><td>查不到单仍退款、空请求被当成可退、脏输入未归一化</td></tr>
 *   <tr><td>人工闸门</td><td>EVAL-11</td><td>大额绕过审批直接退</td></tr>
 *   <tr><td>成本</td><td>EVAL-12/13</td><td>prompt 变长 / 多绕一步导致单位成本上涨</td></tr>
 *   <tr><td>契约与账本</td><td>EVAL-14/15/20</td><td>结果格式变动、事件账本缺项（事后查不清）</td></tr>
 *   <tr><td>语义（judge）</td><td>EVAL-16/17/18</td><td>措辞与信息完整性；<b>只做参考信号，不阻断</b></td></tr>
 *   <tr><td>安全不变量</td><td>EVAL-19</td><td>失败路径下仍产生了副作用</td></tr>
 * </table>
 *
 * <h2>写用例的三条纪律</h2>
 * <ol>
 *   <li><b>只断言事实，不断言实现</b>：断言「退款成功了」「refund 只成功 1 次」，
 *       而不是断言内部方法被调用的顺序细节——否则重构就红，人会开始习惯性忽略红。</li>
 *   <li><b>金额用字符串断言</b>：{@code "66.60"} 比 {@code 66.6} 更能抓出精度问题。</li>
 *   <li><b>每条 case 至少两条断言</b>：只有一条「终态=COMPLETED」的用例，
 *       连「直接退款不看状态」这种事故都拦不住。</li>
 * </ol>
 */
public final class RefundEvalCases {

    /** 需要走 {@code refundRequiringApproval} 的用例带这个 tag，夹具据此换 plan 变体。 */
    public static final String TAG_APPROVAL = "require-approval";

    private RefundEvalCases() {
    }

    public static List<EvalCase> all() {
        return List.of(
                // ---------------- 应退 ----------------
                EvalCase.of("EVAL-01-paid-refund", PAID)
                        .tag("refundable")
                        .expectStatus(TaskStatus.COMPLETED)
                        .expectTools("queryOrder", "refund")
                        .expectResultContains("退款成功")
                        .expectAtMostOnce("refund")
                        .build(),

                EvalCase.of("EVAL-02-shipped-refund", SHIPPED)
                        .tag("refundable")
                        .expectStatus(TaskStatus.COMPLETED)
                        .expectTools("queryOrder", "refund")
                        .expectResultContains("退款成功", "39.90")
                        .expectAtMostOnce("refund")
                        .build(),

                EvalCase.of("EVAL-03-small-amount-refund", PAID_SMALL)
                        .tag("refundable")
                        .expectStatus(TaskStatus.COMPLETED)
                        .expectTools("queryOrder", "refund")
                        // 66.60 而不是 66.6：金额经 JSON 化/还原后不许掉精度
                        .expectResultContains("66.60")
                        .expectAtMostOnce("refund")
                        .build(),

                // ---------------- 不应退（资损面） ----------------
                EvalCase.of("EVAL-04-closed-not-refundable", CLOSED)
                        .tag("not-refundable")
                        .expectStatus(TaskStatus.COMPLETED)
                        .expectTools("queryOrder")
                        .expectNeverCalled("refund")
                        .expectResultContains("不可退款", "CLOSED")
                        .build(),

                EvalCase.of("EVAL-05-refunded-not-refundable", REFUNDED)
                        .tag("not-refundable")
                        .expectStatus(TaskStatus.COMPLETED)
                        .expectTools("queryOrder")
                        .expectNeverCalled("refund")
                        .expectResultContains("不可退款", "REFUNDED")
                        .build(),

                EvalCase.of("EVAL-06-cancelled-not-refundable", CANCELLED)
                        .tag("not-refundable")
                        .expectStatus(TaskStatus.COMPLETED)
                        .expectTools("queryOrder")
                        .expectNeverCalled("refund")
                        .expectResultContains("不可退款", "CANCELLED")
                        .build(),

                EvalCase.of("EVAL-07-pending-not-refundable", PENDING)
                        .tag("not-refundable")
                        .expectStatus(TaskStatus.COMPLETED)
                        .expectTools("queryOrder")
                        .expectNeverCalled("refund")
                        .expectResultContains("不可退款", "PENDING")
                        .build(),

                // ---------------- 异常输入 ----------------
                EvalCase.of("EVAL-08-missing-order", MISSING)
                        .tag("error-path")
                        .expectStatus(TaskStatus.FAILED)
                        .expectErrorContains("订单不存在")
                        .expectTools()
                        .expectNeverCalled("refund")
                        .build(),

                EvalCase.of("EVAL-09-blank-request", "")
                        .tag("error-path")
                        .expectStatus(TaskStatus.FAILED)
                        .expectErrorContains("订单号")
                        .expectTools()
                        .build(),

                EvalCase.of("EVAL-10-padded-request", "  " + PAID + "  ")
                        .tag("input-normalization")
                        .expectStatus(TaskStatus.COMPLETED)
                        .expectTools("queryOrder", "refund")
                        .expectResultContains("退款成功")
                        .build(),

                // ---------------- 人工闸门 ----------------
                EvalCase.of("EVAL-11-large-refund-needs-approval", LARGE)
                        .tag(TAG_APPROVAL, "hitl")
                        .expectStatus(TaskStatus.SUSPENDED)
                        // 挂起意味着工具没执行：账本里只有查询
                        .expectTools("queryOrder")
                        .expectNeverCalled("refundRequiringApproval")
                        .expectMaxDecisions(2)
                        .build(),

                // ---------------- 成本 ----------------
                EvalCase.of("EVAL-12-cost-budget-refund", PAID)
                        .tag("cost")
                        .expectStatus(TaskStatus.COMPLETED)
                        .expectMaxCost("0.0095")
                        .expectMaxTokens(4000)
                        .build(),

                EvalCase.of("EVAL-13-cost-budget-reject-path", CLOSED)
                        .tag("cost")
                        .expectStatus(TaskStatus.COMPLETED)
                        // 不该退的单子只要两步，成本必须明显低于退款路径
                        .expectMaxCost("0.0055")
                        .expectMaxTokens(2400)
                        .build(),

                // ---------------- 契约与账本 ----------------
                EvalCase.of("EVAL-14-receipt-format", PAID)
                        .tag("contract")
                        .expectStatus(TaskStatus.COMPLETED)
                        .expect(Expectations.satisfies(
                                "回执文案格式", FailureKind.RESULT_CONTENT,
                                t -> t.resultText().matches(
                                        "退款成功，回执号 RFD-[0-9a-f]{8}，金额 128\\.00 元")))
                        .build(),

                EvalCase.of("EVAL-15-ledger-shape", PAID)
                        .tag("contract")
                        .expectStatus(TaskStatus.COMPLETED)
                        .expect(Expectations.satisfies(
                                "事件账本=2 决策 + 2 工具 + 1 完成", FailureKind.RESULT_CONTENT,
                                t -> count(t, StepType.LLM_DECISION) == 2
                                        && count(t, StepType.TOOL_CALL) == 2
                                        && count(t, StepType.COMPLETION) == 1))
                        .build(),

                // ---------------- 语义（judge 只做参考） ----------------
                EvalCase.of("EVAL-16-judge-polite-tone", PAID)
                        .tag("judge")
                        .expectStatus(TaskStatus.COMPLETED)
                        .expectTools("queryOrder", "refund")
                        .judge("礼貌：面向用户的回复应含致歉或感谢用语")
                        .build(),

                EvalCase.of("EVAL-17-judge-mentions-order", CLOSED)
                        .tag("judge")
                        .expectStatus(TaskStatus.COMPLETED)
                        .judge("可追溯：回复里应出现被处理的订单号")
                        .build(),

                EvalCase.of("EVAL-18-judge-explains-rejection", CLOSED)
                        .tag("judge")
                        .expectStatus(TaskStatus.COMPLETED)
                        .judge("解释性：拒绝退款时应说明原因")
                        .build(),

                // ---------------- 安全不变量 ----------------
                EvalCase.of("EVAL-19-no-side-effect-on-failure", MISSING)
                        .tag("safety")
                        .expectStatus(TaskStatus.FAILED)
                        .expectAtMostOnce("refund")
                        .expectNeverCalled("refund")
                        .build(),

                EvalCase.of("EVAL-20-audit-trail-complete", PAID)
                        .tag("contract")
                        .expectStatus(TaskStatus.COMPLETED)
                        .expect(Expectations.satisfies(
                                "每条 TOOL_CALL 都记了入参", FailureKind.RESULT_CONTENT,
                                t -> t.events().stream()
                                        .filter(e -> e.stepType() == StepType.TOOL_CALL)
                                        .allMatch(e -> e.payload().get("args") instanceof java.util.Map)))
                        .build());
    }

    private static long count(EvalTrace trace, StepType type) {
        return trace.events().stream().filter(e -> e.stepType() == type).count();
    }
}
