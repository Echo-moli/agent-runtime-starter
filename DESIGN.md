# Agent Runtime Spring Boot Starter — 实现规格文档（SDD）

> 本文档用于把一个 **可嵌入 Spring Boot 的 Agent 执行引擎 Starter** 交给实现 Agent 落地。
> 读者应当可以仅凭本文完成全部代码，无需额外澄清。所有接口签名、表结构、模块边界均已给出。

---

## 1. 项目定位

### 1.1 它是什么（定位澄清）

一个 Maven 依赖（`agent-runtime-spring-boot-starter`）。
**定位：企业 Java 中台专用的「Agent 可靠性增强层」，不是又一个 Agent 编排框架。** 它补 Spring AI / LangChain4j 不管的三块缺口（幂等防资损、成本可控、开箱评测），并以零侵入注解接入存量业务。
**不强制绑定 LLM 框架（framework-agnostic）**：核心模块零 Spring AI / LangChain4j 依赖，二者仅作可选集成模块；业务方只加本 Starter 一个依赖即可运行（`AgentPlan.decide()` 内部用什么调模型，引擎不关心）。

业务方引入后，只需：

1. 在启动类加 `@EnableAgentRuntime`；
2. 在自己的业务方法上加 `@AgentTool`；
3. 用一个类描述「每一步该调哪个工具」（`AgentPlan`）；
4. 调用 `AgentEngine.submit(...)` 运行。

引擎自动提供：**持久化执行、断点续跑、幂等、失败重试、全链路 trace、成本归因、人工挂起/恢复、评测门禁**。

### 1.2 解决的真问题

Agent 上生产的「最后一公里」不是智商（调模型），而是可靠性：

- 长任务（跑 20 分钟、调 8 个系统）中途任一环节超时 → 传统写法整单失败重跑，烧钱且客户久等；
- 写操作（退款、改单、发券）副作用重复 = 直接资损；
- 出事无法定位到底哪一步挂了；
- 改 prompt / 换模型后效果是否回退无人知晓。

### 1.3 非目标（明确不做）

- 不做模型推理层（不封装 LLM 调用本身，只规定 `AgentPlan.decide()` 由业务方实现）；
- 不做独立平台 / SaaS（不要求独立部署，纯嵌入式 jar）；
- 不绑定具体 LLM SDK（业务方用 LangChain4j / Spring AI / 自写 HTTP 均可）。

### 1.4 竞品现状与差异化定位（实现者必读，关乎项目是否成立）

> 经调研（2026），本项目原方案的「执行引擎 + 断点续跑 + 人工挂起」部分已被开源项目覆盖。
> 若定位成「又一个 Agent 执行引擎」会与下述项目正面撞车且做不过社区，**必须按本节的差异化重定位**。

**已被覆盖的能力（不要重复造）：**

| 能力 | 已有实现 | 说明 |
| --- | --- | --- |
| 工具注解 | Spring AI `@Tool`、LangChain4j `@Tool` | 本项目的 `@AgentTool` 仅在其基础上**增加幂等/重试/截断语义**，不是从零定义工具机制 |
| 对话记忆持久化 | Spring AI `ChatMemory` + JDBC/Cassandra/Neo4j 仓储 | 不要重做记忆存储 |
| 状态化执行 + checkpoint 续跑 | **LangGraph4j**（Java 版 LangGraph，活跃，1.8.x）`Checkpoints` + `PostgresSaver/RedisSaver` | 已覆盖持久化与重放 |
| 暂停/恢复（HITL） | LangGraph4j `Breakpoints` | 已覆盖人工挂起 |
| 评测接口 | Spring AI `Evaluator` | 有接口无门禁 |
| 可观测 | LangGraph4j OpenTelemetry、Spring Micrometer | 已覆盖 |

**本项目的差异化缺口（Java 生态空白，重点投入）：**

