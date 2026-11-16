package com.agentruntime.engine;

import com.agentruntime.exception.AgentRuntimeException;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * 把 {@code @AgentTool.idempotencyKey} 这种「迷你表达式」求值成幂等键。
 *
 * <p>支持的语法只有两种，够用且不引依赖（Spring SpEL 会把 core 绑到 Spring 上，违背 §2.1）：
 * <ul>
 *   <li>{@code #orderId} —— 取工具入参；</li>
 *   <li>{@code #order.id} —— 取入参的字段路径（Map / record / getter 均可）。</li>
 * </ul>
 * 刻意不支持运算与函数调用：幂等键必须是稳定字符串，任何「聪明」的表达式都是不一致的来源。
 *
 * <p><b>键的组成是 {@code 工具名 + ":" + 求值结果}</b>，不是裸的求值结果。DESIGN §4.4 写的是
 * 「业务表达式求值结果，唯一」，但示例里 {@code queryOrder} 与 {@code refund} 都用 {@code #orderId}：
 * 不按工具名隔离，查询结果会被当成退款结果直接读出来，一次退款就变成了「返回订单快照」。
 *
 * <p>求值结果为 null 时直接抛错（fail closed）：没有键就等于没有幂等保护，
 * 对退款这种接口，宁可不执行也不能裸奔。
 */
public final class IdempotencyKeys {

    private IdempotencyKeys() {
    }

    /**
     * @return 幂等键；表达式为空时返回 {@code null}（表示该工具不做幂等）
     */
    public static String evaluate(String expression, String toolName, Map<String, Object> args) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        String expr = expression.trim();
        if (!expr.startsWith("#")) {
            throw new AgentRuntimeException(
                    "工具 " + toolName + " 的 idempotencyKey 必须以 # 开头（入参名），实际: " + expr);
        }
        String[] path = expr.substring(1).split("\\.");
        Object value = args.get(path[0]);
        if (value == null) {
            throw new AgentRuntimeException("工具 " + toolName + " 的幂等键求值为 null：表达式 " + expr
                    + "，入参 " + args.keySet() + "。没有幂等键就不能执行有副作用的工具");
        }
        for (int i = 1; i < path.length; i++) {
            value = field(value, path[i]);
            if (value == null) {
                throw new AgentRuntimeException("工具 " + toolName + " 的幂等键在路径 " + path[i]
                        + " 处为 null：表达式 " + expr);
            }
        }
        return toolName + ":" + value;
    }

    private static Object field(Object source, String name) {
        if (source instanceof Map<?, ?> map) {
            return map.get(name);
        }
        String suffix = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (String accessor : new String[]{name, "get" + suffix, "is" + suffix}) {
            try {
                Method method = source.getClass().getMethod(accessor);
                return method.invoke(source);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // 试下一个访问器
            }
        }
        return null;
    }
}
