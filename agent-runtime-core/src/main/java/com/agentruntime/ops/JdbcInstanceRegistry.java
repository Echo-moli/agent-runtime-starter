package com.agentruntime.ops;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import javax.sql.DataSource;

/**
 * 跨实例共享的实例注册表（JDBC 实现）。
 *
 * <p>内存版只在本进程可见：多副本部署时每个实例都认为「只有自己报到过」，
 * 注册表就退化成一个永远返回 false 的第二意见——跟没有它一样。本实现把报到信息
 * 放进共享数据库，任何实例都能看到别人的心跳，恢复决策才真的多了一个独立信号。
 *
 * <p><b>单调性</b>：心跳只往前走（{@code WHERE last_heartbeat < ?}），避免乱序消息或
 * 时钟回拨把活跃实例「写死」。首次报到用主键冲突收口，和幂等占位是同一个套路——
 * 先写，冲突了再更新，不先查后写。
 *
 * <p>这里不引任何第三方依赖，只用 JDK 自带的 {@link DataSource}；
 * PG 与 H2 的语法差异由 DDL 脚本吸收，SQL 本身双方言通用。
 */
public final class JdbcInstanceRegistry implements InstanceRegistry {

    private static final String BUMP =
            "UPDATE agent_instance SET last_heartbeat=? WHERE instance_id=? AND last_heartbeat < ?";

    private static final String INSERT =
            "INSERT INTO agent_instance (instance_id, last_heartbeat, registered_at) VALUES (?,?,?)";

    private static final String DELETE =
            "DELETE FROM agent_instance WHERE instance_id=?";

    private static final String IS_ACTIVE =
            "SELECT 1 FROM agent_instance WHERE instance_id=? AND last_heartbeat >= ?";

    private static final String LIST =
            "SELECT instance_id, last_heartbeat FROM agent_instance ORDER BY last_heartbeat DESC";

    private static final String EVICT =
            "DELETE FROM agent_instance WHERE last_heartbeat < ?";

    private final DataSource dataSource;

    public JdbcInstanceRegistry(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource 不能为空");
    }

    @Override
    public void heartbeat(String instanceId, Instant at) {
        if (instanceId == null || instanceId.isBlank()) {
            return;
        }
        Instant when = at == null ? Instant.now() : at;
        try (Connection c = dataSource.getConnection()) {
            if (bump(c, instanceId, when) == 0) {
                try (PreparedStatement ps = c.prepareStatement(INSERT)) {
                    ps.setString(1, instanceId);
                    ps.setTimestamp(2, Timestamp.from(when));
                    ps.setTimestamp(3, Timestamp.from(Instant.now()));
                    ps.executeUpdate();
                } catch (SQLException duplicate) {
                    if (!isDuplicateKey(duplicate)) {
                        throw duplicate;
                    }
                    // 另一个进程刚插入：再走一次带单调条件的更新，不覆盖它的新心跳。
                    bump(c, instanceId, when);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("实例注册表心跳失败: " + instanceId, e);
        }
    }

    @Override
    public void deregister(String instanceId) {
        if (instanceId == null) {
            return;
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(DELETE)) {
            ps.setString(1, instanceId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("实例注册表注销失败: " + instanceId, e);
        }
    }

    @Override
    public boolean isActive(String instanceId, Duration within) {
        if (instanceId == null || within == null) {
            return false;
        }
        // 与 InMemory 版同一语义：用调用方时钟算窗口，不偷偷改用数据库时钟。
        Timestamp cutoff = Timestamp.from(Instant.now().minus(within));
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(IS_ACTIVE)) {
            ps.setString(1, instanceId);
            ps.setTimestamp(2, cutoff);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("实例注册表判活失败: " + instanceId, e);
        }
    }

    @Override
    public Map<String, Instant> activeInstances() {
        Map<String, Instant> all = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(LIST);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Timestamp t = rs.getTimestamp("last_heartbeat");
                if (t != null) {
                    all.put(rs.getString("instance_id"), t.toInstant());
                }
            }
            return all;
        } catch (SQLException e) {
            throw new IllegalStateException("读取实例注册表失败", e);
        }
    }

    /** 清掉超过 ttl 没报到的僵尸条目，避免注册表自身无界增长。 */
    public int evictExpired(Duration ttl) {
        if (ttl == null) {
            return 0;
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(EVICT)) {
            ps.setTimestamp(1, Timestamp.from(Instant.now().minus(ttl)));
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("清理过期实例失败", e);
        }
    }

    private static int bump(Connection c, String instanceId, Instant when) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(BUMP)) {
            ps.setTimestamp(1, Timestamp.from(when));
            ps.setString(2, instanceId);
            ps.setTimestamp(3, Timestamp.from(when));
            return ps.executeUpdate();
        }
    }

    private static boolean isDuplicateKey(SQLException e) {
        if (e instanceof SQLIntegrityConstraintViolationException) {
            return true;
        }
        return "23505".equals(e.getSQLState());
    }
}
