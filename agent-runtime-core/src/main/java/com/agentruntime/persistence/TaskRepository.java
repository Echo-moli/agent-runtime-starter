package com.agentruntime.persistence;

import com.agentruntime.domain.TaskStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 任务与事件的存取口（DESIGN.md §9 的 {@code *Repository}）。
 *
 * <p>三个实现：内存版（单进程/评测）、JDBC 版（生产，支持连接池）、以及业务方自己的实现。
 * 接口只承诺语义（原子认领、存储侧累加、租约 CAS），不承诺存储技术——
 * 这也是把引擎与存储解耦的意义。
 */
public interface TaskRepository {

    /** 保存/更新任务聚合根。 */
    void save(Task task);

    Optional<Task> findById(String taskId);

    /** 追加一条事件；id 由仓库分配，入参中的 id 被忽略。 */
    TaskEvent appendEvent(TaskEvent event);

    /** 按 stepIndex 升序返回该任务的全部事件。 */
    List<TaskEvent> findEvents(String taskId);

    /** 全部任务（供「启动时恢复所有 RUNNING 任务」使用）。 */
    List<Task> findAll();

    /**
     * 原子累加用量，返回累加后的总量（M4）。
     *
     * <h2>为什么必须有这个方法，而不是 {@code task.setTotalCost(old + delta)}</h2>
     * 读改写是有损的：两个线程（同一任务的重试线程 + 指标回调，或两台实例共享库）
     * 同时读到 {@code 0.10}，各自加 {@code 0.05} 再写回，结果永远是 {@code 0.15} 而不是 {@code 0.20}——
     * 账面上少了钱，而且<b>没有任何报错</b>。DESIGN §6 M4 只写了「每事件写回」，
     * 照那个写法在并发下就是对不上账。
     *
     * <p>实现必须保证累加发生在存储侧（JDBC 用
     * {@code UPDATE ... SET total_tokens = total_tokens + ?}），且返回值是累加后的<b>真值</b>；
     * 调用方拿它刷新内存对象，不要自己算。
     */
    UsageTotal addUsage(String taskId, long tokens, BigDecimal cost);

    /**
     * 绝对覆盖累计用量（M4）：<b>只</b>用于「按事件账本校正物化汇总」。
     *
     * <p>{@link #appendEvent} 与 {@link #addUsage} 是两条语句，中间崩溃会让汇总少算一步。
     * 事件才是账本（appendEvent 在前且不可变），汇总是可重算的物化视图——
     * 所以恢复时按事件重算并覆盖，而不是「尽量补一笔」。用这个方法做累加是错的。
     */
    void setUsage(String taskId, long tokens, BigDecimal cost);

    /**
     * 按状态列出任务（M9，运维端点用）。
     *
     * <p>默认实现是「全量拉回来再过滤」，只为让自定义实现不必立刻改代码。
     * <b>JDBC 实现已覆盖为 {@code WHERE status=?}</b>——运维端点在百万级任务表上
     * 必须走覆盖版，否则一次「看看有多少任务挂起了」就够把数据库拖垮。
     *
     * @param limit 最多返回多少条（端点不该一次吐出十万行）
     */
    default List<Task> findByStatus(TaskStatus status, int limit) {
        List<Task> matched = new java.util.ArrayList<>();
        for (Task task : findAll()) {
            if (task.getStatus() == status) {
                matched.add(task);
                if (matched.size() >= limit) {
                    break;
                }
            }
        }
        return matched;
    }

    /**
     * 带租约的认领（M10）：只有「状态符合」<b>且</b>「租约已过期或本来就是自己」时才成功。
     *
     * <h2>为什么需要它</h2>
     * {@link #tryClaim} 只看状态。可 {@code RUNNING} 在库里只有一种样子：
     * 可能是另一台实例正在跑，也可能是上一个实例崩了。批量恢复用它判断孤儿，
     * 就会在滚动发布时把「正在跑」的任务抢过来重放——决策被重复计费、副作用被重复请求
     * （幂等能兜住钱，但兜不住浪费与挂起）。
     *
     * <p>{@code owner_id + heartbeat_at} 才让这两种情况可区分：心跳新鲜 = 有人还活着。
     *
     * @param staleBefore 心跳早于这个时刻才算过期；调用方一般是
     *                    {@code now.minus(租约 TTL)}
     */
    default boolean claimWithLease(String taskId, Set<TaskStatus> expected, TaskStatus target,
                                   String ownerId, Instant staleBefore) {
        return findById(taskId)
                .filter(t -> expected.contains(t.getStatus()))
                .filter(t -> t.isLeaseExpired(staleBefore) || ownerId.equals(t.getOwnerId()))
                .map(t -> {
                    t.setStatus(target);
                    t.setOwner(ownerId, Instant.now());
                    t.touch();
                    save(t);
                    return true;
                })
                .orElse(false);
    }

