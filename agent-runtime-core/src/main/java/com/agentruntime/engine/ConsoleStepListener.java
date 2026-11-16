package com.agentruntime.engine;

import com.agentruntime.api.AgentEngine;
import com.agentruntime.api.TaskView;
import com.agentruntime.persistence.TaskEvent;

import java.time.Duration;
import java.time.Instant;

/**
 * 把每步事件打到标准输出（DESIGN §6 M1 的验收要求「打印每步日志」）。
 * 面向人类阅读，不承载机器可解析的格式——机器可解析的那份是 {@link TaskEvent#payloadJson()}。
 */
public class ConsoleStepListener implements AgentEngine.StepListener {

    private final boolean verbose;
    private Instant startedAt;

    public ConsoleStepListener() {
        this(false);
    }

    public ConsoleStepListener(boolean verbose) {
        this.verbose = verbose;
    }

    @Override
    public void onTaskStart(TaskView view) {
        startedAt = Instant.now();
        System.out.printf("[task] %s | agent=%s | RUNNING%n", shortId(view.taskId()), view.agentName());
    }

    @Override
    public void onEvent(TaskEvent event) {
        String detail = verbose ? event.payloadJson() : summarize(event);
        String cost = event.tokens() > 0 || event.cost().signum() > 0
                ? String.format(" [%d tok ¥%s]", event.tokens(), event.cost().toPlainString())
                : "";
        String ms = event.durationMs() > 0 ? " " + event.durationMs() + "ms" : "";
        System.out.printf("[step %d] %-12s %-14s %s%s%s%s%n",
                event.stepIndex(),
                event.stepType(),
                event.toolName() == null ? "-" : event.toolName(),
                event.success() ? "ok   " : "FAIL ",
                detail, ms, cost);
    }

    @Override
    public void onSuspended(TaskView view) {
        System.out.printf("[task] SUSPENDED（等待人工），已完成 %d 步，事件 %d 条%n",
                view.currentStep(), view.events().size());
    }

    @Override
    public void onCompleted(TaskView view) {
        long ms = startedAt == null ? -1 : Duration.between(startedAt, Instant.now()).toMillis();
        System.out.printf("[task] COMPLETED | 步数=%d 事件=%d 耗时=%dms%n",
                view.currentStep(), view.events().size(), ms);
    }

    @Override
    public void onFailed(TaskView view, String error) {
        System.out.printf("[task] FAILED | 步数=%d 原因=%s%n", view.currentStep(), error);
    }

    private static String summarize(TaskEvent event) {
        Object payload = event.payload();
        if (!(payload instanceof java.util.Map<?, ?> map)) {
            return String.valueOf(payload);
        }
        return switch (event.stepType()) {
            case LLM_DECISION -> {
                Object action = map.get("action");
                Object tool = map.get("toolName");
                yield action == null ? "" : "action=" + action + (tool == null ? "" : " → " + tool);
            }
            case TOOL_CALL -> {
                Object result = map.get("result");
                Object err = map.get("error");
                yield err != null ? "error=" + err : "result=" + String.valueOf(result);
            }
            case HUMAN_REVIEW -> "reason=" + map.get("reason");
            case COMPLETION -> "result=" + String.valueOf(map.get("result"));
        };
    }

    private static String shortId(String taskId) {
        return taskId == null ? "-" : taskId.substring(0, 8);
    }
}