- **幂等 Outbox（写操作防资损）**：LangGraph4j 有 checkpoint 但不强制工具幂等；Spring AI 完全没有。企业最痛的「退款退两次」由本项目兜底，并与 Java 中台事务/CDC 绑定。
- **成本按步骤归因 + 成本路由器**：框架只管「能不能跑」，本项目管「花了多少、小模型能否先试」。
- **开箱评测门禁**：把 Spring AI `Evaluator` 做成「任务集管理 + 报告 + 失败 block build」的产品化能力。
- **零侵入注解接入存量业务**：LangGraph4j 是「图编排」心智，需把业务重写成 node/edge；本项目是「给现有方法贴 `@AgentTool`」，对中台存量代码友好得多。

**与 LangChain4j / LangGraph4j 的关系**：互补且正交。它们是编排与 checkpoint 底座；本项目叠加在它们外层，补「企业落地三缺口」。业务方用 LangChain4j/Spring AI 写 `AgentPlan.decide()`，用本项目跑执行并获幂等/成本/评测。

**M2 续跑实现决策**：保留自研轻量事件溯源续跑（见 §11.4），原因是与「注解零侵入 + 幂等同事务」心智统一；实现者也可改为依赖 LangGraph4j `PostgresSaver` 做 checkpoint，但需在 README 明确说明「基于 LangGraph4j checkpoint 实现续跑」，避免被质疑重复造轮子。

---

## 2. 技术栈

| 维度 | 选型 | 说明 |
| --- | --- | --- |
| 语言 | Java 21 | 用 record、虚拟线程友好 |
| 框架 | Spring Boot 3.3+ | 自动装配、AOP |
| 构建 | Maven | 产出 Starter jar |
| 持久化 | PostgreSQL 16 | 任务/事件/幂等表 |
| ORM | Spring Data JPA | 也可换 MyBatis，本文按 JPA 描述 |
| AOP | Spring AOP | 拦截 `@AgentTool` 做校验/幂等/重试/trace |
| 重试/熔断 | Resilience4j | 退避、熔断、舱壁 |
| 可观测 | OpenTelemetry + Micrometer | span、指标 |
| 表达式求值 | Spring EL (SpEL) | 解析 `idempotencyKey`/`arg` 表达式 |
| 事务 | Spring 声明式事务 + Transactional Outbox | 保证副作用与状态一致 |

**外部依赖坐标（待实现者确认版本）**：

```
org.springframework.boot:spring-boot-starter
org.springframework.boot:spring-boot-starter-aop
org.springframework.boot:spring-boot-starter-data-jpa
org.springframework.boot:spring-boot-starter-actuator
io.github.resilience4j:resilience4j-spring-boot3
io.micrometer:micrometer-tracing-bridge-otel
io.opentelemetry:opentelemetry-api
org.postgresql:postgresql
```

**可选集成依赖（用 `<optional>true</optional>`，不进核心）**：

```
org.springframework.ai:spring-ai-core            // 可选：@Tool 桥接
dev.langchain4j:langchain4j                      // 可选：@Tool 桥接
```

---

## 2.1 模块划分（framework-agnostic，必须这样拆）

> 核心诉求：业务方只加 `agent-runtime-spring-boot-starter` 一个依赖，**不被强制引入 Spring AI / LangChain4j**。

```
agent-runtime-parent/                (pom, packaging=pom)
├── agent-runtime-core/              ← 零 LLM 框架依赖：注解/引擎/持久化/幂等/trace/成本
├── agent-runtime-spring-ai/         ← 可选：把 Spring AI @Tool 适配为 @AgentTool 语义
├── agent-runtime-langchain4j/       ← 可选：同上
└── agent-runtime-spring-boot-starter/ ← 聚合 + 自动装配，仅依赖 core
```

约束：

- `core` 的 `pom` 中**不得**出现 `spring-ai-*` / `langchain4j-*`；
- 两个集成模块对框架依赖标 `<optional>true</optional>`；
- starter 只传递依赖 `core`；引入 `-spring-ai` 时由业务方显式添加；
- 自动装配用 `@ConditionalOnClass` 保护，缺框架时集成模块自动不生效、core 照常工作。

---

## 3. 总体架构

