package com.agentruntime.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JSON 往返（写→读）是事件账本落 JSONB、恢复时再还原的基座。
 * 断言只验逻辑，不验任何数据库方言。
 */
class JsonRoundTripTest {

    @Test
    void 嵌套对象与数组往返保持类型与顺序() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("text", "订单 ORD-001");
        source.put("amount", new BigDecimal("128.00"));
        source.put("ok", true);
        source.put("nothing", null);
        source.put("tags", List.of("paid", "vip"));
        Map<String, Object> child = new LinkedHashMap<>();
        child.put("id", 42L);
        child.put("nested", true);
        source.put("child", child);

        Map<String, Object> back = JsonParser.parseObject(Json.write(source));

        assertEquals("订单 ORD-001", back.get("text"));
        assertEquals(new BigDecimal("128.00"), back.get("amount"));
        assertEquals(Boolean.TRUE, back.get("ok"));
        assertNull(back.get("nothing"));
        assertEquals(List.of("paid", "vip"), back.get("tags"));
        assertEquals(42L, ((Map<?, ?>) back.get("child")).get("id"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) back.get("child")).get("nested"));
    }

    @Test
    void 特殊字符与转义往返后逐字不变() {
        String tricky = "引号\" 反斜杠\\ 换行\n 回车\r 制表\t 控制\u0001 中文 😀";
        Map<String, Object> source = Map.of("v", tricky);

        String json = Json.write(source);
        Object back = JsonParser.parseObject(json).get("v");

        assertEquals(tricky, back, "转义/反转义必须无损，否则事件 payload 读出来就变了");
    }

    @Test
    void 整数溢出时退化为BigDecimal而不是丢失精度() {
        String big = "123456789012345678901234567890";
        Object parsed = JsonParser.parse("{\"n\":" + big + "}");
        assertEquals(new BigDecimal(big), ((Map<?, ?>) parsed).get("n"));
    }

    @Test
    void record序列化为对象且可解析() {
        record Point(int x, int y) {
        }
        Map<String, Object> back = JsonParser.parseObject(Json.write(new Point(3, 7)));
        assertEquals(3L, back.get("x"));
        assertEquals(7L, back.get("y"));
    }

    @Test
    void 非法JSON给出明确错误而不是静默接受() {
        assertThrows(IllegalArgumentException.class, () -> JsonParser.parse("{\"a\":}"));
        assertThrows(IllegalArgumentException.class, () -> JsonParser.parse("{\"a\":1} trailing"));
        assertThrows(IllegalArgumentException.class, () -> JsonParser.parse("{'a':1}"));
    }
}
