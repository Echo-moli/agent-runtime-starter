package com.agentruntime.engine;

import com.agentruntime.exception.AgentRuntimeException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 幂等键必须由「工具名 + 求值结果」组成，且 null 时 fail closed。 */
class IdempotencyKeysTest {

    @Test
    void 简单参数求值为工具名加前缀() {
        assertEquals("refund:ORD-1",
                IdempotencyKeys.evaluate("#orderId", "refund", Map.of("orderId", "ORD-1")));
    }

    @Test
    void 不同工具同参数得到不同键_防止快照当回执() {
        Map<String, Object> args = Map.of("orderId", "ORD-1");
        String queryKey = IdempotencyKeys.evaluate("#orderId", "queryOrder", args);
        String refundKey = IdempotencyKeys.evaluate("#orderId", "refund", args);
        assertNotEquals(queryKey, refundKey);
    }

    @Test
    void 支持Map字段路径与getter路径() {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("id", "ORD-9");
        assertEquals("refund:ORD-9",
                IdempotencyKeys.evaluate("#order.id", "refund", Map.of("order", order)));

        record Order(String id) {
        }
        assertEquals("refund:ORD-8",
                IdempotencyKeys.evaluate("#order.id", "refund", Map.of("order", new Order("ORD-8"))));
    }

    @Test
    void 空表达式表示不做幂等() {
        assertNull(IdempotencyKeys.evaluate(null, "refund", Map.of()));
        assertNull(IdempotencyKeys.evaluate("  ", "refund", Map.of()));
    }

    @Test
    void 缺参数与非法表达式都failClosed() {
        assertThrows(AgentRuntimeException.class,
                () -> IdempotencyKeys.evaluate("#orderId", "refund", Map.of()));
        assertThrows(AgentRuntimeException.class,
                () -> IdempotencyKeys.evaluate("orderId", "refund", Map.of("orderId", "x")));
        assertThrows(AgentRuntimeException.class,
                () -> IdempotencyKeys.evaluate("#order.id", "refund", Map.of("order", Map.of())));
    }
}
