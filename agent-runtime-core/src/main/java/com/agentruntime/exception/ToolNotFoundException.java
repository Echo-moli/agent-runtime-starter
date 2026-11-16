package com.agentruntime.exception;

/** 工具名未注册。 */
public class ToolNotFoundException extends AgentRuntimeException {

    public ToolNotFoundException(String message) {
        super(message);
    }
}
