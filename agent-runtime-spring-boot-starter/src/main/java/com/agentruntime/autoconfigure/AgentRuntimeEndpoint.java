package com.agentruntime.autoconfigure;

import com.agentruntime.api.AgentEngine;
import com.agentruntime.api.TaskView;
import com.agentruntime.domain.TaskStatus;
import com.agentruntime.engine.DefaultAgentEngine;
import com.agentruntime.exception.AgentRuntimeException;
import com.agentruntime.ops.OpsAction;
import com.agentruntime.ops.OpsAlert;
import com.agentruntime.ops.OpsAuditLog;
import com.agentruntime.ops.OpsAuthorizer;
import com.agentruntime.ops.OpsDecision;
import com.agentruntime.ops.OpsPrincipal;
import com.agentruntime.persistence.IdempotencyInspector;
import com.agentruntime.persistence.IdempotencyRecord;
import com.agentruntime.persistence.Task;
import com.agentruntime.persistence.TaskRepository;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 运维控制面（M9）：一个 actuator 端点，把「挂起/在途未知」变成可操作的东西。
 *
 * <pre>
 * GET  /actuator/agentruntime                          概览（不带路径段也算 overview）
 * GET  /actuator/agentruntime/overview                 各状态计数 + 超期未决数
 * GET  /actuator/agentruntime/suspended                挂起清单（在等哪个工具、等了多久、为什么）
 * GET  /actuator/agentruntime/in-doubt                 超期停在 INIT 的幂等记录（资损面巡检）
 * GET  /actuator/agentruntime/orphans                  租约已过期的孤儿任务（recover-all 会接管的就是它们）
 * GET  /actuator/agentruntime/task/{taskId}            单个任务的完整视图
 * POST /actuator/agentruntime/recover/{taskId}         续跑一个中断的任务
 * POST /actuator/agentruntime/resume/{taskId}          批准并继续       body: {"note":"审批依据"}
 * POST /actuator/agentruntime/resume/{taskId}/reject   拒绝并继续（plan 会读到 humanRejected）
 * POST /actuator/agentruntime/resume/{taskId}/force    在途未知时人工确认「确实没发生」，强制重试
 * POST /actuator/agentruntime/recover-all              批量恢复（默认 dryRun）
 * POST /actuator/agentruntime/recover-all/apply        批量恢复（真动手）
 * </pre>
 *
 * <h2>为什么这个端点比「再多写几个 SPI」重要</h2>
 * M3 已经把「挂起」做成了引擎的一等公民：任务会安全地停在那里等人。
 * 但停在那里之后呢？如果没有一个地方能看到「哪些在等、在等什么、等了多久」，
 * 那些任务就等于<b>静默丢失</b>——库里躺着 SUSPENDED，业务侧永远等不到退款。
 * 对一次退款来说，「卡住且没人知道」和「失败」是同一件事，甚至更糟（没人会去重试）。
 *
 * <h2>两条安全默认</h2>
 * <ol>
 *   <li><b>批量恢复默认禁止</b>（{@code agent.runtime.ops.allow-bulk-recover=false}）：
 *       滚动发布时新实例「热心地」把所有 RUNNING 任务抢过来重放，会成为一次人为事故。</li>
 *   <li><b>批量恢复默认 dryRun</b>：先看清楚会动哪些任务，再决定要不要真动。</li>
 * </ol>
 *
 * <h2>为什么读操作只有一个（用 selector 分发）</h2>
 * Actuator 要求同一端点内「操作类型 + selector 名」唯一。多个无 selector 的
 * {@code @ReadOperation} 会直接启动失败，多个同名单 selector 的读操作则互相冲突。
 * 所以这里做成 REST 风格的「一个读 + 一个写，用路径段分发」——
 * 既满足框架约束，也让 URL 一眼能看懂。
 */
@Endpoint(id = "agentruntime")
public class AgentRuntimeEndpoint {

    private static final Log log = LogFactory.getLog(AgentRuntimeEndpoint.class);

    private final AgentEngine engine;
    private final ObjectProvider<DefaultAgentEngine> bulkCapable;
    private final TaskRepository repository;
    private final ObjectProvider<IdempotencyInspector> inspector;
    private final AgentRuntimeProperties properties;
    private final OpsAuthorizer authorizer;
    private final OpsAuditLog audit;
    private final OpsAlert alert;
    /** 身份来源（P0）：业务方接 Spring Security 时提供一个 OpsPrincipal bean。 */
    private final ObjectProvider<OpsPrincipal> principal;

