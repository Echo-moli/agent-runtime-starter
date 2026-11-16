package com.agentruntime.persistence;

import com.agentruntime.domain.StepType;
import com.agentruntime.domain.TaskStatus;
import com.agentruntime.exception.AgentRuntimeException;
import com.agentruntime.util.Json;
import com.agentruntime.util.JsonParser;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

/**
 * JDBC 实现的 Task/TaskEvent 仓储（M2）。
 *
 * <p>表结构对齐 DESIGN.md §7（PostgreSQL DDL 为准绳）；示例用 H2 的 PostgreSQL 兼容模式
 * 跑同一套语义，见 samples 模块的 {@code schema-h2.sql}。
 *
 * <p>实现约定（刻意写进注释，避免 M3 时踩坑）：
 * <ul>
 *   <li>连接由 {@link ConnectionSource} 提供：单连接（demo）或连接池（生产，M8 自动装配用
 *       {@link #JdbcTaskRepository(DataSource)}）。借还边界与「一个方法=一块逻辑」对齐；</li>
 *   <li>每条语句独立提交（autocommit）：事件「先落库再执行副作用」是 at-least-once 语义，
 *       崩溃窗口内的重复由 M3 的幂等记录兜底——这正是 DESIGN §11「幂等边界」决策的落地方式；</li>
 *   <li>payload / context 走 {@link Json} 写、{@link JsonParser} 读，
 *       还原后的结果是 {@code Map}（JSONB 的事实），业务 plan 需按字段名读取；</li>
 *   <li>{@link #tryClaim} 是真正的原子 UPDATE，多实例同时 recover 只会命中一个。</li>
 * </ul>
 */
public class JdbcTaskRepository implements TaskRepository {

    // 注意：total_cost / total_tokens 故意<b>不在</b>这条 UPDATE 里。
    // M10 同理：owner_id / heartbeat_at 也不在这里——租约只能由 claimWithLease / renewLease 写。
    // 否则一个「已经丢了租约但还没察觉」的实例，下一次 save() 就会把别人的租约覆盖回去，
    // 双跑就从「理论上可能」变成「一定会」。单写入口是这里唯一的解法。
    // 它们只能由 addUsage 在存储侧原子累加；一旦让 save 回写内存里的旧值，
    // 并发下就会把别人刚加上的钱覆盖掉（丢更新）。计数列只有一个写入口，这是刻意的。
    private static final String UPDATE_TASK =
            "UPDATE task SET agent_name=?, status=?, current_step=?, context_json=?, "
                    + "updated_at=? WHERE task_id=?";

    private static final String INSERT_TASK =
            "INSERT INTO task (task_id, agent_name, status, user_id, user_request, current_step, "
                    + "context_json, total_cost, total_tokens, owner_id, heartbeat_at, "
                    + "created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private static final String INSERT_EVENT =
            "INSERT INTO task_event (task_id, step_index, step_type, tool_name, payload, success, "
                    + "tokens, cost, duration_ms, created_at) VALUES (?,?,?,?,?,?,?,?,?,?)";

    private final ConnectionSource source;

    /** 单连接模式（M2 起的既有写法，demo 与测试用）。 */
    public JdbcTaskRepository(Connection connection) {
        this(ConnectionSource.single(connection));
    }

    /** 连接池模式（M8）：Spring 自动装配用这个。 */
    public JdbcTaskRepository(DataSource dataSource) {
        this(ConnectionSource.pooled(dataSource));
    }

    public JdbcTaskRepository(ConnectionSource source) {
        this.source = source;
    }

    /** 便捷工厂：用 DriverManager 打开单连接。 */
    public static JdbcTaskRepository open(String url, String user, String password) {
        try {
            return new JdbcTaskRepository(DriverManager.getConnection(url, user, password));
        } catch (SQLException e) {
            throw new AgentRuntimeException("无法连接数据库: " + url, e);
        }
    }

    @Override
    public void save(Task task) {
        try {
            source.withConnection(connection -> {
                int updated = tryUpdate(connection, task);
                if (updated == 0) {
                    insert(connection, task);
                }
                return null;
            });
        } catch (SQLException e) {
            throw new AgentRuntimeException("保存任务失败: " + task.getTaskId(), e);
        }
    }

