# agent-runtime-starter

企业 Java 中台的 **Agent 可靠性增强层**，不是又一个 Agent 编排框架。

它补 Spring AI / LangChain4j 不管的三块缺口——**幂等防资损、成本可控、开箱评测**——并以零侵入注解接入存量业务：

```java
@SpringBootApplication
public class HisApp { public static void main(String[] a) { SpringApplication.run(HisApp.class, a); } }

// 引擎、仓储、幂等、工具扫描由 starter 自动装配；@EnableAgentRuntime 是可选的显式开关
// （com.agentruntime.autoconfigure.EnableAgentRuntime）

@AgentTool(name = "refund", idempotencyKey = "#orderId")
public RefundReceipt refund(String orderId, BigDecimal amount) { ... }

String taskId = agentEngine.submit(new RefundAgent(), userId, "ORD-20261114-001");
```

引擎自动提供：持久化执行、断点续跑、幂等、失败重试、全链路 trace、成本归因、人工挂起/恢复、评测门禁。

> 完整规格见 [`DESIGN.md`](DESIGN.md)。本仓库已完成 **M1–M5 + M8–M13**：闭环、持久化续跑、
> 幂等/重试/人工挂起、可观测与成本归因、评测门禁、Spring Boot 自动装配、运维控制面、
> 租约与孤儿判定、PostgreSQL 方言、企业级治理补齐（安全/审计/稳定性/迁移/发布）。
> （M9/M10 及之后不在 DESIGN 的里程碑清单里，是实现与评审过程中补的——理由见下。）

---

## 当前进度

| 里程碑 | 内容 | 状态 |
| --- | --- | --- |
| **M1** | `@AgentTool` + `AgentPlan` + `AgentEngine` 跑通「决策 → 调工具 → 完成」，内存仓库，每步日志 | ✅ 已完成 |
| **M2** | 持久化（JDBC + 事件溯源）、异步执行、`recover()` 断点续跑、原子认领防双跑 | ✅ 已完成 |
| **M3** | 幂等记录 + Outbox、指数退避重试、`requireHumanConfirm` 人工挂起与 `resume()`、结果截断 | ✅ 已完成 |
| M4 | OTel span、步级成本归因、Actuator 指标 | ✅ 已完成 |
| M5 | 评测门禁（`EvalCase` / `EvalRunner` / CI 阈值） | ✅ 已完成 |
| M8 | Spring Boot 自动装配（`AgentRuntimeAutoConfiguration` + `AutoConfiguration.imports`、Micrometer/OTel 适配器、连接池支持） | ✅ 已完成 |
| M9 | 运维控制面（actuator 端点：挂起清单 / 在途未知巡检 / 恢复 / 批量护栏） | ✅ 已完成（DESIGN 未列，落地增补） |
| M10 | 租约与孤儿判定（`owner_id` + 心跳 + 状态 CAS，多实例不再抢跑） | ✅ 已完成（DESIGN 未列，落地增补） |
| M11 | 数据库方言从 H2 改为 **PostgreSQL**（默认 PG、DDL 主脚本、连接配置、docker compose、真方言 CI） | ✅ 已完成（DESIGN §2 本就要求 PostgreSQL） |
| M12 | core 单元测试（34 条，零第三方依赖不破）+ GitHub Actions CI（`mvn verify` 一键门禁） | ✅ 已完成（补齐工程治理缺口） |
| M13 | **企业级补齐**：端点鉴权/归属校验/审计、有界队列与结果缓存/优雅停机、配置元数据与校验、Flyway 迁移、健康探针、发布治理、实例注册表/告警、Java 21 profile + 第二评测域 | ✅ 已完成（对照「完整性评审」逐条清零） |
| M14 | Docker PostgreSQL 真方言门禁（一键验收脚本 + CI service container） | ✅ 已完成 |
| M15 | 共享实例注册表 JDBC 版 + Webhook 告警出口（多副本判活、告警真发出去） | ✅ 已完成 |

## 快速开始

CI 双轨：`verify` job 跑 core 单测、starter 装配/租约/鉴权/健康检查断言、双业务域评测门禁（替身，不依赖外部数据库）；
`pg-dialect` job 用 Docker PostgreSQL 16 跑全部演示（真方言）：

```bash
mvn clean verify
# core 47 条 + starter 36 条 + 退款域评测 20/20 + 报表域评测 6/6
# 评测报告落 agent-runtime-samples/target/eval-report.md

# DESIGN 目标版本 Java 21（需 JDK 21+，本机 JDK25 实测通过）：
JAVA_HOME=/path/to/jdk21 mvn clean verify -Pjava21
```

`.github/workflows/ci.yml` 用 JDK 17 与 21 双版本矩阵跑同一条命令（Maven 缓存 + upload-artifact）。
core 的 JUnit 只在 `test` 作用域，主依赖树仍为空——「零第三方依赖」约束不变。

### M13 企业级补齐（对照评审缺口）

| 维度 | 补齐内容 | 如何验证 |
| --- | --- | --- |
| 安全 | `OpsAuthorizer.ownerOrAdmin()`：拿到 taskId 的陌生人不能 resume 他人任务；`agent.runtime.ops.strict-auth=true` 开启；`OpsPrincipal` bean 接 Spring Security 身份 | `AgentRuntimeEndpointTest.strictAuthRejectsStrangerAndAudits` |
| 审计 | `OpsAuditLog`（默认有界内存 1000 条）；`agent.runtime.ops.audit-jdbc=true` 落进 `ops_audit` 表（schema-init/Flyway 均建表）：所有运维动作含被拒的都留痕 | 同上断言 + `auditWritesToDatabase` |
| 稳定性 | 执行器有界队列（满则 CallerRuns 背压）、结果缓存有界（淘汰最早已完成，在途不淘汰）、优雅停机（先等在途再停心跳） | `BoundedResultsTest` / `EngineLifecycleTest` |
| 配置治理 | `spring-configuration-metadata.json`（IDE 自动提示）+ `@Validated` + `spring-boot-starter-validation` | `configurationMetadataIsPackaged` |
| 数据库迁移 | Flyway 版本化脚本（按 PG/H2 分目录、独立历史表）；`agent.runtime.flyway.enabled=true` 启用，自动与 schema-init 互斥 | `flywayMigrationRunsAndDisablesLegacyInitializer` |
| 可运维 | `AgentRuntimeHealthIndicator`（`/actuator/health/agentRuntime`）：停机中/DB 不可连/孤儿堆积报 DOWN，挂起只上报不误杀 | `HealthIndicatorTest` |
| 发布 | Apache-2.0 LICENSE、pom licenses/scm/developers、`-Prelease` 产出 sources/javadoc/GPG 签名 | `mvn -Prelease -Dgpg.skip=true package` |
| 多实例 | `InstanceRegistry`（租约之外的判活第二意见；`agent.runtime.ops.registry-jdbc=true` 切共享 JDBC 版）+ `OpsAlert`（配 `alert-webhook-url` 即发 webhook，默认 noop） | `JdbcInstanceRegistryTest` / `InstanceRegistryRecoveryTest` |
| 评测泛化 | 第二业务域「报表查询」6 条用例，同一 `EvalRunner` 不改一行即可跑；假成本用例被刻意剔除 | `mvn verify` 输出「报表域通过」 |

> **安全默认仍为兼容**：`strict-auth` 默认 false（放行 + 审计），因为直接改成拒绝会打断现有接入方；
> 生产务必设 `strict-auth=true` 并注入 `OpsPrincipal`。这是「升级不破坏用户」与「默认安全」之间的显式取舍。
>
> **告警默认 noop**：框架埋好了告警点；配 `agent.runtime.ops.alert-webhook-url` 即可把告警打到
> 企微/钉钉机器人或自建转发服务（异步、失败不阻塞业务、同事件 30 秒去重）。不配则保持 noop——框架不绑定任何厂商。


