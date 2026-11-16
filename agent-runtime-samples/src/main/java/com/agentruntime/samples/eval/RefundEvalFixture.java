package com.agentruntime.samples.eval;

import com.agentruntime.api.AgentPlan;
import com.agentruntime.engine.DefaultAgentEngine;
import com.agentruntime.engine.ToolRegistry;
import com.agentruntime.eval.EvalCase;
import com.agentruntime.eval.EvalFixture;
import com.agentruntime.persistence.InMemoryIdempotencyStore;
import com.agentruntime.persistence.InMemoryTaskRepository;
import com.agentruntime.samples.refund.CostAwareRefundAgent;
import com.agentruntime.samples.refund.Order;
import com.agentruntime.samples.refund.OrderQueryService;
import com.agentruntime.samples.refund.RefundService;

import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * 退款 agent 的评测夹具（M5）：每条 case 一套全新的内存引擎 + 全新的 mock 服务。
 *
 * <h2>为什么每条 case 都重建引擎，而不是复用一个</h2>
 * 评测最容易出的低级错误是「用例之间串味」：上一条退过的款留在幂等表里，
 * 下一条同样的 orderId 会命中缓存直接返回，于是「退款真的发生了吗」这个问题再也测不出来。
 * 这里每条 case 都是 {@code new InMemoryTaskRepository()} + {@code new InMemoryIdempotencyStore()}
 * + {@code new RefundService()}，用例之间零共享。跨用例共享状态反而是<b>故意</b>要测的场景，
 * 那种场景应该在单条 case 内部显式做（M3 就是这么测的）。
 *
 * <h2>为什么用同步执行器</h2>
 * {@code executor(Runnable::run)} 让 {@code submit} 返回时任务已经跑完，
 * 断言拿到的必然是终态，不需要 sleep、不需要等轮询。评测里任何「等一会儿再看」
 * 都是 flaky 的种子。
 *
 * <h2>为什么所有 plan 都套一层 {@link CostAwareRefundAgent}</h2>
 * 因为成本门禁必须建立在真实数字上。规则版 agent 不调模型、用量为 0，
 * 拿它测「不超预算」等于永远通过——那种用例只会给报告凑分母。
 */
public final class RefundEvalFixture implements EvalFixture {

    private final List<Order> orders;
    private final Function<EvalCase, AgentPlan> planOf;

    /**
     * @param orders 本批评测的订单种子
     * @param planOf 按 case 决定被测的决策实现（例如某些 case 要换成需审批的退款工具）
     */
    public RefundEvalFixture(Collection<Order> orders, Function<EvalCase, AgentPlan> planOf) {
        this.orders = List.copyOf(orders);
        this.planOf = planOf;
    }

    @Override
    public Session open(EvalCase evalCase) {
        OrderQueryService queryService = new OrderQueryService(orders);
        // 关掉副作用流水：20 条用例会把 demo 的日志刷满，让人看不清报告
        RefundService refundService = new RefundService().withVerbose(false);

        ToolRegistry registry = new ToolRegistry()
                .register(queryService)
                .register(refundService);

        DefaultAgentEngine engine = DefaultAgentEngine.builder(
                        new InMemoryTaskRepository(), registry)
                .executor(Runnable::run)
                .idempotencyStore(new InMemoryIdempotencyStore())
                .maxSteps(10)
                .build();

        AgentPlan brain = planOf.apply(evalCase);
        AgentPlan observed = new CostAwareRefundAgent(
                brain.name(), brain, CostAwareRefundAgent.PriceList.DEMO);
        return Session.of(engine, observed);
    }
}