```
┌─────────────────────────────────────────────────────────────┐
│  业务方应用 (Spring Boot)                                      │
│                                                               │
│   @EnableAgentRuntime                                          │
│        │                                                       │
│   ┌────▼──────────────────────────────────────────────┐      │
│   │  AgentEngine (submit / recover / resume)           │      │
│   │      │                                             │      │
│   │      ├─ 1. 上下文组装 (ContextAssembler)           │      │
│   │      ├─ 2. 决策       (调用 AgentPlan.decide)      │      │
│   │      ├─ 3. 工具执行   (@AgentTool 拦截器)          │      │
│   │      │        ├─ Schema 校验                       │      │
│   │      │        ├─ 幂等检查 (IdempotencyKey)         │      │
│   │      │        ├─ 重试/退避 (Resilience4j)          │      │
│   │      │        └─ 结果截断                          │      │
│   │      ├─ 4. 持久化     (Task / TaskEvent / Outbox)  │      │
│   │      └─ 5. 判定       (完成 / 续跑 / 挂起人工)      │      │
│   └────────────────────────────────────────────────────┘      │
│        │                   │                    │              │
│   ┌────▼────┐        ┌─────▼─────┐       ┌─────▼─────┐        │
│   │PostgreSQL│       │OpenTelemetry│      │ 评测门禁   │        │
│   │(事件溯源)│       │ (span/trace)│      │ (CI 任务集)│        │
│   └─────────┘        └───────────┘       └───────────┘        │
└─────────────────────────────────────────────────────────────┘
```

横切能力（贯穿每一步）：**持久化（事件溯源）**、**可观测（OTel span + 成本）**、**幂等（Outbox）**。

---

## 4. 核心领域模型

### 4.1 枚举与状态

```java
public enum TaskStatus {
    PENDING,       // 已提交，未开始
    RUNNING,       // 执行中
    SUSPENDED,     // 挂起等人工（HITL）
    COMPLETED,     // 完成
    FAILED,        // 最终失败
    RECOVERING     // 恢复中
}

public enum StepType {
    LLM_DECISION,  // 决策步
    TOOL_CALL,     // 工具调用步
    HUMAN_REVIEW,  // 人工介入步
    COMPLETION     // 完成步
}
```

### 4.2 Task（一次 Agent 运行的聚合根）

```java
public class Task {
    String taskId;              // UUID
    String agentName;           // AgentPlan 标识
    TaskStatus status;
    String userId;              // 触发者
    String userRequest;         // 初始输入
    int currentStep;            // 当前执行到第几步
    Map<String, Object> context; // 分层记忆（工作区/摘要/检索）
    Instant createdAt, updatedAt;
    BigDecimal totalCost;       // 累计成本（元）
    Long totalTokens;           // 累计 token
}
```

### 4.3 TaskEvent（事件溯源：每一步的结果记录）

```java
public class TaskEvent {
    Long id;
    String taskId;
    int stepIndex;
    StepType stepType;
    String toolName;            // 若是工具步
    String payload;             // JSON：输入/输出/错误
    Boolean success;
    Long tokens;
    BigDecimal cost;
    Instant createdAt;
}
```

### 4.4 IdempotencyRecord（幂等 + Outbox）

```java
public class IdempotencyRecord {
    String idempotencyKey;     // 业务表达式求值结果，唯一
    String taskId;
    int stepIndex;
    String status;             // INIT / DONE / FAILED
    String resultPayload;      // 已执行结果缓存
    Instant createdAt;
}
```

---

## 5. 公共 API（业务方接触的接口）

### 5.1 注解

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AgentTool {
    String name();                                  // 工具名，唯一
    String idempotencyKey() default "";             // SpEL，如 "#orderId"
    int maxAttempts() default 3;                    // 最大重试
    long retryBaseDelayMs() default 500;            // 退避基准
    int resultTruncateChars() default 4000;         // 结果截断长度
    boolean requireHumanConfirm() default false;     // 执行前是否需人工确认
}
```

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import(AgentRuntimeAutoConfiguration.class)
public @interface EnableAgentRuntime {}
```

