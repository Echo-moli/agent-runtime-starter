package com.agentruntime.persistence;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 连接来源（M8）：把「一个连接」与「一个连接池」统一成同一个入口。
 *
 * <h2>为什么 M2 的单连接写法必须改掉</h2>
 * M2 的两个 JDBC 实现直接持有一个 {@link Connection}，原因是当时的验收目标是
 * 「同一套 SQL 语义能在 H2/PostgreSQL 上跑通」，单连接最省事。但它有个无法绕过的问题：
 * <b>企业里的连接是池化资源</b>。自动装配若把 {@code dataSource.getConnection()} 直接交给
 * 仓储持有，那个连接就永远回不到池里——池大小 10 的应用跑起 agent 就只剩 9 个，
 * 而且这种泄漏在压测里才会暴露。反过来，每条语句各借一次连接，又会让「一个方法里
 * 多条语句」失去事务边界（例如幂等 DONE 与 outbox 必须同事务）。
 *
 * <p>所以这里把「借还」与「执行」绑定在一起：{@link #withConnection} 内拿到的连接，
 * 在整块逻辑执行完之前不会被还回去。单连接模式复用一个连接并加锁串行化，
 * 池化模式每次借还——上层代码一行不用分叉。
 *
 * <h2>为什么这个接口放在 core 而不是 starter</h2>
 * {@link DataSource} 是 {@code java.sql} 模块里的 JDK 类型，不是 Spring 类型。
 * 也就是说：支持连接池并不需要引入任何第三方依赖，core 的零依赖约束依然成立。
 */
public interface ConnectionSource {

    /** 借一个连接执行一段工作；实现负责关闭（池化）或复用（单连接）。 */
    <T> T withConnection(SqlWork<T> work) throws SQLException;

    /** 借连接执行的一段工作，允许抛 {@link SQLException}。 */
    @FunctionalInterface
    interface SqlWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    /**
     * 单连接模式（demo / 测试）：复用同一个连接，内部加锁串行化。
     *
     * <p>锁必须在这里而不是调用方：单连接的并发访问会互相破坏
     * {@code autoCommit} 状态（一个线程开事务，另一个线程的语句就被卷进去了）。
     */
    static ConnectionSource single(Connection connection) {
        return new Single(connection);
    }

    /** 连接池模式（生产）：每次借还，由池负责归还与状态重置。 */
    static ConnectionSource pooled(DataSource dataSource) {
        return new Pooled(dataSource);
    }

    /** 单连接实现。 */
    final class Single implements ConnectionSource {

        private final Connection connection;
        private final Object lock = new Object();

        Single(Connection connection) {
            if (connection == null) {
                throw new IllegalArgumentException("connection 不能为 null");
            }
            this.connection = connection;
            try {
                this.connection.setAutoCommit(true);
            } catch (SQLException e) {
                throw new com.agentruntime.exception.AgentRuntimeException("无法设置 autocommit", e);
            }
        }

        @Override
        public <T> T withConnection(SqlWork<T> work) throws SQLException {
            synchronized (lock) {
                return work.apply(connection);
            }
        }
    }

    /** 池化实现。 */
    final class Pooled implements ConnectionSource {

        private final DataSource dataSource;

        Pooled(DataSource dataSource) {
            if (dataSource == null) {
                throw new IllegalArgumentException("dataSource 不能为 null");
            }
            this.dataSource = dataSource;
        }

        @Override
        public <T> T withConnection(SqlWork<T> work) throws SQLException {
            try (Connection connection = dataSource.getConnection()) {
                // 池里的连接可能带着上一次事务的 autoCommit=false 回来（Hikari 会重置，
                // 但不是所有池都默认重置），这里显式对齐，免得一条语句悬在未提交的事务里
                if (!connection.getAutoCommit()) {
                    connection.setAutoCommit(true);
                }
                return work.apply(connection);
            }
        }
    }
}
