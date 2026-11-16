package com.agentruntime.engine;

import com.agentruntime.domain.InDoubtPolicy;
import com.agentruntime.exception.AgentRuntimeException;
import com.agentruntime.exception.IdempotencyInDoubtException;
import com.agentruntime.exception.TransientFailureException;
import com.agentruntime.persistence.IdempotencyStore;
import com.agentruntime.persistence.OutboxEvent;
import com.agentruntime.util.Json;
import com.agentruntime.util.JsonParser;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 工具执行的可靠性外壳（DESIGN §6 M3 的核心）：
 * <b>幂等占位 → 重试退避 → 结果缓存与 outbox → 截断</b>，四件事按这个顺序做。
 *
 * <h2>为什么顺序不能换</h2>
 * 占位必须在副作用<b>之前</b>。先执行再记录，中间任何一次崩溃都会留下「钱退了但没有记录」，
 * 下次必然再退一遍。占位的代价是可能留下「记录在但钱没退」（INIT），
 * 后者可以靠人工判别，前者不可逆——两种不一致里只能选可修复的那个。
 *
 * <h2>重试的安全边界（M3 最重要的判断）</h2>
 * 对<b>有副作用</b>的工具（声明了 {@code idempotencyKey}），默认<b>不自动重试</b>任何异常：
 * 因为「读超时」和「请求没到」在异常层面无法区分，前者重试就是多退一笔钱。
 * 只有业务方显式抛 {@link TransientFailureException}（声明「肯定没发生副作用」）才重试。
 * 无副作用的工具（纯查询）按 {@link RetryPolicy} 正常重试。
 *
 * <p>反过来，有副作用的工具遇到<b>语义不明</b>的异常时不会被打成 FAILED，
 * 而是抛 {@link IdempotencyInDoubtException} 让任务挂起等人工——这是本项目对「防资损」的最终态度：
 * 不确定的时候，框架选择停下来，而不是替人做决定。
 */
public final class ToolExecutor {

    /** 人工对上次执行状态的裁定。 */
    public enum Directive {
        /** 没有人工裁定，按默认策略走。 */
        NONE,
        /** 人工确认「上次确实没发生」，允许强制再执行一次。 */
        FORCE_RETRY,
        /** 人工直接给出了结果（例如查到了退款流水号），不再执行副作用。 */
        USE_GIVEN_RESULT
    }

    /** 一次执行的人为干预信息。 */
    public record Hint(Directive directive, Object humanResult) {

        public static final Hint NONE = new Hint(Directive.NONE, null);

        public static Hint forceRetry() {
            return new Hint(Directive.FORCE_RETRY, null);
        }

        public static Hint givenResult(Object result) {
            return new Hint(Directive.USE_GIVEN_RESULT, result);
        }
    }

    /**
     * 一次执行的完整事实，全部要进事件（否则出事时无法复盘）。
     *
     * @param output         交给 plan 的结果（可能已被截断）
     * @param fromCache      是否命中幂等缓存（true 表示本次没有真实副作用）
     * @param fromHuman      结果是否由人工提供
     * @param attempts       实际调用次数（缓存命中为 0）
     * @param idempotencyKey 幂等键，无幂等的工具为 null
     * @param truncated      是否发生截断
     */
    public record Execution(Object output, boolean fromCache, boolean fromHuman,
                            int attempts, String idempotencyKey, boolean truncated) {
    }

    private final IdempotencyStore idempotency;
    private final RetryPolicy retry;
    private final InDoubtPolicy inDoubtPolicy;
    private final Consumer<String> log;

    public ToolExecutor(IdempotencyStore idempotency, RetryPolicy retry, InDoubtPolicy inDoubtPolicy,
                        Consumer<String> log) {
        this.idempotency = idempotency;
        this.retry = retry == null ? RetryPolicy.exponential() : retry;
        this.inDoubtPolicy = inDoubtPolicy == null ? InDoubtPolicy.SUSPEND_FOR_HUMAN : inDoubtPolicy;
        this.log = log == null ? s -> {
        } : log;
    }

