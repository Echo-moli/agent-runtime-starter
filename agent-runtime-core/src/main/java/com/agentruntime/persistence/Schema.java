package com.agentruntime.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 执行 DDL 脚本（按分号切分，逐条执行）。
 *
 * <p>约定：脚本里不能出现分号字面量（DDL 天然满足）。真实生产更推荐 Flyway/Liquibase，
 * 这里保持零依赖的启动期兜底。
 */
public final class Schema {

    private Schema() {
    }

    public static void runScript(Connection connection, String sql) throws SQLException {
        for (String raw : sql.split(";")) {
            String stmt = raw.trim();
            if (stmt.isEmpty()) {
                continue;
            }
            try (Statement st = connection.createStatement()) {
                st.execute(stmt);
            }
        }
    }
}