> **框架无关约束**：`AgentPlan.decide()` 是业务方唯一需要接触 LLM 的地方，其内部可用原生 HttpClient、Spring AI、LangChain4j 或规则引擎——**引擎不感知、不依赖**。这是「只加一个依赖即可运行」的技术前提。

### 5.2 决策接口（业务方实现）

```java
public interface AgentPlan {
    /** 根据当前上下文决定下一步：调哪个工具 + 参数，或直接完成 */
    Step decide(Context ctx);
}

public class Step {
    public static Step tool(String toolName, Map<String, Object> args);
    public static Step humanReview(String reason);   // 请求人工介入
    public static Step complete(Object result);      // 结束

    /** M4：把这次决策的用量交给引擎（输入/输出 token + 金额），引擎据此做成本归因 */
    public Step withUsage(Usage usage);
}

/** M4：一次决策的花费；金额精度 4 位小数，与 task_event.cost 的 NUMERIC(12,4) 一致 */
public record Usage(long inputTokens, long outputTokens, BigDecimal cost) {
    public static Usage priced(long in, long out, BigDecimal inPricePerMillion, BigDecimal outPricePerMillion);
    public long totalTokens();
}
```

### 5.3 引擎接口

```java
public interface AgentEngine {
    /** 提交一次 Agent 运行，返回 taskId（异步执行，立即返回） */
    String submit(AgentPlan plan, String userId, String userRequest);

    /** 阻塞获取最终结果（带超时） */
    TaskResult await(String taskId, Duration timeout);

    /** 从持久化状态恢复一个中断/崩溃的任务（断点续跑） */
    void recover(String taskId);

    /** 人工在后台确认后，从挂起点继续 */
    void resume(String taskId, Map<String, Object> humanInput);

    /** 查询任务状态与全链路事件 */
    TaskView getTask(String taskId);
}
```

### 5.4 启动类用法（业务方）

```java
@SpringBootApplication
@EnableAgentRuntime
public class HisApp { public static void main(String[] a){ SpringApplication.run(HisApp.class,a); } }
```

---

## 6. 模块拆分与实现任务（按此顺序交付）

> 实现 Agent 应按 M1→M5 顺序，每完成一个里程碑即可独立验证。

### M1 — 最小可跑闭环（不做持久化）

**目标**：`@AgentTool` + `AgentPlan` + `AgentEngine` 能跑通「决策→调工具→完成」。

- 定义 `AgentTool`、`AgentPlan`、`Step`、`AgentEngine` 接口
- `AgentEngine.submit` 同步循环：组装 context → `plan.decide()` → 反射调用 `@AgentTool` 方法 → 判定
- 提供内存版 `TaskRepository`（Map 实现）
- 写一个示例 `RefundAgent` + `refund()` 工具（mock 返回）

**验收**：`main` 里跑通一次退款流程，打印每步日志

### M2 — 持久化 + 断点续跑（核心卖点）

- JPA 实体 `Task` / `TaskEvent` + PostgreSQL 表（见 §7）
- `TaskRepository` 实现（JPA）
- `submit` 改为异步（提交即落库 `PENDING`，后台线程池执行 `RUNNING`）
- `recover(taskId)`：读 `Task` + 重放 `TaskEvent` 到 `currentStep`，从断点继续
- 进程崩溃模拟：执行中抛 `RuntimeException`，验证 `recover` 能从最后成功事件续跑，不重复已完成步骤

**验收**：跑任务到一半 kill，重启后 `recover` 续跑成功，token 不重算已完成步

### M3 — 幂等 + 重试 + 人工挂起

- 幂等拦截（本实现放在 `ToolExecutor`，不依赖 Spring AOP）：
  - 解析 `idempotencyKey` → 查 `IdempotencyRecord`；**键 = 工具名 + ":" + 求值结果**（见下方 §7 注）
  - 命中 `DONE` → 直接返回缓存结果（不重执行）
  - 未命中 → 占位 `INIT` → 执行 → 写 `DONE` + `resultPayload`（与 outbox 同事务，见 §7）
  - 命中 `INIT` → **副作用状态未知**，默认挂起等人工（`InDoubtPolicy`）
  - `requireHumanConfirm=true` → 执行<b>前</b>写 `SUSPENDED` + 事件后正常返回，等待 `resume`
    （不要用抛异常实现：抛异常 = 回滚，刚写的 SUSPENDED 会一起消失；
    `HumanConfirmRequiredException` 仍保留并识别，供业务在工具内部主动抛出）
