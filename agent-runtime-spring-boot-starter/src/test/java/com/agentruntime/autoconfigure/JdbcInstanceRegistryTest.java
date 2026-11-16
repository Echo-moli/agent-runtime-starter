package com.agentruntime.autoconfigure;

import com.agentruntime.ops.InstanceRegistry;
import com.agentruntime.ops.JdbcInstanceRegistry;
import com.agentruntime.persistence.Schema;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 共享实例注册表的语义断言。
 *
 * <p>关键用例是「两个 registry 对象看到同一份心跳」——这正是内存版做不到、
 * 而多副本部署必须做到的。其余用例守住单调性与清理语义。
 */
class JdbcInstanceRegistryTest {

    @Test
    void heartbeatIsVisibleAcrossInstances() throws Exception {
        DataSource db = freshDb();
        JdbcInstanceRegistry nodeA = new JdbcInstanceRegistry(db);
        JdbcInstanceRegistry nodeB = new JdbcInstanceRegistry(db);

        nodeA.heartbeat("worker-a", Instant.now());

        // 内存版在这里必然返回 false（各持一份 Map）；共享版才看得到别人。
        assertThat(nodeB.isActive("worker-a", Duration.ofMinutes(1))).isTrue();
        assertThat(nodeB.activeInstances()).containsKey("worker-a");
        assertThat(InstanceRegistry.activeAmong(java.util.Set.of("worker-a", "worker-b"),
                nodeB, Duration.ofMinutes(1))).containsExactly("worker-a");

        nodeA.deregister("worker-a");
        assertThat(nodeB.isActive("worker-a", Duration.ofMinutes(1))).isFalse();
    }

    @Test
    void heartbeatNeverGoesBackwards() throws Exception {
        JdbcInstanceRegistry registry = new JdbcInstanceRegistry(freshDb());
        Instant future = Instant.now().plusSeconds(3600);

        registry.heartbeat("worker-a", future);
        registry.heartbeat("worker-a", Instant.now());   // 乱序的旧心跳不能把时间写回去

        Map<String, Instant> all = registry.activeInstances();
        assertThat(all).containsKey("worker-a");
        assertThat(all.get("worker-a")).isAfter(Instant.now().plusSeconds(3590));
    }

    @Test
    void evictExpiredRemovesOnlyStaleEntries() throws Exception {
        JdbcInstanceRegistry registry = new JdbcInstanceRegistry(freshDb());
        registry.heartbeat("old", Instant.now().minusSeconds(120));
        registry.heartbeat("fresh", Instant.now());

        assertThat(registry.evictExpired(Duration.ofMinutes(1))).isEqualTo(1);
        assertThat(registry.activeInstances()).containsOnlyKeys("fresh");
        assertThat(registry.isActive("old", Duration.ofMinutes(10))).isFalse();
    }

    @Test
    void starterSwitchesRegistryByProperty() throws Exception {
        DataSource db = freshDb();
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentRuntimeAutoConfiguration.class))
                .withBean(DataSource.class, () -> db);

        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(InstanceRegistry.class))
                    .isInstanceOf(InstanceRegistry.InMemory.class);
        });
        runner.withPropertyValues("agent.runtime.ops.registry-jdbc=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(InstanceRegistry.class))
                    .isInstanceOf(JdbcInstanceRegistry.class);
        });
    }

    /** 用真实建表脚本建库，顺带验证 DDL 里确实有 agent_instance 表。 */
    private static DataSource freshDb() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:registry-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        try (Connection c = ds.getConnection()) {
            Schema.runScript(c, resource("/com/agentruntime/autoconfigure/schema-h2.sql"));
        }
        return ds;
    }

    private static String resource(String path) {
        try (InputStream in = JdbcInstanceRegistryTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("找不到资源: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
