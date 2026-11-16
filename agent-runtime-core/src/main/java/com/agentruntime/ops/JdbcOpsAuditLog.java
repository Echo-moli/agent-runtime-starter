package com.agentruntime.ops;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * 审计落库（P0 安全）：把运维动作写进 {@code ops_audit} 表，而不是只留一行会被轮转掉的 warn 日志。
 *
 * <p>审计写入刻意<b>不让步</b>于业务：如果连审计都写不进去，日志还会有一条 ERROR——
 * 「操作成功了但没留下证据」在合规场景里等同于事故。这里不吞异常，让调用方（端点）看见失败。
 *
 * <p>DDL 见 {@code schema-*.sql} 与 Flyway 的 {@code V1__init.sql}，两种方言都含 {@code ops_audit} 表。
 */
public final class JdbcOpsAuditLog implements OpsAuditLog {

    private static final String INSERT =
            "INSERT INTO ops_audit (at, action, task_id, owner_user_id, principal, note, allowed, reason) "
                    + "VALUES (?,?,?,?,?,?,?,?)";

    private final DataSource dataSource;

    public JdbcOpsAuditLog(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void record(OpsAuditEvent event) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(INSERT)) {
            ps.setTimestamp(1, Timestamp.from(event.at() == null ? java.time.Instant.now() : event.at()));
            ps.setString(2, event.action() == null ? null : event.action().name());
            ps.setString(3, event.taskId());
            ps.setString(4, event.ownerUserId());
            ps.setString(5, event.principal() == null ? null : event.principal().name());
            ps.setString(6, event.note());
            ps.setBoolean(7, event.allowed());
            ps.setString(8, event.reason());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("写运维审计失败（操作可能已执行，但没留下审计证据）: "
                    + e.getMessage(), e);
        }
    }
}
