package com.agentruntime.engine;

import com.agentruntime.api.Context;
import com.agentruntime.persistence.Task;
import com.agentruntime.persistence.TaskEvent;

import java.util.List;

/**
 * 把 Task + 事件流组装成 {@link Context}（DESIGN.md §3 第 1 步「上下文组装」）。
 *
 * <p>M1–M4 全量注入（DESIGN §11：默认不丢信息），M5+ 才在这里做摘要、检索、压缩。
 */
public final class ContextAssembler {

    public Context assemble(Task task, List<TaskEvent> events) {
        return new Context(
                task.getTaskId(),
                task.getAgentName(),
                task.getUserId(),
                task.getUserRequest(),
                task.getCurrentStep(),
                task.getContext(),
                events);
    }
}
