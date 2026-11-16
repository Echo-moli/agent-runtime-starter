package com.agentruntime.engine;

import com.agentruntime.util.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 截断后的值必须仍是合法 JSON —— 它是要落 JSONB 的，砍字符串会毁掉整条事件链路。 */
class ResultTruncatorTest {

    @Test
    void 未超阈值时原样返回同一对象() {
        Object value = Map.of("result", "ok");
        assertSame(value, ResultTruncator.truncate(value, 10_000));
        assertFalse(ResultTruncator.isTruncated(value));
    }

    @Test
    void 非正阈值表示不截断() {
        Object value = Map.of("result", "x".repeat(5000));
        assertSame(value, ResultTruncator.truncate(value, 0));
        assertSame(value, ResultTruncator.truncate(value, -1));
    }

    @Test
    void 超阈值后包装成合法JSON且带截断标记() {
        Object huge = Map.of("result", "x".repeat(5000));
        Object wrapped = ResultTruncator.truncate(huge, 120);

        assertTrue(ResultTruncator.isTruncated(wrapped));
        Map<String, Object> back = JsonParser.parseObject(
                com.agentruntime.util.Json.write(wrapped)); // 能被解析 = 合法 JSON
        assertEquals(Boolean.TRUE, back.get(ResultTruncator.MARKER));
        long originalChars = com.agentruntime.util.Json.write(huge).length();
        assertEquals(originalChars, ((Number) back.get("_originalChars")).longValue());
        assertEquals(String.class, back.get("body").getClass());
        assertTrue(((String) back.get("body")).length() <= 120);
        assertTrue(((Number) back.get("_originalChars")).longValue() > 5000);
    }

    @Test
    void 截断点落在字符串中间也不会产生非法转义() {
        // 原 JSON 的截断点正好落在一个 \n 转义序列中间，直接砍会留下半截反斜杠
        Object value = Map.of("v", "a\n" + "b".repeat(500));
        Object wrapped = ResultTruncator.truncate(value, 30);
        assertDoesNotThrow(() -> JsonParser.parseObject(com.agentruntime.util.Json.write(wrapped)));
    }
}
