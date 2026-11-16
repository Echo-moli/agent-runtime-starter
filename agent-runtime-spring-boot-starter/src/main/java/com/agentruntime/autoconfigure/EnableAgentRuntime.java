package com.agentruntime.autoconfigure;

import org.springframework.boot.autoconfigure.ImportAutoConfiguration;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 显式开启 Agent Runtime（可选）。
 *
 * <h2>为什么这个注解从 core 挪到了 starter（M8）</h2>
 * M1–M7 它住在 {@code com.agentruntime.annotation} 里，是个「纯 JDK 标记注解」——
 * 语义上什么都没做。原因很实在：{@code core} 零第三方依赖，而一个真正有用的
 * {@code @EnableXxx} 必须 {@code @Import} 一个 {@code @Configuration}，那是 Spring 的东西。
 * 留在 core 只会让人以为贴了它就会生效（贴了不生效的开关，比没有开关更糟）。
 *
 * <h2>用它还是用 AutoConfiguration.imports</h2>
 * 正常情况下<b>什么都不用贴</b>：{@code Starter} 的
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 会让 Spring Boot 自动装配。这个注解只在两种场合有用：
 * <ul>
 *   <li>应用里 {@code spring.autoconfigure.exclude} 排掉了自动配置，你想局部显式打开；</li>
 *   <li>你希望启动类上「一眼能看出用了哪些能力」（可读性诉求）。</li>
 * </ul>
 *
 * <p>实现上用 Spring Boot 的 {@link ImportAutoConfiguration} 而不是裸 {@code @Import}：
 * 后者会把同一个配置类注册两次（一次来自 imports 文件、一次来自注解），
 * 条件装配的执行顺序也会变；{@code ImportAutoConfiguration} 走的是同一套去重机制。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ImportAutoConfiguration(AgentRuntimeAutoConfiguration.class)
public @interface EnableAgentRuntime {
}
