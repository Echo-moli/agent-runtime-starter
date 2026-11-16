package com.agentruntime.autoconfigure;

import com.agentruntime.persistence.Schema;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.InitializingBean;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Locale;

/**
 * 启动时建表（M8）——「加一个依赖就能跑」的最后一块拼图。
 *
 * <h2>为什么用脚本而不是 JPA 的 {@code ddl-auto}</h2>
 * 两个原因，都与本项目的定位有关：
 * <ol>
 *   <li><b>依赖成本</b>：{@code ddl-auto} 意味着 Hibernate。为了建 4 张表，
 *       让所有使用方都背上一个 ORM 与它的版本约束，与「core 零依赖、starter 只加一个依赖」
 *       的承诺直接冲突；</li>
 *   <li><b>语义对不上</b>：这个引擎重度依赖 {@code JSONB}、{@code UPDATE ... SET x = x + ?}
 *       的原子累加、以及「幂等 DONE 与 outbox 同事务」。这些用 JPA 表达要么失真，要么要写
 *       原生 SQL——那还不如不引入 ORM。</li>
 * </ol>
 *
 * <h2>生产怎么办</h2>
 * 关掉它（{@code agent.runtime.schema-init=false}），把两份脚本交给 Flyway/Liquibase。
 * 脚本就是 {@code resources/com/agentruntime/autoconfigure/schema-*.sql}，可以直接搬。
 * 这里的实现只是「本地开发 / 集成测试 / 评估阶段」的零运维兜底。
 *
 * <h2>为什么按方言分两份脚本</h2>
 * 同一份 DDL 在两边的差异不是语法糖：{@code JSONB} 在 H2 里没有，{@code BIGSERIAL}
 * 在 H2 里要写 {@code IDENTITY}。硬凑成一份「通用 SQL」的结果是两边都拿不到最优类型
 * （PG 的 {@code JSONB} 索引与 {@code ->>} 查询全废）。所以宁可两份。
 */
public final class AgentSchemaInitializer implements InitializingBean {

    private static final Log log = LogFactory.getLog(AgentSchemaInitializer.class);

    private final DataSource dataSource;

    public AgentSchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() throws SQLException {
        String dialect = detectDialect();
        String script = load(dialect);
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                Schema.runScript(connection, script);
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw new SQLException("初始化 agent-runtime 表结构失败（方言 " + dialect + "）："
                        + e.getMessage() + "；可设 agent.runtime.schema-init=false 交由 Flyway 处理", e);
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }
        log.info("[agent-runtime] 表结构已就绪（方言 " + dialect + "，脚本 " + script.length() + " 字符）");
    }

    /** 只区分 H2 与 PostgreSQL：其余走 PostgreSQL 脚本（大多数企业库兼容）。 */
    private String detectDialect() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            String product = meta.getDatabaseProductName() == null
                    ? "" : meta.getDatabaseProductName().toLowerCase(Locale.ROOT);
            if (product.contains("h2")) {
                return "h2";
            }
            if (!product.contains("postgre")) {
                log.warn("[agent-runtime] 未识别的数据库方言 " + product
                        + "，按 PostgreSQL 脚本初始化；若失败请手工建表并关闭 schema-init");
            }
            return "postgresql";
        }
    }

    private String load(String dialect) {
        String resource = "/com/agentruntime/autoconfigure/schema-" + dialect + ".sql";
        try (InputStream in = AgentSchemaInitializer.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("找不到建表脚本: " + resource
                        + "（starter jar 打包时 resources 是否被排除？）");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取建表脚本失败: " + resource, e);
        }
    }
}
