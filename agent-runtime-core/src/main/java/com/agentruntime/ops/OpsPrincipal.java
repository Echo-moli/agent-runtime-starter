package com.agentruntime.ops;

/**
 * 当前操作者身份（P0 安全）。
 *
 * <p>starter 不绑定任何安全框架：{@code OpsPrincipal} 是个简单 record，
 * Spring Security 的适配由业务方写一行 bean 把 {@code Authentication.getName()} 映射进来即可。
 * core 侧只认「名字 + 角色集合」这两个事实，不认 JWT / Session / 网关头——
 * 那是应用层的责任，不该渗进运行时内核。
 *
 * @param name  主体名（唯一的、可审计的；如工号或服务账号）
 * @param roles 角色集合（如 {@code OPS_ADMIN}、{@code FINANCE_APPROVER}）
 */
public record OpsPrincipal(String name, java.util.Set<String> roles) {

    public static final String ROLE_OPS_ADMIN = "OPS_ADMIN";
    /** 匿名：只有显式配置允许匿名读写时才具备权限。 */
    public static final OpsPrincipal ANONYMOUS = new OpsPrincipal("anonymous", java.util.Set.of());

    public OpsPrincipal {
        if (name == null || name.isBlank()) {
            name = "anonymous";
        }
        roles = roles == null ? java.util.Set.of() : java.util.Set.copyOf(roles);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean isAnonymous() {
        return "anonymous".equals(name);
    }
}
