package com.agentruntime.exception;

/**
 * 幂等记录停在 {@code INIT}：副作用是否真的发生过未知（见 {@code InDoubtPolicy}）。
 *
 * <p>由 {@code ToolExecutor} 抛出、{@code DecisionLoop} 捕获后转成「挂起 + 人工确认」，
 * 不会被记成任务失败——未决不等于失败。
 */
public class IdempotencyInDoubtException extends AgentRuntimeException {

    private final String idempotencyKey;

    public IdempotencyInDoubtException(String idempotencyKey, String toolName) {
        super("工具 " + toolName + " 的幂等记录 " + idempotencyKey
                + " 停在 INIT：上次执行的副作用是否已发生无法判定，需人工确认");
        this.idempotencyKey = idempotencyKey;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }
}
