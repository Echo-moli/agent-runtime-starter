package com.agentruntime.engine;

import com.agentruntime.util.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 结果截断（DESIGN §6 M3 的 {@code resultTruncateChars}）。
 *
 * <p>DESIGN 原话是「结果截断至 {@code resultTruncateChars}」，但直接砍字符串会产出
 * <b>非法 JSON</b>——payload 是要进 {@code JSONB} 的，一旦截断点落在引号或括号中间，
 * 后续 {@code JsonParser} 解析就会炸，整条事件链路读不出来。
 *
 * <p>因此截断后的形态是「合法 JSON + 明确的截断标记」：
 * <pre>{@code {"_truncated": true, "_originalChars": 15234, "body": "<前 N 个字符>"}}</pre>
 * 业务 plan 在恢复后拿到这个形态时能分辨「数据被截了」而不是「字段丢了」，
 * 也不会把半个 JSON 当对象用。
 *
 * <p>注意截断只影响<b>事件与缓存的可读性</b>：本次执行返回给 plan 的仍是完整结果。
 * 需要完整结果时用幂等缓存（{@code idempotency_record.result_payload} 存的是截断前的全量）。
 */
public final class ResultTruncator {

    public static final String MARKER = "_truncated";

    private ResultTruncator() {
    }

    /**
     * @param value     原始结果
     * @param maxChars  截断阈值，{@code <= 0} 表示不截断
     * @return 原对象（未超阈值）或截断包装（已超阈值）
     */
    public static Object truncate(Object value, int maxChars) {
        if (value == null || maxChars <= 0) {
            return value;
        }
        String json = Json.write(value);
        if (json.length() <= maxChars) {
            return value;
        }
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put(MARKER, true);
        wrapper.put("_originalChars", json.length());
        wrapper.put("body", json.substring(0, maxChars));
        return wrapper;
    }

    /** 是否被截断过（事件还原后的判断依据）。 */
    public static boolean isTruncated(Object value) {
        return value instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get(MARKER));
    }
}
