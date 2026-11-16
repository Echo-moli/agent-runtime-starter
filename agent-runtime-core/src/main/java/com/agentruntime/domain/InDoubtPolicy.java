package com.agentruntime.domain;

/**
 * 「副作用状态未知」时的处置策略。
 *
 * <p>场景：{@code idempotency_record} 里该 key 停在 {@code INIT}——说明上次执行已经<b>开始</b>，
 * 但没走到「写 DONE」。此时无法从数据库区分两种事实：
 * <ol>
 *   <li>崩溃发生在副作用<b>之前</b>（钱没退）；</li>
 *   <li>崩溃发生在副作用<b>之后</b>、写 DONE 之前（钱已退）。</li>
 * </ol>
 * 两者在存储层完全同构，任何自动化猜测都是 50% 的资损概率。
 *
 * <p>因此默认是 {@link #SUSPEND_FOR_HUMAN}：宁可停下来让人看一眼，也不替业务方赌。
 * 这是 DESIGN.md §2「幂等防资损」在实现上的必然结论——框架不替钱做决定。
 */
public enum InDoubtPolicy {
    /** 挂起任务，等人工作答（默认，资损敏感场景的唯一正确选择）。 */
    SUSPEND_FOR_HUMAN,
    /** 直接重试副作用（仅适用于天然幂等的下游，如覆盖式写）。 */
    RETRY,
    /** 判定失败，不再执行。 */
    FAIL
}
