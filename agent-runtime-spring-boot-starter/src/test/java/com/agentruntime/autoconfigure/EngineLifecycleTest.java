package com.agentruntime.autoconfigure;

import com.agentruntime.api.AgentPlan;
import com.agentruntime.api.Context;
import com.agentruntime.api.Step;
import com.agentruntime.domain.TaskStatus;
import com.agentruntime.engine.DefaultAgentEngine;
import com.agentruntime.exception.AgentRuntimeException;
import com.agentruntime.persistence.InMemoryTaskRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** 优雅停机（P0 稳定性）：拒收新任务、在途任务跑完、结果缓存有界。 */
class EngineLifecycleTest {

    @Test
    @DisplayName("close 后 submit 被拒（不再偷偷开新线程）")
    void submitRejectedAfterClose() {
        DefaultAgentEngine engine = DefaultAgentEngine.builder(
                        new InMemoryTaskRepository(), new com.agentruntime.engine.ToolRegistry())
                .maxResults(100)
                .build();
        String first = engine.submit(new SimplePlan(), "u1", "before close");
        assertThat(engine.await(first, Duration.ofSeconds(5)).status()).isEqualTo(TaskStatus.COMPLETED);

        engine.close();
        assertThat(engine.isShuttingDown()).isTrue();
        assertThatThrownBy(() -> engine.submit(new SimplePlan(), "u1", "after close"))
                .isInstanceOf(AgentRuntimeException.class)
                .hasMessageContaining("优雅停机");
    }

    @Test
    @DisplayName("maxResults 生效：结果缓存不会随提交量无界增长")
    void resultCacheIsBounded() {
        DefaultAgentEngine engine = DefaultAgentEngine.builder(
                        new InMemoryTaskRepository(), new com.agentruntime.engine.ToolRegistry())
                .maxResults(3)
                .shutdownTimeoutMs(2000)
                .build();
        try {
            for (int i = 0; i < 10; i++) {
                String id = engine.submit(new SimplePlan(), "u" + i, "task " + i);
                assertThat(engine.await(id, Duration.ofSeconds(5)).status()).isEqualTo(TaskStatus.COMPLETED);
            }
            assertThat(engine.cachedResultCount()).isLessThanOrEqualTo(3);
        } finally {
            engine.close();
        }
    }

    static class SimplePlan implements AgentPlan {
        @Override
        public Step decide(Context ctx) {
            return Step.complete("done");
        }
    }
}
