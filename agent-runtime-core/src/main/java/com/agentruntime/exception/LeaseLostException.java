package com.agentruntime.exception;

/**
 * 租约已经不属于本实例（M10.1）。
 *
 * <h2>什么时候会抛</h2>
 * M10 加了 {@code owner_id + heartbeat_at}，但只解决了「谁<em>有资格</em>抢」；
 * 原持有者如果只是丢了租约、还继续跑，它下一次保存就会把状态改回去——
 * 那台新持有者刚认领的 {@code RECOVERING} 会被覆盖成 {@code RUNNING}，双跑照样成立。
 *
 * <p>所以所有写入都要带「我以为的状态 + 我以为的主人」，用一条 CAS 语句落地。
 * CAS 失败就是这里：<b>你已经不是这个任务的主人了，立刻停下，什么都不要写。</b>
 *
 * <h2>为什么它不是失败</h2>
 * 调用方（{@code DefaultAgentEngine}）捕获它之后<b>不会</b>把任务标成 FAILED——
 * 任务还好好的，只是换了主人。标 FAILED 才是真正的破坏：新持有者会看到
 * 「我刚认领的任务怎么失败了」。
 */
public class LeaseLostException extends AgentRuntimeException {

    private final String taskId;
    private final String ownerId;

    public LeaseLostException(String taskId, String ownerId) {
        super("任务 " + taskId + " 的租约已不属于本实例（" + ownerId + "）：已停止执行，未写入任何状态");
        this.taskId = taskId;
        this.ownerId = ownerId;
    }

    public String taskId() {
        return taskId;
    }

    public String ownerId() {
        return ownerId;
    }
}
