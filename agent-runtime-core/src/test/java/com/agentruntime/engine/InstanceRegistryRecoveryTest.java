package com.agentruntime.engine;

import com.agentruntime.api.AgentPlan;
import com.agentruntime.api.Context;
import com.agentruntime.api.Step;
import com.agentruntime.domain.TaskStatus;
import com.agentruntime.ops.InstanceRegistry;
import com.agentruntime.persistence.InMemoryTaskRepository;
import com.agentruntime.persistence.Task;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 实例注册表（P2）：租约过期但 owner 还在注册表报活 → 网络分区，不该被当成孤儿抢走。
 * 这是「两个独立信号必须同时失效才接管」的实证。
 */
class InstanceRegistryRecoveryTest {

    @Test
    void 租约过期但注册表活跃时不接管() {
        InMemoryTaskRepository repo = new InMemoryTaskRepository();
        InstanceRegistry.InMemory registry = new InstanceRegistry.InMemory();

        // 造一个「别的实例」持有、租约已过期的 RUNNING 任务
        Task foreign = new Task("foreign-t1", "p", "u", "r");
        foreign.setStatus(TaskStatus.RUNNING);
        foreign.setOwner("other-instance", Instant.now().minusSeconds(600)); // 心跳 10 分钟前
        repo.save(foreign);
        registry.heartbeat("other-instance", Instant.now());                 // 但注册表刚报到

        DefaultAgentEngine engine = DefaultAgentEngine.builder(repo, new ToolRegistry())
                .leaseTtl(Duration.ofMinutes(5))
                .instanceRegistry(registry)
                .build();
        try {
            List<String> recovered = engine.recoverAllInterrupted();
            assertTrue(recovered.isEmpty(),
                    "注册表说 owner 还活着，租约过期只是网络分区，不该抢");
            assertEquals(TaskStatus.RUNNING, repo.findById("foreign-t1").orElseThrow().getStatus());
        } finally {
            engine.close();
        }
    }

    @Test
    void 租约过期且注册表也无心跳时接管() {
        InMemoryTaskRepository repo = new InMemoryTaskRepository();
        InstanceRegistry.InMemory registry = new InstanceRegistry.InMemory();

        Task dead = new Task("dead-t1", "p", "u", "r");
        dead.setStatus(TaskStatus.RUNNING);
        dead.setOwner("dead-instance", Instant.now().minusSeconds(600));
        repo.save(dead);
        // 注册表里没有 dead-instance：两个信号都失效 → 判孤儿
        DefaultAgentEngine engine = DefaultAgentEngine.builder(repo, new ToolRegistry())
                .leaseTtl(Duration.ofMinutes(5))
                .instanceRegistry(registry)
                .build();
        try {
            // 没有注册 plan 会在 recover 内部抛「未注册 plan」而被 catch 跳过，
            // 所以这里只需确认它「通过了判孤儿这一关」——用 force 之外的路径无法直接观察。
            // 改断言：注册一个 plan，让 recover 真正走完认领。
            engine.registerPlan(new AgentPlan() {
                @Override public String name() { return "p"; }
                @Override public Step decide(Context ctx) { return Step.complete("done"); }
            });
            List<String> recovered = engine.recoverAllInterrupted();
            assertTrue(recovered.contains("dead-t1"),
                    "租约过期 + 注册表无心跳 = 真孤儿，应被接管");
        } finally {
            engine.close();
        }
    }
}
