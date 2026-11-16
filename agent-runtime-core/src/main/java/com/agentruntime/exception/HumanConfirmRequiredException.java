package com.agentruntime.exception;

/**
 * DESIGN.md §6 M3 要求的 {@code HumanConfirmRequiredException}。
 *
 * <p>保留这个类型是为了对齐规格：业务方在工具内部自行判断需要人工确认时，可以抛它；
 * {@code DecisionLoop} 会把它当成「挂起」而不是「失败」处理。
 *
 * <p>本工程的默认路径<b>不靠抛异常做挂起</b>：{@code requireHumanConfirm=true} 的门禁在
 * {@code DecisionLoop} 调工具<b>之前</b>判定，直接写 SUSPENDED 并正常返回。
 * 原因很实在——单连接 JDBC（以及 Spring 的声明式事务）下，抛异常触发的是回滚，
 * 刚写进去的 SUSPENDED 会跟着事务一起消失。这个坑在 DESIGN.md §6 M3 的写法里是存在的，
 * 详见 README 的偏差表。
 */
public class HumanConfirmRequiredException extends AgentRuntimeException {

    private final String toolName;

    public HumanConfirmRequiredException(String toolName, String reason) {
        super("工具 " + toolName + " 需要人工确认后执行：" + reason);
        this.toolName = toolName;
    }

    public String toolName() {
        return toolName;
    }
}
