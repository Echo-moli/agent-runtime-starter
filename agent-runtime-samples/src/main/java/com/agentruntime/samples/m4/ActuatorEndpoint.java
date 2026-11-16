package com.agentruntime.samples.m4;

import com.agentruntime.observability.SimpleMetrics;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * 用 JDK 自带的 {@code HttpServer} 把指标挂到 {@code /actuator/prometheus}（M4）。
 *
 * <p>这不是在重造 Spring Boot Actuator——M8 接上 starter 之后这两条路径由 Micrometer 提供。
 * 这里存在的理由只有一个：<b>让「指标能被 Prometheus 抓走」这条验收是真的</b>，
 * 而不是「我们调用了一个 toString 并断言字符串里有某个词」。
 * 抓取用的是真的 HTTP GET，Content-Type、状态码、文本格式都按 Prometheus 的要求给。
 */
final class ActuatorEndpoint implements AutoCloseable {

    private static final String PROM_CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private final HttpServer server;
    private final String baseUrl;

    private ActuatorEndpoint(HttpServer server, String baseUrl) {
        this.server = server;
        this.baseUrl = baseUrl;
    }

    /**
     * @param port      0 表示由系统分配（测试友好，不会因为端口占用而挂）
     * @param metrics   指标源
     * @param agentJson {@code /actuator/agent} 的返回体（任务数、挂起数等）
     */
    static ActuatorEndpoint start(int port, SimpleMetrics metrics, Supplier<String> agentJson)
            throws IOException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/actuator/prometheus", exchange ->
                respond(exchange, 200, PROM_CONTENT_TYPE, metrics.prometheusText()));
        server.createContext("/actuator/agent", exchange ->
                respond(exchange, 200, "application/json; charset=utf-8", agentJson.get()));
        server.createContext("/", exchange ->
                respond(exchange, 404, "text/plain; charset=utf-8", "not found\n"));
        server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "actuator-http");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new ActuatorEndpoint(server, base);
    }

    String baseUrl() {
        return baseUrl;
    }

    String prometheusUrl() {
        return baseUrl + "/actuator/prometheus";
    }

    String agentUrl() {
        return baseUrl + "/actuator/agent";
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
