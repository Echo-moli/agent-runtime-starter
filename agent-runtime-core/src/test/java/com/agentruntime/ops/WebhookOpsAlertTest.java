package com.agentruntime.ops;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 告警 Webhook 的行为断言：发得出去、不刷屏、不拖垮调用方。 */
class WebhookOpsAlertTest {

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void deliversStructuredJson() throws Exception {
        try (WebhookOpsAlert alert = new WebhookOpsAlert(startServer())) {
            alert.send(OpsAlert.Severity.CRIT, OpsAlert.Events.LEASE_LOST,
                    "租约丢失", Map.of("taskId", "t-1"));
            awaitBodies(1);
        }
        String body = bodies.get(0);
        assertTrue(body.contains("\"severity\":\"CRIT\""), body);
        assertTrue(body.contains("\"event\":\"agent.lease.lost\""), body);
        assertTrue(body.contains("\"taskId\":\"t-1\""), body);
    }

    @Test
    void dedupesWithinWindow() throws Exception {
        try (WebhookOpsAlert alert = new WebhookOpsAlert(startServer())) {
            for (int i = 0; i < 5; i++) {
                alert.send(OpsAlert.Severity.WARN, "agent.test", "同一件事", Map.of("n", i));
            }
            awaitBodies(1);
            Thread.sleep(150);
            assertEquals(1, bodies.size(), "去重窗口内的重复告警只应发出一次");
        }
    }

    @Test
    void differentEventsAreNotDeduped() throws Exception {
        try (WebhookOpsAlert alert = new WebhookOpsAlert(startServer())) {
            alert.send(OpsAlert.Severity.WARN, "agent.test.a", "A", Map.of());
            alert.send(OpsAlert.Severity.WARN, "agent.test.b", "B", Map.of());
            awaitBodies(2);
        }
        assertEquals(2, bodies.size());
    }

    @Test
    void deliveryFailureNeverReachesCaller() {
        WebhookOpsAlert alert = new WebhookOpsAlert(URI.create("http://127.0.0.1:1/hook"));
        try (alert) {
            assertDoesNotThrow(() -> alert.send(OpsAlert.Severity.CRIT,
                    "agent.test", "连不上", Map.of()));
        }
    }

    private URI startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
    }

    private void awaitBodies(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (bodies.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(count, bodies.size(), "webhook 没有收到预期数量的告警");
    }
}
