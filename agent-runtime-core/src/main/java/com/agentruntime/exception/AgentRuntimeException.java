package com.agentruntime.exception;

/** 引擎内部可预期的业务异常基类。 */
public class AgentRuntimeException extends RuntimeException {

    public AgentRuntimeException(String message) {
        super(message);
    }

    public AgentRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }
}
