package com.agentruntime.exception;

/** 评测通过率低于门槛（M5）：CI 门禁失败时抛出。 */
public class EvalGateFailedException extends AgentRuntimeException {

    private final double passRate;
    private final double threshold;

    public EvalGateFailedException(String message, double passRate, double threshold) {
        super(message);
        this.passRate = passRate;
        this.threshold = threshold;
    }

    /** 实际通过率（0..1）。 */
    public double passRate() {
        return passRate;
    }

    /** 门槛（0..1）。 */
    public double threshold() {
        return threshold;
    }
}
