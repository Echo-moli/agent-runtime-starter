package com.agentruntime.ops;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实例注册表（P2）——缓解「网络分区被误判成孤儿」。
 *
 * <h2>为什么心跳 TTL 单独不够</h2>
 * 租约判活只看 {@code task.heartbeat_at}：一台<b>还活着但网络分区</b>的实例无法续租，
 * TTL 一到就被别人当成孤儿接管——于是两个实例同时跑同一个任务。
 * 注册表是第二意见：心跳过期时再问一句「这个 owner 最近跟注册中心报过到吗」，
 * 报过就先别抢。它不能根治分区（分布式共识才有能力根治），但把「误抢」的窗口
 * 从「一次 TTL」缩小到「注册表心跳 + TTL 两个独立信号同时失效」。
 *
 * <p>默认实现是本地内存版：单实例部署足够，多实例应接数据库/注册中心实现本接口。
 */
public interface InstanceRegistry {

    void heartbeat(String instanceId, Instant at);

    void deregister(String instanceId);

    /** 该实例是否「刚刚活跃」——在 {@code within} 时间窗内报过到。 */
    boolean isActive(String instanceId, Duration within);

    /** 当前活跃实例快照（运维端点展示用）。 */
    Map<String, Instant> activeInstances();

    /** 内存实现：单实例 / 测试友好。 */
    final class InMemory implements InstanceRegistry {
        private final Map<String, Instant> instances = new ConcurrentHashMap<>();

        @Override
        public void heartbeat(String instanceId, Instant at) {
            if (instanceId != null && !instanceId.isBlank()) {
                instances.put(instanceId, at == null ? Instant.now() : at);
            }
        }

        @Override
        public void deregister(String instanceId) {
            if (instanceId != null) {
                instances.remove(instanceId);
            }
        }

        @Override
        public boolean isActive(String instanceId, Duration within) {
            if (instanceId == null) {
                return false;
            }
            Instant last = instances.get(instanceId);
            if (last == null) {
                return false;
            }
            return !last.isBefore(Instant.now().minus(within));
        }

        @Override
        public Map<String, Instant> activeInstances() {
            return new LinkedHashMap<>(instances);
        }

        /** 清掉超过 ttl 没报到的僵尸条目，避免注册表自身无界增长。 */
        public int evictExpired(Duration ttl) {
            Instant cutoff = Instant.now().minus(ttl);
            int before = instances.size();
            instances.entrySet().removeIf(e -> e.getValue().isBefore(cutoff));
            return before - instances.size();
        }
    }

    InstanceRegistry NOOP = new InstanceRegistry() {
        @Override
        public void heartbeat(String instanceId, Instant at) {
        }

        @Override
        public void deregister(String instanceId) {
        }

        @Override
        public boolean isActive(String instanceId, Duration within) {
            return false;
        }

        @Override
        public Map<String, Instant> activeInstances() {
            return Map.of();
        }
    };

    /** 便捷：按实例名判断一组 owner 里哪些「还活着」。 */
    static Set<String> activeAmong(Set<String> owners, InstanceRegistry registry, Duration within) {
        return owners.stream().filter(id -> registry.isActive(id, within))
                .collect(java.util.stream.Collectors.toSet());
    }
}
