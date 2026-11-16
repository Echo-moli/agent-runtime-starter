package com.agentruntime.exception;

/**
 * 业务代码用它声明一件事：「这次失败<b>肯定没产生副作用</b>，可以安全重试」。
 *
 * <p>这个类型存在的唯一理由是钱：重试一个退款接口时，框架无法从异常里看出
 * 「网关 503（请求根本没到下游）」和「读超时（下游已经扣钱了）」的区别。
 * 后者重试就是多退一笔。因此默认规则是<b>有副作用的工具不自动重试</b>，
 * 只有业务明确抛本异常时才重试。
 *
 * <p>用法示例（连接池拿不到连接、下游明确返回「请重试」语义的错误码）：
 * <pre>
 *   if (resp.status() == 503) throw new TransientFailureException("退款网关暂不可用，可重试", null);
 * </pre>
 */
public class TransientFailureException extends AgentRuntimeException {

    public TransientFailureException(String message) {
        super(message);
    }

    public TransientFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