    private int tryUpdate(Connection connection, Task task) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(UPDATE_TASK)) {
            ps.setString(1, task.getAgentName());
            ps.setString(2, task.getStatus().name());
            ps.setInt(3, task.getCurrentStep());
            JsonbParams.set(ps, 4, Json.write(task.getContext()));
            ps.setTimestamp(5, Timestamp.from(Instant.now()));
            ps.setString(6, task.getTaskId());
            return ps.executeUpdate();
        }
    }

    private void insert(Connection connection, Task task) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(INSERT_TASK)) {
            ps.setString(1, task.getTaskId());
            ps.setString(2, task.getAgentName());
            ps.setString(3, task.getStatus().name());
            ps.setString(4, task.getUserId());
            ps.setString(5, task.getUserRequest());
            ps.setInt(6, task.getCurrentStep());
            JsonbParams.set(ps, 7, Json.write(task.getContext()));
            ps.setBigDecimal(8, task.getTotalCost());
            ps.setLong(9, task.getTotalTokens());
            ps.setString(10, task.getOwnerId());
            ps.setTimestamp(11, task.getHeartbeatAt() == null
                    ? null : Timestamp.from(task.getHeartbeatAt()));
            ps.setTimestamp(12, Timestamp.from(task.getCreatedAt()));
            ps.setTimestamp(13, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
    }

    @Override
    public Optional<Task> findById(String taskId) {
        try {
            return source.withConnection(connection -> readById(connection, taskId));
        } catch (SQLException e) {
            throw new AgentRuntimeException("读取任务失败: " + taskId, e);
        }
    }

    private Optional<Task> readById(Connection connection, String taskId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM task WHERE task_id=?")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapTask(rs)) : Optional.empty();
            }
        }
    }

    @Override
    public TaskEvent appendEvent(TaskEvent event) {
        try {
            return source.withConnection(connection -> {
                try (PreparedStatement ps = connection.prepareStatement(INSERT_EVENT,
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, event.taskId());
                    ps.setInt(2, event.stepIndex());
                    ps.setString(3, event.stepType().name());
                    ps.setString(4, event.toolName());
                    JsonbParams.set(ps, 5, event.payload().isEmpty() ? null : Json.write(event.payload()));
                    ps.setBoolean(6, event.success());
                    ps.setLong(7, event.tokens());
                    ps.setBigDecimal(8, event.cost());
                    ps.setLong(9, event.durationMs());
                    ps.setTimestamp(10, Timestamp.from(
                            event.createdAt() == null ? Instant.now() : event.createdAt()));
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (keys.next()) {
                            return event.withId(keys.getLong(1));
                        }
                        throw new AgentRuntimeException("数据库未返回事件自增 id: " + event.taskId());
                    }
                }
            });
        } catch (SQLException e) {
            throw new AgentRuntimeException("写入事件失败: " + event.taskId(), e);
        }
    }

    @Override
    public List<TaskEvent> findEvents(String taskId) {
        try {
            return source.withConnection(connection -> {
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT * FROM task_event WHERE task_id=? ORDER BY id")) {
                    ps.setString(1, taskId);
                    try (ResultSet rs = ps.executeQuery()) {
                        List<TaskEvent> events = new ArrayList<>();
                        while (rs.next()) {
                            events.add(mapEvent(rs));
                        }
                        return events;
                    }
                }
            });
        } catch (SQLException e) {
            throw new AgentRuntimeException("读取事件失败: " + taskId, e);
        }
    }

    @Override
    public List<Task> findAll() {
        List<String> ids;
        try {
            ids = source.withConnection(connection -> {
                List<String> collected = new ArrayList<>();
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("SELECT task_id FROM task")) {
                    while (rs.next()) {
                        collected.add(rs.getString(1));
                    }
                }
                return collected;
            });
        } catch (SQLException e) {
            throw new AgentRuntimeException("读取任务列表失败", e);
        }
        List<Task> tasks = new ArrayList<>(ids.size());
        for (String id : ids) {
            findById(id).ifPresent(tasks::add);
        }
        return tasks;
    }

    /**
     * 覆盖默认实现：走 {@code WHERE status=? ... LIMIT ?}，不把整张任务表拉进内存。
     *
     * <p>按 {@code updated_at DESC} 排序：运维最先想看的是「刚刚卡住的那批」。
     */
    @Override
    public List<Task> findByStatus(TaskStatus status, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        try {
            return source.withConnection(connection -> {
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT * FROM task WHERE status=? ORDER BY updated_at DESC LIMIT ?")) {
                    ps.setString(1, status.name());
                    ps.setInt(2, limit);
                    try (ResultSet rs = ps.executeQuery()) {
                        List<Task> tasks = new ArrayList<>();
                        while (rs.next()) {
                            tasks.add(mapTask(rs));
                        }
                        return tasks;
                    }
                }
            });
        } catch (SQLException e) {
            throw new AgentRuntimeException("按状态读取任务失败: " + status, e);
        }
    }

    /**
     * 带租约的原子认领（M10）：{@code UPDATE ... WHERE status IN (...) AND (租约过期 OR 是我的)}。
     *
     * <p>判断与写入在同一条 UPDATE 里完成——先查再写会让两台实例同时判定「它过期了」，
     * 然后都去抢，结果两条 UPDATE 都成功。这跟幂等占位靠主键冲突是同一个道理。
     */
    @Override
    public boolean claimWithLease(String taskId, Set<TaskStatus> expected, TaskStatus target,
                                  String ownerId, Instant staleBefore) {
        if (expected.isEmpty()) {
            return false;
        }
        StringJoiner in = new StringJoiner(",", "(", ")");
        for (int i = 0; i < expected.size(); i++) {
            in.add("?");
        }
        String sql = "UPDATE task SET status=?, updated_at=?, owner_id=?, heartbeat_at=? "
                + "WHERE task_id=? AND status IN " + in
                + " AND (owner_id IS NULL OR heartbeat_at IS NULL OR heartbeat_at < ? OR owner_id = ?)";
        try {
            return source.withConnection(connection -> {
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    Timestamp now = Timestamp.from(Instant.now());
                    ps.setString(1, target.name());
                    ps.setTimestamp(2, now);
                    ps.setString(3, ownerId);
                    ps.setTimestamp(4, now);
                    ps.setString(5, taskId);
                    int i = 6;
                    for (TaskStatus status : expected) {
                        ps.setString(i++, status.name());
                    }
                    ps.setTimestamp(i++, Timestamp.from(staleBefore));
                    ps.setString(i, ownerId);
                    return ps.executeUpdate() == 1;
                }
            });
        } catch (SQLException e) {
            throw new AgentRuntimeException("认领任务失败（租约）: " + taskId, e);
        }
    }

    /**
     * 带所有权校验的事件追加（M10.2）：{@code INSERT ... SELECT ... WHERE EXISTS(任务属于我)}。
     *
     * <p>写成一条语句而不是「先查 owner 再 INSERT」，是因为后者中间有一个可被抢占的窗口——
     * 而整个改动要解决的恰恰就是这个窗口。
     */
    @Override
    public TaskEvent appendEventIfOwned(TaskEvent event, String ownerId) {
        if (ownerId == null) {
            return appendEvent(event);
        }
        String sql = "INSERT INTO task_event (task_id, step_index, step_type, tool_name, payload, "
                + "success, tokens, cost, duration_ms, created_at) "
                + "SELECT ?,?,?,?,?,?,?,?,?,? "
                + "WHERE EXISTS (SELECT 1 FROM task WHERE task_id=? AND owner_id=?)";
        try {
            return source.withConnection(connection -> {
                try (PreparedStatement ps = connection.prepareStatement(sql,
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, event.taskId());
                    ps.setInt(2, event.stepIndex());
                    ps.setString(3, event.stepType().name());
                    ps.setString(4, event.toolName());
                    JsonbParams.set(ps, 5, event.payload().isEmpty() ? null : Json.write(event.payload()));
                    ps.setBoolean(6, event.success());
                    ps.setLong(7, event.tokens());
                    ps.setBigDecimal(8, event.cost());
                    ps.setLong(9, event.durationMs());
                    ps.setTimestamp(10, Timestamp.from(
                            event.createdAt() == null ? Instant.now() : event.createdAt()));
                    ps.setString(11, event.taskId());
                    ps.setString(12, ownerId);
                    if (ps.executeUpdate() == 0) {
                        return null;   // 已不是主人：事件不落库
                    }
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        return keys.next() ? event.withId(keys.getLong(1)) : event;
                    }
                }
            });
        } catch (SQLException e) {
            throw new AgentRuntimeException("写入事件失败（租约校验）: " + event.taskId(), e);
        }
    }

    /**
     * CAS 保存（M10.1）：{@code WHERE task_id=? AND owner_id=? AND status=?}。
     *
     * <p>注意这里写的是<b>新</b>状态（来自 task），CAS 条件用的是<b>期望的旧</b>状态（参数）。
     * 一条语句同时完成「校验 + 写入」，不存在「先查确认是我的、再写」中间被别人抢走的窗口。
     */
    @Override
    public boolean saveIfOwned(Task task, String ownerId, TaskStatus expectedStatus) {
        if (ownerId == null) {
            save(task);
            return true;
        }
        try {
            int updated = source.withConnection(connection -> {
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE task SET status=?, current_step=?, context_json=?, updated_at=? "
                                + "WHERE task_id=? AND owner_id=? AND status=?")) {
                    ps.setString(1, task.getStatus().name());
                    ps.setInt(2, task.getCurrentStep());
                    JsonbParams.set(ps, 3, Json.write(task.getContext()));
                    ps.setTimestamp(4, Timestamp.from(Instant.now()));
                    ps.setString(5, task.getTaskId());
                    ps.setString(6, ownerId);
                    ps.setString(7, expectedStatus.name());
                    return ps.executeUpdate();
                }
            });
            return updated == 1;
        } catch (SQLException e) {
            throw new AgentRuntimeException("保存任务失败（租约 CAS）: " + task.getTaskId(), e);
        }
    }

    /**
     * 续租（M10）：只更新 {@code heartbeat_at}，<b>不动 {@code updated_at}</b>。
     *
     * <p>两个时间戳的语义不同：{@code updated_at} 是「业务上最后一次有进展」，
     * 运维端点用它算「已经等了多久」；{@code heartbeat_at} 是「这台机器还活着」。
     * 让心跳去刷 updated_at，会让一个卡了两小时的任务在控制台上显示「刚更新」。
     */
    @Override
    public boolean renewLease(String taskId, String ownerId, Instant heartbeatAt) {
        try {
            return source.withConnection(connection -> {
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE task SET heartbeat_at=? WHERE task_id=? AND owner_id=?")) {
                    ps.setTimestamp(1, Timestamp.from(heartbeatAt));
                    ps.setString(2, taskId);
                    ps.setString(3, ownerId);
                    return ps.executeUpdate() == 1;
                }
            });
        } catch (SQLException e) {
            throw new AgentRuntimeException("续租失败: " + taskId, e);
        }
    }

    @Override
    public long countByStatus(TaskStatus status) {
        try {
            return source.withConnection(connection -> {
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT COUNT(*) FROM task WHERE status=?")) {
                    ps.setString(1, status.name());
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getLong(1) : 0L;
                    }
                }
            });
        } catch (SQLException e) {
            throw new AgentRuntimeException("统计任务数失败: " + status, e);
        }
    }

    @Override
    public boolean tryClaim(String taskId, Set<TaskStatus> expected, TaskStatus target) {
        StringJoiner in = new StringJoiner(",", "(", ")");
        for (int i = 0; i < expected.size(); i++) {
            in.add("?");
        }
        String sql = "UPDATE task SET status=?, updated_at=? WHERE task_id=? AND status IN "
                + in.toString();
        try {
            return source.withConnection(connection -> {
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, target.name());
                    ps.setTimestamp(2, Timestamp.from(Instant.now()));
                    ps.setString(3, taskId);
                    int i = 4;
                    for (TaskStatus status : expected) {
                        ps.setString(i++, status.name());
                    }
                    return ps.executeUpdate() == 1;
                }
            });
        } catch (SQLException e) {
            throw new AgentRuntimeException("认领任务失败: " + taskId, e);
        }
    }

    /**
     * 存储侧原子累加（M4）：{@code SET total_tokens = total_tokens + ?, total_cost = total_cost + ?}。
     *
     * <p>这条 SQL 是整个成本归因的正确性基座。换成「查出来 + 加 + 写回」，两台实例各跑一半步骤时
     * 账面上会少钱且不报错——成本归因一旦不可信，后面所有以它为输入的工作（预算、配额、报价）都废了。
     *
     * <p>M8：更新与读回放在<b>同一个</b> {@code withConnection} 里。单连接模式下这与原来等价；
     * 池化模式下这一步很关键——分开借连接会读到别的实例的中间态，也会白白多借一次。
     */
    @Override
    public UsageTotal addUsage(String taskId, long tokens, BigDecimal cost) {
        BigDecimal delta = cost == null ? BigDecimal.ZERO : cost;
        try {
            return source.withConnection(connection -> {
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE task SET total_tokens = total_tokens + ?, total_cost = total_cost + ?, "
                                + "updated_at = ? WHERE task_id = ?")) {
                    ps.setLong(1, tokens);
                    ps.setBigDecimal(2, delta);
                    ps.setTimestamp(3, Timestamp.from(Instant.now()));
                    ps.setString(4, taskId);
                    if (ps.executeUpdate() == 0) {
                        throw new AgentRuntimeException("累加用量失败，任务不存在: " + taskId);
                    }
                }
                Task fresh = readById(connection, taskId)
                        .orElseThrow(() -> new AgentRuntimeException("累加用量后读不到任务: " + taskId));
                return new UsageTotal(fresh.getTotalTokens(), fresh.getTotalCost());
            });
        } catch (SQLException e) {
            throw new AgentRuntimeException("累加用量失败: " + taskId, e);
        }
    }

    @Override
    public void setUsage(String taskId, long tokens, BigDecimal cost) {
        try {
            source.withConnection(connection -> {
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE task SET total_tokens=?, total_cost=?, updated_at=? WHERE task_id=?")) {
                    ps.setLong(1, tokens);
                    ps.setBigDecimal(2, cost == null ? BigDecimal.ZERO : cost);
                    ps.setTimestamp(3, Timestamp.from(Instant.now()));
                    ps.setString(4, taskId);
                    if (ps.executeUpdate() == 0) {
                        throw new AgentRuntimeException("校正用量失败，任务不存在: " + taskId);
                    }
                }
                return null;
            });
        } catch (SQLException e) {
            throw new AgentRuntimeException("校正用量失败: " + taskId, e);
        }
    }

    private Task mapTask(ResultSet rs) throws SQLException {
        Task task = new Task(
                rs.getString("task_id"),
                rs.getString("agent_name"),
                rs.getString("user_id"),
                rs.getString("user_request"));
        task.setStatus(TaskStatus.valueOf(rs.getString("status")));
        task.setCurrentStep(rs.getInt("current_step"));
        task.setTotalCost(rs.getBigDecimal("total_cost") == null ? BigDecimal.ZERO : rs.getBigDecimal("total_cost"));
        task.setTotalTokens(rs.getLong("total_tokens"));
        Timestamp heartbeat = rs.getTimestamp("heartbeat_at");
        String ownerId = rs.getString("owner_id");
        if (ownerId != null) {
            task.setOwner(ownerId, heartbeat == null ? null : heartbeat.toInstant());
        }
        Timestamp createdAt = rs.getTimestamp("created_at");
        if (createdAt != null) {
            task.setCreatedAt(createdAt.toInstant());
        }
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        if (updatedAt != null) {
            task.setUpdatedAt(updatedAt.toInstant());
        }
        String contextJson = rs.getString("context_json");
        if (contextJson != null && !contextJson.isBlank()) {
            Map<String, Object> parsed = JsonParser.parseObject(contextJson);
            task.getContext().putAll(new LinkedHashMap<>(parsed));
        }
        return task;
    }

    private TaskEvent mapEvent(ResultSet rs) throws SQLException {
        Map<String, Object> payload = new LinkedHashMap<>();
        String payloadJson = rs.getString("payload");
        if (payloadJson != null && !payloadJson.isBlank()) {
            payload.putAll(JsonParser.parseObject(payloadJson));
        }
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new TaskEvent(
                rs.getLong("id"),
                rs.getString("task_id"),
                rs.getInt("step_index"),
                StepType.valueOf(rs.getString("step_type")),
                rs.getString("tool_name"),
                payload,
                rs.getBoolean("success"),
                rs.getLong("tokens"),
                rs.getBigDecimal("cost") == null ? BigDecimal.ZERO : rs.getBigDecimal("cost"),
                rs.getLong("duration_ms"),
                createdAt == null ? Instant.now() : createdAt.toInstant());
    }
}
