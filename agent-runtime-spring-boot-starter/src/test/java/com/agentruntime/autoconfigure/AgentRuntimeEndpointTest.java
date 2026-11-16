package com.agentruntime.autoconfigure;

import com.agentruntime.annotation.AgentTool;
import com.agentruntime.api.AgentEngine;
import com.agentruntime.api.AgentPlan;
import com.agentruntime.api.Context;
import com.agentruntime.api.Step;
import com.agentruntime.api.TaskResult;
import com.agentruntime.domain.TaskStatus;
import com.agentruntime.ops.OpsAuditLog;
import com.agentruntime.ops.OpsPrincipal;
import com.agentruntime.persistence.IdempotencyInspector;
import com.agentruntime.persistence.IdempotencyRecord;
import com.agentruntime.persistence.IdempotencyStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运维控制面断言（M9）。
 *
 * <h2>为什么这些用例值得写</h2>
 * 端点这类「给人用的东西」最容易出的问题是<b>返回了一个看起来正常但会误导人的结果</b>：
 * 没有巡检能力时返回 0（读成「一切正常」）、把 RUNNING 任务列成挂起、批量恢复按钮没护栏。
 * 这些都不会报错，只会让运维在事故里做出错误判断。所以每一条都断言「说了什么」而不只是「没抛异常」。
 */
class AgentRuntimeEndpointTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentRuntimeAutoConfiguration.class))
            .withBean(ConfirmTools.class, ConfirmTools::new);

    @Test
    @DisplayName("端点被装配出来，且真的带 @Endpoint(id=agentruntime)")
    void endpointIsWired() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(AgentRuntimeEndpoint.class);
            Endpoint annotation = AgentRuntimeEndpoint.class.getAnnotation(Endpoint.class);
            assertThat(annotation).isNotNull();
            assertThat(annotation.id()).isEqualTo("agentruntime");
        });
    }

    @Test
    @DisplayName("在途未知计数在「没有巡检能力」时返回 -1，而不是 0")
    void overviewDistinguishesUnsupportedFromZero() {
        runner.run(context -> {
            // 默认的内存 store 实现了 IdempotencyInspector，所以这里是 0（真的没有超期记录）
            Map<String, Object> supported = context.getBean(AgentRuntimeEndpoint.class)
                    .view(new String[]{"overview"});
            assertThat(supported.get("staleInDoubt")).isEqualTo(0L);

            // 换成不支持巡检的 store，必须返回 -1：0 会被读成「一切正常」
            ApplicationContextRunner custom = new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(AgentRuntimeAutoConfiguration.class))
                    .withBean(IdempotencyStore.class, BareIdempotencyStore::new);
            custom.run(ctx -> {
                Map<String, Object> result = ctx.getBean(AgentRuntimeEndpoint.class)
                        .view(new String[]{"overview"});
                assertThat(result.get("staleInDoubt")).isEqualTo(-1L);
            });
        });
    }

    @Test
    @DisplayName("挂起清单：说清在等哪个工具、为什么等、等了多久")
    void suspendedListExplainsWhatItIsWaitingFor() {
        runner.run(context -> {
            AgentEngine engine = context.getBean(AgentEngine.class);
            String taskId = engine.submit(new DangerPlan(), "u1", "approve me");
            TaskResult result = engine.await(taskId, Duration.ofSeconds(10));
            assertThat(result.status()).isEqualTo(TaskStatus.SUSPENDED);

            Map<String, Object> suspended = context.getBean(AgentRuntimeEndpoint.class)
                    .view(new String[]{"suspended"});
            assertThat(suspended.get("count")).isEqualTo(1L);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) suspended.get("items");
            assertThat(items).hasSize(1);
            Map<String, Object> item = items.get(0);
            assertThat(item.get("taskId")).isEqualTo(taskId);
            assertThat(item.get("pendingTool")).isEqualTo("dangerous");
            assertThat(item.get("awaitingConfirmation")).isEqualTo(true);
            assertThat(item.get("inDoubt")).isEqualTo(false);
            assertThat(String.valueOf(item.get("reason"))).contains("需要人工确认");
            assertThat((Long) item.get("waitedSeconds")).isGreaterThanOrEqualTo(0L);
        });
    }

    @Test
    @DisplayName("resume 走端点完成挂起任务；批准后工具才真的执行")
    void resumeThroughEndpoint() {
        runner.run(context -> {
            AgentEngine engine = context.getBean(AgentEngine.class);
            AgentRuntimeEndpoint endpoint = context.getBean(AgentRuntimeEndpoint.class);
            ConfirmTools tools = context.getBean(ConfirmTools.class);

            String taskId = engine.submit(new DangerPlan(), "u1", "approve me");
            assertThat(engine.await(taskId, Duration.ofSeconds(10)).status()).isEqualTo(TaskStatus.SUSPENDED);
            assertThat(tools.calls).isZero();          // 挂起时工具绝不能执行

            Map<String, Object> resumed = endpoint.act(new String[]{"resume", taskId}, "财务已核");
            assertThat(resumed.get("ok")).isEqualTo(true);

            TaskResult done = engine.await(taskId, Duration.ofSeconds(10));
            assertThat(done.status()).isEqualTo(TaskStatus.COMPLETED);
            assertThat(tools.calls).isEqualTo(1);      // 批准后恰好执行一次
        });
    }

    @Test
    @DisplayName("运维操作被拒绝时返回原因（ok=false），不是抛 500")
    void invalidOperationsReturnReason() {
        runner.run(context -> {
            AgentRuntimeEndpoint endpoint = context.getBean(AgentRuntimeEndpoint.class);

            assertThat(endpoint.act(new String[]{"resume", "no-such-task"}, "").get("ok"))
                    .isEqualTo(false);
            assertThat(endpoint.act(new String[]{"recover", "no-such-task"}, "").get("ok"))
                    .isEqualTo(false);
            assertThat(endpoint.act(new String[]{"nonsense"}, "").get("ok"))
                    .isEqualTo(false);
            assertThat(endpoint.view(new String[]{"nonsense"}).get("ok")).isEqualTo(false);
        });
    }

    @Test
    @DisplayName("批量恢复默认被禁止，且默认 dryRun")
    void bulkRecoverIsGuarded() {
        runner.run(context -> {
            Map<String, Object> refused = context.getBean(AgentRuntimeEndpoint.class)
                    .act(new String[]{"recover-all"}, "");
            assertThat(refused.get("ok")).isEqualTo(false);
            assertThat(String.valueOf(refused.get("error"))).contains("allow-bulk-recover");
        });
        // 开了权限之后，仍然默认 dryRun：只列候选，不动手
        runner.withPropertyValues("agent.runtime.ops.allow-bulk-recover=true").run(context -> {
            Map<String, Object> dry = context.getBean(AgentRuntimeEndpoint.class)
                    .act(new String[]{"recover-all", "dry"}, "");
            assertThat(dry.get("ok")).isEqualTo(true);
            assertThat(dry.get("dryRun")).isEqualTo(true);
            assertThat(dry.get("candidates")).isInstanceOf(List.class);
        });
    }

    @Test
    @DisplayName("超期未决巡检：INIT 记录能被数出来（这是唯一「钱可能动了」的状态）")
    void staleInDoubtIsInspectable() {
        runner.run(context -> {
            IdempotencyInspector inspector = context.getBean(IdempotencyInspector.class);
            IdempotencyStore store = context.getBean(IdempotencyStore.class);

            store.begin("refund:ORD-1", "task-1", 1);           // 占位后崩溃：停在 INIT
            store.begin("refund:ORD-2", "task-2", 1);
            store.complete("refund:ORD-2", "{\"receipt\":\"R\"}", null);   // 这条已经完成

            List<IdempotencyRecord> stale = inspector.findStale(Duration.ZERO, 10);
            assertThat(stale).extracting(IdempotencyRecord::idempotencyKey)
                    .containsExactly("refund:ORD-1");
            assertThat(inspector.countStale(Duration.ZERO)).isEqualTo(1L);

            Map<String, Object> inDoubt = context.getBean(AgentRuntimeEndpoint.class)
                    .view(new String[]{"in-doubt"});
            assertThat(inDoubt.get("supported")).isEqualTo(true);
            // 默认 15 分钟阈值，刚写入的记录还不算超期
            assertThat(inDoubt.get("count")).isEqualTo(0L);
        });
    }

    @Test
    @DisplayName("strict-auth：拿到 taskId 的陌生人不能 resume 别人的任务，且拒绝被审计")
    void strictAuthRejectsStrangerAndAudits() {
        // 提交人是 alice；操作者是 mallory（无管理员角色）→ 必须被拒
        ApplicationContextRunner strict = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentRuntimeAutoConfiguration.class))
                .withBean(ConfirmTools.class, ConfirmTools::new)
                .withBean(OpsPrincipal.class, () -> new OpsPrincipal("mallory", java.util.Set.of()))
                .withPropertyValues("agent.runtime.ops.strict-auth=true");

        strict.run(context -> {
            AgentEngine engine = context.getBean(AgentEngine.class);
            AgentRuntimeEndpoint endpoint = context.getBean(AgentRuntimeEndpoint.class);
            String taskId = engine.submit(new DangerPlan(), "alice", "approve me");
            assertThat(engine.await(taskId, Duration.ofSeconds(10)).status()).isEqualTo(TaskStatus.SUSPENDED);

            Map<String, Object> refused = endpoint.act(new String[]{"resume", taskId}, "我没权限");
            assertThat(refused.get("ok")).isEqualTo(false);
            assertThat(String.valueOf(refused.get("error"))).contains("未授权");

            // 任务仍挂起：被拒的操作绝不能改变状态
            assertThat(engine.getTask(taskId).status()).isEqualTo(TaskStatus.SUSPENDED);

            // 拒绝也被写进审计（不可抵赖）
            OpsAuditLog audit = context.getBean(OpsAuditLog.class);
            var recent = audit.recent(10);
            assertThat(recent).anySatisfy(e -> {
                assertThat(e.allowed()).isFalse();
                assertThat(e.taskId()).isEqualTo(taskId);
                assertThat(e.principal().name()).isEqualTo("mallory");
            });
        });
    }

    @Test
    @DisplayName("strict-auth：任务归属人本人可以 resume（正向路径没被误伤）")
    void strictAuthAllowsOwner() {
        ApplicationContextRunner strict = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentRuntimeAutoConfiguration.class))
                .withBean(ConfirmTools.class, ConfirmTools::new)
                .withBean(OpsPrincipal.class, () -> new OpsPrincipal("alice", java.util.Set.of()))
                .withPropertyValues("agent.runtime.ops.strict-auth=true");

        strict.run(context -> {
            AgentEngine engine = context.getBean(AgentEngine.class);
            AgentRuntimeEndpoint endpoint = context.getBean(AgentRuntimeEndpoint.class);
            String taskId = engine.submit(new DangerPlan(), "alice", "approve me");
            assertThat(engine.await(taskId, Duration.ofSeconds(10)).status()).isEqualTo(TaskStatus.SUSPENDED);

            Map<String, Object> ok = endpoint.act(new String[]{"resume", taskId}, "本人批准");
            assertThat(ok.get("ok")).isEqualTo(true);
            assertThat(engine.await(taskId, Duration.ofSeconds(10)).status()).isEqualTo(TaskStatus.COMPLETED);
        });
    }

    @Test
    @DisplayName("audit-jdbc=true：运维动作真的落进 ops_audit 表（不只是日志）")
    void auditWritesToDatabase() {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:audit-" + java.util.UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ApplicationContextRunner jdbc = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentRuntimeAutoConfiguration.class))
                .withBean(javax.sql.DataSource.class, () -> ds)
                .withPropertyValues("agent.runtime.ops.audit-jdbc=true");
        jdbc.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(OpsAuditLog.class))
                    .isInstanceOf(com.agentruntime.ops.JdbcOpsAuditLog.class);

            // 触发一次会被拒的运维操作（未知任务），审计仍应落库
            context.getBean(AgentRuntimeEndpoint.class)
                    .act(new String[]{"resume", "no-such-task"}, "审计测试");
            try (var c = ds.getConnection();
                 var rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM ops_audit")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(1);
            } catch (java.sql.SQLException e) {
                throw new AssertionError("ops_audit 表应存在且有审计记录", e);
            }
        });
    }

    // ---------------------------------------------------------------- 夹具

    static class ConfirmTools {
        int calls;

        @AgentTool(name = "dangerous", requireHumanConfirm = true)
        public String dangerous() {
            calls++;
            return "executed";
        }
    }

    /** 一步就撞上「需要人工确认」的工具。 */
    static class DangerPlan implements AgentPlan {
        @Override
        public Step decide(Context ctx) {
            return ctx.stepIndex() == 0
                    ? Step.tool("dangerous", Map.of())
                    : Step.complete("done");
        }
    }

    /** 只实现 IdempotencyStore 的最小实现（验证「没有巡检能力」的降级路径）。 */
    static class BareIdempotencyStore implements IdempotencyStore {
        @Override
        public Optional<IdempotencyRecord> find(String idempotencyKey) {
            return Optional.empty();
        }

        @Override
        public Claim begin(String idempotencyKey, String taskId, int stepIndex) {
            return Claim.claimed();
        }

        @Override
        public void complete(String idempotencyKey, String resultPayload,
                             com.agentruntime.persistence.OutboxEvent event) {
        }

        @Override
        public void fail(String idempotencyKey, String errorPayload) {
        }
    }
}
