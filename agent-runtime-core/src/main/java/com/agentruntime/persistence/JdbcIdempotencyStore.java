package com.agentruntime.persistence;

import com.agentruntime.exception.AgentRuntimeException;
import com.agentruntime.persistence.IdempotencyStore.Claim;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC 实现的幂等记录 + Outbox（M3）。
 *
 * <p>M8 起连接由 {@link ConnectionSource} 提供：demo 传单连接，Spring 自动装配传
 * {@link DataSource}。两种模式共用同一套 SQL 与同一套「谁先占到主键谁执行」的语义。
 */
public class JdbcIdempotencyStore implements IdempotencyStore, OutboxStore, IdempotencyInspector {

    private static final String INSERT_INIT =
            "INSERT INTO idempotency_record (idempotency_key, task_id, step_index, status, created_at, updated_at) "
                    + "VALUES (?,?,?,?,?,?)";

    private static final String MARK_DONE =
            "UPDATE idempotency_record SET status='DONE', result_payload=?, updated_at=? "
                    + "WHERE idempotency_key=?";

    private static final String MARK_FAILED =
            "UPDATE idempotency_record SET status='FAILED', result_payload=?, updated_at=? "
                    + "WHERE idempotency_key=?";

    private static final String INSERT_OUTBOX =
            "INSERT INTO outbox (event_type, aggregate_id, payload, created_at) VALUES (?,?,?,?)";

    private final ConnectionSource source;

    /** 单连接模式（M3 起的既有写法，demo 与测试用）。 */
    public JdbcIdempotencyStore(Connection connection) {
        this(ConnectionSource.single(connection));
    }

    /** 连接池模式（M8）：Spring 自动装配用这个。 */
    public JdbcIdempotencyStore(DataSource dataSource) {
        this(ConnectionSource.pooled(dataSource));
    }

    public JdbcIdempotencyStore(ConnectionSource source) {
        this.source = source;
    }

    public static JdbcIdempotencyStore open(String url, String user, String password) {
        try {
            return new JdbcIdempotencyStore(DriverManager.getConnection(url, user, password));
        } catch (SQLException e) {
            throw new AgentRuntimeException("无法连接数据库: " + url, e);
        }
    }

    @Override
    public Optional<IdempotencyRecord> find(String idempotencyKey) {
        try {
            return source.withConnection(connection -> findInternal(connection, idempotencyKey));
        } catch (SQLException e) {
            throw new AgentRuntimeException("读取幂等记录失败: " + idempotencyKey, e);
        }
    }

    private Optional<IdempotencyRecord> findInternal(Connection connection, String key)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM idempotency_record WHERE idempotency_key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    /**
     * 占位：{@code INSERT} 撞主键才算「别人先到」，绝不先查后写。
     *
     * <p>M8 注意：整个方法跑在<b>同一个</b> {@code withConnection} 里。池化模式下如果
     * 「插入失败 → 再查一次」分两次借连接，两段之间别的实例可能又把状态推进一格，
     * 读到的分类与插入时的冲突就不是同一个瞬间的事实。
     */
    @Override
    public Claim begin(String idempotencyKey, String taskId, int stepIndex) {
        try {
            return source.withConnection(connection -> {
                Instant now = Instant.now();
                try (PreparedStatement ps = connection.prepareStatement(INSERT_INIT)) {
                    ps.setString(1, idempotencyKey);
                    ps.setString(2, taskId);
                    ps.setInt(3, stepIndex);
                    ps.setString(4, IdempotencyRecord.Status.INIT.name());
                    ps.setTimestamp(5, Timestamp.from(now));
                    ps.setTimestamp(6, Timestamp.from(now));
                    ps.executeUpdate();
                    return Claim.claimed();
                } catch (SQLException e) {
                    if (!isDuplicateKey(e)) {
                        throw new AgentRuntimeException("占位幂等键失败: " + idempotencyKey, e);
                    }
                }
                // 撞主键：别人已经占过位，按现有状态分流
                for (int round = 0; round < 3; round++) {
                    IdempotencyRecord existing = findInternal(connection, idempotencyKey)
                            .orElseThrow(() -> new AgentRuntimeException(
                                    "幂等键 " + idempotencyKey + " 冲突但读不到记录（数据库一致性异常）"));
                    switch (existing.status()) {
                        case DONE:
                            return Claim.reused(existing);
                        case INIT:
                            return Claim.inDoubt(existing);
                        case FAILED:
                            if (reclaim(connection, idempotencyKey, taskId, stepIndex)) {
                                return Claim.claimed();
                            }
                            // 抢输给了另一个实例，重新读状态再分类
                            break;
                    }
                }
                throw new AgentRuntimeException("幂等键 " + idempotencyKey + " 状态竞争未能收敛");
            });
        } catch (SQLException e) {
            throw new AgentRuntimeException("占位幂等键失败: " + idempotencyKey, e);
        }
    }

