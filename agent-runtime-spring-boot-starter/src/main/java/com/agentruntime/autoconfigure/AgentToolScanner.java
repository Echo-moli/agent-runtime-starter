package com.agentruntime.autoconfigure;

import com.agentruntime.annotation.AgentTool;
import com.agentruntime.engine.ToolRegistry;
import com.agentruntime.exception.ToolRegistrationException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 扫描容器里带 {@link AgentTool} 方法的 bean，自动注册进 {@link ToolRegistry}（M8）。
 *
 * <h2>为什么用 {@link SmartInitializingSingleton} 而不是 {@code BeanPostProcessor}</h2>
 * BPP 的调用时机太早：它处理每个 bean 时，{@code ToolRegistry} 自己可能还没创建完，
 * 于是只能靠「延迟查找 + 待注册队列 + 绑定后重放」这些绕法，
 * 而这类绕法的 bug 都表现为「有时候工具少了一个」，最难查。
 *
 * <p>{@code afterSingletonsInstantiated()} 在所有单例创建完之后执行一次，此时：
 * <ul>
 *   <li>{@code ToolRegistry} bean 一定已经存在（可能有其他 bean 持有它的引用）；</li>
 *   <li>注册是「往同一个注册表里加」，所以即使引擎 bean 先被创建，拿到的也是同一个对象，
 *       后注册的工具照样可见——{@code ToolRegistry} 本来就是可变的注册表，不是快照。</li>
 * </ul>
 *
 * <h2>两次过滤</h2>
 * <ol>
 *   <li>{@code getBeanDefinitionNames()} 列出全部 bean 名，但只对「类上真的有 @AgentTool 方法」
 *       的类调 {@code getBean}——否则会顺手实例化一堆懒加载 bean，把别人的启动耗时变成自己的；</li>
 *   <li>跳过 Spring 基础设施的名字前缀，省掉对框架类做反射扫描。</li>
 * </ol>
 *
 * <h2>两个会直接启动失败的情况（刻意不静默）</h2>
 * <ul>
 *   <li><b>prototype 作用域的工具 bean</b>：注册表只持有第一次拿到的实例，
 *       后续调用不会新建实例——「以为每次调用都是新对象」的假设会静默失效，
 *       所以直接报错，让人改成 singleton 或显式注入。</li>
 *   <li><b>工具名重复</b>：由 {@link ToolRegistry} 抛出。两个 bean 抢同一个工具名，
 *       运行期表现是「偶尔调到了错的那个」，必须启动期就炸。</li>
 * </ul>
 */
public final class AgentToolScanner implements SmartInitializingSingleton {

    private static final Log log = LogFactory.getLog(AgentToolScanner.class);

    /** Spring 自己的 bean 名前缀，扫了也没用。 */
    private static final String[] IGNORED_PREFIXES = {
            "org.springframework.", "scopedTarget.", "spring.", "applicationTaskExecutor"
    };

    private final ConfigurableApplicationContext context;
    private final ToolRegistry registry;
    /** 类 → 是否含 @AgentTool 方法；同一个类会被反复问到，缓存一下。 */
    private final Map<Class<?>, Boolean> cache = new ConcurrentHashMap<>();

    public AgentToolScanner(ConfigurableApplicationContext context, ToolRegistry registry) {
        this.context = context;
        this.registry = registry;
    }

    @Override
    public void afterSingletonsInstantiated() {
        int registered = 0;
        ConfigurableBeanFactory beanFactory = context.getBeanFactory();
        for (String name : context.getBeanDefinitionNames()) {
            if (isIgnored(name)) {
                continue;
            }
            Class<?> type;
            try {
                type = context.getType(name);
            } catch (RuntimeException e) {
                continue;   // 拿不到类型的 bean（如某些 FactoryBean）与工具无关
            }
            if (type == null || !hasToolMethods(type)) {
                continue;
            }
            if (beanFactory.isPrototype(name)) {
                throw new ToolRegistrationException(
                        "工具 bean 不能是 prototype 作用域: " + name + "（" + type.getName() + "）；"
                                + "注册表只持有第一次拿到的实例，prototype 会让「每次都是新对象」的假设失效");
            }
            Object bean = context.getBean(name);
            Class<?> userClass = ClassUtils.getUserClass(bean);
            int before = registry.names().size();
            // 传 userClass：CGLIB 代理类不继承方法上的注解（见 ToolRegistry#register(Object, Class)）
            registry.register(bean, userClass);
            int added = registry.names().size() - before;
            registered += added;
            log.info("[agent-runtime] 注册工具 bean " + name + "(" + userClass.getSimpleName()
                    + ") → " + added + " 个工具");
        }
        if (registered == 0 && registry.names().isEmpty()) {
            log.warn("[agent-runtime] 没有扫描到任何 @AgentTool 方法。"
                    + "引擎能启动，但任何 agent 调用工具时都会 ToolNotFoundException——"
                    + "检查工具类是否被组件扫描到、注解是否在实现类上（而不是接口上）");
        } else {
            log.info("[agent-runtime] 工具注册完成，共 " + registry.names().size() + " 个: "
                    + registry.names());
        }
    }

    private static boolean isIgnored(String beanName) {
        for (String prefix : IGNORED_PREFIXES) {
            if (beanName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasToolMethods(Class<?> type) {
        return cache.computeIfAbsent(type, t -> {
            for (Method method : t.getMethods()) {
                if (method.isAnnotationPresent(AgentTool.class)) {
                    return true;
                }
            }
            return false;
        });
    }
}
