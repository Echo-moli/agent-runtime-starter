package com.agentruntime.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 把一个普通业务方法登记为 Agent 可调用的工具。
 *
 * <p>对齐 DESIGN.md §5.1。M1 阶段引擎只消费 {@link #name()}，其余属性用于 M3（幂等/重试/挂起）。
 *
 * <p>参数绑定按 <b>方法形参名</b> 匹配 {@code Step.tool(name, args)} 的 key，因此业务代码必须用
 * {@code -parameters} 编译（本工程的 parent pom 已开启）。形参名退化（如 {@code arg0}）时引擎在注册期直接
 * 抛错，而不是等到运行时才静默绑错参数。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AgentTool {

    /** 工具名，全局唯一。留空则取方法名。 */
    String name();

    /** SpEL 表达式，如 {@code "#orderId"}；留空表示不做幂等（M3 已生效）。
     *
     * <p>本实现只支持 {@code #arg} 与 {@code #arg.field}（不引 SpEL，见 {@code IdempotencyKeys}）。
     * 求值结果为 null 时直接抛错（fail closed）：没有幂等键就不执行有副作用的工具。 */
    String idempotencyKey() default "";

    /** 最大尝试次数（M3 已生效）。有副作用的工具只对 `TransientFailureException` 计数。 */
    int maxAttempts() default 3;

    /** 退避基准毫秒（M3 已生效）：第 n 次失败后等 `retryBaseDelayMs * 2^(n-1)`，上限 10s。 */
    long retryBaseDelayMs() default 500;

    /** 结果截断字符数（M3 已生效）：超限结果为 `{_truncated:true,...,body:"..."}`，仍是合法 JSON。 */
    int resultTruncateChars() default 4000;

    /** 执行前是否需要人工确认（M3 已生效）：引擎在调用前挂起，工具方法本身无感知。 */
    boolean requireHumanConfirm() default false;
}
