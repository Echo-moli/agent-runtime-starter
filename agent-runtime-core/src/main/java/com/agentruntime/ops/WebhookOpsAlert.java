package com.agentruntime.ops;

import com.agentruntime.util.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 把运维告警打到 HTTP Webhook（企微 / 钉钉 / PagerDuty 的机器人地址都能接）。
 *
 * <p><b>三条不能让步的约束</b>：
 * <ol>
 *   <li>告警不能拖慢业务：发送走独立守护线程，调用方只做一次入队；</li>
 *   <li>告警不能带崩业务：投递失败只落错误流 + 可选回调，绝不把异常抛回调用栈；</li>
 *   <li>告警不能刷屏：同一事件在去重窗口内只发一次，避免租约丢失风暴把接收端打爆。</li>
 * </ol>
 *
 * <p>零第三方依赖：只用 JDK 自带的 {@link HttpClient} 与 core 的 {@link Json}。
 * 消息体是统一结构（{@code at/severity/event/message/context}），不绑定任何厂商格式——
 * 需要企微/钉钉专用模板时，把 endpoint 指向自己的转发服务即可。
 */
public final class WebhookOpsAlert implements OpsAlert, AutoCloseable {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);
    private static final int DEFAULT_RETRIES = 2;
    private static final Duration DEFAULT_DEDUPE_WINDOW = Duration.ofSeconds(30);
    private static final int QUEUE_CAPACITY = 256;
    private static final int RECENT_LIMIT = 1000;

    private final URI endpoint;
    private final HttpClient client;
    private final Duration timeout;
    private final int retries;
    private final Duration dedupeWindow;
    private final Consumer<AlertRecord> onFailure;
    private final ExecutorService sender;
    private final Map<String, Instant> recent = new ConcurrentHashMap<>();

    public WebhookOpsAlert(URI endpoint) {
        this(endpoint, DEFAULT_TIMEOUT, DEFAULT_RETRIES, DEFAULT_DEDUPE_WINDOW, null);
    }

    public WebhookOpsAlert(URI endpoint, Duration timeout, int retries,
                           Duration dedupeWindow, Consumer<AlertRecord> onFailure) {
        this.endpoint = Objects.requireNonNull(endpoint, "webhook URL 不能为空");
        this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        this.retries = Math.max(0, retries);
        this.dedupeWindow = dedupeWindow == null ? DEFAULT_DEDUPE_WINDOW : dedupeWindow;
        this.onFailure = onFailure;
        this.client = HttpClient.newBuilder().connectTimeout(this.timeout).build();
        this.sender = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
            Thread t = new Thread(runnable, "agent-ops-alert");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void send(Severity severity, String event, String message, Map<String, Object> context) {
        Instant now = Instant.now();
        String key = severity + "|" + event + "|" + message;
        Instant last = recent.get(key);
        if (last != null && !last.isBefore(now.minus(dedupeWindow))) {
            return;   // 同一事件在窗口内已经报过，不再刷屏
        }
        if (recent.size() > RECENT_LIMIT) {
            recent.entrySet().removeIf(e -> e.getValue().isBefore(now.minus(dedupeWindow)));
        }
        recent.put(key, now);

        AlertRecord record = new AlertRecord(now, severity, event, message,
                context == null ? Map.of() : Map.copyOf(context));
        try {
            sender.submit(() -> deliver(record));
        } catch (RejectedExecutionException e) {
            // 队列满了也不能抛回业务：本地错误流是最后一道兜底。
            System.err.println("[agent-ops-alert] 发送队列已满，丢弃告警: " + event);
        }
    }

    private void deliver(AlertRecord record) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("at", record.at());
        payload.put("severity", record.severity());
        payload.put("event", record.event());
        payload.put("message", record.message());
        payload.put("context", record.context());
        String body = Json.write(payload);

        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(endpoint)
                        .timeout(timeout)
                        .header("Content-Type", "application/json; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                int code = response.statusCode();
                if (code >= 200 && code < 300) {
                    return;
                }
                if (attempt == retries) {
                    notifyFailure(record, "HTTP " + code);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                notifyFailure(record, "线程中断");
                return;
            } catch (Exception e) {
                if (attempt == retries) {
                    notifyFailure(record, e.toString());
                }
            }
        }
    }

    private void notifyFailure(AlertRecord record, String reason) {
        System.err.println("[agent-ops-alert] 投递失败(" + reason + "): "
                + record.event() + " - " + record.message());
        if (onFailure != null) {
            try {
                onFailure.accept(record);
            } catch (Exception ignored) {
                // 连失败回调都炸了也不能影响发送线程
            }
        }
    }

    @Override
    public void close() {
        sender.shutdown();
        try {
            if (!sender.awaitTermination(2, TimeUnit.SECONDS)) {
                sender.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sender.shutdownNow();
        }
    }
}
