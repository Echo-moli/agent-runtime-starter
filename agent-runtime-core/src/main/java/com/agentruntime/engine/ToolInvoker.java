package com.agentruntime.engine;

import com.agentruntime.exception.ToolInvocationException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Map;

/**
 * 反射调用 {@link ToolRegistry.ToolHandle}，按形参名从 Map 取值并做窄类型转换。
 *
 * <p>宽松兜底：当 map 只有一个 value 且形参名对不上（例如单参工具传了 {@code {"orderId": ...}} 以外的 key），
 * 按位置绑定。多参工具不做任何猜测。
 */
public final class ToolInvoker {

    private ToolInvoker() {
    }

    public static Object invoke(ToolRegistry.ToolHandle handle, Map<String, Object> args) {
        Method method = handle.method();
        Parameter[] params = method.getParameters();
        Object[] values = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            String name = params[i].getName();
            boolean present = args.containsKey(name);
            Object raw = args.get(name);
            if (!present && params.length == 1 && args.size() == 1) {
                raw = args.values().iterator().next();
                present = true;
            }
            if (!present) {
                throw new ToolInvocationException("工具 " + handle.name() + " 缺少参数 " + name
                        + "（实际传入: " + args.keySet() + "）");
            }
            values[i] = TypeConverter.convert(handle.name(), name, raw, params[i].getType());
        }
        try {
            // 工具类不必是 public：Spring 里 package-private 的 @Component 完全合法，
            // 而 Method.invoke 对一个「public 方法 + 非 public 类」会抛 IllegalAccessException。
            // M8 的自动装配会注册容器里任意 bean，这条路必须能走通，否则表现为
            // 「工具注册成功了，一调就失败」——最难查的那类问题。
            if (!method.canAccess(handle.target())) {
                method.setAccessible(true);
            }
            return method.invoke(handle.target(), values);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getTargetException() == null ? e : e.getTargetException();
            throw new ToolInvocationException(handle.name(), cause);
        } catch (IllegalAccessException | IllegalArgumentException e) {
            throw new ToolInvocationException(handle.name(), e);
        } catch (java.lang.reflect.InaccessibleObjectException e) {
            throw new ToolInvocationException("工具 " + handle.name()
                    + " 所在类不可访问（模块未 open？）: " + handle.method().getDeclaringClass().getName(), e);
        }
    }
}
