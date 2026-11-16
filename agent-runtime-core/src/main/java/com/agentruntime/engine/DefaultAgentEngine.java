package com.agentruntime.engine;

import com.agentruntime.api.AgentEngine;
import com.agentruntime.api.AgentPlan;
import com.agentruntime.api.TaskResult;
import com.agentruntime.api.TaskView;
import com.agentruntime.domain.InDoubtPolicy;
import com.agentruntime.domain.StepType;
import com.agentruntime.domain.TaskStatus;
import com.agentruntime.exception.AgentRuntimeException;
import com.agentruntime.observability.AgentTracer;
import com.agentruntime.observability.Metrics;
import com.agentruntime.ops.InstanceRegistry;
import com.agentruntime.ops.OpsAlert;
import com.agentruntime.persistence.IdempotencyStore;
import com.agentruntime.persistence.Task;
import com.agentruntime.persistence.TaskEvent;
import com.agentruntime.persistence.TaskRepository;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 默认引擎实现（DESIGN.md §9 的 {@code DefaultAgentEngine}）。
 *
 * <p>M2：{@code submit} 异步、{@code recover} 事件重放续跑、{@code tryClaim} 原子认领防双跑。
 *
 * <p>M3：
 * <ul>
 *   <li>{@code resume} 落地——挂起态 {@code SUSPENDED} 被原子认领后，人工作答写进事件，
 *       再由 {@link DecisionLoop} 的同一套断点重放逻辑继续；</li>
 *   <li>工具执行统一走 {@link ToolExecutor}（幂等占位 → 重试 → 缓存/outbox → 截断）；</li>
 *   <li>{@link #suspended()} 列出「等人处理的单子」，给人工控制台用。</li>
 * </ul>
 *
 * <p>线程模型：默认固定线程池、daemon 线程；{@link Builder#executor(Executor)} 传
 * {@code Runnable::run} 即退化为同步执行（M1 兼容模式）。
 */
public class DefaultAgentEngine implements AgentEngine, AutoCloseable {

    public static final int DEFAULT_MAX_STEPS = 20;
    /** recover 允许认领的状态：RUNNING = 崩溃留下的孤儿；RECOVERING = 认领后再次中断。 */
    public static final Set<TaskStatus> RECOVERABLE = Set.of(TaskStatus.RUNNING, TaskStatus.RECOVERING);

    /** 默认租约 TTL（M10）。必须大于最慢的一步；心跳间隔取 TTL/3，允许两次丢包。 */
    public static final Duration DEFAULT_LEASE_TTL = Duration.ofMinutes(5);

    /** 默认结果缓存上限（约几 MB，足够观察最近的任务）。 */
    public static final int DEFAULT_MAX_RESULTS = 10_000;
    /** 默认有界队列容量：满了用 CallerRunsPolicy 背压，而不是无界队列堆到 OOM。 */
    public static final int DEFAULT_QUEUE_CAPACITY = 200;

    /**
     * 「忽略租约」用的视界（M10.1）。
     *
     * <p>刻意不用 {@link Instant#MAX}：{@code Timestamp.from(Instant.MAX)} 会溢出，
     * 结果是 SQL 比较恒假、认领永远失败——一个看起来「安全」的常量反而把功能关掉了。
     * 十年足够跨越任何「这台机器已经死了」的判断，也不会溢出。
     */
    private static final Duration IGNORE_LEASE_WINDOW = Duration.ofDays(3650);

    private final TaskRepository repository;
    private final DecisionLoop loop;
    private final ToolExecutor toolExecutor;
    private final IdempotencyStore idempotencyStore;
    private final AgentTracer tracer;
    private final Metrics metrics;
    /** 当前活跃任务数（供 {@code agent.tasks.active} gauge）。 */
    private final AtomicInteger activeTasks = new AtomicInteger();
    /**
     * 任务结果缓存。默认有界（{@link Builder#maxResults(int)}）：无界 Map 在长跑服务里
     * 会随着提交量线性增长——每个终态任务的 future + TaskResult 都留着，几百万条后就是 OOM。
     * 超限时淘汰最早的<b>已完成</b>结果；未完成的任务绝不淘汰（否则 {@link #await} 会拿不到）。
     */
    private final BoundedResults results;
    private final Map<String, AgentPlan> plans = new ConcurrentHashMap<>();
    private final Executor executor;
    private final ExecutorService ownedExecutor;
    /** 优雅停机标记：置位后 {@link #submit} 拒收新任务，在途的继续跑完。 */
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    /** 优雅停机最多等在途任务多久，超时才强制中断。 */
    private final long shutdownTimeoutMs;
    /** 本实例标识（M10）：写进 {@code task.owner_id}，用于区分「别人在跑」与「别人崩了」。 */
    private final String instanceId;
    /** 租约 TTL：心跳停下超过这个时长，别的实例才允许接管。 */
    private final Duration leaseTtl;
    /** 心跳线程：只为本实例正在跑的任务续租，任务结束就停。 */
    private final ScheduledExecutorService heartbeat;
    /** 本实例当前持有租约的任务（心跳只给这些续租）。 */
    private final Set<String> leased = ConcurrentHashMap.newKeySet();
    /** 实例注册表（P2）：租约之外第二意见，缓解网络分区被误判成孤儿。 */
    private final InstanceRegistry instanceRegistry;
    /** 告警出口（P2）：租约丢失等「人必须知道」的事件。 */
    private final OpsAlert alert;

    private DefaultAgentEngine(TaskRepository repository, ToolRegistry registry,
                               AgentEngine.StepListener listener, int maxSteps, Executor executor,
                               IdempotencyStore idempotencyStore, RetryPolicy retryPolicy,
                               InDoubtPolicy inDoubtPolicy, Consumer<String> retryLogger,
                               AgentTracer tracer, Metrics metrics,
                               String instanceId, Duration leaseTtl,
                               int maxResults, int queueCapacity, long shutdownTimeoutMs,
                               InstanceRegistry instanceRegistry, OpsAlert alert) {
        this.repository = repository;
        this.results = new BoundedResults(maxResults > 0 ? maxResults : DEFAULT_MAX_RESULTS);
        this.shutdownTimeoutMs = shutdownTimeoutMs > 0 ? shutdownTimeoutMs : 10_000L;
        this.instanceRegistry = instanceRegistry == null ? InstanceRegistry.NOOP : instanceRegistry;
        this.alert = alert == null ? OpsAlert.NOOP : alert;
        this.instanceId = instanceId == null || instanceId.isBlank()
                ? defaultInstanceId() : instanceId;
        this.leaseTtl = leaseTtl == null || leaseTtl.isZero() || leaseTtl.isNegative()
                ? DEFAULT_LEASE_TTL : leaseTtl;
        this.idempotencyStore = idempotencyStore;
        this.tracer = tracer == null ? AgentTracer.noop() : tracer;
        this.metrics = metrics == null ? Metrics.noop() : metrics;
        this.toolExecutor = new ToolExecutor(idempotencyStore, retryPolicy, inDoubtPolicy, retryLogger);
        this.loop = new DecisionLoop(repository, registry, new ContextAssembler(),
                listener == null ? new AgentEngine.StepListener() {
                } : listener, toolExecutor, this.tracer, this.metrics, maxSteps, this.instanceId);
        registerMetrics();
        this.heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "agent-runtime-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        long intervalMs = Math.max(1_000L, this.leaseTtl.toMillis() / 3);
        this.instanceRegistry.heartbeat(this.instanceId, Instant.now());   // 启动即报到
        this.heartbeat.scheduleWithFixedDelay(this::renewLeases,
                intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        if (executor == null) {
            // 有界队列 + CallerRunsPolicy：队列满时让提交线程自己跑，形成天然背压。
            // 无界 LinkedBlockingQueue（newFixedThreadPool 的默认）在下游卡住时会堆到 OOM——
            // 那正是「agent 每步都阻塞几秒」这类负载最容易踩的坑。
            int pool = defaultPoolSize();
            ThreadPoolExecutor bounded = new ThreadPoolExecutor(
                    pool, pool, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(queueCapacity > 0 ? queueCapacity : DEFAULT_QUEUE_CAPACITY),
                    workerThreadFactory(), new ThreadPoolExecutor.CallerRunsPolicy());
            this.ownedExecutor = bounded;
            this.executor = this.ownedExecutor;
        } else {
            this.ownedExecutor = null;
            this.executor = executor;
        }
    }

    /**
     * 注册指标（DESIGN §6 M4 的三个必选项 + 两个本次补的）。
     *
     * <p>{@code agent.tasks.active} 用计数器 + gauge 回调，而不是「自己维护一个数再同时增减」：
     * 抓取时刻总有任务在执行，gauge 必须在<b>抓取的那一瞬间</b>取值。
     */
    private void registerMetrics() {
        metrics.describe("agent.tasks.active", "当前运行中的任务数");
        metrics.describe("agent.tasks", "任务终止计数（按终态）");
        metrics.describe("agent.steps", "已执行的步数（按步骤类型与成败）");
        metrics.describe("agent.step.duration", "单步耗时");
        metrics.describe("agent.tokens", "累计 token 数");
        metrics.describe("agent.cost", "累计花费（元）");
        metrics.describe("agent.tasks.suspended", "当前等待人工的任务数（挂起堆积=人工瓶颈）");
        // M10：租约三兄弟。lost 一旦非零，说明本实例有任务被别的实例接管过——
        // 那台机器要么真的停了，要么网络分区到连心跳都发不出去
        metrics.describe("agent.lease.renewed", "成功续租次数");
        metrics.describe("agent.lease.lost", "续租失败次数（租约已被别人接管）");
        metrics.describe("agent.lease.error", "续租异常次数（数据库不可用等）");

        metrics.gauge("agent.tasks.active", activeTasks::get);
        metrics.gauge("agent.tasks.suspended", () -> suspended().size());
    }

    public static Builder builder(TaskRepository repository, ToolRegistry registry) {
        return new Builder(repository, registry);
    }

    /** 注册 plan 供 {@link #recover} / {@link #resume} 按 {@code Task.agentName} 找回实现。 */
    public DefaultAgentEngine registerPlan(AgentPlan plan) {
        plans.put(plan.name(), plan);
        return this;
    }

    @Override
    public String submit(AgentPlan plan, String userId, String userRequest) {
        if (plan == null) {
            throw new IllegalArgumentException("plan 不能为空");
        }
        if (shuttingDown.get()) {
            throw new AgentRuntimeException("引擎正在优雅停机，拒收新任务（在途任务继续执行）");
        }
        String taskId = UUID.randomUUID().toString();
        Task task = new Task(taskId, plan.name(), userId, userRequest);
        task.setOwner(instanceId, Instant.now());   // 提交即持有租约
        repository.save(task);
        registerPlan(plan);
        schedule(task, plan);
        return taskId;
    }

    private void schedule(Task task, AgentPlan plan) {
        CompletableFuture<TaskResult> future = new CompletableFuture<>();
        results.put(task.getTaskId(), future);
        future.whenComplete((r, e) -> results.evictCompleted());
        activeTasks.incrementAndGet();
        leased.add(task.getTaskId());
        executor.execute(() -> {
            TaskResult result;
            try {
                result = loop.run(task, plan);
            } catch (RuntimeException e) {
                // loop 内部已经把业务异常转成 FAILED 结果；走到这里说明是引擎自身的 bug
                activeTasks.decrementAndGet();
                future.completeExceptionally(e);
                throw e;
            }
            // 先减再 complete：await 返回的那一刻，agent.tasks.active 必须已经归零，
            // 否则「任务结束」和「指标归零」之间会有一个可观测的假象窗口。
            activeTasks.decrementAndGet();
            leased.remove(task.getTaskId());   // 终态了，不需要再续租
            future.complete(result);
        });
        // 终态计数：挂在 future 上而不是线程里，同步执行器（Runnable::run）也能正确记
        future.thenAccept(r -> metrics.counter("agent.tasks", "status", r.status().name()).increment());
    }

    @Override
    public TaskResult await(String taskId, Duration timeout) {
        CompletableFuture<TaskResult> future = results.get(taskId);
        if (future == null) {
            return repository.findById(taskId)
                    .map(t -> new TaskResult(t.getTaskId(), t.getStatus(), null,
                            "没有进行中的执行，只有持久化状态（用 recover / resume 续跑）",
                            t.getTotalCost(), t.getTotalTokens()))
                    .orElseThrow(() -> new AgentRuntimeException("未知 taskId: " + taskId));
        }
        try {
            return timeout == null ? future.get() : future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new AgentRuntimeException("等待任务 " + taskId + " 超时（" + timeout + "）", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentRuntimeException("等待任务 " + taskId + " 被中断", e);
        } catch (ExecutionException e) {
            throw new AgentRuntimeException("任务 " + taskId + " 执行异常", e.getCause());
        }
    }

    @Override
    public void recover(String taskId) {
        Task task = repository.findById(taskId)
                .orElseThrow(() -> new AgentRuntimeException("未知 taskId: " + taskId));
        AgentPlan plan = requirePlan(task);
        if (task.getStatus() == TaskStatus.SUSPENDED) {
            throw new AgentRuntimeException("任务 " + taskId
                    + " 处于挂起态，需要人工作答：请调用 resume(taskId, humanInput) 而不是 recover");
        }
        // 显式 recover 是人的决定（「我看过了，这个任务死了」），因此刻意<b>不</b>检查租约：
        // 崩溃重启后的第一件事就是恢复，那时心跳才刚过期几秒，等 TTL 是荒谬的。
        // 需要防「抢跑」的是批量恢复，见 recoverAllInterrupted()。
        // 传一个远未来时间点 = 「所有租约都算过期」，同时把 owner_id 原子改成本实例（M10.1）。
        boolean claimed = repository.claimWithLease(taskId, RECOVERABLE, TaskStatus.RECOVERING,
                instanceId, ignoreLeaseBefore());
        if (!claimed) {
            throw new AgentRuntimeException(
                    "任务 " + taskId + " 当前状态 " + task.getStatus() + "，不可恢复（可能已被其他实例认领）");
        }
        // 内存副本也要跟着改：loop 的第一步 CAS 用「我以为的旧状态」做条件，
        // 如果这里还留着 RUNNING，而库里已经是 RECOVERING，CAS 必然失败。
        task.setStatus(TaskStatus.RECOVERING);
        task.setOwner(instanceId, Instant.now());
        reconcileUsage(taskId);
        schedule(task, plan);
    }

    /**
     * 人工作答后继续（DESIGN §5.3 的 {@code resume}）。
     *
     * <p>{@code humanInput} 支持的键：
     * <ul>
     *   <li>{@code approved}（默认 true）：是否批准被拦下的工具执行；</li>
     *   <li>{@code note}：审批意见，进事件；</li>
     *   <li>{@code retrySideEffect=true}：上次结果未知（INIT）时，人工确认「确实没发生」，强制再执行一次；</li>
     *   <li>{@code result={...}}：人工直接给出结果（例如查到了退款流水号），不再执行副作用。</li>
     * </ul>
     *
     * <p>{@code retrySideEffect} 与 {@code result} 是「在途结果」唯一的人工出口——
     * 框架无法自己区分「钱退了没」，只能让人看一眼。这正是 M3 存在的意义。
     */
    @Override
    public void resume(String taskId, Map<String, Object> humanInput) {
        Task task = repository.findById(taskId)
                .orElseThrow(() -> new AgentRuntimeException("未知 taskId: " + taskId));
        if (task.getStatus() != TaskStatus.SUSPENDED) {
            throw new AgentRuntimeException("任务 " + taskId + " 当前状态 " + task.getStatus()
                    + "，只有 SUSPENDED 可以 resume");
        }
        AgentPlan plan = requirePlan(task);
        if (!repository.claimWithLease(taskId, Set.of(TaskStatus.SUSPENDED), TaskStatus.RECOVERING,
                instanceId, ignoreLeaseBefore())) {
            throw new AgentRuntimeException("任务 " + taskId + " 认领失败（可能已被其他实例 resume）");
        }

        Map<String, Object> input = humanInput == null ? Map.of() : humanInput;
        List<TaskEvent> events = repository.findEvents(taskId);
        int stepIndex = task.getCurrentStep();
        String toolName = DecisionLoop.pendingToolName(events, stepIndex).orElse(null);

        boolean approved = !Boolean.FALSE.equals(input.get("approved"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("answer", true);
        payload.put("approved", approved);
        payload.put("toolName", toolName);
        if (input.get("note") != null) {
            payload.put("note", input.get("note"));
        }
        if (Boolean.TRUE.equals(input.get("retrySideEffect"))) {
            payload.put("retrySideEffect", true);
        }
        if (input.containsKey("result")) {
            payload.put("result", input.get("result"));
        }
        // 与 DecisionLoop 一致：人工作答也是往账本里写，同样要过所有权校验（M10.2）
        if (repository.appendEventIfOwned(TaskEvent.draft(taskId, stepIndex, StepType.HUMAN_REVIEW,
                toolName, payload, true), instanceId) == null) {
            throw new AgentRuntimeException("任务 " + taskId + " 保存人工作答失败：租约刚被其他实例接管");
        }

        // 人工作答同时进 context：plan 可能想读原始输入（例如审批附言）
        task.getContext().put("humanInput", input);
        task.setStatus(TaskStatus.RECOVERING);
        task.setOwner(instanceId, Instant.now());
        if (!repository.saveIfOwned(task, instanceId, TaskStatus.RECOVERING)) {
            throw new AgentRuntimeException("任务 " + taskId + " 保存人工作答失败：租约刚被其他实例接管");
        }

        reconcileUsage(taskId);
        schedule(task, plan);
    }

    /**
     * 按事件账本校正累计用量（M4）。
     *
     * <p>{@code appendEvent} 与 {@code addUsage} 是两次写。如果崩在中间，事件里有这一步的花费、
     * 任务的 {@code total_tokens/total_cost} 却没加上。此时错误方向是「少记账」——
     * 对成本归因来说，少记比多记更危险（超支看不见）。
     *
     * <p>所以恢复时不算增量，直接<b>按事件重算并覆盖</b>：事件是不可变账本，汇总是可重算的视图。
     * 这也是为什么 {@code save()} 故意不回写这两个计数列——它们只有 addUsage/setUsage 两个入口。
     *
     * @return 校正前后的差额（token 数），0 表示本来就一致
     */
    public long reconcileUsage(String taskId) {
        Task task = repository.findById(taskId)
                .orElseThrow(() -> new AgentRuntimeException("未知 taskId: " + taskId));
        long tokens = 0;
        BigDecimal cost = BigDecimal.ZERO;
        for (TaskEvent event : repository.findEvents(taskId)) {
            tokens += event.tokens();
            cost = cost.add(event.cost());
        }
        long before = task.getTotalTokens();
        BigDecimal costBefore = task.getTotalCost() == null ? BigDecimal.ZERO : task.getTotalCost();
        if (tokens != before || cost.compareTo(costBefore) != 0) {
            repository.setUsage(taskId, tokens, cost);
            return tokens - before;
        }
        return 0L;
    }

    /**
     * 把库里所有<b>租约已过期</b>的孤儿任务排队续跑，返回成功认领的 taskId 列表（M10 改造）。
     *
     * <h2>M9 之前这里是个隐患</h2>
     * 原来只要状态是 {@code RUNNING}/{@code RECOVERING} 就抢。可这两个状态在库里只有一种样子，
     * 分不清「另一台实例正在跑」和「上一个实例崩了」。滚动发布时新实例一启动就把老实例
     * 正在执行的任务全部接管：决策重复计费、工具被重复请求（幂等能兜住钱，兜不住浪费和挂起）。
     *
     * <p>现在只接管 {@code heartbeat_at < now - TTL} 的任务。心跳新鲜的一律跳过——
     * 宁可留一个孤儿等 TTL 过期，也不要抢一个可能还活着的任务。
     *
     * <p>紧急情况（确认某台实例已死，不想等 TTL）用 {@link #recover(String)} 单个恢复：
     * 那是人的判断，不受租约约束。
     */
    public List<String> recoverAllInterrupted() {
        return recoverAllInterrupted(false);
    }

    /**
     * @param force {@code true} = 忽略租约，接管所有 RUNNING/RECOVERING 任务。
     *
     * <p>什么时候该用 force：<b>你确定持有者已经不可能还活着</b>。典型场景是单实例应用
     * 「进程崩了、编排系统立刻重启」——新进程天然知道老进程死了，
     * 而按安全默认它得白等一个 TTL 才敢动。
     *
     * <p>什么时候不该用：滚动发布、扩缩容、任何「另一个副本可能正在干活」的时刻。
     * 这也是为什么运维端点只提供安全版的批量恢复：HTTP 请求本身不携带
     * 「我看过了，它们都死了」这种判断，而这个判断正是 force 的唯一理由。
     */
    public List<String> recoverAllInterrupted(boolean force) {
        Instant staleBefore = Instant.now().minus(leaseTtl);
        List<String> recovered = new ArrayList<>();
        for (Task task : repository.findAll()) {
            if (!RECOVERABLE.contains(task.getStatus())) {
                continue;
            }
            if (!force && !task.isLeaseExpired(staleBefore)) {
                continue;   // 租约还新鲜：有人还活着，别抢
            }
            // P2：租约过期但注册表说 owner 刚报过到 → 大概率是网络分区而非真崩，先别抢。
            // 两个独立信号必须同时失效才接管，把误判窗口从「一次 TTL」缩小。
            if (!force && task.getOwnerId() != null
                    && instanceRegistry.isActive(task.getOwnerId(), leaseTtl)) {
                continue;
            }
            try {
                recover(task.getTaskId());
                recovered.add(task.getTaskId());
            } catch (AgentRuntimeException ignored) {
                // 状态竞争（如并发认领失败）下跳过即可
            }
        }
        return recovered;
    }

    /**
     * 给本实例持有的任务续租。
     *
     * <p>续不上（{@code renewLease} 返回 false）说明租约已经被别人拿走：这里只记 metric，
     * 不去强杀正在执行的那一步——工具调用没有安全的取消点，硬中断可能让副作用停在中间。
     * 幂等记录会兜住重复，任务会在下一步的 {@code save()} 后由新持有者接管。
     */
    private void renewLeases() {
        Instant now = Instant.now();
        for (String taskId : leased) {
            try {
                if (repository.renewLease(taskId, instanceId, now)) {
                    metrics.counter("agent.lease.renewed").increment();
                    instanceRegistry.heartbeat(instanceId, now);   // 续租成功 = 本实例还活着
                } else {
                    leased.remove(taskId);
                    metrics.counter("agent.lease.lost", "task_id", taskId).increment();
                    alert.send(OpsAlert.Severity.WARN, OpsAlert.Events.LEASE_LOST,
                            "实例 " + instanceId + " 丢失任务 " + taskId + " 的租约（已被他人接管），"
                                    + "可能是网络分区或本机停摆",
                            Map.of("taskId", taskId, "instanceId", instanceId));
                }
            } catch (RuntimeException e) {
                // 心跳失败不该影响业务线程：下一轮继续试，租约过期自然降级成孤儿
                metrics.counter("agent.lease.error").increment();
            }
        }
    }

    /** 一个「远未来」的时间点：传给 {@code staleBefore} 等于「所有租约都算过期」。 */
    private static Instant ignoreLeaseBefore() {
        return Instant.now().plus(IGNORE_LEASE_WINDOW);
    }

    /** 本实例标识（写进 {@code task.owner_id}，运维排查时能对上是哪台机器）。 */
    public String instanceId() {
        return instanceId;
    }

    private static String defaultInstanceId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown";
        }
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 当前所有挂起任务（供人工控制台列出「等我处理的单子」）。 */
    public List<Task> suspended() {
        List<Task> list = new ArrayList<>();
        for (Task task : repository.findAll()) {
            if (task.getStatus() == TaskStatus.SUSPENDED) {
                list.add(task);
            }
        }
        return list;
    }

    /** 幂等存储（测试与运维用；未配置时为 null）。 */
    public IdempotencyStore idempotencyStore() {
        return idempotencyStore;
    }

    @Override
    public TaskView getTask(String taskId) {
        return repository.findById(taskId)
                .map(t -> TaskView.of(t, repository.findEvents(taskId)))
                .orElseThrow(() -> new AgentRuntimeException("未知 taskId: " + taskId));
    }

    private AgentPlan requirePlan(Task task) {
        AgentPlan plan = plans.get(task.getAgentName());
        if (plan == null) {
            throw new AgentRuntimeException("未注册 plan: " + task.getAgentName()
                    + "（recover/resume 前需 engine.registerPlan(...)）");
        }
        return plan;
    }

    /**
     * 优雅停机（P0 稳定性）：先拒收新任务 → 停在途任务的心跳线程会一直续租直到它们跑完
     * → 等执行器里的任务收尾（最多 {@code shutdownTimeoutMs}）→ 超时才强制中断。
     *
     * <p>顺序很关键：早先版本第一行就 {@code heartbeat.shutdownNow()}，
     * 于是在途任务跑得比 TTL 久时租约会被别人抢走——「优雅」停机反而在停机期间制造了孤儿。
     */
    @Override
    public void close() {
        shuttingDown.set(true);
        if (ownedExecutor != null) {
            ownedExecutor.shutdown();                 // 拒收新任务，已在队列里的继续
            try {
                if (!ownedExecutor.awaitTermination(shutdownTimeoutMs, TimeUnit.MILLISECONDS)) {
                    logWarn("等待在途任务 " + shutdownTimeoutMs + "ms 超时，强制中断剩余任务");
                    ownedExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ownedExecutor.shutdownNow();
            }
        }
        // 在途任务都收尾了，才停心跳：否则停机窗口内租约过期 = 人为制造孤儿
        heartbeat.shutdownNow();
        instanceRegistry.deregister(instanceId);   // 主动注销，别人能立刻判我死，不必等 TTL
        tracer.shutdown();   // flush 导出队列，别把最后几条 span 丢了
    }

    private static void logWarn(String message) {
        System.getLogger("com.agentruntime.engine").log(System.Logger.Level.WARNING, message);
    }

    /** 正在停机中（供运维端点 / 健康检查读取）。 */
    public boolean isShuttingDown() {
        return shuttingDown.get();
    }

    /** 当前缓存的任务结果数（供容量监控）。 */
    public int cachedResultCount() {
        return results.size();
    }

    private static int defaultPoolSize() {
        return Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    }

    private static ThreadFactory workerThreadFactory() {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "agent-runtime-worker-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /**
     * 有界结果缓存：FIFO 淘汰最早的<b>已完成</b>项。
     *
     * <p>不用 LinkedHashMap.removeEldestEntry 的原因是并发：{@code await} 与 {@code put}
     * 来自不同线程，synchronizedMap 会把 get 也串行化；这里用「ConcurrentHashMap + 队列」，
     * 淘汰时先看 future.isDone()，未完成的一律跳过（宁可暂时超限，也不丢在途结果）。
     */
    static final class BoundedResults {
        private final int limit;
        private final ConcurrentHashMap<String, CompletableFuture<TaskResult>> map = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<String> fifo = new ConcurrentLinkedQueue<>();

        BoundedResults(int limit) {
            this.limit = Math.max(1, limit);
        }

        void put(String taskId, CompletableFuture<TaskResult> future) {
            map.put(taskId, future);
            fifo.add(taskId);
            evictCompleted();
        }

        CompletableFuture<TaskResult> get(String taskId) {
            return map.get(taskId);
        }

        int size() {
            return map.size();
        }

        void evictCompleted() {
            int overflow = map.size() - limit;
            if (overflow <= 0) {
                return;
            }
            // 单轮最多扫一遍队列：未完成的一律放回队尾（不能丢，否则它们永远没机会被淘汰）。
            // 用预算封顶，避免「全是还没完成的任务」时无限打转。
            int budget = fifo.size();
            for (int scanned = 0; scanned < budget && overflow > 0; scanned++) {
                String oldest = fifo.poll();
                if (oldest == null) {
                    return;
                }
                CompletableFuture<TaskResult> candidate = map.get(oldest);
                if (candidate == null) {
                    continue;   // 已被别的线程淘汰
                }
                if (!candidate.isDone()) {
                    fifo.add(oldest);   // 在途：放回队尾，下次还有机会
                    continue;
                }
                // remove(k, v) 防止并发下误删刚被替换的新 future
                if (map.remove(oldest, candidate)) {
                    overflow--;
                }
            }
        }
    }

    /** 便捷构造器。 */
    public static final class Builder {
        private final TaskRepository repository;
        private final ToolRegistry registry;
        private AgentEngine.StepListener listener;
        private int maxSteps = DEFAULT_MAX_STEPS;
        private Executor executor;
        private IdempotencyStore idempotencyStore;
        private RetryPolicy retryPolicy = RetryPolicy.exponential();
        private InDoubtPolicy inDoubtPolicy = InDoubtPolicy.SUSPEND_FOR_HUMAN;
        private Consumer<String> retryLogger;
        private AgentTracer tracer;
        private Metrics metrics;
        private String instanceId;
        private Duration leaseTtl;
        private int maxResults = DEFAULT_MAX_RESULTS;
        private int queueCapacity = DEFAULT_QUEUE_CAPACITY;
        private long shutdownTimeoutMs = 10_000L;
        private InstanceRegistry registryInstance;
        private OpsAlert alert;

        private Builder(TaskRepository repository, ToolRegistry registry) {
            this.repository = repository;
            this.registry = registry;
        }

        public Builder listener(AgentEngine.StepListener listener) {
            this.listener = listener;
            return this;
        }

        public Builder maxSteps(int maxSteps) {
            if (maxSteps <= 0) {
                throw new IllegalArgumentException("maxSteps 必须 > 0");
            }
            this.maxSteps = maxSteps;
            return this;
        }

        /** 自定义执行器；传 {@code Runnable::run} 退化为同步执行（M1 兼容模式）。 */
        public Builder executor(Executor executor) {
            this.executor = executor;
            return this;
        }

        /** 配置幂等存储后，带 {@code idempotencyKey} 的工具才具备「最多执行一次」语义。 */
        public Builder idempotencyStore(IdempotencyStore idempotencyStore) {
            this.idempotencyStore = idempotencyStore;
            return this;
        }

        public Builder retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = retryPolicy;
            return this;
        }

        public Builder inDoubtPolicy(InDoubtPolicy inDoubtPolicy) {
            this.inDoubtPolicy = inDoubtPolicy;
            return this;
        }

        /** 重试日志出口（示例接到控制台，生产接 logger）。 */
        public Builder retryLogger(Consumer<String> retryLogger) {
            this.retryLogger = retryLogger;
            return this;
        }

        /** 链路追踪出口（M4）；不配则零开销。 */
        public Builder tracer(AgentTracer tracer) {
            this.tracer = tracer;
            return this;
        }

        /** 指标出口（M4）；不配则零开销。 */
        public Builder metrics(Metrics metrics) {
            this.metrics = metrics;
            return this;
        }

        /** 指定实例标识（多实例部署时默认用「hostname-随机串」，测试里可固定）。 */
        public Builder instanceId(String instanceId) {
            this.instanceId = instanceId;
            return this;
        }

        /** 租约 TTL（M10）。默认 {@link DefaultAgentEngine#DEFAULT_LEASE_TTL}。 */
        public Builder leaseTtl(Duration leaseTtl) {
            this.leaseTtl = leaseTtl;
            return this;
        }

        /** 结果缓存上限（默认 10000）。0 或负数用默认值。 */
        public Builder maxResults(int maxResults) {
            this.maxResults = maxResults;
            return this;
        }

        /** 执行器有界队列容量（默认 200）。仅当未传自定义 executor 时生效。 */
        public Builder queueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
            return this;
        }

        /** 优雅停机等在途任务的最长毫秒数（默认 10000）。 */
        public Builder shutdownTimeoutMs(long shutdownTimeoutMs) {
            this.shutdownTimeoutMs = shutdownTimeoutMs;
            return this;
        }

        /** 实例注册表（P2）：租约之外的判活第二意见。 */
        public Builder instanceRegistry(InstanceRegistry instanceRegistry) {
            this.registryInstance = instanceRegistry;
            return this;
        }

        /** 告警出口（P2）：租约丢失等事件。 */
        public Builder alert(OpsAlert alert) {
            this.alert = alert;
            return this;
        }

        public DefaultAgentEngine build() {
            return new DefaultAgentEngine(repository, registry, listener, maxSteps, executor,
                    idempotencyStore, retryPolicy, inDoubtPolicy, retryLogger, tracer, metrics,
                    instanceId, leaseTtl, maxResults, queueCapacity, shutdownTimeoutMs,
                    registryInstance, alert);
        }
    }
}
