package com.agentruntime.autoconfigure;

import com.agentruntime.engine.DefaultAgentEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.*;

/** 健康探针（P1）：存活/就绪语义真能被读出来。 */
class HealthIndicatorTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentRuntimeAutoConfiguration.class));

    @Test
    @DisplayName("默认健康：UP，带数据库/孤儿/挂起等 detail")
    void healthyByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            AgentRuntimeHealthIndicator indicator = context.getBean(AgentRuntimeHealthIndicator.class);
            Health health = indicator.health();
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsKey("database");
            assertThat(health.getDetails()).containsKey("orphanTasks");
            assertThat(health.getDetails()).containsKey("suspendedTasks");
        });
    }

    @Test
    @DisplayName("引擎停机后：健康变 DOWN，且给出原因")
    void downWhenEngineShuttingDown() {
        runner.run(context -> {
            AgentRuntimeHealthIndicator indicator = context.getBean(AgentRuntimeHealthIndicator.class);
            context.getBean(DefaultAgentEngine.class).close();
            Health health = indicator.health();
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(String.valueOf(health.getDetails().get("reason"))).contains("优雅停机");
        });
    }
}
