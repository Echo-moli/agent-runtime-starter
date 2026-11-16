package com.agentruntime.eval;

import com.agentruntime.api.AgentEngine;
import com.agentruntime.api.AgentPlan;

/**
 * 为一条 case 准备一套<b>干净</b>的运行环境（M5）。
 *
 * <h2>为什么必须是「每条 case 一套」，而不是整个评测共用一个引擎</h2>
 * 评测要能定位问题，前提是 case 之间互不污染：上一条用例退过的钱、缓存的幂等记录、
 * 残留的订单状态，都会让下一条用例的结论变成噪声。所以夹具是工厂而不是单例，
 * 由 {@link EvalRunner} 逐条调用、逐条关闭。这也让「同一个 orderId 二次提交」这类
 * 需要<b>故意</b>共享状态的用例，能在 case 内部自己决定共享什么。
 *
 * <p>返回的 {@link Session} 里的 engine 通常要实现 {@link AutoCloseable}
 * （{@code DefaultAgentEngine} 就实现了，它持有线程池）；runner 用完会负责关掉。
 */
@FunctionalInterface
public interface EvalFixture {

    /** 打开一条 case 的运行环境。 */
    Session open(EvalCase evalCase);

    /**
     * 一次运行的句柄。
     *
     * @param engine  引擎实例（建议同步执行，保证断言确定性）
     * @param plan    被测的决策实现
     * @param userId  提交人；为 {@code null} 时用 {@link EvalCase#userId()}
     */
    record Session(AgentEngine engine, AgentPlan plan, String userId) implements AutoCloseable {

        public static Session of(AgentEngine engine, AgentPlan plan) {
            return new Session(engine, plan, null);
        }

        @Override
        public void close() {
            if (engine instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                    // 关闭失败不影响评测结论；资源回收问题由引擎自己的日志负责暴露
                }
            }
        }
    }
}
