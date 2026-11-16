package com.agentruntime.persistence;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存实现：单进程 / 评测 / 单元测试用（不跨进程，重启即丢）。
 *
 * <p>生产用 {@link JdbcTaskRepository}——它支持连接池、原子认领、租约 CAS 与存储侧累加；
 * 两者实现同一个接口，所以切换只发生在装配层。
 */
public class InMemoryTaskRepository implements TaskRepository {

    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final Map<String, List<TaskEvent>> events = new ConcurrentHashMap<>();
    private final AtomicLong eventSeq = new AtomicLong();

    @Override
    public void save(Task task) {
        task.touch();
        tasks.put(task.getTaskId(), task);
    }

    @Override
    public Optional<Task> findById(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    /**
     * 带所有权校验的事件追加（M10.2）。内存实现直接查同一个 Map，
     * 语义与 JDBC 的 {@code WHERE EXISTS} 一致：不是主人就不写、返回 {@code null}。
     */
    @Override
    public TaskEvent appendEventIfOwned(TaskEvent event, String ownerId) {
        if (ownerId == null) {
            return appendEvent(event);
        }
        Task owner = tasks.get(event.taskId());
        return owner != null && ownerId.equals(owner.getOwnerId()) ? appendEvent(event) : null;
    }

    @Override
    public TaskEvent appendEvent(TaskEvent event) {
        TaskEvent stored = event.withId(eventSeq.incrementAndGet());
        events.computeIfAbsent(event.taskId(), k -> new CopyOnWriteArrayList<>()).add(stored);
        return stored;
    }

    @Override
    public List<TaskEvent> findEvents(String taskId) {
        List<TaskEvent> list = events.get(taskId);
        if (list == null) {
            return List.of();
        }
        List<TaskEvent> copy = new ArrayList<>(list);
        copy.sort(Comparator.comparingLong(TaskEvent::id));
        return copy;
    }

    @Override
    public List<Task> findAll() {
        return List.copyOf(tasks.values());
    }

    /**
     * 内存版的累加也必须同步：{@code findById} 返回的是同一个对象，
     * 不加锁就会和 JDBC 版一样丢更新——那样本地测试通过、上线对不上账，是最坏的组合。
     */
    @Override
    public UsageTotal addUsage(String taskId, long tokens, BigDecimal cost) {
        Task task = tasks.get(taskId);
        if (task == null) {
            throw new com.agentruntime.exception.AgentRuntimeException("未知 taskId: " + taskId);
        }
        synchronized (task) {
            BigDecimal nextCost = task.getTotalCost() == null
                    ? (cost == null ? BigDecimal.ZERO : cost)
                    : task.getTotalCost().add(cost == null ? BigDecimal.ZERO : cost);
            long nextTokens = task.getTotalTokens() + tokens;
            task.setTotalCost(nextCost);
            task.setTotalTokens(nextTokens);
            return new UsageTotal(nextTokens, nextCost);
        }
    }

    @Override
    public void setUsage(String taskId, long tokens, BigDecimal cost) {
        Task task = tasks.get(taskId);
        if (task == null) {
            throw new com.agentruntime.exception.AgentRuntimeException("未知 taskId: " + taskId);
        }
        synchronized (task) {
            task.setTotalTokens(tokens);
            task.setTotalCost(cost == null ? BigDecimal.ZERO : cost);
        }
    }
}
