package com.agentruntime.persistence;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 幂等三态与并发占位：同 key 并发只能有一个 CLAIMED，绝不出现两个都拿到执行权。 */
class InMemoryIdempotencyStoreTest {

    private static final OutboxEvent EVENT = new OutboxEvent("tool.executed", "t1", "{\"ok\":true}");

    @Test
    void 首次占位拿到执行权() {
        IdempotencyStore.Claim claim = new InMemoryIdempotencyStore().begin("refund:ORD-1", "t1", 0);
        assertEquals(IdempotencyStore.Claim.Outcome.CLAIMED, claim.outcome());
    }

    @Test
    void DONE后复用结果且不再执行() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        store.begin("k", "t1", 0);
        store.complete("k", "{\"receipt\":\"R1\"}", EVENT);

        IdempotencyStore.Claim second = store.begin("k", "t2", 1);
        assertEquals(IdempotencyStore.Claim.Outcome.REUSED, second.outcome());
        assertEquals("{\"receipt\":\"R1\"}", second.existing().resultPayload());
    }

    @Test
    void INIT被视为在途未知_不给执行权() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        store.begin("k", "t1", 0);
        IdempotencyStore.Claim second = store.begin("k", "t2", 0);
        assertEquals(IdempotencyStore.Claim.Outcome.IN_DOUBT, second.outcome());
    }

    @Test
    void 明确FAILED后允许重新占位() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        store.begin("k", "t1", 0);
        store.fail("k", "{\"error\":\"业务拒绝\"}");
        assertEquals(IdempotencyStore.Claim.Outcome.CLAIMED, store.begin("k", "t2", 0).outcome());
    }

    @Test
    void complete把DONE与outbox一起写入() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        store.begin("k", "t1", 0);
        store.complete("k", "{\"r\":1}", EVENT);

        assertEquals(IdempotencyRecord.Status.DONE, store.find("k").orElseThrow().status());
        assertEquals(1, store.pendingCount());
        assertEquals("tool.executed", store.pollPending(10).get(0).eventType());
    }

    @Test
    void 并发begin同key只有一个CLAIMED其余全在途() throws Exception {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger claimed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                if (store.begin("refund:ORD-X", "t", 0).outcome()
                        == IdempotencyStore.Claim.Outcome.CLAIMED) {
                    claimed.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdownNow();
        assertEquals(1, claimed.get(), "并发占位必须恰好一个执行者，否则就是重复扣款");
    }

    @Test
    void 超期INIT可被巡检发现且计数一致() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        store.begin("stale", "t1", 0);
        // 负偏移让 cutoff 明确落在未来，避开 Windows 下 Instant.now() 同值导致的
        // 严格 isBefore 竞态——断言的是巡检语义，不是时钟精度。
        Duration overdue = Duration.ofSeconds(-1);
        assertEquals(1, store.countStale(overdue));
        assertEquals("stale", store.findStale(overdue, 10).get(0).idempotencyKey());
        store.complete("stale", "{}", null);
        assertEquals(0, store.countStale(overdue), "DONE 后不应再算未决");
    }
}
