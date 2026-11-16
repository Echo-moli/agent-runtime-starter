package com.agentruntime.exception;

/** 工具调用失败：参数缺失、类型不匹配，或业务方法本身抛出异常。 */
public class ToolInvocationException extends AgentRuntimeException {

    private final String toolName;

    public ToolInvocationException(String message) {
        super(message);
        this.toolName = null;
    }

    public ToolInvocationException(String toolName, Throwable cause) {
        super("工具 " + toolName + " 执行失败: " + cause, cause);
        this.toolName = toolName;
    }

    public String toolName() {
        return toolName;
    }
}
