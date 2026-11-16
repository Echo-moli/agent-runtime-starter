package com.agentruntime.util;

import java.util.Iterator;
import java.util.Map;

/**
 * 极简 JSON 序列化器。
 *
 * <p>存在的唯一理由是：M1 要保持 {@code core} 零第三方依赖（DESIGN.md §2.1 的硬约束），
 * 但日志与 M2 的 {@code JSONB} 落库都需要一个稳定、可读的 payload 视图。
 * 只支持 String / Number / Boolean / Enum / Map / Iterable / 数组 / null——刻意不做反序列化。
 */
public final class Json {

    private Json() {
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(64);
        append(sb, value);
        return sb.toString();
    }

    private static void append(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
            return;
        }
        if (v instanceof CharSequence || v instanceof Character) {
            quote(sb, v.toString());
            return;
        }
        if (v instanceof java.time.Instant instant) {
            quote(sb, instant.toString());
            return;
        }
        if (v instanceof java.time.temporal.TemporalAccessor temporal) {
            quote(sb, temporal.toString());
            return;
        }
        if (v instanceof java.math.BigDecimal decimal) {
            sb.append(decimal.toPlainString());
            return;
        }
        if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
            return;
        }
        if (v.getClass().isRecord()) {
            appendRecord(sb, v);
            return;
        }
        if (v instanceof Enum<?> e) {
            quote(sb, e.name());
            return;
        }
        if (v instanceof Map<?, ?> map) {
            sb.append('{');
            Iterator<? extends Map.Entry<?, ?>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<?, ?> entry = it.next();
                quote(sb, String.valueOf(entry.getKey()));
                sb.append(':');
                append(sb, entry.getValue());
                if (it.hasNext()) {
                    sb.append(',');
                }
            }
            sb.append('}');
            return;
        }
        if (v instanceof Iterable<?> iterable) {
            sb.append('[');
            Iterator<?> it = iterable.iterator();
            while (it.hasNext()) {
                append(sb, it.next());
                if (it.hasNext()) {
                    sb.append(',');
                }
            }
            sb.append(']');
            return;
        }
        if (v.getClass().isArray()) {
            sb.append('[');
            int len = java.lang.reflect.Array.getLength(v);
            for (int i = 0; i < len; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                append(sb, java.lang.reflect.Array.get(v, i));
            }
            sb.append(']');
            return;
        }
        quote(sb, v.toString());
    }

    private static void appendRecord(StringBuilder sb, Object record) {
        java.lang.reflect.RecordComponent[] components = record.getClass().getRecordComponents();
        sb.append('{');
        for (int i = 0; i < components.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            quote(sb, components[i].getName());
            sb.append(':');
            try {
                append(sb, components[i].getAccessor().invoke(record));
            } catch (ReflectiveOperationException e) {
                quote(sb, "<unreadable>");
            }
        }
        sb.append('}');
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
