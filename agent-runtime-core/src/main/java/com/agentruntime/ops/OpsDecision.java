package com.agentruntime.ops;

/** 鉴权结果。DENY 必须带原因，否则被拒的人不知道找谁申请权限。 */
public record OpsDecision(boolean allowed, String reason) {

    private static final OpsDecision ALLOW = new OpsDecision(true, null);

    public static OpsDecision allow() {
        return ALLOW;
    }

    public static OpsDecision deny(String reason) {
        return new OpsDecision(false, reason == null ? "未授权" : reason);
    }

    public boolean denied() {
        return !allowed;
    }
}
