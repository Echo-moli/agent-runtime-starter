package com.agentruntime.exception;

/** 工具注册期错误：重名、方法签名不适配、未开启 -parameters 等。启动期暴露，不允许拖到运行期。 */
public class ToolRegistrationException extends AgentRuntimeException {

    public ToolRegistrationException(String message) {
        super(message);
    }
}
