package com.agentruntime.persistence;

import com.agentruntime.domain.TaskStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次 Agent 运行的聚合根（DESIGN.md §4.2）。
 *
 * <p>普通 POJO，字段与 §7 的 {@code task} 表一一对应。刻意不用 JPA 实体（M8 的取舍，
 * 见根 README 偏差表）：引擎依赖 JSONB、存储侧原子累加、以及「幂等 DONE 与 outbox 同事务」，
 * ORM 表达这些要么失真、要么还是要写原生 SQL。
 */
public class Task {

    private final String taskId;
    private final String agentName;
    private TaskStatus status;
    private final String userId;
    private final String userRequest;
    private int currentStep;
    /** 分层记忆的工作区部分；M1–M4 全量存，M5+ 再做压缩。 */
    private final Map<String, Object> context = new LinkedHashMap<>();
    private final Instant createdAt;
    private Instant restoredCreatedAt;
    private Instant updatedAt;
    private BigDecimal totalCost = BigDecimal.ZERO;
    private long totalTokens;
    /**
     * 当前持有租约的实例（M10）。
     *
     * <p>没有它，「RUNNING 的任务」在数据库眼里只有一种样子：可能是另一个实例正在跑，
     * 也可能是上一个实例崩了。批量恢复无法区分这两者，滚动发布时就会抢跑。
     */
    private String ownerId;
    /** 最后一次心跳。租约是否过期由 {@code ownerId + heartbeatAt} 一起判断。 */
    private Instant heartbeatAt;

    public Task(String taskId, String agentName, String userId, String userRequest) {
        this.taskId = taskId;
        this.agentName = agentName;
        this.userId = userId;
        this.userRequest = userRequest;
        this.status = TaskStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getAgentName() {
        return agentName;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public String getUserId() {
        return userId;
    }

    public String getUserRequest() {
        return userRequest;
    }

    public int getCurrentStep() {
        return currentStep;
    }

    public void setCurrentStep(int currentStep) {
        this.currentStep = currentStep;
    }

    public Map<String, Object> getContext() {
        return context;
    }

    public Instant getCreatedAt() {
        return restoredCreatedAt != null ? restoredCreatedAt : createdAt;
    }

    /** 供持久化层还原历史值（仅 JDBC 读取路径使用，业务代码不要调）。 */
    public void setCreatedAt(Instant createdAt) {
        this.restoredCreatedAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** 供持久化层还原历史值。 */
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    public String getOwnerId() {
        return ownerId;
    }

    public Instant getHeartbeatAt() {
        return heartbeatAt;
    }

    /** 供持久化层还原历史值。 */
    public void setOwner(String ownerId, Instant heartbeatAt) {
        this.ownerId = ownerId;
        this.heartbeatAt = heartbeatAt;
    }

    /**
     * 该任务的租约是否已经过期（{@code staleBefore} 之前没有心跳）。
     *
     * <p>没有 owner 的老数据（M2–M9 写的行）一律视为「无主」：它们可能是升级前
     * 崩溃留下的，也可能是升级瞬间还在跑的——所以只把<b>明确过期</b>的当孤儿。
     */
    public boolean isLeaseExpired(Instant staleBefore) {
        if (ownerId == null || heartbeatAt == null) {
            return true;
        }
        return heartbeatAt.isBefore(staleBefore);
    }

    public BigDecimal getTotalCost() {
        return totalCost;
    }

    public void setTotalCost(BigDecimal totalCost) {
        this.totalCost = totalCost;
    }

    public long getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(long totalTokens) {
        this.totalTokens = totalTokens;
    }

    @Override
    public String toString() {
        return "Task{" + taskId + ", agent=" + agentName + ", status=" + status
                + ", step=" + currentStep + ", owner=" + ownerId + '}';
    }
}
