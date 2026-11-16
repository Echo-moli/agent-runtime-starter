package com.agentruntime.engine;

import com.agentruntime.api.TaskResult;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/** 有界结果缓存的并发安全语义：只淘汰已完成的，在途的绝不丢。 */
class BoundedResultsTest {

    @Test
    void 超限只淘汰最早已完成的() {
        DefaultAgentEngine.BoundedResults cache = new DefaultAgentEngine.BoundedResults(2);
        CompletableFuture<TaskResult> a = new CompletableFuture<>();
        CompletableFuture<TaskResult> b = new CompletableFuture<>();
        CompletableFuture<TaskResult> c = new CompletableFuture<>();

        cache.put("a", a);
        cache.put("b", b);
        a.complete(null);   // a 完成
        cache.put("c", c); // 触发淘汰：a 已完成 → 被淘汰；c 在途 → 保留

        assertNull(cache.get("a"), "最早的已完成任务应被淘汰");
        assertSame(b, cache.get("b"), "b 未完成，即使更早也不能淘汰");
        assertSame(c, cache.get("c"), "新放入的在途任务必须在");
        assertEquals(2, cache.size());
    }

    @Test
    void 全在途时宁可超限也不丢结果() {
        DefaultAgentEngine.BoundedResults cache = new DefaultAgentEngine.BoundedResults(1);
        CompletableFuture<TaskResult> a = new CompletableFuture<>();
        CompletableFuture<TaskResult> b = new CompletableFuture<>();
        cache.put("a", a);
        cache.put("b", b);

        assertEquals(2, cache.size(), "两个都在途，不能淘汰任何一个");
        assertSame(a, cache.get("a"));
        assertSame(b, cache.get("b"));
    }

    @Test
    void 完成后再次触发淘汰收敛到上限() {
        DefaultAgentEngine.BoundedResults cache = new DefaultAgentEngine.BoundedResults(1);
        CompletableFuture<TaskResult> a = new CompletableFuture<>();
        CompletableFuture<TaskResult> b = new CompletableFuture<>();
        cache.put("a", a);
        cache.put("b", b);
        b.complete(null);
        a.complete(null);
        cache.evictCompleted();

        assertEquals(1, cache.size());
        assertSame(b, cache.get("b"), "b 先完成，应在 a 之前被保留");
    }
}
