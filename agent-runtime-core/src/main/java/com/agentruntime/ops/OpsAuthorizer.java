package com.agentruntime.ops;

/**
 * 运维动作鉴权策略（P0 安全）。
 *
 * <h2>默认策略为什么是「可配的宽容」而非「默认拒绝」</h2>
 * 这个 starter 的历史行为是端点完全开放——直接把默认改成拒绝会让所有现有接入方
 * 在一夜之间失去运维能力。所以默认实现 {@link #permissive()} 保留旧行为<b>但记录审计</b>，
 * 真正的企业部署应当注入自己的实现（例如把 {@code OpsPrincipal} 接到 Spring Security）。
 *
 * <h2>归属校验落在这里</h2>
 * 评审指出的具体风险是「任何人拿到 taskId 就能 resume/recover」。
 * {@link #authorize(OpsAction, String, String, OpsPrincipal)} 的
 * {@code ownerUserId} 参数就是任务的归属人：策略可以据此要求「操作者必须是任务提交人
 * 或具备 OPS_ADMIN 角色」。starter 端点在调用引擎前<b>必须先过这一层</b>。
 */
@FunctionalInterface
public interface OpsAuthorizer {

    /**
     * @param action      动作（区分只读/变更）
     * @param taskId      任务号；批量操作时为 null
     * @param ownerUserId 任务归属人（从 {@code Task.userId} 读）；批量时为 null
     * @param principal   当前操作者
     * @return 允许或拒绝（拒绝必须带原因）
     */
    OpsDecision authorize(OpsAction action, String taskId, String ownerUserId, OpsPrincipal principal);

    /** 兼容模式：全部放行，但调用方仍会写审计。企业部署请覆盖此 bean。 */
    static OpsAuthorizer permissive() {
        return (action, taskId, owner, principal) -> OpsDecision.allow();
    }

    /**
     * 归属校验策略：读操作放行；变更操作要求操作者是任务归属人或具备 {@code OPS_ADMIN}。
     * 无归属信息（批量）时只认管理员。这是「拿到 taskId 也不能乱动」的标准答案。
     */
    static OpsAuthorizer ownerOrAdmin() {
        return (action, taskId, owner, principal) -> {
            if (!action.isMutation()) {
                return OpsDecision.allow();
            }
            if (principal == null || principal.isAnonymous()) {
                return OpsDecision.deny("匿名主体不得执行变更操作 " + action);
            }
            if (principal.hasRole(OpsPrincipal.ROLE_OPS_ADMIN)) {
                return OpsDecision.allow();
            }
            if (owner != null && owner.equals(principal.name())) {
                return OpsDecision.allow();
            }
            return OpsDecision.deny("主体 " + principal.name()
                    + " 既非任务归属人 (" + owner + ") 也不具备 " + OpsPrincipal.ROLE_OPS_ADMIN
                    + " 角色，不得执行 " + action);
        };
    }
}
