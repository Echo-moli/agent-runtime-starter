package com.agentruntime.ops;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** P0/P2 运维 SPI 的纯逻辑测试：不碰 Spring、不碰数据库。 */
class OpsSpisTest {

    @Test
    void ownerOrAdmin_strangerCannotTouchOthersTask() {
        OpsAuthorizer auth = OpsAuthorizer.ownerOrAdmin();
        OpsPrincipal stranger = new OpsPrincipal("mallory", Set.of());
        OpsDecision denied = auth.authorize(OpsAction.RESUME_APPROVE, "t1", "alice", stranger);
        assertTrue(denied.denied());
        assertTrue(denied.reason().contains("alice"));

        OpsPrincipal owner = new OpsPrincipal("alice", Set.of());
        assertTrue(auth.authorize(OpsAction.RESUME_APPROVE, "t1", "alice", owner).allowed());

        OpsPrincipal admin = new OpsPrincipal("ops", Set.of(OpsPrincipal.ROLE_OPS_ADMIN));
        assertTrue(auth.authorize(OpsAction.RECOVER_ALL_APPLY, null, null, admin).allowed());
        assertTrue(auth.authorize(OpsAction.RECOVER_ALL_APPLY, null, null, stranger).denied());
        assertTrue(auth.authorize(OpsAction.RECOVER_ALL_APPLY, null, null, OpsPrincipal.ANONYMOUS).denied());
    }

    @Test
    void ownerOrAdmin_readsAlwaysAllowed() {
        OpsAuthorizer auth = OpsAuthorizer.ownerOrAdmin();
        for (OpsAction read : List.of(OpsAction.RECOVER, OpsAction.RECOVER_ALL_DRY)) {
            assertTrue(auth.authorize(read, "t1", "alice", OpsPrincipal.ANONYMOUS).allowed(),
                    "只读动作 " + read + " 不该被归属校验挡住（视图本身可脱敏）");
        }
    }

    @Test
    void 审计内存实现有界且保留最近的() {
        OpsAuditLog log = OpsAuditLog.inMemory(2);
        for (int i = 1; i <= 5; i++) {
            log.record(new OpsAuditLog.OpsAuditEvent(Instant.now(), OpsAction.RECOVER,
                    "t" + i, "u", OpsPrincipal.ANONYMOUS, null, true, null));
        }
        List<OpsAuditLog.OpsAuditEvent> recent = log.recent(10);
        assertEquals(2, recent.size());
        assertEquals("t4", recent.get(0).taskId());
        assertEquals("t5", recent.get(1).taskId());
    }

    @Test
    void 实例注册表判活与淘汰() {
        InstanceRegistry.InMemory registry = new InstanceRegistry.InMemory();
        registry.heartbeat("i1", Instant.now());
        assertTrue(registry.isActive("i1", Duration.ofMinutes(5)));
        assertFalse(registry.isActive("i2", Duration.ofMinutes(5)));
        registry.deregister("i1");
        assertFalse(registry.isActive("i1", Duration.ofMinutes(5)));

        registry.heartbeat("old", Instant.now().minusSeconds(120));
        assertEquals(1, registry.evictExpired(Duration.ofMinutes(1)));
        assertTrue(registry.activeInstances().isEmpty());
    }
}
