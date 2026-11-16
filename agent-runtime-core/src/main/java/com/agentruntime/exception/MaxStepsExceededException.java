package com.agentruntime.exception;

/** 步数超过上限——通常意味着 {@code AgentPlan.decide()} 没有收敛，属于业务缺陷而非引擎故障。 */
public class MaxStepsExceededException extends AgentRuntimeException {

    public MaxStepsExceededException(int maxSteps) {
        super("任务步数超过上限 " + maxSteps + "，疑似 AgentPlan.decide() 未收敛");
    }
}