    public Execution execute(ToolRegistry.ToolHandle handle, Map<String, Object> args,
                             String taskId, int stepIndex, Hint hint) {
        Hint effective = hint == null ? Hint.NONE : hint;
        String key = idempotency == null
                ? null
                : IdempotencyKeys.evaluate(handle.meta().idempotencyKey(), handle.name(), args);

        if (key != null) {
            IdempotencyStore.Claim claim = idempotency.begin(key, taskId, stepIndex);
            switch (claim.outcome()) {
                case REUSED -> {
                    if (effective.directive() != Directive.FORCE_RETRY) {
                        log.accept("[幂等] 命中缓存，跳过真实副作用（key=" + key + "）");
                        return new Execution(parseCached(key, claim.existing().resultPayload()),
                                true, false, 0, key, false);
                    }
                    log.accept("[幂等] 人工裁定强制重试，忽略已有的 DONE 记录（key=" + key + "）");
                }
                case IN_DOUBT -> {
                    if (effective.directive() == Directive.USE_GIVEN_RESULT) {
                        idempotency.complete(key, Json.write(effective.humanResult()),
                                outboxEvent(handle, taskId, key, 0, effective.humanResult()));
                        log.accept("[幂等] 采用人工提供的结果，不再执行副作用（key=" + key + "）");
                        return new Execution(effective.humanResult(), false, true, 0, key, false);
                    }
                    if (effective.directive() != Directive.FORCE_RETRY) {
                        switch (inDoubtPolicy) {
                            case SUSPEND_FOR_HUMAN -> throw new IdempotencyInDoubtException(key, handle.name());
                            case FAIL -> throw new AgentRuntimeException(
                                    "工具 " + handle.name() + " 的幂等记录 " + key + " 停在 INIT，策略=FAIL");
                            case RETRY -> log.accept("[幂等] 策略=RETRY，对未知状态强制重试（key=" + key + "）");
                        }
                    }
                }
                case CLAIMED -> {
                    // 本调用者拿到执行权，继续往下走
                }
            }
        } else if (effective.directive() == Directive.USE_GIVEN_RESULT) {
            return new Execution(effective.humanResult(), false, true, 0, null, false);
        }

        int attempts = 0;
        int maxAttempts = Math.max(1, handle.meta().maxAttempts());
        while (true) {
            attempts++;
            try {
                Object output = ToolInvoker.invoke(handle, args);
                if (key != null) {
                    // 全量结果进幂等缓存（截断只影响事件可读性，不影响缓存）
                    idempotency.complete(key, Json.write(output),
                            outboxEvent(handle, taskId, key, attempts, output));
                }
                Object visible = ResultTruncator.truncate(output, handle.meta().resultTruncateChars());
                return new Execution(visible, false, false, attempts, key,
                        visible != output);
            } catch (RuntimeException e) {
                boolean declaredTransient = ExponentialBackoffRetry.rootCause(e) instanceof TransientFailureException;
                boolean businessRejection = isBusinessRejection(e);
                boolean sideEffectful = key != null;

                // 重试资格：有副作用的工具只认「业务明确声明没发生副作用」这一种情况
                boolean canRetry = attempts < maxAttempts
                        && !businessRejection
                        && (sideEffectful ? declaredTransient : retry.retryable(e));

                if (!canRetry) {
                    if (sideEffectful && !businessRejection && !declaredTransient) {
                        // 结果语义不明：保持 INIT，让 recover / 人工看到「未知」而不是被骗成 FAILED
                        throw new IdempotencyInDoubtException(key, handle.name());
                    }
                    if (key != null) {
                        idempotency.fail(key, Json.write(Map.of("error", describe(e), "attempts", attempts)));
                    }
                    throw e;
                }
                long delay = Math.max(0, retry.delayMs(attempts, handle.meta().retryBaseDelayMs()));
                log.accept("[重试] 工具 " + handle.name() + " 第 " + attempts + " 次尝试失败（" + describe(e)
                        + "），" + delay + "ms 后重试");
                sleep(delay);
            }
        }
    }

    private OutboxEvent outboxEvent(ToolRegistry.ToolHandle handle, String taskId, String key,
                                    int attempts, Object result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId);
        payload.put("toolName", handle.name());
        payload.put("idempotencyKey", key);
        payload.put("attempts", attempts);
        payload.put("result", result);
        String json = Json.write(payload);
        return new OutboxEvent("tool.executed", taskId, json.length() > 8000 ? json.substring(0, 8000) : json);
    }

    /**
     * 业务拒绝 = 业务方明确说「这事不成」，不是基础设施抖动。
     * 这类异常不重试，且可以直接把幂等记录标成 FAILED（因为没走到副作用）。
     */
    private static boolean isBusinessRejection(Throwable e) {
        Throwable root = ExponentialBackoffRetry.rootCause(e);
        return root instanceof IllegalArgumentException
                || root instanceof IllegalStateException
                || root instanceof UnsupportedOperationException;
    }

    private static Object parseCached(String key, String payload) {
        if (payload == null || payload.isBlank()) {
            throw new AgentRuntimeException("幂等记录 " + key + " 是 DONE 但没有缓存结果，无法复用");
        }
        return JsonParser.parse(payload);
    }

    private static String describe(Throwable e) {
        String msg = e.getMessage();
        return msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg;
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentRuntimeException("重试等待被中断", e);
        }
    }
}
