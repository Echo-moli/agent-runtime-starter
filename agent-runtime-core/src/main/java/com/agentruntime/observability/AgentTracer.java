package com.agentruntime.observability;

import com.agentruntime.domain.StepType;

/**
 * 链路追踪出口（M4）。
 *
 * <p>引擎在三个位置建 span：任务开始、每次决策、每次工具调用。属性按 DESIGN §6 M4 给：
 * {@code tokens}、{@code cost}、{@code success}、{@code stepIndex}，另外补 {@code attempts} /
 * {@code fromCache}——排查「为什么这一步慢」时，重试次数比成败更有信息量。
 *
 * <h2>为什么不让引擎直接依赖 OTel SDK</h2>
 * 一旦 {@code core} 依赖 OTel，业务方想换 Micrometer Tracing 或自研埋点就得改引擎。
 * 这里只定义「我需要吐什么」，怎么吐由适配器决定；默认实现是
 * {@link NoopTracer}，所以不配也能跑（M1–M3 的验收不受影响）。
 */
public interface AgentTracer {

    /** 一次任务运行的根 span。 */
    Span startTaskSpan(String taskId, String agentName, String userId);

    /**
     * 一步的 span。
     *
     * @param parent 上级 span；为 null 时按根 span 处理
     */
    Span startStepSpan(Span parent, int stepIndex, StepType stepType, String toolName);

    /** 优雅关闭（例如 flush 导出队列）。默认为空。 */
    default void shutdown() {
    }

    static AgentTracer noop() {
        return NoopTracer.INSTANCE;
    }
}
