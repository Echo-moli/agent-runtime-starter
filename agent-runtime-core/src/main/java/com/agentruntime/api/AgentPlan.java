package com.agentruntime.api;

/**
 * 业务方唯一需要实现的智能点（DESIGN.md §5.2）。
 *
 * <p>引擎不替它做规划，只保证可靠地执行它的每一步决策；内部用什么调模型（原生 HttpClient /
 * Spring AI / LangChain4j / 纯规则）引擎完全不感知。
 *
 * <p>{@link #name()} 是补全项：DESIGN §4.2 的 {@code Task.agentName} 需要一个来源，
 * 而 §5.2 的接口签名里没有，故补一个带默认实现的标识方法。
 */
@FunctionalInterface
public interface AgentPlan {

    /** 根据当前上下文决定下一步：调哪个工具 + 参数，或直接完成。 */
    Step decide(Context ctx);

    /** Agent 标识，落到 {@code Task.agentName}。 */
    default String name() {
        return getClass().getSimpleName();
    }
}