    /**
     * 带所有权校验的事件追加（M10.2）：不是主人就<b>不写</b>，返回 {@code null}。
     *
     * <h2>为什么事件也要校验</h2>
     * M10.1 把状态写入改成了 CAS，但事件仍是无条件 {@code INSERT}。设想：A 在一次慢工具调用里
     * 丢了租约，B 已经接管并重放到第 1 步；A 的工具这时才返回，于是它把 {@code TOOL_CALL(refund)}
     * 写进了同一个任务的账本——账本里出现了「另一个主人执行的一步」，而任务实际只由 B 推进。
     * 事后对账时这条事件无法解释：它既不是 B 的操作，也不是被 B 重放的操作。
     *
     * <p>实现用一条语句完成「校验 + 写入」：
     * {@code INSERT ... SELECT ... WHERE EXISTS (SELECT 1 FROM task WHERE task_id=? AND owner_id=?)}。
     * 影响行数为 0 即说明租约已易主，调用方必须停下。
     */
    default TaskEvent appendEventIfOwned(TaskEvent event, String ownerId) {
        if (ownerId == null) {
            return appendEvent(event);
        }
        boolean mine = findById(event.taskId())
                .filter(t -> ownerId.equals(t.getOwnerId()))
                .isPresent();
        return mine ? appendEvent(event) : null;
    }

    /**
     * 带所有权与状态校验的保存（M10.1）：CAS 写入，失败即「我已经不是主人了」。
     *
     * <h2>为什么普通 {@link #save} 不够</h2>
     * {@code save} 无条件写 {@code status / current_step / context_json}。设想：
     * A 的心跳过期 → B 认领（库里变成 {@code RECOVERING}，owner=B）→ A 那一轮工具刚好跑完，
     * 它手里还是「RUNNING，第 1 步」的旧对象，一次 {@code save} 就把 B 的认领覆盖了回去。
     * 双跑于是从「理论上可能」变成「一定发生」。
     *
     * <p>所以状态写入必须带条件：{@code WHERE task_id=? AND owner_id=? AND status=?}。
     * 这条 UPDATE 同时承担三件事：校验主人、校验状态没被别人动过、写入新状态——
     * 判断与写入不能再分成两步（跟幂等占位靠主键冲突是一个道理）。
     *
     * <p><b>关于 {@code expectedStatus}</b>：默认实现只用它做「尽力而为」的校验，不做真正的
     * 状态 CAS——因为内存仓储返回的就是循环手里那个对象，读到的永远是<b>新</b>状态，
     * 拿它比对必然假失败。状态 CAS 需要存储侧能力，{@code JdbcTaskRepository} 覆盖版做了。
     * 这一点是刻意的降级：单进程的内存实现本来就没有「另一个实例」，
     * 租约校验对它没有意义，有意义的只是别假装做到了。
     *
     * @param expectedStatus 调用方认为数据库里<b>当前</b>是什么状态（JDBC 实现用它做 CAS）
     * @return {@code false} 表示租约（或状态，JDBC）已经变了，调用方必须停下且不要重试
     */
    default boolean saveIfOwned(Task task, String ownerId, TaskStatus expectedStatus) {
        if (ownerId == null) {
            save(task);
            return true;
        }
        boolean mine = findById(task.getTaskId())
                .filter(t -> ownerId.equals(t.getOwnerId()))
                .isPresent();
        if (!mine) {
            return false;
        }
        save(task);
        return true;
    }

    /**
     * 续租（M10）：只有自己持有的任务才续，返回是否续上。
     *
     * <p>返回 false 说明租约已经被人拿走（或任务已终态）——调用方应当<b>停下来</b>，
     * 而不是继续跑。这就是「双跑」的最后一道闸：即便真的被抢了，原持有者也会很快发现。
     */
    default boolean renewLease(String taskId, String ownerId, Instant heartbeatAt) {
        return findById(taskId)
                .filter(t -> ownerId.equals(t.getOwnerId()))
                .map(t -> {
                    t.setOwner(ownerId, heartbeatAt);
                    save(t);
                    return true;
                })
                .orElse(false);
    }

    /**
     * 某状态的任务总数（M9）：运维看板要的是「挂起了多少」，不是「前 50 条长什么样」。
     *
     * <p>默认实现靠 {@link #findByStatus} 全量过滤；JDBC 实现覆盖为 {@code SELECT COUNT(*)}。
     */
    default long countByStatus(TaskStatus status) {
        return findByStatus(status, Integer.MAX_VALUE).size();
    }

    /** 累加后的累计用量。 */
    record UsageTotal(long tokens, BigDecimal cost) {
    }

    /**
     * 原子认领：仅当当前状态在 {@code expected} 集合内时改为 {@code target}，返回是否认领成功。
     *
     * <p>这是多实例防双跑的关键：两个进程同时对同一 {@code taskId} 调 {@code recover}，
     * 只有一个的 UPDATE 会命中。内存实现是非原子的近似（单 JVM 内不测并发抢占），
     * JDBC 实现是真正的 {@code UPDATE ... WHERE status IN (...)} 原子操作。
     */
    default boolean tryClaim(String taskId, Set<TaskStatus> expected, TaskStatus target) {
        return findById(taskId)
                .filter(t -> expected.contains(t.getStatus()))
                .map(t -> {
                    t.setStatus(target);
                    save(t);
                    return true;
                })
                .orElse(false);
    }
}
