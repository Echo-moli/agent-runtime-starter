package com.agentruntime.autoconfigure;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.InitializingBean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.Locale;

/**
 * Flyway 版本化迁移（P1）——生产环境替代 {@link AgentSchemaInitializer} 的方案。
 *
 * <h2>为什么不直接用 Spring Boot 自带的 FlywayAutoConfiguration</h2>
 * 那个自动配置认 {@code spring.flyway.locations}，而本项目的 DDL 是<b>按方言分两份</b>的
 * （PG 用 JSONB/BIGSERIAL，H2 用 VARCHAR/IDENTITY）。让使用方自己写
 * 「PG 环境填 pg 目录、H2 环境填 h2 目录」是一份等着写错的文档；这里自动探测方言、选目录，
 * 并刻意用独立路径 {@code db/migration/agent-runtime/} 避免和业务自己的 Flyway 迁移撞车。
 *
 * <h2>默认关闭</h2>
 * 只有 {@code agent.runtime.flyway.enabled=true} 且 classpath 有 flyway-core 时才启用。
 * 与 {@code schema-init} 互斥：开了 Flyway 就该关掉幂等 DDL，否则两套机制都会建表。
 */
public final class AgentFlywayInitializer implements InitializingBean {

    private static final Log log = LogFactory.getLog(AgentFlywayInitializer.class);

    private final DataSource dataSource;

    public AgentFlywayInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() {
        String dialect = detectDialect();
        String location = "classpath:db/migration/agent-runtime/" + dialect;
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(location)
                // 业务自己的 flyway_schema_history 不动；用独立表名隔离本项目迁移历史
                .table("agent_runtime_schema_history")
                .baselineOnMigrate(true)
                .load();
        var result = flyway.migrate();
        log.info("[agent-runtime] Flyway 迁移完成（方言 " + dialect + "，location " + location
                + "，应用 " + result.migrationsExecuted + " 个脚本）");
    }

    private String detectDialect() {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            String product = meta.getDatabaseProductName() == null
                    ? "" : meta.getDatabaseProductName().toLowerCase(Locale.ROOT);
            return product.contains("h2") ? "h2" : "pg";
        } catch (Exception e) {
            log.warn("[agent-runtime] 无法探测数据库方言，按 PostgreSQL 处理: " + e.getMessage());
            return "pg";
        }
    }
}
