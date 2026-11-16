package com.agentruntime.engine;

import com.agentruntime.annotation.AgentTool;
import com.agentruntime.exception.ToolNotFoundException;
import com.agentruntime.exception.ToolRegistrationException;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 工具注册表：扫描对象上的 {@link AgentTool} 方法，按 {@code AgentTool.name()} 建索引。
 *
 * <p>所有可诊断的错误都在<b>注册期</b>抛出（重名、形参名丢失、静态方法等），
 * 不留给运行期去猜——一次退款跑 15 分钟后才报「找不到工具」是最贵的失败方式。
 */
public final class ToolRegistry {

    /** 一个可调用的工具句柄。 */
    public record ToolHandle(String name, Object target, Method method, AgentTool meta) {
    }

    private final Map<String, ToolHandle> tools = new LinkedHashMap<>();

    public ToolRegistry register(Object bean) {
        return register(bean, bean == null ? null : bean.getClass());
    }

    /**
     * 按指定类型扫描工具方法（M8）。
     *
     * <h2>为什么需要显式传类型</h2>
     * Spring 注入的 bean 通常是 <b>CGLIB 代理</b>（{@code @Transactional}、{@code @Async}、
     * {@code @AgentTool} 所在类被 AOP 拦过）。代理类是运行期生成的子类，
     * 它<b>覆写</b>了业务方法却<b>不继承</b>方法上的注解——{@code proxy.getClass().getMethods()}
     * 拿到的 {@link Method} 上没有 {@link AgentTool}，扫描结果就是「一个工具都没找到」，
     * 而且不报错，直到运行期才变成 {@code ToolNotFoundException}。
     *
     * <p>调用方传 {@code ClassUtils.getUserClass(bean)} 即可拿到被代理的原始类。
     * 工具句柄里的 {@code target} 仍是代理对象，所以调用时事务/切面依然生效——
     * 这点很重要：注册的是「代理的元数据 + 代理的执行」，不能只取其一。
     *
     * <p><b>已知限制</b>：只支持 CGLIB（子类）代理。若应用把 Spring 配成
     * {@code proxyTargetClass=false} 且工具 bean 实现了接口，得到的 JDK 动态代理
     * 并非原始类的实例，{@code Method.invoke} 会抛 {@code IllegalArgumentException}。
     * Spring Boot 2.x 起默认就是 CGLIB，所以默认可用；自定义代理策略时请让工具方法
     * 声明在类上（或把 {@code @AgentTool} 放在实现类而不是接口）。
     */
    public ToolRegistry register(Object bean, Class<?> type) {
        if (bean == null) {
            throw new ToolRegistrationException("注册对象不能为 null");
        }
        if (type == null) {
            throw new ToolRegistrationException("注册类型不能为 null");
        }
        for (Method method : type.getMethods()) {
            AgentTool ann = method.getAnnotation(AgentTool.class);
            if (ann == null) {
                continue;
            }
            String name = ann.name().isBlank() ? method.getName() : ann.name().trim();
            if (java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                throw new ToolRegistrationException(
                        "@AgentTool 不支持 static 方法: " + type.getName() + "#" + method.getName());
            }
            if (method.getReturnType() == void.class) {
                throw new ToolRegistrationException(
                        "@AgentTool 必须有返回值（结果要进事件溯源）: " + type.getName() + "#" + method.getName());
            }
            for (Parameter p : method.getParameters()) {
                if (!p.isNamePresent()) {
                    throw new ToolRegistrationException(
                            "工具 " + name + " 的形参名未保留，无法按名绑定参数；请用 -parameters 编译（parent pom 已配置）。"
                                    + " 方法: " + type.getName() + "#" + method.getName());
                }
            }
            ToolHandle previous = tools.putIfAbsent(name, new ToolHandle(name, bean, method, ann));
            if (previous != null) {
                throw new ToolRegistrationException("工具名重复: " + name + "（已被 "
                        + previous.target().getClass().getName() + "#" + previous.method().getName() + " 占用）");
            }
        }
        return this;
    }

    public ToolHandle require(String name) {
        ToolHandle handle = tools.get(name);
        if (handle == null) {
            throw new ToolNotFoundException("未注册的工具: " + name + "（已注册: " + tools.keySet() + "）");
        }
        return handle;
    }

    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    public Set<String> names() {
        return Collections.unmodifiableSet(tools.keySet());
    }

    public int size() {
        return tools.size();
    }
}
