package com.agentruntime.persistence;

import com.agentruntime.domain.StepType;
import com.agentruntime.domain.TaskStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/** 仓储语义：租约所有权校验、事件归属、并发累加不丢钱。 */
class InMemoryTaskRepositoryTest {

    private static Task task(String id, String owner) {
        Task t = new Task(id, "Agent", "u", "req");
        t.setOwner(owner, Instant.now());
        return t;
    }

    @Test
    void 事件追加只有主人才写得进() {
        InMemoryTaskRepository repo = new InMemoryTaskRepository();
        repo.save(task("t1", "A"));
        var event = TaskEvent.draft("t1", 0, StepType.TOOL_CALL, "refund", java.util.Map.of(), true);

        assertNotNull(repo.appendEventIfOwned(event, "A"));
        assertNull(repo.appendEventIfOwned(event, "B"), "租约易主后旧主人一条都不该写进账本");
        assertEquals(1, repo.findEvents("t1").size());
    }

    @Test
    void 非主人在途未知时保存失败() {
        InMemoryTaskRepository repo = new InMemoryTaskRepository();
        Task t = task("t1", "A");
        repo.save(t);
        assertFalse(repo.saveIfOwned(t, "B", TaskStatus.RUNNING));
        assertTrue(repo.saveIfOwned(t, "A", TaskStatus.RUNNING));
    }

    @Test
    void 只有主人能续租_且续租不动业务状态() {
        InMemoryTaskRepository repo = new InMemoryTaskRepository();
        Task t = task("t1", "A");
        t.setStatus(TaskStatus.RUNNING);
        repo.save(t);
        Instant hb = Instant.now().plusSeconds(10);

        assertFalse(repo.renewLease("t1", "B", hb));
        assertTrue(repo.renewLease("t1", "A", hb));
        assertEquals(hb, repo.findById("t1").orElseThrow().getHeartbeatAt());
        assertEquals(TaskStatus.RUNNING, repo.findById("t1").orElseThrow().getStatus());
    }

    @Test
    void 租约认领看过期时间() {
        InMemoryTaskRepository repo = new InMemoryTaskRepository();
        Task t = task("t1", "A");
        t.setOwner("A", Instant.now().minusSeconds(60));
        t.setStatus(TaskStatus.RUNNING);
        repo.save(t);

        var recoverable = java.util.Set.of(TaskStatus.RUNNING);
        // 心跳已早于 staleBefore → 过期，B 可以接管
        assertTrue(repo.claimWithLease("t1", recoverable, TaskStatus.RECOVERING,
                "B", Instant.now().minusSeconds(30)));
        assertEquals(TaskStatus.RECOVERING, repo.findById("t1").orElseThrow().getStatus());
        assertEquals("B", repo.findById("t1").orElseThrow().getOwnerId());
    }

    @Test
    void 心跳新鲜时不允许别人抢() {
        InMemoryTaskRepository repo = new InMemoryTaskRepository();
        Task t = task("t1", "A");   // 心跳 = now，很新鲜
        t.setStatus(TaskStatus.RUNNING);
        repo.save(t);

        assertFalse(repo.claimWithLease("t1", java.util.Set.of(TaskStatus.RUNNING),
                TaskStatus.RECOVERING, "B", Instant.now().minusSeconds(30)));
        assertEquals(TaskStatus.RUNNING, repo.findById("t1").orElseThrow().getStatus());
    }

    @Test
    void 并发累加不丢更新() throws Exception {
        InMemoryTaskRepository repo = new InMemoryTaskRepository();
        repo.save(task("t1", "A"));
        int threads = 8, per = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int j = 0; j < per; j++) {
                    repo.addUsage("t1", 10, new BigDecimal("0.0001"));
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdownNow();

        Task finalTask = repo.findById("t1").orElseThrow();
        assertEquals(threads * per * 10L, finalTask.getTotalTokens());
        assertEquals(new BigDecimal("0.0800"),
                finalTask.getTotalCost().setScale(4, RoundingMode.HALF_UP));
    }

    @Test
    void setUsage是绝对覆盖而非累加() {
        InMemoryTaskRepository repo = new InMemoryTaskRepository();
        repo.save(task("t1", "A"));
        repo.addUsage("t1", 100, new BigDecimal("0.50"));
        repo.setUsage("t1", 42, new BigDecimal("0.20"));
        Task t = repo.findById("t1").orElseThrow();
        assertEquals(42L, t.getTotalTokens());
        assertEquals(0, new BigDecimal("0.20").compareTo(t.getTotalCost()));
    }
}