- 指数退避重试（`maxAttempts` / `retryBaseDelayMs`）：**有副作用的工具只重试
  `TransientFailureException`**（业务声明「肯定没发生副作用」），其余异常进入在途未知流程
- 结果截断至 `resultTruncateChars`，截断后包装为 `{_truncated:true,_originalChars:N,body:...}` 以保证仍是合法 JSON
- `resume(taskId, humanInput)`：认领 `SUSPENDED` → 写人工作答事件 → 走与崩溃恢复相同的重放路径继续。
  `humanInput` 支持 `approved` / `note` / `retrySideEffect` / `result`

**验收**：同一 `orderId` 二次调用退款只真正执行一次；工具抛错自动重试 3 次；带 `requireHumanConfirm` 的工具正确挂起，`resume` 后继续

### M4 — 可观测（OTel + 成本归因）

- 每步开始前 `tracer.spanBuilder(toolName)`，结束时记录 attribute：`tokens`、`cost`、`success`、`stepIndex`
- `Task.totalTokens` / `totalCost` 累加（每事件写回）
- Actuator 暴露 `agent.tasks.active`、`agent.steps.total`、`agent.cost.total` 指标

**验收**：一次运行后 `getTask` 能看到每步 span 与分步成本；指标可经 `/actuator/prometheus` 抓取

#### M4 实现修正（实测得出，建议回写本规格）

1. **「每事件写回」会丢更新。** `task.total_cost = 内存里的旧值 + delta` 在并发下必然少账且不报错。
   改为存储侧原子累加：`UPDATE task SET total_tokens = total_tokens + ?, total_cost = total_cost + ?`，
   并且 `save()` **不再写这两列**——计数列只有一个写入口。接口相应增加
   `TaskRepository.addUsage(taskId, tokens, cost)`。
2. **用量必须由 plan 上报。** `AgentPlan.decide()` 是唯一知道「调了谁、花了多少」的地方，
   因此 `Step` 增加 `withUsage(Usage)`；引擎不猜价目、不认供应商。
3. **收尾决策也要计费。** `Step.complete()` 背后同样是一次模型调用，其 `Usage` 记在
   `COMPLETION` 事件上；漏掉它等于「最贵的那次调用永远不入账」。
4. **重放不计费。** 断点重放（崩溃 / 人工确认）不重新调用模型，因此也不得再次累加用量。
5. **事件是账本，汇总是物化视图。** `appendEvent` 与 `addUsage` 是两次写，中间崩溃会让汇总少算；
   恢复时须按事件重算并覆盖（`reconcileUsage`），而不是「尽量补一笔」。
6. **时长落库。** `task_event` 增加 `duration_ms`：OTel 收集器有保留期，而「上周那笔为什么慢」
   是三天后才问的问题。`TaskView.spans()` 因此可以完全离线还原步级时间线。
7. **可观测是 SPI，不是依赖。** `AgentTracer` / `Metrics` 由 `core` 定义、默认 noop；
   OTel / Micrometer 由 starter 以适配器形式接入，不破坏 §2.1 的零依赖约束。


### M5 — 评测门禁（可选但强烈建议）

- `EvalCase`：输入 + 期望（确定性断言 or LLM-judge 表达式）
- `EvalRunner`：批量跑 `List<EvalCase>`，输出通过率/失败分类
- 接 CI：pom 加 `exec-maven-plugin` 跑 `EvalRunner`，通过率低于阈值则 build 失败

**验收**：内置 20 条退款场景 case，改动 `RefundAgent` 后跑评测能拦截一次回退

---

## 7. 数据模型（PostgreSQL DDL）

