package com.agentruntime.samples.eval;

import com.agentruntime.eval.EvalCase;
import com.agentruntime.eval.EvalTrace;
import com.agentruntime.eval.Judge;

/**
 * 词法判官：<b>假装成 LLM 的确定性替身</b>（M5 demo 用）。
 *
 * <h2>为什么 demo 里不接真的模型</h2>
 * 两个原因，第二个更重要：
 * <ol>
 *   <li>演示不该依赖网络、API Key 和余额——否则「跑一下看看」这件事就废了；</li>
 *   <li>它要说明的问题恰好是<b>「judge 的结论不可重放」</b>。用真模型演示，你会看到
 *       同一条 case 这次通过下次不通过，但那到底是模型波动还是代码回退？
 *       演示里没法区分，读者会觉得是 demo 不稳定，而不是 judge 不可靠。
 *       换成确定性替身，反而能把「即使 judge 判否，构建也不该红」这条规矩演清楚。</li>
 * </ol>
 *
 * <p>真实项目里这个类会是一次 {@code HttpClient} 调用（或 Spring AI 的
 * {@code ChatClient}），并把 {@code rubric + 事件轨迹} 拼进 prompt。接口不变。
 */
public final class LexicalJudge implements Judge {

    @Override
    public Verdict judge(EvalCase evalCase, EvalTrace trace) {
        String rubric = evalCase.judgeRubric() == null ? "" : evalCase.judgeRubric();
        String text = trace.resultText();

        if (rubric.contains("礼貌")) {
            boolean polite = text.contains("请") || text.contains("感谢") || text.contains("抱歉");
            return polite
                    ? Verdict.pass("含致歉/感谢用语")
                    // 刻意判否：RefundAgent 的回复是标准业务口径，不带客套话。
                    // 这条就是用来演示「judge 说不合格 ≠ 构建该红」的样本。
                    : Verdict.reject("缺少致歉或感谢用语，语气偏事务化", 0.4);
        }
        if (rubric.contains("订单号")) {
            String orderId = evalCase.request().trim();
            return text.contains(orderId)
                    ? Verdict.pass("已回显订单号 " + orderId)
                    : Verdict.reject("回复未回显订单号 " + orderId, 0.2);
        }
        if (rubric.contains("解释")) {
            boolean explains = text.contains("不可退款") || text.contains("原因") || text.contains("状态");
            return explains
                    ? Verdict.pass("已说明不受理原因")
                    : Verdict.reject("未说明拒绝原因", 0.3);
        }
        return Verdict.skip("无法识别的 rubric：" + rubric);
    }
}
