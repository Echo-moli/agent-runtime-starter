package com.agentruntime.samples.refund;

import com.agentruntime.annotation.AgentTool;
import com.agentruntime.exception.TransientFailureException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 退款服务（mock 的真实副作用 + 故障注入）。
 *
 * <h2>为什么故障注入写在业务方法里，而不是外挂一个 mock 框架</h2>
 * 因为它要验证的是<b>引擎</b>在三种故障下的行为，而三种故障的触发点必须与库里那一行数据对齐：
 * <ul>
 *   <li>{@link Fault#HALT_BEFORE_SIDE_EFFECT}：幂等记录已 INIT，钱没退 → 恢复后可直接重试；</li>
 *   <li>{@link Fault#HALT_AFTER_SIDE_EFFECT}：幂等记录 INIT，钱已退 → 恢复后<b>必须挂起问人</b>
 *       （这两个状态在库里长得一模一样，这才是 M3 存在的理由）；</li>
 *   <li>{@link Fault#TRANSIENT}：前几次抛 {@link TransientFailureException}（明确声明没产生副作用），
 *       用于验证指数退避重试。</li>
 * </ul>
 */
public class RefundService {

    /** 故障剧本。 */
    public enum Fault {
        /** 正常。 */
        NONE,
        /** 副作用发生前 halt（M2 的崩溃演示）。 */
        HALT_BEFORE_SIDE_EFFECT,
        /** 副作用发生后 halt（M3 的在途未知演示——钱已退，但记录停在 INIT）。 */
        HALT_AFTER_SIDE_EFFECT,
        /** 前 {@code transientFailures} 次抛可重试异常。 */
        TRANSIENT
    }

    private final AtomicInteger refundExecutions = new AtomicInteger();
    private final AtomicInteger invocations = new AtomicInteger();
    private final Fault fault;
    private final int transientFailures;
    /** 人为延迟（M4 演示用），默认 0。 */
    private long latencyMs;
    /** 是否打印「真实副作用」流水（M5 评测时关掉，否则 20 条用例刷屏）。 */
    private boolean verbose = true;

    public RefundService() {
        this(Fault.NONE, 0);
    }

    public RefundService(Fault fault) {
        this(fault, 2);
    }

    public RefundService(Fault fault, int transientFailures) {
        this.fault = fault;
        this.transientFailures = transientFailures;
    }

    /** 每次退款模拟这么多毫秒的网关往返（M4 让 span 有可读的耗时）。 */
    public RefundService withLatency(long latencyMs) {
        this.latencyMs = Math.max(0, latencyMs);
        return this;
    }

    /**
     * 关闭副作用流水打印（M5）。
     *
     * <p>评测跑的是同一套业务代码，它不该被 demo 的输出格式绑架；但也不该为了评测
     * 就删掉业务代码里的日志——那等于改了被测对象。所以做成开关。
     */
    public RefundService withVerbose(boolean verbose) {
        this.verbose = verbose;
        return this;
    }

    /** 带幂等的退款：同一 {@code orderId} 只会真正执行一次。 */
    @AgentTool(name = "refund", idempotencyKey = "#orderId")
    public RefundReceipt refund(String orderId, BigDecimal amount) {
        return doRefund(orderId, amount);
    }

    /**
     * 需要人工确认的退款（大额场景的真实写法）。
     *
     * <p>{@code requireHumanConfirm=true} 让引擎在<b>调用前</b>挂起，工具方法本身完全不知道自己被拦过——
     * 这是与「在方法里抛 HumanConfirmRequiredException」相比更干净的地方：
     * 业务代码不用关心审批流程，它只管退钱。
     */
    @AgentTool(name = "refundRequiringApproval", idempotencyKey = "#orderId", requireHumanConfirm = true)
    public RefundReceipt refundRequiringApproval(String orderId, BigDecimal amount) {
        return doRefund(orderId, amount);
    }

    private RefundReceipt doRefund(String orderId, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("退款金额非法: " + amount);
        }
        SampleLatency.sleep(latencyMs);
        if (fault == Fault.HALT_BEFORE_SIDE_EFFECT) {
            System.out.printf("        └─ [模拟崩溃] 副作用之前 halt 进程（orderId=%s）%n", orderId);
            Runtime.getRuntime().halt(17);
        }

        int call = invocations.incrementAndGet();
        if (fault == Fault.TRANSIENT && call <= transientFailures) {
            // 明确声明：这次失败没有产生副作用，可以安全重试
            throw new TransientFailureException(
                    "退款网关暂时不可用（第 " + call + " 次调用，未产生副作用）", null);
        }

        int n = refundExecutions.incrementAndGet();
        if (verbose) {
            System.out.printf("        └─ [真实副作用] 调用退款接口 orderId=%s amount=%s（本进程第 %d 次扣款）%n",
                    orderId, amount, n);
        }
        RefundReceipt receipt = new RefundReceipt(
                "RFD-" + UUID.randomUUID().toString().substring(0, 8), orderId, amount, Instant.now());

        if (fault == Fault.HALT_AFTER_SIDE_EFFECT) {
            System.out.printf("        └─ [模拟崩溃] 副作用已发生，但幂等记录来不及写 DONE 就 halt（orderId=%s）%n",
                    orderId);
            Runtime.getRuntime().halt(17);
        }
        return receipt;
    }

    /** 本进程真实扣款次数（断言用）。 */
    public int refundExecutions() {
        return refundExecutions.get();
    }

    /** 本进程工具被调用的次数（含重试）。 */
    public int invocations() {
        return invocations.get();
    }
}
