package com.agentruntime.ops;

/**
 * 运维动作分类（P0 安全）。
 *
 * <p>鉴权与审计都按「动作」而非 URL 字符串工作：URL 会改，动作语义不会。
 * {@link #isMutation()} 为 true 的是危险动作——会改变任务状态或触发副作用，必须鉴权 + 审计。
 */
public enum OpsAction {
    RECOVER(false),
    RESUME_APPROVE(true),
    RESUME_REJECT(true),
    RESUME_FORCE(true),
    RECOVER_ALL_DRY(false),
    RECOVER_ALL_APPLY(true);

    private final boolean mutation;

    OpsAction(boolean mutation) {
        this.mutation = mutation;
    }

    public boolean isMutation() {
        return mutation;
    }
}