    public AgentRuntimeEndpoint(AgentEngine engine,
                                ObjectProvider<DefaultAgentEngine> bulkCapable,
                                TaskRepository repository,
                                ObjectProvider<IdempotencyInspector> inspector,
                                AgentRuntimeProperties properties,
                                ObjectProvider<OpsAuthorizer> authorizer,
                                ObjectProvider<OpsAuditLog> audit,
                                ObjectProvider<OpsAlert> alert,
                                ObjectProvider<OpsPrincipal> principal) {
        this.engine = engine;
        this.bulkCapable = bulkCapable;
        this.repository = repository;
        this.inspector = inspector;
        this.properties = properties;
        this.authorizer = authorizer.getIfAvailable(OpsAuthorizer::permissive);
        this.audit = audit.getIfAvailable(OpsAuditLog::noop);
        this.alert = alert.getIfAvailable(() -> OpsAlert.NOOP);
        this.principal = principal;
    }


    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /**
     * 读操作：{@code /agentruntime/{view}} 或 {@code /agentruntime/task/{taskId}}。
     *
     * <h2>为什么用 {@code ALL_REMAINING} 而不是「一个 selector + 一个 query 参数」</h2>
     * Actuator 会把读操作上的<b>非 selector 参数当成必填的查询参数</b>——
     * 于是 {@code GET /overview} 会因为「缺少 taskId」直接 400。
     * 把 taskId 放进路径段（{@code Match.ALL_REMAINING}）既避开了这个坑，
     * 也让 URL 更像 REST：{@code /task/{id}} 一眼能看懂。
     */
    @ReadOperation
    public Map<String, Object> view(@Selector(match = Selector.Match.ALL_REMAINING) String[] path) {
        String head = path.length == 0 ? "overview" : path[0].trim().toLowerCase(java.util.Locale.ROOT);
        String argument = path.length > 1 ? path[1] : null;
        return switch (head) {
            case "overview", "" -> overview();
            case "suspended" -> suspended();
            case "in-doubt", "indoubt" -> inDoubt();
            case "orphans" -> orphans();
            case "task" -> task(argument);
            default -> error("未知视图: " + head + "（可用：overview / suspended / in-doubt / task/{id}）");
        };
    }

    private Map<String, Object> overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (TaskStatus status : TaskStatus.values()) {
            counts.put(status.name(), repository.countByStatus(status));
        }
        result.put("tasks", counts);

