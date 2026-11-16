package com.agentruntime.api;

import com.agentruntime.persistence.TaskEvent;

import java.time.Duration;
import java.util.Map;

/**
 * 引擎接口（DESIGN.md §5.3）。
 *
 * <p>实现为 {@code DefaultAgentEngine}：{@code submit} 落库后<b>立刻返回 taskId</b>，任务在独立线程池里跑
 * （同步模式用 {@code builder.executor(Runnable::run)} 退化）。六个方法的分工：
 * <ul>
 *   <li>{@link #submit}：新建任务，提交即返回；调用方用 {@link #await} 拿结果；</li>
 *   <li>{@link #await}：阻塞等终态（带超时）；超时抛异常而不是返回 null——</li>
 *   <li>{@link #recover}：续跑一个中断的任务（人的判断，不看租约，见 M10）；</li>
 *   <li>{@link #resume}：人工作答后从挂起点继续；</li>
 *   <li>{@link #getTask}：状态 + 全量事件 + 步级 span；</li>
 *   <li>{@link StepListener}：每步的旁路观察者，用于打日志 / 接 OTel。</li>
 * </ul>
 */
public interface AgentEngine {

    /** 提交一次 Agent 运行，<b>立刻返回 taskId</b>（不阻塞到任务跑完）。 */
    String submit(AgentPlan plan, String userId, String userRequest);

    /** 阻塞获取最终结果（带超时）。 */
    TaskResult await(String taskId, Duration timeout);

    /** 从持久化状态恢复一个中断/崩溃的任务（M2）。 */
    void recover(String taskId);

    /** 人工确认后从挂起点继续（M3）。 */
    void resume(String taskId, Map<String, Object> humanInput);

    /** 查询任务状态与全链路事件。 */
    TaskView getTask(String taskId);

    /** 每步事件的旁路观察者，便于打日志/接 OTel（M4）。 */
    interface StepListener {
        default void onTaskStart(TaskView view) {
        }

        default void onEvent(TaskEvent event) {
        }

        default void onSuspended(TaskView view) {
        }

        default void onCompleted(TaskView view) {
        }

        default void onFailed(TaskView view, String error) {
        }
    }
}