```sql
CREATE TABLE task (
    task_id        VARCHAR(64) PRIMARY KEY,
    agent_name     VARCHAR(128) NOT NULL,
    status         VARCHAR(32)  NOT NULL,
    user_id        VARCHAR(64),
    user_request   TEXT,
    current_step   INT          NOT NULL DEFAULT 0,
    context_json   JSONB,
    total_cost     NUMERIC(12,4) NOT NULL DEFAULT 0,
    total_tokens   BIGINT        NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE task_event (
    id           BIGSERIAL PRIMARY KEY,
    task_id      VARCHAR(64) NOT NULL REFERENCES task(task_id),
    step_index   INT         NOT NULL,
    step_type    VARCHAR(32) NOT NULL,
    tool_name    VARCHAR(128),
    payload      JSONB,
    success      BOOLEAN,
    tokens       BIGINT,
    cost         NUMERIC(12,4),
    duration_ms  BIGINT,                        -- M4 补：本步耗时，支持离线还原步级时间线
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_event_task ON task_event(task_id, step_index);

CREATE TABLE idempotency_record (
    idempotency_key VARCHAR(256) PRIMARY KEY,
    task_id         VARCHAR(64) NOT NULL,
    step_index      INT         NOT NULL,
    status          VARCHAR(16) NOT NULL,   -- INIT / DONE / FAILED
    result_payload  JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_idem_task ON idempotency_record(task_id);

-- 补上：outbox 表（原文缺少，而正文要求「副作用与幂等标记同事务 + CDC 投递」，
-- 没有这张表这个要求无处落地）
CREATE TABLE outbox (
    id            BIGSERIAL PRIMARY KEY,
    event_type    VARCHAR(64)  NOT NULL,      -- 如 tool.executed
    aggregate_id  VARCHAR(64)  NOT NULL,      -- 本例是 task_id
    payload       JSONB,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,                -- NULL = 待投递
    attempts      INT          NOT NULL DEFAULT 0,
    last_error    VARCHAR(1024)
);
CREATE INDEX idx_outbox_pending ON outbox(published_at, id);
```

**幂等键的组成（实现时发现的必要修正）**：`idempotency_key` 必须是 `工具名 + ":" + 表达式求值结果`。
原文只说「业务表达式求值结果，唯一」，但 `queryOrder` 与 `refund` 常常共用 `idempotencyKey="#orderId"`；
不按工具名隔离时，`queryOrder` 写入的 `DONE` 记录会让 `refund` 直接返回订单快照当作退款回执——
一次真实退款被静默跳过。这是资损级缺陷，不是命名偏好。

**Transactional Outbox 约束**：`@AgentTool` 的真实副作用（如 `orderClient.refund`）与 `idempotency_record` 写入必须在**同一数据库事务**内提交，确保「执行成功」与「幂等标记」原子。跨服务副作用若无法同事务，改用 CDC（Debezium）消费 outbox 表投递。

实现说明：跨服务 HTTP 调用不可能与本地事务原子，因此落地形态是
**`idempotency_record` 置 `DONE` 与 `outbox` 插入同事务**（两者同库，可以做到），
「副作用已发生」这条事实由 outbox 负责不丢；投递侧是 at-least-once，消费方需按
`event_type + aggregate_id` 幂等。

---

## 8. 自动装配（Spring Boot Starter 规范）

