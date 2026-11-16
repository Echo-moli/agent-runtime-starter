# agent-runtime-spring-boot-starter（M8：自动装配）

业务方**只加这一个依赖**，引擎、仓储、幂等、工具扫描、指标与 span 全部自动就位。

```xml
<dependency>
    <groupId>com.agentruntime</groupId>
    <artifactId>agent-runtime-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```java
@SpringBootApplication
public class HisApp { }                     // 连 @EnableAgentRuntime 都不用贴（自动装配已生效）

@Service
public class RefundTools {
    @AgentTool(name = "refund", idempotencyKey = "#orderId")   // 普通 Spring bean，注解零侵入
    public RefundReceipt refund(String orderId, BigDecimal amount) { ... }
}

// 注入即用
@Autowired AgentEngine engine;
String taskId = engine.submit(new RefundAgent(), userId, "ORD-001");
```

## 装配出什么

| bean | 默认实现 | 切换条件 |
| --- | --- | --- |
| `ToolRegistry` | 空注册表，随后由 `AgentToolScanner` 填充 | — |
| `TaskRepository` | 容器有 `DataSource` → `JdbcTaskRepository`；否则 `InMemoryTaskRepository` | 自定义同类型 bean 即接管 |
| `IdempotencyStore` | 同上（`JdbcIdempotencyStore` / `InMemoryIdempotencyStore`） | 同上 |
| `OutboxStore` | 就是上面那个 store（一个 bean 定义、两个接口角色） | 同上 |
| `AgentEngine` | `DefaultAgentEngine`，串起仓储/幂等/重试/挂起策略/埋点 | 同上 |
| `AgentTracer` | 有 OTel `Tracer` bean → `OtelTracer`；否则按配置 noop / console | 同上 |
| `Metrics` | 有 Micrometer `MeterRegistry` → 适配器；否则 `SimpleMetrics` | 同上 |
| `ExecutorService`（name=`agentRuntimeExecutor`） | 独立 daemon 线程池，容器关闭时 shutdown | 定义同名 bean 可替换 |
| `AgentSchemaInitializer` | 启动时执行 `schema-*.sql`（`IF NOT EXISTS`） | `agent.runtime.schema-init=false` |
| `AgentRuntimeEndpoint` | actuator 端点 `/actuator/agentruntime`（挂起清单 / 在途未知 / 恢复） | 需引 actuator；`management.endpoint.agentruntime.enabled=false` 可关 |

所有 bean 都是 `@ConditionalOnMissingBean`：业务方定义了就完全接管，不需要 `@Primary`、不需要排除自动配置。

## 配置项（`agent.runtime.*`）

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `max-steps` | 25 | 单任务最多决策几步（防 `decide()` 不收敛） |
| `executor-threads` | 0（按 CPU 自动，min 2） | 引擎线程池大小，daemon 线程 |
| `retry-enabled` | true | false = 完全不重试 |
| `retry-max-delay-ms` | 0（用引擎默认） | 指数退避上限 |
| `in-doubt-policy` | `SUSPEND_FOR_HUMAN` | 在途未知时：挂起 / 重试 / 失败 |
| `schema-init` | true | 启动建表；交给 Flyway 时设 false |
| `metrics-enabled` | true | false = noop |
| `tracer` | `NONE` | `NONE` / `CONSOLE` |
| `ops.allow-bulk-recover` | false | 是否允许 `recover-all/apply` |
| `ops.stale-in-doubt-after` | `15m` | `INIT` 停留多久算「超期未决」 |
| `ops.max-list-size` | 50 | 端点一次最多返回多少条 |
| `lease-ttl` | `5m` | 心跳停多久算死；批量恢复据此判孤儿。**必须大于最慢的一步** |
| `instance-id` | hostname-随机串 | 写进 `task.owner_id`，多实例排查时对得上机器 |

## 运维控制面（M9）

```
GET  /actuator/agentruntime                          概览（各状态计数 + 超期未决数）
GET  /actuator/agentruntime/overview
GET  /actuator/agentruntime/suspended                挂起清单：在等哪个工具、等了多久、为什么
GET  /actuator/agentruntime/in-doubt                 超期停在 INIT 的幂等记录（资损面巡检）
GET  /actuator/agentruntime/orphans                  租约已过期的孤儿（批量恢复会接管的就是它们）
GET  /actuator/agentruntime/task/{taskId}            轨迹 + 步级 span
POST /actuator/agentruntime/recover/{taskId}         续跑中断任务
POST /actuator/agentruntime/resume/{taskId}          批准并继续        body: {"note":"审批依据"}
POST /actuator/agentruntime/resume/{taskId}/reject   拒绝并继续
POST /actuator/agentruntime/resume/{taskId}/force    强制重试在途未知（危险）
POST /actuator/agentruntime/recover-all[/apply]      批量恢复（默认 dryRun，需开权限）
```

暴露：`management.endpoints.web.exposure.include=agentruntime`。

`POST /resume/{taskId}/force`（强制重试可能已发生的副作用）与 `POST /recover-all/apply`（批量接管）
会改钱，务必单独授权——别把整个 `/actuator/**` 敞给运维就算完事。

## 租约：谁在跑（M10）

多实例部署时，`RUNNING` 在库里只有一种样子——可能是另一台正在跑，也可能是上一台崩了。
所以要能区分，才能安全地批量恢复：

```
task.owner_id      持有租约的实例（instance-id）
task.heartbeat_at  最后一次心跳；本实例每 lease-ttl/3 续租一次
```

| 入口 | 看租约 | 说明 |
| --- | --- | --- |
| `recover(taskId)` / `POST /recover/{id}` | 否 | 人看过单个任务后的决定；崩溃重启立刻恢复靠它 |
| `recoverAllInterrupted()` / `POST /recover-all/apply` | 是 | 只接管心跳过期的任务 |
| `recoverAllInterrupted(true)` | 否 | 代码级 force，仅用于「确定持有者已死」（单实例崩溃重启） |

租约列只有一个写入口（`claimWithLease` / `renewLease`），`save()` 不碰它们——
否则一个丢了租约但还没察觉的实例，下次保存就会把别人的租约覆盖回去。

**状态写入也是 CAS**：`saveIfOwned(task, ownerId, expectedStatus)` →
`UPDATE ... WHERE owner_id=? AND status=?`。失败即抛 `LeaseLostException`，执行循环立刻停下、
一个字都不写、返回 `RECOVERING`（**不是** FAILED——任务只是换了主人，没坏）。

**事件追加同样校验**：`appendEventIfOwned(event, ownerId)` →
`INSERT ... WHERE EXISTS(任务属于我)`，一条语句完成校验 + 写入；不是主人的事件不落库。
再加上每步开始前的主人校验，账本里不会出现「另一个持有者写的步骤」。

## 三件容易踩的事（都在测试里锁住了）

1. **可选依赖必须 `@ConditionalOnClass` 隔离**：Micrometer / OTel 不在 classpath 时，那些配置类连加载都不能发生，否则启动直接 `NoClassDefFoundError`。
2. **bean 定义的顺序会反转语义**：Spring 先处理嵌套配置类、再处理本类的 `@Bean` 方法。所以「JDBC 版仓储」写在嵌套类里、「内存版兜底」写在外层——反过来写就永远是内存版赢。同理 Micrometer 适配器也必须是嵌套类。
3. **一个对象两种角色 → 一个 bean 定义**：幂等 store 同时实现 `IdempotencyStore` 与 `OutboxStore`。若再声明一个 `OutboxStore` bean 指向同一对象，按类型注入 `IdempotencyStore` 会撞上两个 bean 定义（`NoUniqueBeanDefinitionException`）。第一版就是这么炸的。
4. **嵌套配置类上不能写 `@ConditionalOnBean(本类的某个 @Bean)`**：条件在嵌套类解析时求值，那时外层 `@Bean` 定义还不存在，条件恒假——端点会无声消失。第一版也这么炸过。
5. **Actuator 的参数规则**：读操作的非 selector 参数 = 必填查询参数（`GET /overview` 会因「缺少 taskId」400）；写操作的参数 = 全量必填。所以这里用 `@Selector(match = Match.ALL_REMAINING) String[] path` 把 id 放进路径。

## 为什么这里没有 JPA 映射

`DESIGN.md` §8 提到 JPA 映射，实现时放弃了，理由写在 `AgentRuntimeAutoConfiguration` 的注释与根 README 的偏差表里：这个引擎依赖 `JSONB`、`UPDATE ... SET x = x + ?` 的存储侧原子累加、以及「幂等 DONE 与 outbox 同事务」，用 ORM 表达要么失真要么写回原生 SQL；而为了建 4 张表就让所有使用方背上 Hibernate，与「只加一个依赖」的承诺冲突。`TaskRepository` 只有 5 个方法，业务方真要 JPA，自己实现这个接口即可。

## 验证

```bash
mvn -pl agent-runtime-spring-boot-starter test     # 21 条断言 = 装配 8 + 端点 7 + 租约 6
mvn -pl agent-runtime-samples verify               # 端到端：M5 评测门禁
java -cp "…" com.agentruntime.samples.m8.M8Demo    # 装配 + 一次真实退款
java -cp "…" com.agentruntime.samples.m9.M9Demo    # 起 Web 容器，真 HTTP 打控制面
```