        // 只取一次：ObjectProvider 每次调用都会走一遍类型解析，而且分两次取会让
        // 「判空」与「使用」之间理论上可能拿到不同实例
        IdempotencyInspector available = inspector.getIfAvailable();
        // -1 明确表示「没有巡检能力」，而不是 0——0 会被读成「一切正常」
        long stale = available == null ? -1L : available.countStale(staleAfter());
        result.put("staleInDoubt", stale);
        result.put("staleInDoubtAfter", staleAfter().toString());
        result.put("bulkRecoverAllowed", properties.ops().allowBulkRecover());
        return result;
    }

    private Map<String, Object> suspended() {
        int limit = properties.ops().maxListSize();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Task task : repository.findByStatus(TaskStatus.SUSPENDED, limit)) {
            TaskView taskView = engine.getTask(task.getTaskId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("taskId", taskView.taskId());
            item.put("agentName", taskView.agentName());
            item.put("stepIndex", taskView.currentStep());
            item.put("pendingTool", taskView.pendingTool().orElse(null));
            item.put("reason", taskView.suspendedReason().orElse(null));
            item.put("inDoubt", taskView.inDoubt());
            item.put("inDoubtKey", taskView.inDoubtKey().orElse(null));
            item.put("awaitingConfirmation", taskView.awaitingConfirmation());
            item.put("updatedAt", taskView.updatedAt());
            item.put("waitedSeconds", waitedSeconds(taskView.updatedAt()));
            // inDoubt 排前面：钱可能已经动了的那批，优先级高于普通审批
            items.add(item);
        }
        items.sort((a, b) -> Boolean.compare((Boolean) b.get("inDoubt"), (Boolean) a.get("inDoubt")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", repository.countByStatus(TaskStatus.SUSPENDED));
        result.put("returned", items.size());
        result.put("limit", limit);
        result.put("items", items);
        return result;
    }

    private Map<String, Object> inDoubt() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("staleAfter", staleAfter().toString());
        IdempotencyInspector available = inspector.getIfAvailable();
        if (available == null) {
            result.put("supported", false);
            result.put("count", -1);
            result.put("items", List.of());
            result.put("hint", "当前 IdempotencyStore 未实现 IdempotencyInspector，无法巡检超期未决记录");
            return result;
        }
        List<IdempotencyRecord> stale = available.findStale(staleAfter(), properties.ops().maxListSize());
        List<Map<String, Object>> items = new ArrayList<>();
        for (IdempotencyRecord record : stale) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("idempotencyKey", record.idempotencyKey());
            item.put("taskId", record.taskId());
            item.put("stepIndex", record.stepIndex());
            item.put("status", record.status().name());
            item.put("updatedAt", record.updatedAt());
            item.put("waitedSeconds", waitedSeconds(record.updatedAt()));
            item.put("action",
                    "去对账系统查这笔流水：确认没发生 → resume(retrySideEffect=true)；已发生 → resume(result=...)");
            items.add(item);
        }
        result.put("supported", true);
        result.put("count", available.countStale(staleAfter()));
        result.put("returned", items.size());
        result.put("items", items);
        return result;
    }

    /**
     * 孤儿任务：状态还在跑、但心跳已经过期（M10）。
     *
     * <p>这个视图存在的意义是回答「recover-all 到底会动谁」。运维不该盲点一个批量按钮，
     * 然后事后去猜它抢了哪些任务。
     */
    private Map<String, Object> orphans() {
        Duration ttl = properties.leaseTtl();
        Instant staleBefore = Instant.now().minus(ttl);
        List<Map<String, Object>> items = new ArrayList<>();
        for (TaskStatus status : List.of(TaskStatus.RUNNING, TaskStatus.RECOVERING,
                TaskStatus.PENDING)) {
            for (Task task : repository.findByStatus(status, properties.ops().maxListSize())) {
                if (!task.isLeaseExpired(staleBefore)) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("taskId", task.getTaskId());
                item.put("agentName", task.getAgentName());
                item.put("status", task.getStatus().name());
                item.put("stepIndex", task.getCurrentStep());
                item.put("ownerId", task.getOwnerId());
                item.put("heartbeatAgeSeconds", ageSeconds(task.getHeartbeatAt()));
                items.add(item);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("leaseTtl", ttl.toString());
        result.put("count", items.size());
        result.put("items", items);
        result.put("hint", "这些任务的持有者心跳已过期；POST /recover/{id} 单个恢复，"
                + "或（开启权限后）POST /recover-all/apply");
        return result;
    }

    private Map<String, Object> task(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return error("需要任务号：GET /agentruntime/task/{taskId}");
        }
        Optional<Task> task = repository.findById(taskId);
        if (task.isEmpty()) {
            return error("任务不存在: " + taskId);
        }
        TaskView taskView = engine.getTask(taskId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskView.taskId());
        result.put("agentName", taskView.agentName());
        result.put("status", taskView.status().name());
        result.put("stepIndex", taskView.currentStep());
        result.put("totalCost", taskView.totalCost());
        result.put("totalTokens", taskView.totalTokens());
        result.put("pendingTool", taskView.pendingTool().orElse(null));
        result.put("suspendedReason", taskView.suspendedReason().orElse(null));
        result.put("inDoubtKey", taskView.inDoubtKey().orElse(null));
        result.put("ownerId", task.get().getOwnerId());
        result.put("heartbeatAgeSeconds", ageSeconds(task.get().getHeartbeatAt()));
        result.put("leaseTtl", properties.leaseTtl().toString());
        result.put("trajectory", trajectory(taskView));
        result.put("spans", taskView.spans());
        return result;
    }

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    /**
     * 写操作：{@code POST /agentruntime/{action}/{taskId}[/{mode}]}，body 里只放一个 {@code note}。
     *
     * <h2>为什么动作与模式都放进路径，而不是 body 参数</h2>
     * Actuator 对写操作的参数是<b>全量必填</b>的：方法上声明了 4 个参数，调用方就得把 4 个都放进 body，
     * 否则直接 400「Missing parameters: retrySideEffect,dryRun」。这对
     * 「调恢复接口的人根本不关心 dryRun」这种场景是荒唐的。
     *
     * <p>所以把语义搬进 URL：{@code resume/{id}}、{@code resume/{id}/reject}、
     * {@code resume/{id}/force}、{@code recover-all/apply}。好处不止是能调用：
     * <ul>
     *   <li><b>危险操作在访问日志里一眼可见</b>——{@code /force}、{@code /apply} 是可 grep 的；</li>
     *   <li>URL 自带说明，运维不用查文档就知道自己在干什么；</li>
     *   <li>body 只剩 {@code note} 一个字段：<b>强制填审批依据</b>，事故复盘时有据可查。</li>
     * </ul>
     */
    @WriteOperation
    public Map<String, Object> act(@Selector(match = Selector.Match.ALL_REMAINING) String[] path,
                                   String note) {
        String action = path.length == 0 ? "" : path[0].trim().toLowerCase(java.util.Locale.ROOT);
        String taskId = path.length > 1 ? path[1] : null;
        String mode = path.length > 2 ? path[2].trim().toLowerCase(java.util.Locale.ROOT) : null;

        OpsAction opsAction = classify(action, mode);
        OpsPrincipal who = principal.getIfAvailable(() -> OpsPrincipal.ANONYMOUS);
        String owner = ownerOf(taskId);

        // P0 安全：任何动作（含只读的 dryRun）都先过鉴权。默认 permissive 保持兼容，
        // 但审计一定写——「谁在什么时候动过什么」从此可查，不再只是一行会被轮转掉的 warn。
        OpsDecision decision = opsAction == null
                ? OpsDecision.allow()   // 未知动作让下面的 switch 去报「未知操作」
                : authorizer.authorize(opsAction, taskId, owner, who);
        if (decision.denied()) {
            auditRecord(opsAction, taskId, owner, who, note, false, decision.reason());
            alert.send(OpsAlert.Severity.WARN, OpsAlert.Events.OPS_DENIED,
                    "运维操作被鉴权拒绝: " + decision.reason(),
                    Map.of("action", String.valueOf(opsAction), "taskId", String.valueOf(taskId),
                            "principal", who.name()));
            return error("未授权：" + decision.reason());
        }

        try {
            Map<String, Object> result = switch (action) {
                case "recover" -> recover(taskId, note);
                case "resume" -> resume(taskId, !"reject".equals(mode), note, "force".equals(mode));
                case "recover-all" -> recoverAll(!"apply".equals(mode));
                default -> error("未知操作: " + action
                        + "（可用：recover/{id} / resume/{id}[/reject|/force] / recover-all[/apply]）");
            };
            auditRecord(opsAction, taskId, owner, who, note, Boolean.TRUE.equals(result.get("ok")), null);
            return result;
        } catch (AgentRuntimeException e) {
            // 运维操作被拒绝（状态不对、已被人抢先）不是系统故障：返回原因比抛 500 更有用
            log.warn("[agent-runtime] 运维操作被拒绝 action=" + action + " taskId=" + taskId
                    + " note=" + note + "：" + e.getMessage());
            auditRecord(opsAction, taskId, owner, who, note, false, e.getMessage());
            return error(e.getMessage());
        }
    }

    /** URL 动作 → 语义分类。未知动作返回 null，交给 switch 报「未知操作」。 */
    private static OpsAction classify(String action, String mode) {
        return switch (action) {
            case "recover" -> OpsAction.RECOVER;
            case "resume" -> "reject".equals(mode) ? OpsAction.RESUME_REJECT
                    : "force".equals(mode) ? OpsAction.RESUME_FORCE : OpsAction.RESUME_APPROVE;
            case "recover-all" -> "apply".equals(mode)
                    ? OpsAction.RECOVER_ALL_APPLY : OpsAction.RECOVER_ALL_DRY;
            default -> null;
        };
    }

    private String ownerOf(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        return repository.findById(taskId).map(Task::getUserId).orElse(null);
    }

    private void auditRecord(OpsAction action, String taskId, String owner, OpsPrincipal who,
                             String note, boolean allowed, String reason) {
        if (action == null) {
            return;
        }
        audit.record(new OpsAuditLog.OpsAuditEvent(
                Instant.now(), action, taskId, owner, who, note, allowed, reason));
        if (action.isMutation() && allowed) {
            alert.send(OpsAlert.Severity.WARN, OpsAlert.Events.OPS_MUTATION,
                    "危险运维操作已执行: " + action + " taskId=" + taskId,
                    Map.of("action", action.name(), "taskId", String.valueOf(taskId),
                            "principal", who.name(), "note", note == null ? "" : note));
        }
    }

    private Map<String, Object> recover(String taskId, String note) {
        if (taskId == null || taskId.isBlank()) {
            return error("recover 需要任务号：POST /agentruntime/recover/{taskId}");
        }
        engine.recover(taskId);
        Map<String, Object> result = ok(taskId, "已提交续跑；用 /task/{id} 观察状态");
        if (note != null && !note.isBlank()) {
            result.put("note", note);
        }
        return result;
    }

    private Map<String, Object> resume(String taskId, Boolean approved, String note, Boolean retrySideEffect) {
        if (taskId == null || taskId.isBlank()) {
            return error("resume 需要任务号：POST /agentruntime/resume/{taskId}");
        }
        Map<String, Object> humanInput = new LinkedHashMap<>();
        humanInput.put("approved", approved == null || approved);
        if (note != null && !note.isBlank()) {
            humanInput.put("note", note);
        }
        if (Boolean.TRUE.equals(retrySideEffect)) {
            humanInput.put("retrySideEffect", true);
            log.warn("[agent-runtime] 人工确认重试副作用 taskId=" + taskId
                    + "——这一步会真的再执行一次工具，请确认已对过账");
        }
        engine.resume(taskId, humanInput);
        Map<String, Object> result = ok(taskId, "已恢复执行");
        result.put("humanInput", humanInput);
        return result;
    }

    private Map<String, Object> recoverAll(boolean dryRun) {
        DefaultAgentEngine impl = bulkCapable.getIfAvailable();
        if (impl == null) {
            return error("当前 AgentEngine 实现不支持批量恢复（需要 DefaultAgentEngine）");
        }
        if (!properties.ops().allowBulkRecover()) {
            return error("批量恢复未开启：设 agent.runtime.ops.allow-bulk-recover=true 后重试。"
                    + "默认关闭是因为滚动发布时新实例抢跑会重放正在执行的任务");
        }
        List<Task> candidates = new ArrayList<>();
        for (TaskStatus recoverable : List.of(TaskStatus.PENDING, TaskStatus.RUNNING,
                TaskStatus.RECOVERING)) {
            candidates.addAll(repository.findByStatus(recoverable, properties.ops().maxListSize()));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dryRun", dryRun);
        result.put("candidates", candidates.stream().map(Task::getTaskId).toList());
        if (dryRun) {
            result.put("ok", true);
            result.put("hint", "确认无误后带 dryRun=false 再来一次");
            return result;
        }
        result.putAll(ok(null, "批量恢复杂跑：" + candidates.size() + " 个候选"));
        result.put("recovered", impl.recoverAllInterrupted());
        return result;
    }

    // ------------------------------------------------------------------

    private Duration staleAfter() {
        return properties.ops().staleInDoubtAfter();
    }

    private static Long ageSeconds(Instant since) {
        return since == null ? null : Math.max(0, Duration.between(since, Instant.now()).toSeconds());
    }

    private static long waitedSeconds(Instant since) {
        return since == null ? -1L : Math.max(0, Duration.between(since, Instant.now()).toSeconds());
    }

    private static String trajectory(TaskView view) {
        StringBuilder sb = new StringBuilder();
        view.events().forEach(event -> {
            if (sb.length() > 0) {
                sb.append(" → ");
            }
            sb.append('[').append(event.stepIndex()).append(':').append(event.stepType());
            if (event.toolName() != null) {
                sb.append(' ').append(event.toolName()).append(event.success() ? " ✓" : " ✗");
            }
            sb.append(']');
        });
        return sb.toString();
    }

    private static Map<String, Object> ok(String taskId, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        if (taskId != null) {
            result.put("taskId", taskId);
        }
        result.put("message", message);
        return result;
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", false);
        result.put("error", message);
        return result;
    }
}
