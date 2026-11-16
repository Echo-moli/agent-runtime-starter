package com.agentruntime.engine;

import com.agentruntime.exception.ToolInvocationException;

import java.math.BigDecimal;

/**
 * 把 {@code Step.tool(...)} 的 Map 值转换成工具方法形参的声明类型。
 *
 * <p>只做「显式、可预测」的窄转换：不做隐式字符串到任意对象的反序列化，
 * 避免工具签名一改就在运行期悄悄拿到 null。转换失败直接抛错，由引擎记为失败步。
 */
public final class TypeConverter {

    private TypeConverter() {
    }

    public static Object convert(String toolName, String paramName, Object raw, Class<?> target) {
        if (raw == null) {
            if (target.isPrimitive()) {
                throw new ToolInvocationException(
                        "工具 " + toolName + " 的形参 " + paramName + " 是基本类型，不能传 null");
            }
            return null;
        }
        Class<?> boxed = wrap(target);
        if (boxed.isInstance(raw)) {
            return raw;
        }
        if (boxed == String.class) {
            return String.valueOf(raw);
        }
        if (raw instanceof Number n) {
            if (boxed == Integer.class) {
                return n.intValue();
            }
            if (boxed == Long.class) {
                return n.longValue();
            }
            if (boxed == Double.class) {
                return n.doubleValue();
            }
            if (boxed == Float.class) {
                return n.floatValue();
            }
            if (boxed == Short.class) {
                return n.shortValue();
            }
            if (boxed == Byte.class) {
                return n.byteValue();
            }
            if (boxed == BigDecimal.class) {
                return n instanceof BigDecimal bd ? bd : new BigDecimal(n.toString());
            }
        }
        if (raw instanceof String s) {
            try {
                if (boxed == Integer.class) {
                    return Integer.valueOf(s.trim());
                }
                if (boxed == Long.class) {
                    return Long.valueOf(s.trim());
                }
                if (boxed == Double.class) {
                    return Double.valueOf(s.trim());
                }
                if (boxed == BigDecimal.class) {
                    return new BigDecimal(s.trim());
                }
                if (boxed == Boolean.class) {
                    return Boolean.valueOf(s.trim());
                }
            } catch (NumberFormatException e) {
                throw new ToolInvocationException(
                        "工具 " + toolName + " 的形参 " + paramName + " 无法从 \"" + s + "\" 转换为 " + target.getName());
            }
        }
        if (boxed.isEnum() && raw instanceof String s) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object value = Enum.valueOf((Class<Enum>) boxed, s.trim());
            return value;
        }
        if (boxed.isInstance(raw)) {
            return raw;
        }
        throw new ToolInvocationException(
                "工具 " + toolName + " 的形参 " + paramName + " 类型不匹配：期望 " + target.getName()
                        + "，实际 " + raw.getClass().getName());
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        return type;
    }
}
