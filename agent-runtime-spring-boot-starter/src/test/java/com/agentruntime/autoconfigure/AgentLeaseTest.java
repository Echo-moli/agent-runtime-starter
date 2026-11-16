package com.agentruntime.autoconfigure;

import com.agentruntime.annotation.AgentTool;
import com.agentruntime.api.AgentPlan;
import com.agentruntime.api.Context;
import com.agentruntime.api.Step;
import com.agentruntime.api.TaskResult;
import com.agentruntime.domain.StepType;
import com.agentruntime.domain.TaskStatus;
import com.agentruntime.engine.DefaultAgentEngine;
import com.agentruntime.engine.ToolRegistry;
import com.agentruntime.persistence.JdbcTaskRepository;
import com.agentruntime.persistence.Task;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 租约与孤儿判定（M10）。
 *
 * <h2>这一组用例守的是「抢跑」这条线</h2>
 * M9 之前 {@code recoverAllInterrupted()} 只看状态：{@code RUNNING} 就当成孤儿。
 * 滚动发布时新实例一启动，就会把老实例<b>正在执行</b>的任务全部接管——
 * 决策重复计费、工具被重复请求。幂等能兜住钱，但兜不住成本、延迟和莫名挂起。
 *
 * <p>所以这里断言的不是「代码能编译」，而是三个具体的行为：
 * <ol>
 *   <li>心跳新鲜的任务<b>抢不走</b>；</li>
 *   <li>心跳过期（持有者确实死了）才<b>能</b>接管；</li>
 *   <li>老持有者若还活着，它下一次 {@code save()} <b>不能</b>把新持有者的租约覆盖回去。</li>
 * </ol>
 * 第 3 条是最容易漏的：租约列如果有两个写入口，整个机制就是纸做的。
 */
class AgentLeaseTest {

