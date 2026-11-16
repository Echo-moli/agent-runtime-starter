package com.agentruntime.autoconfigure;

import com.agentruntime.api.AgentEngine;
import com.agentruntime.api.TaskView;
import com.agentruntime.persistence.TaskEvent;

import java.util.List;

/**
 * 把容器里的多个 {@link AgentEngine.StepListener} 合成一个（M8）。
 *
 * <h2>为什么需要它</h2>
 * 业务方通常各写各的监听器：一个打业务日志、一个推企业微信、一个统计 SLA。
 * 引擎只接受一个 listener，于是应用里必然出现「谁先注册谁赢」的胶水代码。
 * 自动装配把这一步收进来：容器里有几个就合成几个，顺序按 {@code @Order}。
 *
 * <h2>为什么单个监听器抛异常不能影响任务</h2>
 * 旁路观察者不该改变主流程的结局：日志系统挂了不该让一笔退款变成 FAILED。
 * 所以这里逐个 try/catch 并吞掉异常（只打 debug 日志）——这是刻意的，
 * 也是「可观测性组件不能成为单点」这条工程常识的落地。
 */
public final class CompositeStepListener implements AgentEngine.StepListener {

    private static final org.apache.commons.logging.Log log =
            org.apache.commons.logging.LogFactory.getLog(CompositeStepListener.class);

    private final List<AgentEngine.StepListener> delegates;

    public CompositeStepListener(List<AgentEngine.StepListener> delegates) {
        this.delegates = List.copyOf(delegates);
    }

    /** 空列表返回 {@code null}：引擎对 null listener 的路径是零开销的。 */
    public static AgentEngine.StepListener of(List<AgentEngine.StepListener> delegates) {
        if (delegates == null || delegates.isEmpty()) {
            return null;
        }
        if (delegates.size() == 1) {
            return delegates.get(0);
        }
        return new CompositeStepListener(delegates);
    }

    @Override
    public void onTaskStart(TaskView view) {
        for (AgentEngine.StepListener delegate : delegates) {
            try {
                delegate.onTaskStart(view);
            } catch (RuntimeException e) {
                log.warn("StepListener 异常已忽略（不影响任务）: onTaskStart", e);
            }
        }
    }

    @Override
    public void onEvent(TaskEvent event) {
        for (AgentEngine.StepListener delegate : delegates) {
            try {
                delegate.onEvent(event);
            } catch (RuntimeException e) {
                log.warn("StepListener 异常已忽略（不影响任务）: onEvent", e);
            }
        }
    }

    @Override
    public void onSuspended(TaskView view) {
        for (AgentEngine.StepListener delegate : delegates) {
            try {
                delegate.onSuspended(view);
            } catch (RuntimeException e) {
                log.warn("StepListener 异常已忽略（不影响任务）: onSuspended", e);
            }
        }
    }

    @Override
    public void onCompleted(TaskView view) {
        for (AgentEngine.StepListener delegate : delegates) {
            try {
                delegate.onCompleted(view);
            } catch (RuntimeException e) {
                log.warn("StepListener 异常已忽略（不影响任务）: onCompleted", e);
            }
        }
    }

    @Override
    public void onFailed(TaskView view, String error) {
        for (AgentEngine.StepListener delegate : delegates) {
            try {
                delegate.onFailed(view, error);
            } catch (RuntimeException e) {
                log.warn("StepListener 异常已忽略（不影响任务）: onFailed", e);
            }
        }
    }
}