    private boolean reclaim(Connection connection, String key, String taskId, int stepIndex)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE idempotency_record SET status='INIT', task_id=?, step_index=?, result_payload=NULL, "
                        + "updated_at=? WHERE idempotency_key=? AND status='FAILED'")) {
            ps.setString(1, taskId);
            ps.setInt(2, stepIndex);
            ps.setTimestamp(3, Timestamp.from(Instant.now()));
            ps.setString(4, key);
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * 标记完成 + 写 outbox，<b>同一事务</b>。
     *
     * <p>这是 M3 最不能退让的一条：分开写会出现「幂等记录说做过了、事件没发出去」，
     * 下游对账永远差一条，而且没有任何报错。池化模式下事务边界由
     * {@code withConnection} 保证——两条语句在同一个连接上，才不会各自成为独立事务。
     */
    /**
     * 超期 INIT 巡检（M9）。
     *
     * <p>{@code updated_at < cutoff} 而不是 {@code created_at}：记录在 FAILED 后被重新占位时
     * {@code updated_at} 会刷新，用创建时间会把「刚重试过」的也算成超期。
     */
    @Override
    public List<IdempotencyRecord> findStale(java.time.Duration olderThan, int limit) {
        if (olderThan == null || limit <= 0) {
            return List.of();
        }
        Timestamp cutoff = Timestamp.from(Instant.now().minus(olderThan));
        try {
            return source.withConnection(connection -> {
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT * FROM idempotency_record WHERE status='INIT' AND updated_at < ? "
                                + "ORDER BY updated_at LIMIT ?")) {
                    ps.setTimestamp(1, cutoff);
                    ps.setInt(2, limit);
                    try (ResultSet rs = ps.executeQuery()) {
                        List<IdempotencyRecord> stale = new ArrayList<>();
                        while (rs.next()) {
                            stale.add(map(rs));
                        }
                        return stale;
                    }
                }
            });
        } catch (SQLException e) {
            throw new AgentRuntimeException("巡检超期幂等记录失败", e);
        }
    }

    @Override
    public long countStale(java.time.Duration olderThan) {
        if (olderThan == null) {
            return 0L;
        }
        Timestamp cutoff = Timestamp.from(Instant.now().minus(olderThan));
        try {
            return source.withConnection(connection -> {
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT COUNT(*) FROM idempotency_record WHERE status='INIT' AND updated_at < ?")) {
                    ps.setTimestamp(1, cutoff);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getLong(1) : 0L;
                    }
                }
            });
        } catch (SQLException e) {
            throw new AgentRuntimeException("统计超期幂等记录失败", e);
        }
    }

    @Override
    public void complete(String idempotencyKey, String resultPayload, OutboxEvent event) {
        try {
            source.withConnection(connection -> {
                boolean autoCommit = connection.getAutoCommit();
                try {
                    connection.setAutoCommit(false);
                    Instant now = Instant.now();
                    try (PreparedStatement ps = connection.prepareStatement(MARK_DONE)) {
                        JsonbParams.set(ps, 1, resultPayload);
                        ps.setTimestamp(2, Timestamp.from(now));
                        ps.setString(3, idempotencyKey);
                        ps.executeUpdate();
                    }
                    if (event != null) {
                        try (PreparedStatement ps = connection.prepareStatement(INSERT_OUTBOX)) {
                            ps.setString(1, event.eventType());
                            ps.setString(2, event.aggregateId());
                            JsonbParams.set(ps, 3, event.payloadJson());
                            ps.setTimestamp(4, Timestamp.from(now));
                            ps.executeUpdate();
                        }
                    }
                    connection.commit();
                } catch (SQLException e) {
                    rollbackQuietly(connection);
                    throw new AgentRuntimeException("提交幂等结果失败（已回滚，不会出现半成品）: "
                            + idempotencyKey, e);
                } finally {
                    restoreAutoCommit(connection, autoCommit);
                }
                return null;
            });
        } catch (SQLException e) {
            throw new AgentRuntimeException("提交幂等结果失败: " + idempotencyKey, e);
        }
    }

    @Override
    public void fail(String idempotencyKey, String errorPayload) {
        try {
            source.withConnection(connection -> {
                try (PreparedStatement ps = connection.prepareStatement(MARK_FAILED)) {
                    JsonbParams.set(ps, 1, errorPayload);
                    ps.setTimestamp(2, Timestamp.from(Instant.now()));
                    ps.setString(3, idempotencyKey);
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (SQLException e) {
            throw new AgentRuntimeException("标记幂等失败失败: " + idempotencyKey, e);
        }
    }

    @Override
    public List<OutboxEntry> pollPending(int limit) {
        try {
            return source.withConnection(connection -> {
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT * FROM outbox WHERE published_at IS NULL ORDER BY id LIMIT ?")) {
                    ps.setInt(1, limit);
                    try (ResultSet rs = ps.executeQuery()) {
                        List<OutboxEntry> list = new ArrayList<>();
                        while (rs.next()) {
                            list.add(mapOutboxEntry(rs));
                        }
                        return list;
                    }
                }
            });
        } catch (SQLException e) {
            throw new AgentRuntimeException("读取 outbox 失败", e);
        }
    }

    @Override
    public void markPublished(long id) {
        try {
            source.withConnection(connection -> {
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE outbox SET published_at=?, attempts=attempts+1 WHERE id=?")) {
                    ps.setTimestamp(1, Timestamp.from(Instant.now()));
                    ps.setLong(2, id);
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (SQLException e) {
            throw new AgentRuntimeException("标记 outbox 已投递失败: " + id, e);
        }
    }

    @Override
    public void markFailed(long id, String error) {
        try {
            source.withConnection(connection -> {
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE outbox SET attempts=attempts+1, last_error=? WHERE id=?")) {
                    ps.setString(1, error == null ? null : truncate(error, 1000));
                    ps.setLong(2, id);
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (SQLException e) {
            throw new AgentRuntimeException("标记 outbox 投递失败失败: " + id, e);
        }
    }

    @Override
    public long pendingCount() {
        try {
            return source.withConnection(connection -> {
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT COUNT(*) FROM outbox WHERE published_at IS NULL");
                     ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            });
        } catch (SQLException e) {
            throw new AgentRuntimeException("统计 outbox 待投递失败", e);
        }
    }

    private IdempotencyRecord map(ResultSet rs) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp updated = rs.getTimestamp("updated_at");
        return new IdempotencyRecord(
                rs.getString("idempotency_key"),
                rs.getString("task_id"),
                rs.getInt("step_index"),
                IdempotencyRecord.Status.valueOf(rs.getString("status")),
                rs.getString("result_payload"),
                created == null ? Instant.now() : created.toInstant(),
                updated == null ? Instant.now() : updated.toInstant());
    }

    private OutboxEntry mapOutboxEntry(ResultSet rs) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp published = rs.getTimestamp("published_at");
        return new OutboxEntry(
                rs.getLong("id"),
                rs.getString("event_type"),
                rs.getString("aggregate_id"),
                rs.getString("payload"),
                created == null ? Instant.now() : created.toInstant(),
                published == null ? null : published.toInstant(),
                rs.getInt("attempts"),
                rs.getString("last_error"));
    }

    private void restoreAutoCommit(Connection connection, boolean value) {
        try {
            connection.setAutoCommit(value);
        } catch (SQLException ignored) {
            // 恢复失败不应掩盖原始异常
        }
    }

    private void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 同上
        }
    }

    private static boolean isDuplicateKey(SQLException e) {
        if (e instanceof SQLIntegrityConstraintViolationException) {
            return true;
        }
        String state = e.getSQLState();
        // 23505 = unique_violation（PostgreSQL / H2 一致）
        return "23505".equals(state);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