业务接入只看 [M8 一节](#m8spring-boot-自动装配一个依赖--一个注解)——加一个依赖、把 `@AgentTool` 打在普通 bean 的方法上即可。
下面这些 `java -cp` 命令是给**维护者**跑各里程碑验收用的，每条都自带断言。

```bash
docker compose up -d          # 起 PostgreSQL 16（默认库 agent_runtime / postgres / postgres）
mvn clean install

# 生成一次 classpath（驱动与依赖都在里面）
mvn -q -pl agent-runtime-samples dependency:build-classpath \
    -Dmdep.outputFile=target/cp.txt -Dmdep.includeScope=runtime
CP="agent-runtime-core/target/classes;agent-runtime-samples/target/classes;$(cat agent-runtime-samples/target/cp.txt)"

# 跑 M1 验收 demo（内存仓库，不碰数据库）
java -cp "$CP" com.agentruntime.samples.refund.RefundAgentDemo
```

## 数据库（PostgreSQL）

M11 起**默认方言是 PostgreSQL**，DDL 主脚本是 `schema-postgresql.sql`（`JSONB` / `BIGSERIAL` /
`TIMESTAMPTZ` 都是真类型，不再靠兼容模式模拟）。连接配置三处生效，系统属性优先：

| 配置 | 系统属性 | 环境变量 | 默认值 |
| --- | --- | --- | --- |
| 方言 | `-Dagent.db=pg\|h2` | `AGENT_DB` | `pg` |
| 地址 | `-Dagent.db.url` | `AGENT_DB_URL` | `jdbc:postgresql://localhost:5432/agent_runtime` |
| 用户 | `-Dagent.db.user` | `AGENT_DB_USER` | `postgres` |
| 密码 | `-Dagent.db.password` | `AGENT_DB_PASSWORD` | `postgres` |

演示会自己建表（DDL 全 `IF NOT EXISTS`）并按需建 schema：每个 demo 用独立 schema
（`rt_m4_...`），互不干扰，也不用手工清库。**M2/M3 的崩溃演示会先 `DROP TABLE`** 重建现场，
所以别把演示指向业务库。

**H2 在这个项目里不是「数据库」，是测试替身**：严格说它是一个嵌入式 RDBMS（有事务、约束、索引、
SQL 引擎），但它不承担本项目的数据库角色——没有高可用、没有运维体系、优化器与并发也不面向生产负载。
所以定位是明确的：**H2 = 让测试与离线演示能跑起来的替身；PostgreSQL = 本项目的数据库**。

这条定位落到实处就是四件事：
1. DDL 主脚本只有 `schema-postgresql.sql`；`schema-h2.sql` 只服务替身，不代表生产表结构；
2. 替身单测的断言只验逻辑（原子认领、幂等、租约、成本累加），**不验方言**，也不依赖 H2 特有行为；
3. 替身只在两处出现：starter 的单元测试与 `-Dagent.db=h2` 的离线兜底
   （没装 PG 时跑「崩溃续跑」两条跨进程链路，用 H2 文件库 + `WRITE_DELAY=0`）；
4. 方言由 CI 的 `pg-dialect` job 用 Docker `postgres:16-alpine` service container 跑全部验收 demo 覆盖
   （`JSONB`、`BIGSERIAL`、`INSERT ... WHERE EXISTS`、`DROP ... CASCADE`）。

CI 是双轨的：`verify` job 用替身跑单测 + 评测门禁（JDK 17/21 各一遍，不依赖外部服务），
`pg-dialect` job 用真库跑 demo。后者不是摆设：真库第一次跑就抓到了
`context_json` / `result_payload` 等 JSONB 列用 `setString` 写入的真实缺陷——
H2 兼容模式会隐式转换，真 PG 直接报 `column is of type jsonb but expression is of type character varying`。
修复见 `JsonbParams`：用 `setObject(..., Types.OTHER)` 让服务端按目标列推断类型，
core 因此不需要引入任何 PG 专有依赖。本地复跑这条轨道只需一条命令：
`bash scripts/verify-pg.sh`（Git Bash / Linux / macOS）——脚本自己起库、构建、跑全部 demo；
CI 的 `pg-dialect` job 用 `SKIP_DOCKER_UP=1 bash scripts/verify-pg.sh` 调同一份脚本。

### M2：真实崩溃续跑（两个独立 JVM）

```bash
# 默认连 PG（上文的 docker compose 起来即可）；没装 PG 时所有命令加 -Dagent.db=h2
java -cp "$CP" com.agentruntime.samples.refund.RecoverDemo crash     # 阶段 A：跑一半 halt 进程（exit 17）
java -cp "$CP" com.agentruntime.samples.refund.RecoverDemo recover   # 阶段 B：新进程读同一库，断点续跑
```

阶段 A 的退款工具在副作用发生前 `Runtime.halt(17)`，进程没有任何收尾动作（不落 FAILED、不关线程池）；
阶段 B 打开同一个数据库（PG 或 H2 兜底），先把「安全默认」跑一遍（心跳还新鲜 → 接管 0 个，证明护栏生效），
再用 `recoverAllInterrupted(true)` 走「单实例崩溃重启」语义接管并续跑。
验收断言：`queryOrder` 只执行 1 次（已完成步不重复）、`refund` 只执行 1 次、
**LLM_DECISION 只有 2 条（崩溃前已落库的决策不重算）**、终态 COMPLETED。

### M3：幂等 / 重试 / 人工挂起（单进程，8 组场景 / 41 条断言）

```bash
java -cp "$CP" com.agentruntime.samples.refund.M3Demo
```

覆盖：① 同订单提交两次只扣一次款（第二次 `fromCache=true`，工具根本没被调用）② 暂时性故障退避重试
（500ms+1000ms，扣款仍 1 次）③ `requireHumanConfirm` 挂起 → 批准 → 继续 ④ 拒绝 → 不执行副作用
⑤ 幂等记录停在 `INIT` → 挂起问人，绝不盲目重试 ⑥ 超长结果截断后仍是合法 JSON
⑦ 副作用事实进 outbox 并可投递 ⑧ 两个任务并发退同一笔订单只扣一次款（真实 JDBC 主键冲突）。

### M3：副作用已发生却崩溃（两个独立 JVM，最值钱的场景）

```bash
java -cp "$CP" com.agentruntime.samples.refund.InDoubtDemo crash     # 扣款成功后 halt(17)
java -cp "$CP" com.agentruntime.samples.refund.InDoubtDemo recover   # 新进程：不猜、挂起问人
```

阶段 A 先真正扣款、再 `halt`，所以库里留下的是「`idempotency_record` = INIT、`task_event` 里没有任何 refund 记录」——
和「扣款前崩溃」的现场**完全一样**，框架无从区分。阶段 B 续跑时（同样先跑安全默认、再 `force` 接管崩溃现场）
不做任何猜测：任务挂起等人工；
人查过账本，把退款流水号交给系统（`resume(taskId, {result: {...}})`），任务完成，**全程只扣一次款**。

### M4：span / 分步成本 / Prometheus 指标（44 条断言，真 HTTP 抓取）

```bash
java -cp "$CP" com.agentruntime.samples.m4.M4Demo
```

覆盖：① 每步 span + 分步成本表，`totalTokens/totalCost` 与事件账本逐笔对上
② 挂起→resume 后「被重放的那一步不再计费」③ 8 个独立连接并发累加用量不丢
④ 崩溃窗口（事件已写、汇总未加）按账本校正 ⑤ `/actuator/prometheus` 用 `HttpClient` 真抓，
指标名/值/格式逐项校验（含 `agent_tasks_active` 在任务运行瞬间为 1、结束后归零）。

实测输出（节选）：

```
  ● llm_decision ✓ 3ms {step.kind=TOOL, tokens=820, cost=0.0020}
  ● tool:queryOrder ✓ 21ms {attempts=1, fromCache=false}
  ● llm_decision ✓ 1ms {step.kind=TOOL, tokens=1182, cost=0.0029}
  ● tool:refund ✓ 39ms {attempts=1, fromCache=false}
  ● llm_decision ✓ 0ms {step.kind=COMPLETE, tokens=1544, cost=0.0038}
● agent.task RefundAgent ✓ 125ms {agent.status=COMPLETED, agent.tokens=3546, agent.cost=0.0087}

 step type           tool             result     tokens    cost(¥)       ms
 0    LLM_DECISION   -                ok            820     0.0020        1
 0    TOOL_CALL      queryOrder       ok              0     0.0000       21
 1    LLM_DECISION   -                ok           1182     0.0029        1
 1    TOOL_CALL      refund           ok              0     0.0000       39
 2    COMPLETION     -                ok           1544     0.0038        0
      TOTAL                                        3546     0.0087
```

预期输出（节选）：

```
──────── 场景 1：正常退款 ORD-20261114-001 ────────
[task] 21a41491 | agent=RefundAgent | RUNNING
[step 0] LLM_DECISION -              ok   {"action":"tool","toolName":"queryOrder","args":{"orderId":"ORD-20261114-001"}}
[step 0] TOOL_CALL    queryOrder     ok   {"args":{...},"result":{"orderId":"ORD-20261114-001","amount":128.00,"status":"PAID"}}
[step 1] LLM_DECISION -              ok   {"action":"tool","toolName":"refund","args":{"amount":128.00,"orderId":"ORD-20261114-001"}}
[step 1] TOOL_CALL    refund         ok   {"args":{...},"result":{"receiptId":"RFD-948a436c",...}}
[step 2] COMPLETION   -              ok   {"result":"退款成功，回执号 RFD-948a436c，金额 128.00 元"}
[task] COMPLETED | 步数=2 事件=5 耗时=20ms
...
✅ M1 验收通过：决策→工具→完成闭环成立，事件可定位每一步，失败可归因。
```

demo 覆盖三个场景：正常退款、工具抛业务异常（能否定位到挂在哪一步）、业务判定不可退（不是故障，不应该 FAILED）。

### M5：评测门禁（20 条用例 / 5 段验收 / 23 条断言 / 接 CI）

```bash
java -cp "$CP" com.agentruntime.samples.eval.M5Demo   # 机制验收：基线全绿 + 回退被拦

mvn verify                                            # CI 门禁：verify 阶段自动跑，低于门槛即 build 失败
mvn verify -Deval.threshold=0.9 -Deval.maxCost=0.20   # 调通过率门槛 / 单批成本上限
mvn verify -Deval.skip=true                           # 紧急跳过（请在 PR 里写明原因）
```

每次 `verify` 都会把 Markdown 版报告写到 `agent-runtime-samples/target/eval-report.md`
（`-Deval.report=...` 可改路径）。落盘而不是只打日志，是因为构建日志会过期，
而「这个月通过率与单条成本怎么变的」要跨时间看。

覆盖：① 20 条退款用例，基线 100%（状态矩阵 7 条，其中 **4 条是「不该退」**）
② `JudgeMode.OFF` → 3 条 judge 用例转 SKIP，通过率分母随之收缩（不虚高）
③ `JudgeMode.BLOCKING` → 同一条 judge 判否就把构建打红（用来证明「默认不阻断」是刻意的）
④ 删掉一行订单状态校验（`RegressedRefundAgent`，最小 diff 回退）→ 4 条工具序列失败 + 1 条成本超预算，门禁拦下
⑤ 阈值语义：同一份 75% 的报告在 0.9 门槛下 `exitCode=1`、0.7 门槛下放行。

实测输出（节选）：

```
[1/5] 基线：正常版 RefundAgent（ADVISORY judge）
评测报告 · baseline
----------------------------------------------------------------
PASS  EVAL-04-closed-not-refundable            0ms      0.0049     2002tok
PASS  EVAL-16-judge-polite-tone                1ms      0.0087     3546tok  [judge] reject（仅参考，不阻断）：缺少致歉或感谢用语
----------------------------------------------------------------
合计 20 条：通过 20 / 失败 0 / 跳过 0 · 通过率 100.0% · 总成本 0.1215 元 · 总 token 49570 · 墙钟 197ms

[3/5] JudgeMode.BLOCKING：把 judge 当门禁的后果
      通过 19 / 20，通过率 95.0%，失败分类 [JUDGE_REJECTED×1：judge 判否]
      门禁消息：评测通过率 95.0% 低于门槛 100.0%；失败 1 条：EVAL-16-judge-polite-tone[JUDGE_REJECTED]

[4/5] 回退拦截：删掉订单状态校验（RegressedRefundAgent）
合计 20 条：通过 15 / 失败 5 / 跳过 0 · 通过率 75.0%
失败分类：TOOL_SEQUENCE(工具序列不符)×4，BUDGET_EXCEEDED(成本超预算)×1

失败明细
  1) EVAL-04-closed-not-refundable [TOOL_SEQUENCE]
     原因：[TOOL_SEQUENCE] tools=[queryOrder] → 工具序列期望 [queryOrder]，实际 [queryOrder, refund]；…
     轨迹：[0:LLM_DECISION] → [0:TOOL_CALL queryOrder ✓] → [1:LLM_DECISION] → [1:TOOL_CALL refund ✓] → [2:COMPLETION]
```

一次回退在报告里同时暴露了两件事：**它多退了一笔钱**（工具序列），**它还变贵了**（成本超预算）。
后者是传统单测永远看不见的那一维。

### M8：Spring Boot 自动装配（一个依赖 + 一个注解）

业务方只加这一个依赖，引擎/仓储/幂等/工具扫描/指标/span 全部自动就位：

```xml
<dependency>
  <groupId>com.agentruntime</groupId>
  <artifactId>agent-runtime-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

包发布在 GitHub Packages（`0.1.0` 起），接收方还需要两处配置。**注意：GitHub Packages 的公开包也要求认证**，这是平台规则：

```xml
<!-- 接收方 pom.xml：告诉 Maven 去哪个货架找 -->
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/Echo-moli/agent-runtime-starter</url>
  </repository>
</repositories>
```
```xml
<!-- 接收方 ~/.m2/settings.xml：token 只需 read:packages 权限 -->
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>你的GitHub用户名</username>
      <password>ghp_xxx</password>
    </server>
  </servers>
</settings>
```

不想配 token 的，最省事仍是 clone 本仓库后跑 `mvn clean install`——坐标完全一样，只是包只在自己机器上。

```java
@SpringBootApplication
public class HisApp { }                       // 不需要 @EnableAgentRuntime：imports 文件已生效

@Bean OrderQueryService orderQueryService() { return new OrderQueryService(); }  // 普通 bean，方法上带 @AgentTool

@Service
public class RefundTools {
    @AgentTool(name = "refund", idempotencyKey = "#orderId")
    public RefundReceipt refund(String orderId, BigDecimal amount) { ... }
}
```

```bash
mvn -pl agent-runtime-spring-boot-starter test   # 21 条断言（8 装配 + 7 端点 + 6 租约）
java -cp "…全量 classpath…" com.agentruntime.samples.m8.M8Demo
```

实测输出（节选）：

```
[agent-runtime] 表结构已就绪（方言 h2，脚本 2321 字符）
[agent-runtime] 注册工具 bean refundService(RefundService) → 2 个工具
[agent-runtime] 工具注册完成，共 4 个: [auditLog, queryOrder, refundRequiringApproval, refund]

      TaskRepository  = JdbcTaskRepository
      IdempotencyStore= JdbcIdempotencyStore
      AgentEngine     = DefaultAgentEngine（maxSteps=10，inDoubt=SUSPEND_FOR_HUMAN）
      AgentTracer     = ConsoleTracer        ← agent.runtime.tracer=CONSOLE
      Metrics         = SimpleMetrics
   ✓ 有 DataSource → 仓储走 JDBC
   ✓ AgentEngine 只有一个 bean（@EnableAgentRuntime 没有重复注册）

[4/5] 同一订单再提交一次：幂等仍然生效
      ● tool:refund ✓ 0ms {attempts=0, fromCache=true}      ← 命中幂等缓存，没再扣款
      第二次终态=COMPLETED，本进程真实扣款次数=1

[5/5] 数据真的落到了库里
      task=2，task_event=10，幂等 DONE=2，outbox=2
```

覆盖：① 无 `DataSource` → 全内存（评估阶段不用先装库）② 有 `DataSource` → JDBC 仓储 + 幂等 +
启动建表（`IF NOT EXISTS`）③ `@AgentTool` bean 自动扫描（含 CGLIB 代理场景）④ 业务方 bean 优先
⑤ `agent.runtime.*` 配置真的绑到引擎 ⑥ Micrometer / OTel 在 classpath 上时自动接管指标与 span。

配置项与装配清单见 [`agent-runtime-spring-boot-starter/README.md`](agent-runtime-spring-boot-starter/README.md)。

### M9：运维控制面（挂起任务真的能被人捞回来）

M3 让「挂起」成了引擎的一等公民：任务会安全地停在那里等人。但**停在那里之后呢**？
没有一个地方能看到「哪些在等、在等什么、等了多久」，那些任务就等于静默丢失——
对一笔退款来说，「卡住且没人知道」和「失败」是同一件事，甚至更糟（没人会去重试）。

所以补了一个 actuator 端点：

```
GET  /actuator/agentruntime                          概览（各状态计数 + 超期未决数）
GET  /actuator/agentruntime/suspended                挂起清单（在等哪个工具、等了多久、为什么）
GET  /actuator/agentruntime/in-doubt                 超期停在 INIT 的幂等记录（资损面巡检）
GET  /actuator/agentruntime/task/{taskId}            单个任务的完整视图（轨迹 + 步级 span）
POST /actuator/agentruntime/recover/{taskId}         续跑中断任务
POST /actuator/agentruntime/resume/{taskId}          批准并继续          body: {"note":"审批依据"}
POST /actuator/agentruntime/resume/{taskId}/reject   拒绝并继续
POST /actuator/agentruntime/resume/{taskId}/force    在途未知时人工确认「确实没发生」→ 强制重试
POST /actuator/agentruntime/recover-all[/apply]      批量恢复（默认 dryRun）
```

```bash
# 只暴露这一个端点即可
management.endpoints.web.exposure.include=agentruntime

mvn -pl agent-runtime-samples install
java -cp "…全量 classpath…" com.agentruntime.samples.m9.M9Demo
```

实测输出（节选，全部是真实 HTTP 调用）：

```
GET /overview
{"tasks":{"PENDING":0,"RUNNING":0,"SUSPENDED":1,"COMPLETED":0,"FAILED":0,"RECOVERING":0},
 "staleInDoubt":0,"staleInDoubtAfter":"PT15M","bulkRecoverAllowed":false}

GET /suspended
{"taskId":"10520701-…","agentName":"RefundAgent","stepIndex":1,
 "pendingTool":"refundRequiringApproval",
 "reason":"工具 refundRequiringApproval 需要人工确认后才能执行（requireHumanConfirm=true）",
 "inDoubt":false,"awaitingConfirmation":true,"waitedSeconds":0}

POST /resume/10520701-…   {"note":"财务已核对发票"}
{"ok":true,"taskId":"10520701-…","message":"已恢复执行",
 "humanInput":{"approved":true,"note":"财务已核对发票"}}
      └─ [真实副作用] 调用退款接口 orderId=ORD-20261114-001 amount=128.00（本进程第 1 次扣款）
      终态=COMPLETED，真实扣款次数=1

GET /task/10520701-…
trajectory=[0:LLM_DECISION] → [0:TOOL_CALL queryOrder ✓] → [1:LLM_DECISION] → [1:HUMAN_REVIEW]
          → [1:HUMAN_REVIEW refundRequiringApproval ✓] → [1:TOOL_CALL refundRequiringApproval ✓]
          → [2:COMPLETION]
```

覆盖：① 挂起时钱一分没动 ② 概览能看出挂了几笔 ③ 清单能看出「在等哪个工具、为什么、
等了多久」④ 批准后工具恰好执行 1 次 ⑤ 事后能翻出完整轨迹 ⑥ 批量恢复默认拒绝。

### M10：租约与孤儿判定（心跳，多实例不再抢跑）

M9 的批量恢复有个隐患：`RUNNING` 在库里只有一种样子，**分不清「另一台实例正在跑」和「上一个实例崩了」**。
滚动发布时新实例一启动，就会把老实例正在执行的任务全部接管——决策重复计费、工具被重复请求
（幂等能兜住钱，兜不住成本、延迟和莫名挂起）。

修法是把「谁在跑」记下来：

```
task.owner_id      持有租约的实例（hostname-随机串）
task.heartbeat_at  最后一次心跳；本实例每 TTL/3 续租一次
```

```sql
-- 认领（判断与写入在同一条 UPDATE 里，和幂等占位靠主键冲突是同一个道理）
UPDATE task SET status=?, updated_at=?, owner_id=?, heartbeat_at=?
WHERE task_id=? AND status IN (...)
  AND (owner_id IS NULL OR heartbeat_at IS NULL OR heartbeat_at < ? OR owner_id = ?)
```

行为变化：

| 入口 | 是否看租约 | 为什么 |
| --- | --- | --- |
| `recover(taskId)`（单个，人触发） | ❌ 不看 | 「我看过了，这个任务死了」是人的判断；崩溃重启后的第一件事就是它，等 TTL 是荒谬的 |
| `recoverAllInterrupted()`（批量，安全默认） | ✅ 看 | 只接管心跳过期（`now - lease-ttl`）的任务 |
| `recoverAllInterrupted(true)`（批量，force） | ❌ 不看 | 只在「确定持有者已死」时用，例如单实例应用崩了马上重启 |
| 端点 `/recover-all/apply` | ✅ 看 | HTTP 请求本身不携带「我看过了，它们都死了」这个判断 |

`M2` 的崩溃续跑 demo 现在会把两种语义都演一遍：

```
[phase-B] 安全默认（等租约过期）接管了 0 个任务 —— 崩溃刚发生，心跳还是新鲜的，所以按规矩不能抢
[phase-B] 单实例重启：force 接管本机遗留租约
[phase-B] 已认领并续跑 1 个任务
```

新增 `/actuator/agentruntime/orphans`：列出「心跳已过期、会被批量恢复接管」的任务。
运维不该盲点一个批量按钮，然后事后猜它抢了哪些。

### 关掉最后两个写入窗口：状态 CAS 与事件所有权

有了租约还有一条缝：A 丢了租约、但还跑到一半，它手里的对象还是「RUNNING，第 1 步」，
一次普通的 `save()` 就会把 B 刚认领的 `RECOVERING` 覆盖回 `RUNNING`——双跑照样成立。

所以状态写入改成 CAS：

```sql
UPDATE task SET status=?, current_step=?, context_json=?, updated_at=?
WHERE task_id=? AND owner_id=? AND status=?      -- 我是谁 + 我以为的状态
```

**事件追加同样要过所有权校验**（`appendEventIfOwned`）：

```sql
INSERT INTO task_event (...) SELECT ...
WHERE EXISTS (SELECT 1 FROM task WHERE task_id=? AND owner_id=?)
```

写成一条语句而不是「先查 owner 再 INSERT」，因为后者中间有一个可被抢占的窗口，而这次要关的恰恰是这个窗口。
影响行数为 0 就说明租约已易主——此时连「我执行了这一步」都不该写进账本：那条事件事后无法解释
（既不是新主人的操作，也不是它重放的操作）。副作用本身无法撤回，重复执行由幂等记录兜住。

`saveIfOwned` 返回 false 就抛 `LeaseLostException`，`DecisionLoop` 捕获后**什么都不写**，
直接返回一个 `RECOVERING` 的结果——**不是 FAILED**。标 FAILED 才是破坏：
新持有者会看到「我刚认领的任务怎么失败了」。另外每步开始前还做一次主人校验，
这样连「半截步骤的事件」都不会留给新主人。

改完之后 M2 的崩溃续跑立刻红了，暴露出三个只有真跑才会发现的问题：

| 问题 | 表现 | 修法 |
| --- | --- | --- |
| 内存仓储返回的就是循环手里那个对象 | `saveIfOwned` 读到的永远是**新**状态，CAS 恒假 | 默认实现退化为「只校验 owner」，状态 CAS 由 JDBC 覆盖版提供（单进程本来没有第二个实例，假装做到了才更糟） |
| `Timestamp.from(Instant.MAX)` 溢出 | 「忽略租约」的哨兵值让 SQL 比较恒假，认领永远失败 | 换成 `now + 10 年` |
| `recover()` 只改库、不改内存副本 | 内存里还是 `RUNNING`，第一步 CAS 条件对不上，续跑直接中止 | 认领成功后同步 `task.setStatus(RECOVERING)` |

## 模块

```
agent-runtime-starter/                      # 仓库根 = parent pom
├── DESIGN.md                               # 实现规格（SDD）
├── agent-runtime-core/                     # 纯 JDK，零 Spring / 零 LLM 框架依赖
│   └── src/main/java/com/agentruntime/
│       ├── annotation/   AgentTool（@EnableAgentRuntime 在 starter 里，见偏差表）
│       ├── api/          AgentPlan, Step, AgentEngine, Context, TaskResult, TaskView,
│       │                 Usage(决策花费), StepSpan(步级 span 视图)
│       ├── domain/       TaskStatus, StepType, InDoubtPolicy
│       ├── engine/       DefaultAgentEngine(异步+recover+resume+reconcileUsage),
│       │                 DecisionLoop(断点/挂起重放 + 埋点), ToolExecutor(幂等→重试→缓存→截断),
│       │                 RetryPolicy, ExponentialBackoffRetry, IdempotencyKeys, ResultTruncator,
│       │                 ContextAssembler, ToolRegistry, ToolInvoker, TypeConverter
│       ├── observability/ AgentTracer/Span 与 Metrics 两套 SPI，Noop / Console / Recording /
│       │                 Composite 四种 tracer，SimpleMetrics(Prometheus 文本), CostReport
│       ├── persistence/  Task, TaskEvent, TaskRepository, InMemoryTaskRepository, JdbcTaskRepository,
│       │                 ConnectionSource(单连接/连接池), IdempotencyRecord/Store,
│       │                 OutboxEntry/Store/Event, Schema
│       ├── eval/         EvalCase, EvalRunner, EvalReport, EvalTrace, Expectations,
│       │                 Assertion, Judge(JudgeMode), EvalFixture
│       ├── outbox/       OutboxDispatcher（轮询投递，等价于 CDC 消费）
│       ├── exception/    注册期与运行期的可诊断异常、TransientFailureException、InDoubt
│       └── util/         Json(写), JsonParser(读)——零依赖 JSON 往返
├── agent-runtime-spring-boot-starter/      # M8 自动装配 + Micrometer/OTel 适配器（含建表脚本）
└── agent-runtime-samples/                 # RefundAgentDemo(M1) / RecoverDemo(M2) /
                                            #   M3Demo, InDoubtDemo(M3) / M4Demo(M4) /
                                            #   M5Demo, EvalGate + RefundEvalCases(M5)
                                            #   M8Demo(M8) / M9Demo(M9) / eval(M5)
                                            #   db/DbSupport（PG 默认 / H2 兜底）
                                            #   默认连 PostgreSQL（H2 仅离线兜底）
```

## 与 DESIGN.md 的偏差（显式取舍，均已验证）

| 偏差 | 原因 | 何时收敛 |
| --- | --- | --- |
| `maven.compiler.release=17`（DESIGN 目标 Java 21） | 本机工具链只有 JDK 17；record / switch arrow 均已可用 | 装 JDK 21 后改一个属性即可 |
| M2 用纯 JDBC 而非 Spring Data JPA | 保持 `core` 零第三方依赖；事件溯源引擎需要的是 SQL 语义而非 ORM 映射 | 已收敛：M8 明确不做 JPA 映射（见下表），方言用真 PostgreSQL 验证而非兼容模式 |
| `core` 连 Spring 都不依赖 | 保持「只加一个依赖」的干净基线；`core` 的依赖树里没有任何 Spring 坐标 | 已收敛：M8 的自动装配放在 starter |
| `TaskEvent.payload` 用 `Map<String,Object>` 承载，落库时序列化为 JSON 文本 | 续跑后从 JSONB 还原的客观事实就是 Map；`payloadJson()` 给出等价视图 | 不收敛——这是正确行为 |
| 补全了 `Context` / `TaskResult` / `TaskView` / `AgentPlan.name()` / `registerPlan()` | DESIGN §5.3 引用了这几个类型但未定义；`recover(taskId)` 也未说明如何找回 plan 实例 | 建议回写进 DESIGN.md |
| `Step.tool(String, Map<String,Object>)` | 原签名 `Map` 无泛型 | 同上 |
| 幂等键 = `工具名 + ":" + 表达式求值结果` | DESIGN §4.4 只说「求值结果唯一」，但 `queryOrder` 与 `refund` 共用 `#orderId`；不按工具隔离会把订单快照当成退款回执返回 | 建议回写进 DESIGN（这是必须的修正，不是取舍） |
| 幂等键表达式只支持 `#arg` / `#arg.field`，不用 SpEL | SpEL 会把 `core` 绑到 Spring，违背 §2.1 零依赖硬约束；幂等键也不该允许运算 | M8 可提供 SpEL 实现作为可选项 |
| 挂起改为「写事件后正常返回」，不抛 `HumanConfirmRequiredException` | 抛异常 = rollback，刚写的 `SUSPENDED` 会跟着事务消失（DESIGN §6 M3 的写法有这个洞）。该异常类型仍保留并被识别 | 建议 DESIGN 改成「挂起是结果，不是异常」 |
| 幂等记录 IN_DOUBT 默认挂起而非重试 | 「读超时」与「请求没到」在异常层无法区分，重试就是多退一笔。默认 `SUSPEND_FOR_HUMAN`，可配 `RETRY`/`FAIL` | 不收敛——这是资损场景的正确默认 |
| `TransientFailureException` 是「有副作用工具可重试」的唯一声明 | 无脑重试有副作用的工具是资损的头号来源；只有业务能声明「这次肯定没发生」 | 不收敛 |
| M4 自研 `SimpleMetrics` + Prometheus 文本，而非 Micrometer | `core` 零依赖是硬约束；要验的是「指标能不能被真正抓走」，而这只依赖文本格式。demo 用 JDK `HttpServer` 挂 `/actuator/prometheus`，用 `HttpClient` 真抓 | M8 换 Micrometer Registry，指标名/标签语义保持不变 |
| `TaskEvent.durationMs` 落库（DESIGN §7 的 event 表没有这一列） | 事后排查「上周那笔为什么慢」时，OTel 收集器早过了保留期，事件还在库里。`getTask` 因此能离线给出步级时间线 | 建议回写进 DESIGN 的 DDL |
| 收尾决策的用量记在 `COMPLETION` 事件上（不额外插一条 `LLM_DECISION`） | 收尾也是一次模型调用，必须计费；拆成两条事件会让「本步已有 COMPLETION 就不重跑」的幂等判断变复杂 | 不收敛 |
| `Task.total_cost/total_tokens` 不再由 `save()` 回写，只由 `addUsage/setUsage` 改 | 读改写会丢更新（见下节），计数列必须只有一个写入口 | 不收敛 |
| **judge（LLM 语义评判）默认不阻断构建**（`JudgeMode.ADVISORY`） | DESIGN §6 M5 把「确定性断言」与「LLM-judge 表达式」并列。并列在纸面上成立，进 CI 就崩：judge 输出受温度/模型版本/限流影响，同一次 commit 重跑可能 19/20 也可能是 20/20；一旦进分母，构建会**随机变红**，三次之后团队就会加 `-DskipEval` 把门禁关掉 | 不收敛——只有可重放的东西能进红绿判定 |
| `EvalTrace.decisions()` 把 `COMPLETION` 也计为一次决策 | 收尾同样是一次模型调用、同样计费（M4 修过的真缺陷）；只数 `LLM_DECISION` 会系统性低估「agent 想了几次」 | 不收敛 |
| M5 用 `exec-maven-plugin` 而不是 JUnit 跑门禁 | 门禁本身是个 `main`，CI 与本地同一条命令，不必两头维护；（更正：**test 作用域的 junit 不会传递给使用方**，所以 starter 的装配测试正常用了 `spring-boot-starter-test`） | 不收敛 |
| **`@EnableAgentRuntime` 从 core 移到 starter** | 一个真正有用的 `@EnableXxx` 必须 `@Import` `@Configuration`，那是 Spring 的东西；留在 core 只能是个「贴了也不生效」的假开关，比没有更糟。现在它用 `@ImportAutoConfiguration` 实现，且**不贴也会自动装配** | 不收敛 |
| **不做 JPA 实体映射**（DESIGN §8 提到） | 这个引擎依赖 `JSONB`、`UPDATE ... SET x = x + ?` 的存储侧原子累加、幂等 DONE 与 outbox 同事务——用 ORM 表达要么失真要么写回原生 SQL；为建 4 张表让所有使用方背 Hibernate 也与「只加一个依赖」冲突 | 不收敛；`TaskRepository` 只有 5 个方法，要用 JPA 自行实现即可 |
| `ConnectionSource` 抽象（单连接 / 连接池） | M2 起的 JDBC 实现直接持有一个 `Connection`，在生产里等于「从池里借一个永不归还」；自动装配必须支持 `DataSource`。`javax.sql.DataSource` 是 JDK 类型，所以 core 仍是零第三方依赖 | 不收敛 |
| **新增 M9 运维控制面**（DESIGN 只到 M5 + M8） | 挂起任务没有运维出口就等于静默丢失——「卡住且没人知道」对退款业务而言与失败等价。这不是加功能，是把 M3 的挂起补完整 | 建议回写进 DESIGN |
| `task` 表加 `owner_id` + `heartbeat_at`（DESIGN §7 没有这两列） | 没有它们，「RUNNING」分不清「在跑」与「崩了」，批量恢复在滚动发布时必然抢跑。这是修正确认过的缺陷，不是新增功能 | 建议回写进 DESIGN 的 DDL |
| `save()` **不写** `owner_id`/`heartbeat_at` | 与 `total_cost` 同理：租约列必须有唯一写入口（claim/renew）。若 save 也写，一个「丢了租约但还没察觉」的实例下一次保存就会把别人的租约覆盖回去，双跑从「可能」变成「一定」 | 不收敛 |
| 控制面用 **actuator 端点 + 路径编码动作**，不自研控制台 | 企业已有的 `/actuator` 鉴权、审计、网关、告警全套能直接复用；自研一个 Web 控制台是几周的活，而且会成为新的攻击面。动作放路径（`/resume/{id}/force`）而非 body 参数，是为了让危险操作在访问日志里可 grep | 不收敛 |

## M2 新增的关键机制

- **事件溯源续跑**：`recover(taskId)` 读 `Task` + 重放 `TaskEvent` 到 `currentStep`；
  「决策已落库、工具未执行」的断点上，直接从事件还原决策执行，**不重调 `decide()`**
  （DESIGN §11：决策是最贵的调用，不能重算）。
- **原子认领**：`TaskRepository.tryClaim` 用 `UPDATE ... WHERE status IN (...)` 防止多实例对同一任务双跑；
  比任务表加 `version` 列更直接（状态即乐观锁）。
- **at-least-once 边界**：崩溃若发生在「副作用已发生、事件未落库」的窗口，重放会再次执行工具——
  这是 DESIGN §11 的设计决策，由 M3 幂等记录兑底，不是 bug。
- **异步执行**：`submit` 先落库 `PENDING` 再交给默认线程池（daemon 线程，不悬挂 JVM）；
  `builder.executor(Runnable::run)` 可退化为 M1 同步模式。

## M3 新增的关键机制

- **幂等占位靠数据库主键冲突**（不是先查后写）：`INSERT` 撞主键才说明别人先到，
  两个实例同时退同一笔只会有一个拿到 `CLAIMED`。
- **DONE 与 outbox 同事务**：`JdbcIdempotencyStore.complete` 在 `autoCommit=false` 下先更新幂等记录、
  再插 outbox，一起 commit——不存在「标记说做过了、事件却没发出去」。
- **`INIT` 不自动修复**：这是「副作用是否发生未知」的唯一状态，交给 `InDoubtPolicy`（默认挂起）。
  框架不替业务赌钱。
- **统一重放路径**：崩溃断点、等人工确认、等人工裁定在途结果，在事件层是同一形状
  （`LLM_DECISION(tool)` 后面没有 `TOOL_CALL`），所以只有一个 `pendingStep`，不留两套逻辑。
- **重试的安全边界**：有副作用的工具（声明了 `idempotencyKey`）默认不自动重试，
  只有业务显式抛 `TransientFailureException` 才重试；纯查询工具按 `RetryPolicy` 正常退避。
- **截断不产生非法 JSON**：超长结果包成 `{_truncated:true,_originalChars:N,body:"..."}`，
  而不是砍字符串——砍完 `JSONB` 就读不回来了。

## 已知边界（M2 / M3 补充）

- **并发重复提交的第二方会挂起**：第一方还在执行时，第二方看到 `INIT`，无法区分
  「正在执行」与「崩溃留下的 INIT」，因此按在途未知处理（挂起等人工）。
  若要优化，可加「有界等待窗口」（如 500ms 内轮询 `INIT→DONE`），或给 `INIT` 记录加心跳时间戳
  用于区分「新鲜的在途」与「陈旧的孤儿」。当前选择不猜。
- **outbox 投递是 at-least-once**：消费方必须按 `eventType + aggregateId` 幂等，
  这是 outbox 模式的固有契约（与是否用 Kafka 无关）。
- **`INIT` 记录没有超时清理**：长期积压会让「挂起任务」变多，生产应加 TTL + 告警。
- **`agent.tasks.suspended` 是每次抓取都扫全表**：示例规模无感，任务上百万时要换成计数列或
  `SELECT count(*) WHERE status='SUSPENDED'` 加索引。
- **`results` Map 不会清理**：完成的 future 一直留着，长跑进程会慢慢涨。生产应按 TTL 清或换成
  只保留「进行中」的集合 + 状态查库。
- **成本只到「步」，没到「一次调用」**：一步里的重试会合并成一条事件，因此重试多花的 token
  记不到单独的重试上（工具重试本来也不花 token，模型重试才会）。

## M4 新增的关键机制

- **用量由 plan 上报**：`Step.withUsage(Usage)` 是唯一入口。引擎不认识模型供应商，也不猜价目；
  它只负责把花费记到「哪一步」头上。
- **累加在存储侧完成**：`UPDATE task SET total_tokens = total_tokens + ?, total_cost = total_cost + ?`。
  demo 用 8 个**独立连接**并发打 400 次，断言总量精确——换成读改写，这个断言必红。
  同理 `save()` 不再回写这两列：计数列只有一个写入口。
- **事件是账本，汇总是物化视图**：`appendEvent` 与 `addUsage` 是两次写，崩在中间会让汇总少算。
  恢复时 `reconcileUsage` 按事件重算并覆盖，`setUsage` 只服务于此，`save()` 也不能碰这两列。
- **重放不计费**：`applyUsage` 只在真实 `decide()` 之后调用。挂起→resume 会重放第 1 步，
  但不会重新决策、也不会再收费（demo 断言「计费步数 = 3」且「totalTokens == 事件之和」）。
- **收尾决策也要钱**：`COMPLETION` 事件带 `tokens/cost`。这是实现时**测出来的真缺陷**——
  第一版把它传成 `Usage.ZERO`，结果最后一步（上下文最长、输出最长，通常最贵）永远不入账。
- **span 与指标是两套 SPI**：`AgentTracer` / `Metrics` 都提供 noop 默认实现，不配就零开销；
  要 OTel 就写一个适配器（`Span` 包一层 `io.opentelemetry.api.trace.Span`），引擎一行不用改。
- **指标命名细节**：内部用点分名（`agent.cost`），导出时补 `_total`；**名字里别自带 total**，
  否则线上会出现 `agent_cost_total_total`。金额用整数微元累加，不用 `double`。

## M5 新增的关键机制

- **门禁只认可重放的事实**：断言全部只读引擎落库的东西（终态、`TaskEvent` 账本、累计成本），
  不含时间、不含随机、不含模型输出。所以同一份代码跑一百次结论一样——这是它能进 CI 的前提。
- **judge 是情报，不是门禁**：`Judge` 只是一个函数式接口（`core` 仍零 LLM 依赖，业务用
  `HttpClient` / Spring AI / 内部评分服务实现都行）。它的结论写进报告、可做趋势，但默认
  `ADVISORY` 不阻断。要阻断必须显式 `BLOCKING` —— 选择权在人，不在框架默认值。
- **SKIP ≠ PASS ≠ FAIL**：声明了 judge 但 `JudgeMode.OFF` 时记 SKIP 并且**不计入分母**。
  另外 `assertGate(threshold, minCases)` 会拒绝「把用例全跳过拿满分」这种自欺；
  用例被跳过到低于 `minCases` 时照样失败。
- **失败分类是报告骨架**：`STATUS_MISMATCH / TOOL_SEQUENCE / SIDE_EFFECT / RESULT_CONTENT /
  BUDGET_EXCEEDED / STEP_LIMIT / JUDGE_REJECTED / JUDGE_ERROR / ENGINE_ERROR`。
  目的是让 CI 红了之后三十秒内能决定改哪：`TOOL_SEQUENCE` 指逻辑，`BUDGET_EXCEEDED` 指成本。
- **断言全收集不短路**：一条 case 常常同时坏在多维（工具序列 + 结果文案 + 成本），
  报告一次说完，省掉「修一条红再跑一遍」的往返。
- **报告带轨迹**：失败明细里有 `[0:LLM_DECISION] → [0:TOOL_CALL queryOrder ✓] → …`。
  只有结论没有证据的报告，会让人重新打开 IDE 从零开始排查。
- **每条 case 一套夹具**：新建引擎、新建内存仓储、新建 mock 服务，跑完立刻关。
  用例之间零共享是评测可信的前提；需要共享状态的场景（M3 那种）应在单条 case 内部显式做。
- **成本也是门禁**：`EvalReport` 汇总每批花费，`EvalGate` 另有一条成本上限。
  通过率绿而账单翻倍，同样是回退。

## 已知边界（M5 补充）

- **串行执行**：评测逐条跑，为的是可复现。20 条用例在 200ms 内跑完，暂不需要并行；
  真要并行，得先让夹具各自持有独立资源（例如独立 schema），不能靠调大线程数掩盖不确定性。
- **judge 的通过率不可比**：换模型/换温度后 `judge` 结论会漂，所以报告里 judge 只给单条旁注，
  不参与任何比率计算。要跟踪 judge 质量，应另建一条离线评测，而不是塞进 CI 门禁。
- **`minCases` 是硬编码在调用方的**：门禁代码里写的是 `cases.size()`，
  如果有人删用例，`minCases` 会跟着变小。生产建议把用例数写进配置并与基线对比。
- **阈值 1.0 的代价**：任何一条确定性断言波动都会红。这是刻意的——
  本项目的断言不依赖时间/网络，红了一定是代码变了。若你的 agent 依赖外部服务，
  应把外部依赖换成夹具，而不是把阈值调低。
- **评测不覆盖多实例并发**：`EvalFixture` 是单进程单引擎。多实例双跑、真实 JDBC 主键冲突
  这类场景仍由 M3 的 demo 覆盖，不在评测集里。

## M8 新增的关键机制

- **条件装配的顺序就是语义**：Spring 先处理嵌套配置类、再处理本类的 `@Bean` 方法。所以
  「JDBC 版仓储」写在嵌套类里、「内存版兜底」写在外层，「Micrometer 适配器」也必须是嵌套类——
  反过来写，兜底实现会永远赢，而且不报错。这条不是风格问题，是顺序问题。
- **一个对象两种角色 → 一个 bean 定义**：幂等 store 同时实现 `IdempotencyStore` 与 `OutboxStore`。
  第一版又单独声明了一个指向同一对象的 `OutboxStore` bean，结果按类型注入 `IdempotencyStore` 时
  撞上两个 bean 定义（`NoUniqueBeanDefinitionException`）——装配测试抓到的真实问题。
- **可选依赖只在 classpath 上才存在**：Micrometer / OTel 都是 `<optional>true</optional>` +
  `@ConditionalOnClass(name=...)`。不引入时这些配置类连加载都不能发生，
  否则启动就是 `NoClassDefFoundError`——企业最怕的正是「为了用一个库，顺手换掉了我的 tracing 版本」。
- **工具扫描用 `SmartInitializingSingleton`，不用 `BeanPostProcessor`**：BPP 时机太早，
  注册表可能还没创建完，只能靠「延迟查找 + 待注册队列 + 绑定后重放」绕，而这类绕法的 bug
  表现都是「有时候工具少了一个」。扫完之后注册表仍然可变，所以先创建引擎也不影响。
- **代理类不继承方法注解**：`@Transactional` 的 CGLIB 代理覆写方法但不继承注解，
  `proxy.getClass().getMethods()` 上一个 `@AgentTool` 都看不到。所以
  `ToolRegistry.register(bean, userClass)` 分开拿「元数据」与「执行目标」，
  注册的是代理的注解 + 代理的执行（事务仍然生效）。
- **`Method.invoke` 对「public 方法 + package-private 类」会抛 `IllegalAccessException`**：
  Spring 里 package-private 的 `@Service` 完全合法，自动装配会把它们都注册进来。
  这个问题是测试抓出来的——表现为「工具注册成功，一调就失败」。
- **建表按方言两份脚本**（默认 PG，H2 仅兜底）：`JSONB` 在 H2 里没有、`BIGSERIAL` 要写 `IDENTITY`，
  硬凑「通用 SQL」的结果是两边都拿不到最优类型（PG 的 `JSONB` 索引与 `->>` 全废）。
  生产推荐关掉 `schema-init` 交给 Flyway，脚本可直接搬。

## 已知边界（M8 补充）

- **`AgentToolScanner` 只扫 singleton**：prototype 作用域的工具 bean 直接启动失败（刻意的）——
  注册表只持有第一次拿到的实例，「以为每次都是新对象」的假设会静默失效。
- **只支持 CGLIB 代理**：JDK 接口代理不是原始类的实例，`Method.invoke` 会失败。
  Spring Boot 2.x 起默认 CGLIB，所以默认可用；自定义 `proxyTargetClass=false` 时请把注解放在实现类上。
- **`agentRuntimeExecutor` 是独立线程池**，不与应用的 `TaskExecutor` 共用：agent 每步都可能阻塞几秒
  （等模型、等网关），共用会互相拖死。要共用就定义同名 bean 覆盖。
- **不做 `recoverAllInterrupted()` 的自动调度**：多实例启动时自动「抢所有孤儿任务」在滚动发布期间
  很危险（新实例会去抢还在跑的任务）。DESIGN 要求的能力已经具备（原子认领保证不会双跑），
  但它应该由运维决定何时触发，建议做成独立的运维端点或定时任务。
- **Micrometer 的 `describe()` 是空实现**：`Meter.Builder#description` 只能建 meter 时给，事后补写不了。
  可读性靠指标名本身（`agent.cost` 而不是 `agent.cost.total`）。
- **`schema-init` 不做版本管理**：只有 `IF NOT EXISTS`，不会帮你加列。表结构演进请上 Flyway。
- **starter 的测试用了 JUnit（test 作用域）**：这与 M5 用 `exec` 跑门禁不矛盾——
  test 作用域的依赖不会传递给使用方，而门禁需要一个能直接被 CI 调用的入口。

## M9 新增的关键机制

- **幂等巡检单独成 SPI**（`IdempotencyInspector`，不往 `IdempotencyStore` 里加方法）：
  `INIT` 是唯一「钱可能已经动了但没人知道」的状态，巡检是只读旁路能力。
  用 `ObjectProvider` 可选注入——没有实现时端点返回 `staleInDoubt: -1`，
  而不是 0。**0 会被读成「一切正常」，这是运维界面最危险的谎言。**
- **不自动清理 `INIT`**：自动过期等于「假装没发生过」，下一次重试就是多退一笔。
  端点只提供观测 + 一份「下一步该做什么」的提示（对账 → force 或交回结果），由人决定。
- **`TaskView` 增加挂起态三元组**：`pendingTool()` / `suspendedReason()` / `inDoubtKey()`。
  运维看到的应该是「ORD-… 在等人工批准 refund」，而不是一串 taskId。
  判定方式与引擎的断点重放同源（本步有 `LLM_DECISION(tool)` 无 `TOOL_CALL`）。
- **按状态查任务走 SQL**：`TaskRepository.findByStatus` 有默认实现（全量过滤）供自定义实现兼容，
  JDBC 覆盖成 `WHERE status=? LIMIT ?`。运维端点在百万级任务表上必须走覆盖版。
- **危险操作在 URL 里**：`/force`（强制重试在途未知）、`/apply`（真批量恢复）是可 grep 的，
  访问日志天然留下审计线索；body 只剩一个必填的 `note`，强制写清审批依据。
- **两条护栏**：批量恢复默认关闭（`allow-bulk-recover=false`），且即使开启也默认 dryRun。

### Actuator 的两个坑（都是实测撞出来的）

1. **读操作的非 selector 参数是必填查询参数**：`@ReadOperation void view(@Selector String v, String taskId)`
   会让 `GET /overview` 直接 400「Missing parameters: taskId」。改用
   `@Selector(match = Match.ALL_REMAINING) String[] path`，把 id 放进路径段。
2. **写操作的参数是全量必填**：声明 4 个参数，调用方就得把 4 个都塞进 body，
   否则 400「Missing parameters: retrySideEffect,dryRun」。这也是「动作进路径」的另一个理由。

## 已知边界（M9 补充）

- **批量恢复的抢跑风险已由 M10 收敛**（`owner_id` + `heartbeat_at`）：安全默认只接管心跳过期的任务。
  剩下的风险是「心跳发不出去」——数据库连接池打满、网络分区时，活着的实例也会被误判成孤儿；
  代价是重复执行一次，由幂等记录兜底。要精确判定需要实例注册表，见「已知边界（M10 补充）」。
- **端点无内置鉴权**：只依赖 Spring Security / 网关。`/resume/{id}/force` 与 `/recover-all/apply`
  属于「能改钱」的操作，必须单独授权，别让整个 `/actuator/**` 对运维开放就算完事。
  （注意端点上没有 `/force` 这个批量动作——批量强制只能在代码里调，见 M10。）
- **`in-doubt` 列表没有「已处理」标记**：人确认完之后记录状态变成了 DONE/FAILED，
  自动从列表消失——所以它只适合当「待办清单」，不适合当审计流水（审计看 `idempotency_record` + outbox）。
- **`waitedSeconds` 基于 `updatedAt`**：任务被 `resume` 拒绝过一次后又重新挂起时，
  这个数字会回到 0。要精确统计「业务等了多久」需要额外记业务时间戳。
- **控制面不返回事件明细**：`/task/{id}` 给轨迹摘要与 span，全量事件仍走 `engine.getTask()`——
  避免把上千条事件塞进一次 HTTP 响应。

## M10 新增的关键机制

- **心跳是唯一能证明「它还活着」的信号**：本实例每 `TTL/3` 续租一次（允许丢两次），
  只为自己 `leased` 集合里的任务续。任务进终态就注销，不为死任务留心跳。
- **判过期与抢任务在同一条 SQL 里**：先查再写会让两台实例同时判定「它过期了」，
  然后两条 UPDATE 都成功。跟幂等占位靠主键冲突是同一个道理。
- **两个时间戳各有语义**：`heartbeat_at` 是「机器活着」，`updated_at` 是「业务有进展」。
  续租只动前者——否则一个卡了两小时的任务在控制台上会显示「刚更新」。
- **`save()` 不碰租约列**：单写入口。测试 `saveDoesNotClobberLease` 就是钉这条的：
  A 拿旧副本保存，B 的租约不能被覆盖。
- **丢失租约不立刻自杀**：工具调用没有安全取消点，硬中断可能让副作用停在中间。
  续不上只记 `agent.lease.lost` 指标并从 `leased` 移除；任务会在下一步由新持有者接管，
  重复的那一次由幂等记录兜住。宁可多一次缓存命中，不要一次半截的退款。
- **单个 vs 批量分开**：单个恢复是人看过的，不看租约；批量恢复是机器猜的，必须看租约。
- **状态写入是 CAS，不是覆盖**：`saveIfOwned(task, ownerId, expectedStatus)` 用一条
  `UPDATE ... WHERE owner_id=? AND status=?` 同时完成「校验 + 写入」，中间没有可被抢的窗口。
- **丢租约不是失败**：`LeaseLostException` 被捕获后返回 `RECOVERING` 结果，一个字都不写。
  把任务标成 FAILED 会毁掉新持有者刚接手的任务。
- **每步开始前校验主人**：代价是每步一次 SELECT，换来的是账本里不会出现「另一个主人写的半截步骤」。
- **事件写入也带所有权**：`INSERT ... WHERE EXISTS(任务属于我)`，一条语句完成校验 + 写入。
  账本只记录「归属明确的事实」，否则事后对账无法解释。

## 已知边界（M10 补充）

- **租约 TTL 必须大于最慢的一步**（默认 5 分钟，心跳间隔 TTL/3）。一步跑 10 分钟的模型调用
  会被判成死亡——生产上要么调大 TTL，要么给工具加大于 TTL 的超时保护。
- **事件追加与状态改写仍是两条语句**：两者各自都带所有权校验（M10.2），所以旧主人<b>一条都写不进去</b>；
  但「事件已写、状态未改」这个崩溃窗口还在（进程在这两条语句之间死掉）。它不影响金额安全：
  恢复时 `reconcileUsage` 按事件重算，`pendingStep` 也能从这个形状继续。
  合成单条事务是可选优化，收益是一致性更直观，不是新发现的缺陷。
- **租约 TTL 与工具超时必须配对设置**：一步跑得比 TTL 还久（大模型长输出、慢网关），
  心跳线程还在续租所以本身没问题；但**如果心跳线程也卡住**（例如数据库连接池打满），
  任务就会在还活着的时候被判死。生产上给 TTL 留足余量。
- **`heartbeat_at` 为空的老数据一律视为无主**：升级瞬间如果恰有任务在跑，
  它可能被批量恢复接管一次。滚动升级应该在低峰做，或者升级后先单实例跑一轮再扩容。
- **一台机器上多个实例共享 hostname 时会混淆**：`instanceId` 默认为
  `hostname-随机串`，同一主机多进程时随机串能区分；但运维要通过 hostname 定位机器
  就只能看日志。需要严格归属时请显式配置 `agent.runtime.instance-id`。
- **实例注册表默认是内存版**（M15 已补共享 JDBC 版）：判断「谁活着」现在是
  「租约心跳 + 注册表报到」两个独立信号，网络分区时不会立刻误抢；默认内存版适合单实例，
  多副本部署设 `agent.runtime.ops.registry-jdbc=true` 即切到 `JdbcInstanceRegistry`
  （`agent_instance` 表由 schema-init/Flyway 自动建），也可自己注入 Redis / 注册中心实现。
  它缩小了误判窗口，但没有用共识算法根治分区——分布式系统里这不可能只靠本地状态做到。

## 下一步该改哪里

DESIGN 的里程碑已全部落地，M9–M15 是评审与实现过程中补的。M13 把「实例注册表 /
第二评测域 / 告警埋点」从待办变成现实，M14/M15 把真方言门禁、共享注册表与告警接收端补齐。
剩下的按价值排序：

1. ~~共享实例注册表实现~~：**已完成**（M15）。`JdbcInstanceRegistry` +
   `agent.runtime.ops.registry-jdbc=true`，多副本不再退化回单信号。
2. **`agent-runtime-spring-ai` / `agent-runtime-langchain4j` 桥接模块**（DESIGN §9 规划过）：
   把 `ChatModel` / `ChatLanguageModel` 包成 `AgentPlan` 的便利基类，
   让「业务方只有一个依赖」覆盖到真正的 LLM 调用。**注意**：DESIGN 明确不与 LangGraph4j
   竞争编排，这两个模块只做「模型调用 → Step」的翻译。
3. **Resilience4j 适配器**：`RetryPolicy` 接口已留好，写一个实现即可，
   让企业的熔断/限流策略与重试统一到一套配置。
4. ~~告警接收端~~：**已完成**（M15）。`WebhookOpsAlert` +
   `agent.runtime.ops.alert-webhook-url`，异步投递、失败不阻塞业务、同事件 30 秒去重；默认仍 noop。
5. ~~PostgreSQL 真方言 CI~~：**已完成**。`pg-dialect` job 用 Docker service container 起
   PostgreSQL 16，跑全部 M1–M5 / M8 / M9 演示（含两个 `halt(17)` 崩溃场景），
   不再是「有 PG 服务的团队可以自己加」的待办。