    private JdbcDataSource dataSource;
    private JdbcTaskRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:lease-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        // 用 starter 自己的建表器，顺手验证脚本在 H2 上可执行（含 M10 的 ALTER）
        new AgentSchemaInitializer(dataSource).afterPropertiesSet();
        repository = new JdbcTaskRepository(dataSource);
    }

    @Test
    @DisplayName("心跳新鲜 → 抢不走；心跳过期 → 才能接管（同一 owner 续租永远成功）")
    void claimRespectsLease() {
        String taskId = runningTaskOwnedBy("instance-A", Instant.now());
        Instant staleBefore = Instant.now().minus(Duration.ofMinutes(5));

        assertThat(repository.claimWithLease(taskId, Set.of(TaskStatus.RUNNING),
                TaskStatus.RECOVERING, "instance-B", staleBefore))
                .as("A 刚发过心跳，B 不能接管")
                .isFalse();

        assertThat(repository.claimWithLease(taskId, Set.of(TaskStatus.RUNNING),
                TaskStatus.RECOVERING, "instance-A", staleBefore))
                .as("自己续租/重复认领不受租约限制")
                .isTrue();

        // A 的进程死了：心跳停在一小时前
        setHeartbeat(taskId, Instant.now().minus(Duration.ofHours(1)));
        assertThat(repository.claimWithLease(taskId, Set.of(TaskStatus.RECOVERING),
                TaskStatus.RECOVERING, "instance-B", staleBefore))
                .as("租约过期后 B 才能接管")
                .isTrue();

        Task taken = repository.findById(taskId).orElseThrow();
        assertThat(taken.getOwnerId()).isEqualTo("instance-B");
    }

    @Test
    @DisplayName("save() 不能覆盖别人的租约（租约列只有一个写入口）")
    void saveDoesNotClobberLease() {
        String taskId = runningTaskOwnedBy("instance-A", Instant.now());
        Task staleCopy = repository.findById(taskId).orElseThrow();   // A 手里的旧对象

        setHeartbeat(taskId, Instant.now().minus(Duration.ofHours(1)));   // A 已经死了
        assertThat(repository.claimWithLease(taskId, Set.of(TaskStatus.RUNNING),
                TaskStatus.RECOVERING, "instance-B", Instant.now().minus(Duration.ofMinutes(5))))
                .as("租约过期后 B 认领成功")
                .isTrue();
        assertThat(repository.findById(taskId).orElseThrow().getOwnerId()).isEqualTo("instance-B");

        // A 还没察觉，继续按自己的旧副本保存（这是真实会发生的：它正跑到一半）
        staleCopy.setCurrentStep(3);
        repository.save(staleCopy);

        Task after = repository.findById(taskId).orElseThrow();
        assertThat(after.getOwnerId()).as("租约必须还是 B").isEqualTo("instance-B");
        assertThat(after.getCurrentStep()).as("业务字段照常写入").isEqualTo(3);
    }

    @Test
    @DisplayName("批量恢复跳过活着的实例持有的任务")
    void bulkRecoverSkipsLiveInstance() {
        CountDownLatch release = new CountDownLatch(1);
        DefaultAgentEngine engineA = engine("instance-A", new BlockingTools(release));
        DefaultAgentEngine engineB = engine("instance-B", new BlockingTools(release));
        try {
            String taskId = engineA.submit(new BlockPlan(), "u1", "x");
            waitUntilRunning(taskId);

            engineB.registerPlan(new BlockPlan());
            assertThat(engineB.recoverAllInterrupted())
                    .as("A 正在跑（心跳新鲜），B 的批量恢复必须什么都不做")
                    .isEmpty();

            release.countDown();
            assertThat(engineA.await(taskId, Duration.ofSeconds(10)).status())
                    .isEqualTo(TaskStatus.COMPLETED);
        } finally {
            release.countDown();
            engineA.close();
            engineB.close();
        }
    }

    @Test
    @DisplayName("租约被夺走后，原持有者的写入被拒，且不会把任务标成完成（M10.1）")
    void staleOwnerCannotWrite() {
        CountDownLatch release = new CountDownLatch(1);
        DefaultAgentEngine engineA = engine("instance-A", new BlockingTools(release));
        try {
            String taskId = engineA.submit(new BlockPlan(), "u1", "x");
            waitUntilRunning(taskId);                       // A 正卡在工具里

            // 模拟 A 的心跳停了很久，B 合法接管
            setHeartbeat(taskId, Instant.now().minus(Duration.ofHours(1)));
            assertThat(repository.claimWithLease(taskId, Set.of(TaskStatus.RUNNING),
                    TaskStatus.RECOVERING, "instance-B", Instant.now().minus(Duration.ofMinutes(5))))
                    .as("B 认领成功")
                    .isTrue();

            release.countDown();                            // A 的工具跑完了，它要去保存这一步
            TaskResult result = engineA.await(taskId, Duration.ofSeconds(10));

            assertThat(result.status())
                    .as("A 必须停下并如实说「任务换主人了」，而不是 COMPLETED 或 FAILED")
                    .isEqualTo(TaskStatus.RECOVERING);
            assertThat(result.error()).contains("租约");

            Task after = repository.findById(taskId).orElseThrow();
            assertThat(after.getStatus())
                    .as("DB 状态不能被 A 改回 RUNNING")
                    .isEqualTo(TaskStatus.RECOVERING);
            assertThat(after.getOwnerId()).as("租约仍是 B 的").isEqualTo("instance-B");
            assertThat(repository.findEvents(taskId))
                    .as("A 没有写完成事件——账本里不留另一个主人的结局")
                    .noneMatch(event -> event.stepType() == StepType.COMPLETION);
            assertThat(repository.findEvents(taskId))
                    .as("M10.2：A 连工具步事件都写不进去——账本里不出现「另一个主人执行的一步」")
                    .noneMatch(event -> event.stepType() == StepType.TOOL_CALL);
        } finally {
            release.countDown();
            engineA.close();
        }
    }

    @Test
    @DisplayName("事件追加也做所有权校验（M10.2）：不是主人就写不进去")
    void appendEventRequiresOwnership() {
        String taskId = runningTaskOwnedBy("instance-A", Instant.now());
        var draft = com.agentruntime.persistence.TaskEvent.draft(taskId, 0, StepType.TOOL_CALL,
                "x", Map.of(), true);

        assertThat(repository.appendEventIfOwned(draft, "instance-B"))
                .as("B 不是主人，事件不落库")
                .isNull();
        assertThat(repository.findEvents(taskId)).isEmpty();

        assertThat(repository.appendEventIfOwned(draft, "instance-A"))
                .as("A 是主人，正常写入")
                .isNotNull();
        assertThat(repository.findEvents(taskId)).hasSize(1);
    }

    @Test
    @DisplayName("孤儿视图：心跳过期才出现在 /orphans，且带上持有者与心跳年龄")
    void orphansViewUsesLease() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentRuntimeAutoConfiguration.class))
                .withBean(javax.sql.DataSource.class, () -> dataSource);

        String alive = runningTaskOwnedBy("instance-A", Instant.now());
        String dead = runningTaskOwnedBy("instance-A", Instant.now().minus(Duration.ofHours(1)));

        runner.run(context -> {
            Map<String, Object> orphans = context.getBean(AgentRuntimeEndpoint.class)
                    .view(new String[]{"orphans"});
            assertThat(orphans.get("count")).isEqualTo(1);

            java.util.List<Map<String, Object>> items = (java.util.List<Map<String, Object>>) orphans.get("items");
            assertThat(items).extracting(item -> item.get("taskId")).containsExactly(dead);
            assertThat(items.get(0).get("ownerId")).isEqualTo("instance-A");
            assertThat(items.get(0).get("heartbeatAgeSeconds")).isNotNull();
            assertThat(items).noneMatch(item -> alive.equals(item.get("taskId")));
        });
    }

    // ---------------------------------------------------------------- 夹具

    private DefaultAgentEngine engine(String instanceId, BlockingTools tools) {
        ToolRegistry registry = new ToolRegistry().register(tools);
        return DefaultAgentEngine.builder(repository, registry)
                .instanceId(instanceId)
                .leaseTtl(Duration.ofMinutes(5))
                .build();
    }

    private String runningTaskOwnedBy(String ownerId, Instant heartbeatAt) {
        String taskId = "t-" + UUID.randomUUID();
        Task task = new Task(taskId, "BlockPlan", "u1", "x");
        task.setStatus(TaskStatus.RUNNING);
        task.setOwner(ownerId, heartbeatAt);
        repository.save(task);
        return taskId;
    }

    private void setHeartbeat(String taskId, Instant heartbeatAt) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "UPDATE task SET heartbeat_at=? WHERE task_id=?")) {
            ps.setTimestamp(1, java.sql.Timestamp.from(heartbeatAt));
            ps.setString(2, taskId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void waitUntilRunning(String taskId) {
        for (int i = 0; i < 200; i++) {
            if (repository.findById(taskId)
                    .map(t -> t.getStatus() == TaskStatus.RUNNING)
                    .orElse(false)) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new IllegalStateException("任务没有进入 RUNNING: " + taskId);
    }

    /** 阻塞工具：模拟「一步跑很久」，同时让心跳成为唯一能证明「它还活着」的信号。 */
    static class BlockingTools {
        private final CountDownLatch release;

        BlockingTools(CountDownLatch release) {
            this.release = release;
        }

        @AgentTool(name = "block")
        public String block() {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "unblocked";
        }
    }

    static class BlockPlan implements AgentPlan {
        @Override
        public Step decide(Context ctx) {
            return ctx.stepIndex() == 0 ? Step.tool("block", Map.of()) : Step.complete("ok");
        }
    }

}
