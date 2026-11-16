package com.agentruntime.samples.refund;

/**
 * 演示用的「假网络延迟」。
 *
 * <p>M4 的 span 树要能看出「哪一步慢」，如果所有工具都是 0ms，时长这一列全是 0，
 * 既看不出问题也验证不了计时。默认值 0，所以既有 demo 的行为完全不变。
 */
final class SampleLatency {

    private SampleLatency() {
    }

    static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
