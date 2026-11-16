package com.agentruntime.samples.refund;

import com.agentruntime.api.AgentPlan;
import com.agentruntime.api.Context;
import com.agentruntime.api.Step;
import com.agentruntime.api.Usage;

import java.math.BigDecimal;

/**
 * 把「决策花了多少 token」报给引擎的装饰器（M4）。
 *
 * <p>示例里的 {@link RefundAgent} 是规则版，不调模型，所以它没什么可报的。
 * 真实项目里 {@code decide()} 里就是一次 LLM 调用，用量天然可得；这里用一个确定性的
 * 伪用量模拟「agent 每想一步都要花钱」，好让成本归因链路能端到端跑通并被断言。
 *
 * <h2>为什么用量必须由 plan 报，而不是引擎猜</h2>
 * 引擎不知道供应商、不知道计价规则、更不知道这一轮是缓存命中还是全价。
 * 只有写 {@code decide()} 的人知道。所以 {@link Step#withUsage} 是唯一的入口，
 * 引擎只负责把它记到「哪一步」头上并原子累加。
 */
public class CostAwareRefundAgent implements AgentPlan {

    /** 价目表：元 / 百万 token（示例数字，非真实报价）。 */
    public record PriceList(BigDecimal inputPerMillion, BigDecimal outputPerMillion) {

        public static final PriceList DEMO = new PriceList(new BigDecimal("2.00"), new BigDecimal("8.00"));

        public static PriceList of(String inputPerMillion, String outputPerMillion) {
            return new PriceList(new BigDecimal(inputPerMillion), new BigDecimal(outputPerMillion));
        }
    }

    private final String name;
    private final AgentPlan delegate;
    private final PriceList prices;

    public CostAwareRefundAgent(String name, AgentPlan delegate, PriceList prices) {
        this.name = name;
        this.delegate = delegate;
        this.prices = prices == null ? PriceList.DEMO : prices;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Step decide(Context ctx) {
        Step step = delegate.decide(ctx);
        return step.withUsage(usageFor(ctx));
    }

    /**
     * 确定性伪用量：步数越大上下文越长（真实 LLM 也是这个趋势）。
     *
     * <p>刻意依赖 {@code ctx.events().size()}——它让「恢复后的步」拿到的上下文长度与正常运行一致，
     * 从而能验证「重放的那一步没有再次计费」。
     */
    private Usage usageFor(Context ctx) {
        long prompt = 760L + 320L * ctx.stepIndex() + 7L * ctx.events().size();
        long completion = 60L + 28L * ctx.stepIndex();
        return Usage.priced(prompt, completion, prices.inputPerMillion(), prices.outputPerMillion());
    }
}
