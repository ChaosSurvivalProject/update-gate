package com.updategate.update;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 可观测上报（对齐第 7 节）。
 *
 * <p>上报字段：instanceId(匿名 UUID)、from→to、result_code、mirror_used、
 * duration_ms、bytes、fail_reason；<b>不上报 IP 与账号信息</b>。</p>
 *
 * <p>result_code 枚举：SUCCESS / VERSION_GATE_BLOCKED / NETWORK_ALL_MIRRORS_FAILED /
 * SHA256_MISMATCH / SIGNATURE_INVALID / DISK_FULL / INSTALL_REJECTED / ROLLED_BACK。</p>
 *
 * <p>设计为「尽力而为、失败静默」：上报失败不影响主流程；默认仅本地日志，可配置远端上报地址。</p>
 */
public final class UpdateReporter {

    private final String instanceId;
    private final String endpoint;
    private final HttpClient http;
    private final ExecutorService executor;

    public UpdateReporter(String endpoint) {
        this.instanceId = UUID.randomUUID().toString();
        this.endpoint = endpoint == null ? "" : endpoint;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "update-reporter");
            t.setDaemon(true);
            return t;
        });
    }

    /** 获取本实例匿名 ID（可用于灰度哈希，避免上报 IP）。 */
    public String getInstanceId() { return instanceId; }

    /** 按灰度百分比计算本实例是否命中放量（基于 instanceId 哈希）。 */
    public boolean isInRollout(int rolloutPercent) {
        if (rolloutPercent >= 100) return true;
        if (rolloutPercent <= 0) return false;
        int h = Math.floorMod(instanceId.hashCode(), 100);
        return h < rolloutPercent;
    }

    /** 上报一次更新事件（异步、非阻塞）。 */
    public void report(Report report) {
        String json = toJson(report);
        if (endpoint.isEmpty()) {
            // 默认仅日志（生产可配置 endpoint 远端聚合）
            System.out.println("[update-reporter] " + json);
            return;
        }
        executor.submit(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .timeout(Duration.ofSeconds(5))
                        .build();
                http.send(req, HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) {
                // 上报失败静默丢弃，不影响主流程
            }
        });
    }

    private String toJson(Report r) {
        return "{"
                + "\"instanceId\":\"" + instanceId + "\""
                + ",\"from\":\"" + n(r.from) + "\""
                + ",\"to\":\"" + n(r.to) + "\""
                + ",\"result_code\":\"" + r.resultCode + "\""
                + ",\"mirror_used\":\"" + n(r.mirrorUsed) + "\""
                + ",\"duration_ms\":" + r.durationMs
                + ",\"bytes\":" + r.bytes
                + ",\"fail_reason\":\"" + n(r.failReason) + "\""
                + "}";
    }

    private static String n(String s) { return s == null ? "" : s; }

    /** 上报数据载体。 */
    public static final class Report {
        public final String from;
        public final String to;
        public final ResultCode resultCode;
        public final String mirrorUsed;
        public final long durationMs;
        public final long bytes;
        public final String failReason;

        public Report(String from, String to, ResultCode resultCode, String mirrorUsed,
                      long durationMs, long bytes, String failReason) {
            this.from = from;
            this.to = to;
            this.resultCode = resultCode;
            this.mirrorUsed = mirrorUsed;
            this.durationMs = durationMs;
            this.bytes = bytes;
            this.failReason = failReason;
        }
    }
}
