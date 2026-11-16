package com.agentruntime.samples.eval.reporting;

import com.agentruntime.annotation.AgentTool;
import com.agentruntime.api.AgentPlan;
import com.agentruntime.api.Context;
import com.agentruntime.api.Step;
import com.agentruntime.eval.EvalCase;
import com.agentruntime.eval.EvalFixture;
import com.agentruntime.engine.DefaultAgentEngine;
import com.agentruntime.engine.ToolRegistry;
import com.agentruntime.persistence.InMemoryIdempotencyStore;
import com.agentruntime.persistence.InMemoryTaskRepository;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * 第二业务域（P2）：报表查询 agent——用来证明 {@code EvalRunner} 与退款域无关、可泛化。
 *
 * <p>刻意做一个和退款完全不同的业务语义（读多写少、无资损、有权限与空结果分支），
 * 而不是把退款用例复制一遍改个名。只有业务语义不同，才能证明评测量测的是框架能力，
 * 而不是「有人把答案硬编码进了夹具」。
 */
public final class ReportAgent {

    private ReportAgent() {
    }

    /** 报表元数据（mock 数据源）。 */
    public record Report(String id, String owner, String title, int rows, BigDecimal cost) {
    }

    /** 报表服务：查询 + 一个写操作（导出，需权限）。 */
    public static final class ReportService {
        private final Map<String, Report> reports;

        public ReportService() {
            this.reports = Map.of(
                    "R-001", new Report("R-001", "alice", "日销售汇总", 120, new BigDecimal("0.30")),
                    "R-002", new Report("R-002", "bob", "退款分析", 45, new BigDecimal("0.12")));
        }

        @AgentTool(name = "queryReport")
        public Report queryReport(String reportId) {
            Report report = reports.get(reportId);
            if (report == null) {
                throw new IllegalArgumentException("报表不存在: " + reportId);
            }
            return report;
        }

        /** 导出是写操作：无权限时抛异常，模拟越权被拦。 */
        @AgentTool(name = "exportReport", idempotencyKey = "#reportId")
        public String exportReport(String reportId, String requester) {
            Report report = reports.get(reportId);
            if (report == null) {
                throw new IllegalArgumentException("报表不存在: " + reportId);
            }
            if (!report.owner().equals(requester)) {
                // 用业务异常而非 SecurityException：框架的 retryPolicy 把 IllegalArgumentException
                // 视为不可重试的业务失败，直接 FAIL（与退款域的「订单不存在」一致）。
                // 真正的权限框架异常应在 agent 外层转成这种确定性失败，避免无意义重试。
                throw new IllegalArgumentException("无权导出他人报表: " + reportId);
            }
            return "已导出 " + report.title() + "（" + report.rows() + " 行）";
        }
    }

    /** 决策实现：输入是 {@code reportId:userId}；先查，查到且本人所有则导出。 */
    public static final class Plan implements AgentPlan {
        private final String name;
        private final boolean skipQuery;   // 回退变体：跳过查询直接导出

        public Plan(String name, boolean skipQuery) {
            this.name = name;
            this.skipQuery = skipQuery;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Step decide(Context ctx) {
            String[] parts = ctx.userRequest().split(":", 2);
            String reportId = parts.length > 0 ? parts[0].trim() : "";
            String user = parts.length > 1 ? parts[1].trim() : "";
            if (reportId.isBlank()) {
                throw new IllegalArgumentException("报表号不能为空");
            }
            // 用明确步号收敛：0 查询、1 导出、2 完成。
            // 早先版本用 lastToolResult().isEmpty() 判断「有没有结果」，导出成功后
            // lastToolResult 仍有值 → 反复导出直到步数上限——这正是评测要拦的「不收敛」回退。
            return switch (ctx.stepIndex()) {
                case 0 -> skipQuery
                        ? Step.tool("exportReport", Map.of("reportId", reportId, "requester", user))
                        : Step.tool("queryReport", Map.of("reportId", reportId));
                case 1 -> Step.tool("exportReport", Map.of("reportId", reportId, "requester", user));
                default -> Step.complete(ctx.lastToolResult().orElse("无结果"));
            };
        }
    }

    /** 每条 case 一套干净引擎，和退款域夹具同构——证明框架复用、业务隔离。 */
    public static final class Fixture implements EvalFixture {
        @Override
        public Session open(EvalCase evalCase) {
            ToolRegistry registry = new ToolRegistry().register(new ReportService());
            DefaultAgentEngine engine = DefaultAgentEngine.builder(
                            new InMemoryTaskRepository(), registry)
                    .executor(Runnable::run)
                    .idempotencyStore(new InMemoryIdempotencyStore())
                    .maxSteps(8)
                    .build();
            return Session.of(engine, new Plan("report-plan",
                    evalCase.tags().contains("regressed-skip-query")));
        }
    }

    /** 方便 demo 断言的常量。 */
    public static final Set<String> KNOWN = Set.of("R-001", "R-002");
}