在 `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 写入：

```
com.agentruntime.autoconfigure.AgentRuntimeAutoConfiguration
```

`AgentRuntimeAutoConfiguration` 需注册：`AgentEngine` 实现 Bean、`AgentToolInterceptor`、`TaskRepository`、OTel `Tracer` Bean、Resilience4j 配置。`@ConditionalOnMissingBean` 允许业务方覆盖。

Starter artifactId 命名：`agent-runtime-spring-boot-starter`，groupId：`com.yourname`（实现者替换为自己的）。

---

## 9. 目录结构（多模块，framework-agnostic）

```
agent-runtime-starter/                     # 仓库根 = parent pom (packaging=pom)
├── pom.xml
├── DESIGN.md
├── README.md                              # 含 Maven 引用片段 + 快速开始（见 §10）
├── agent-runtime-core/                    # 核心，零 LLM 依赖
│   └── src/main/java/com/agentruntime/
│       ├── annotation/  AgentTool.java, EnableAgentRuntime.java
│       ├── api/         AgentPlan.java, Step.java, AgentEngine.java, Task*.java
│       ├── engine/      DefaultAgentEngine.java, ContextAssembler.java, DecisionLoop.java
│       ├── interceptor/ AgentToolInterceptor.java
│       ├── persistence/ Task.java, TaskEvent.java, IdempotencyRecord.java, *Repository.java
│       ├── observability/ TracingAspect.java, Metrics.java
│       ├── eval/        EvalCase.java, EvalRunner.java
│       └── autoconfigure/ AgentRuntimeAutoConfiguration.java
├── agent-runtime-spring-ai/               # 可选：SpringAiToolBridge（@ConditionalOnClass）
├── agent-runtime-langchain4j/             # 可选：LangChain4jToolBridge
├── agent-runtime-spring-boot-starter/     # 聚合，仅依赖 core（+ 自动装配 imports）
└── agent-runtime-samples/                 # 可跑 demo：RefundAgentDemo（原生 HttpClient 版）
```

---

## 10. README 必须包含的引用片段（实现者写出）

```xml
<dependency>
  <groupId>com.yourname</groupId>
  <artifactId>agent-runtime-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

README 第一屏给出：上述依赖 → `@EnableAgentRuntime` → `@AgentTool` 示例 → `engine.submit()` → 预期输出。解决「别人不知道这依赖是啥」。

---

## 11. 关键设计决策与约束（实现者必读）

- **幂等边界 = at-least-once + 幂等工具，不追求 exactly-once**。跨服务副作用用 Outbox/CDC 保证最终一致，不在引擎内做分布式事务。
- **`idempotencyKey` 为空时不做幂等**，由业务方决定；写操作务必填。
- **`AgentPlan.decide()` 是业务方唯一必须实现的智能点**，引擎不替它做规划，只负责可靠执行其决策。
- **崩溃恢复基于事件溯源重放**：`recover` 不重算 LLM 决策（耗钱），而是从 `TaskEvent` 重建 `context` 到 `currentStep` 后继续。
- **上下文 (`context_json`) 默认全量存 JSONB**；压缩/分层记忆为 M5+ 增强，M1–M4 先全量，保证不丢信息。
- **与 Temporal 的取舍**：本文档用自研轻量状态机（便于讲清机制、依赖少）；若实现者更想体现工程判断力，可把 M2 换成 Temporal SDK，但需额外讲清 workflow 确定性约束。默认走自研。
- **不与 LangGraph4j 正面竞争**：编排与 checkpoint 已由 LangGraph4j 覆盖，本项目不重做图引擎，聚焦幂等防资损、成本归因、开箱评测三缺口 + 零侵入注解接入。任何「执行/续跑」相关实现都要在 README 注明与现有框架的关系，避免出现「重复造轮子」质疑。
- **Framework-agnostic 是硬约束**：`core` 模块禁止依赖任何 LLM 框架；Spring AI / LangChain4j 集成必须独立成模块并用 `<optional>true</optional>` + `@ConditionalOnClass` 隔离。目的是让「只加一个依赖」真正成立，这是对企业存量系统（不愿被框架绑定、怕版本冲突）的核心卖点。

---

## 12. 验收总脚本（交给实现者自测）

```
1. mvn clean install
2. 运行 RefundAgentDemo.main()
3. 断言：
   - 任务从 PENDING→RUNNING→COMPLETED
   - getTask 返回 3+ 步事件，含 tokens/cost
   - 同 orderId 二次调用，真实 refund 只执行 1 次（查日志/DB）
   - 中途 kill 后 recover，COMPLETED 且 total_tokens == 中断前已完成步之和
   - 带 requireHumanConfirm 的工具 SUSPENDED，resume 后 COMPLETED
4. mvn test 全绿
```
