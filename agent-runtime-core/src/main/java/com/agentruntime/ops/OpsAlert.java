package com.agentruntime.ops;

import java.time.Instant;
import java.util.Map;

/**
 * 告警出口（P2）。
 *
 * <p>框架本身不接任何告警平台：默认 no-op，企业接入时实现本接口转发到 PagerDuty / 企微 /
 * 钉钉即可。关键是<b>告警点已经埋好</b>——租约丢失、在途未知超期、鉴权拒绝这三类
 * 「人必须知道」的事件，不该等到出事故才发现没人报。
 */
@FunctionalInterface
public interface OpsAlert {

    /** @param severity WARN / CRIT；@param context 结构化上下文（taskId、owner 等）。 */
    void send(Severity severity, String event, String message, Map<String, Object> context);

    enum Severity {
        WARN, CRIT
    }

    OpsAlert NOOP = (severity, event, message, context) -> {
    };

    /** 告警事件名常量，避免各处硬编码字符串拼错。 */
    final class Events {
        public static final String LEASE_LOST = "agent.lease.lost";
        public static final String IN_DOUBT_STALE = "agent.indoubt.stale";
        public static final String OPS_DENIED = "agent.ops.denied";
        public static final String OPS_MUTATION = "agent.ops.mutation";
        public static final String EVAL_GATE_FAILED = "agent.eval.gate.failed";

        private Events() {
        }
    }

    /** 用于断言与简单转发的值对象。 */
    record AlertRecord(Instant at, Severity severity, String event, String message,
                       Map<String, Object> context) {
    }
}
