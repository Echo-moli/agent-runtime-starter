package com.agentruntime.persistence;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/**
 * JSONB 列的写入参数（core 零第三方依赖，不碰 {@code org.postgresql.util.PGobject}）。
 *
 * <p>为什么不能直接用 {@code setString}：在 H2 的 PostgreSQL 兼容模式下，varchar → JSONB
 * 会隐式转换，测试全绿；但在真正的 PostgreSQL 16 上会报
 * {@code column "context_json" is of type jsonb but expression is of type character varying}。
 * 这是「H2 只是替身、PG 才是数据库」这条定位最容易漏掉的一处方言差异。
 *
 * <p>{@link Types#OTHER} 让 JDBC 驱动把参数当「未指定类型」发出，由服务端按目标列推断：
 * PG 推断为 {@code jsonb}，H2 推断为 {@code VARCHAR}。已用真实 PG 16 与 H2 2.2 各自验证。
 */
final class JsonbParams {

    private JsonbParams() {
    }

    static void set(PreparedStatement ps, int index, String json) throws SQLException {
        if (json == null) {
            ps.setNull(index, Types.OTHER);
        } else {
            ps.setObject(index, json, Types.OTHER);
        }
    }
}
