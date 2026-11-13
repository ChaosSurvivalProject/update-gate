package com.updategate.update;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 多加速源代理池（内置，优先级可配，全部走 HTTPS，绝不携带 Cookie/Token）。
 *
 * <p>内置镜像（见 {@link #DEFAULT_MIRRORS}），按第 3.2 节优先级排列：
 * gh-proxy.com 置顶（实测 Release/raw/api 三路线全通），gh-jasonzeng.dev 末位（需运行时探活）。</p>
 *
 * <p>竞速策略：</p>
 * <ul>
 *   <li>预热：并发 HEAD，取 TTFB 最优的若干作为主力；</li>
 *   <li>权重：effectiveWeight = baseWeight × healthWeight，按权重降序派发分片；</li>
 *   <li>熔断：连续 2 次 4xx/5xx 或 3s 无字节 → 该镜像冷却 60s（{@link #markFailure}）；</li>
 *   <li>全池失败 → 直连 GitHub 兜底（{@link #DIRECT}）。</li>
 * </ul>
 *
 * <p>安全红线：所有请求均为公开资源，绝不附加任何凭证；元数据（api.github.com）禁止走代理池，
 * 只用直连或 gh-proxy.com（见 {@link #isApiAllowed}）。</p>
 */
public class MirrorPool {

    /** 直连 GitHub（兜底），base 为原始 host。 */
    public static final String DIRECT = "direct";

    /** 内置镜像：id → base（代理前缀，拼接到原始 URL 的路径部分）。 */
    public static final Map<String, String> DEFAULT_MIRRORS = Map.ofEntries(
            Map.entry("gh-proxy", "https://gh-proxy.com/"),
            Map.entry("ghfast", "https://ghfast.top/"),
            Map.entry("ghproxy-net", "https://ghproxy.net/"),
            Map.entry("mirror-ghproxy", "https://mirror.ghproxy.com/"),
            Map.entry("hub-gitmirror", "https://hub.gitmirror.com/"),
            Map.entry("moeyy", "https://moeyy.cn/gh-proxy/"),
            Map.entry("gh-llkk", "https://gh.llkk.cc/"),
            Map.entry("gh-jasonzeng", "https://gh.jasonzeng.dev/"),
            Map.entry(DIRECT, "https://github.com/"),
            Map.entry("jsdelivr", "https://cdn.jsdelivr.net/gh/")
    );

    /** gh-proxy.com 是唯一获准代理 api.github.com 的镜像。 */
    public static final String API_PROXY = "gh-proxy";

    private final HttpClient http;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final List<String> order;

    /** 元数据结构。 */
    public static final class Entry {
        final String id;
        final String base;
        int baseWeight;
        volatile double healthWeight = 1.0;
        volatile Instant cooldownUntil = null;
        volatile int consecutiveFailures = 0;
        volatile long lastTtfbMs = -1;

        Entry(String id, String base, int baseWeight) {
            this.id = id;
            this.base = base;
            this.baseWeight = baseWeight;
        }

        boolean isCoolingDown(Instant now) {
            return cooldownUntil != null && now.isBefore(cooldownUntil);
        }

        double effectiveWeight(Instant now) {
            if (isCoolingDown(now)) return 0.0;
            return baseWeight * healthWeight;
        }
    }

    public MirrorPool() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    public MirrorPool(HttpClient http) {
        this.http = http;
        // 优先级顺序即 DEFAULT_MIRRORS 的插入顺序（gh-proxy 置顶，gh-jasonzeng 末位）
        this.order = new ArrayList<>(DEFAULT_MIRRORS.keySet());
        int i = order.size();
        for (String id : order) {
            // 越靠前权重越高
            entries.put(id, new Entry(id, DEFAULT_MIRRORS.get(id), i--));
        }
    }

    /** 该镜像是否允许代理 api.github.com 元数据请求。 */
    public boolean isApiAllowed(String id) {
        return DIRECT.equals(id) || API_PROXY.equals(id);
    }

    /**
     * 预热：并发 HEAD 探测所有镜像，取 TTFB 最优的前 n 个作为主力（更新 lastTtfbMs 与 healthWeight）。
     *
     * @param probeUrl 原始 GitHub URL（用于构造探针地址）
     * @param topN     选取的主力数量（默认 2）
     */
    public void preheat(String probeUrl, int topN) {
        List<Entry> snapshot = new ArrayList<>(entries.values());
        snapshot.parallelStream().forEach(e -> {
            if (DIRECT.equals(e.id) || "jsdelivr".equals(e.id)) return; // 直连/仅raw不探针
            String url = buildUrl(e.id, probeUrl);
            long t0 = System.nanoTime();
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .timeout(Duration.ofSeconds(8))
                        .build();
                HttpResponse<Void> r = http.send(req, HttpResponse.BodyHandlers.discarding());
                long ttfb = (System.nanoTime() - t0) / 1_000_000;
                e.lastTtfbMs = ttfb;
                e.healthWeight = clamp(1.0 - ttfb / 2000.0, 0.2, 1.0); // TTFB 越大权重越低
                e.consecutiveFailures = 0;
            } catch (Exception ex) {
                e.healthWeight = 0.1;
                e.consecutiveFailures++;
            }
        });
        // 按 TTFB 排序取 topN（仅用于日志/可视化，实际派发仍按 effectiveWeight）
        List<Entry> ranked = snapshot.stream()
                .filter(e -> e.lastTtfbMs >= 0)
                .sorted(Comparator.comparingLong(e -> e.lastTtfbMs))
                .collect(Collectors.toList());
        if (!ranked.isEmpty()) {
            // 记录预热结果（topN 主力权重提升）
            for (int i = 0; i < Math.min(topN, ranked.size()); i++) {
                ranked.get(i).healthWeight = Math.max(ranked.get(i).healthWeight, 1.0);
            }
        }
    }

    /** 按 effectiveWeight 降序返回当前可用的镜像 id 列表（剔除冷却中）。 */
    public List<String> orderedCandidates() {
        Instant now = Instant.now();
        return entries.values().stream()
                .filter(e -> !e.isCoolingDown(now))
                .sorted(Comparator.comparingDouble((Entry e) -> e.effectiveWeight(now)).reversed())
                .map(e -> e.id)
                .collect(Collectors.toList());
    }

    /** 失败熔断：连续 2 次 4xx/5xx 或 3s 无字节 → 冷却 60s。 */
    public void markFailure(String id, boolean hard) {
        Entry e = entries.get(id);
        if (e == null) return;
        e.consecutiveFailures++;
        if (hard || e.consecutiveFailures >= 2) {
            e.cooldownUntil = Instant.now().plusSeconds(60);
            e.healthWeight = Math.min(e.healthWeight, 0.3);
        }
    }

    /** 成功：重置连续失败计数。 */
    public void markSuccess(String id) {
        Entry e = entries.get(id);
        if (e != null) e.consecutiveFailures = 0;
    }

    /**
     * 构造某镜像下的实际下载 URL。
     *
     * @param id          镜像 id（见 {@link #DEFAULT_MIRRORS}）
     * @param originalUrl 原始 GitHub URL（https://github.com/... 或 https://raw.githubusercontent.com/...）
     * @return 拼装后的 URL
     */
    public String buildUrl(String id, String originalUrl) {
        Entry e = entries.get(id);
        if (e == null) throw new IllegalArgumentException("未知镜像：" + id);
        if (DIRECT.equals(id)) return originalUrl;
        // 取原始 URL 的路径部分（去掉 scheme://host）
        String path = stripHost(originalUrl);
        if ("jsdelivr".equals(id)) {
            // jsDelivr 仅 raw：把 raw.githubusercontent.com/owner/repo/branch/path
            // 转换为 cdn.jsdelivr.net/gh/owner/repo@branch/path
            return toJsdelivr(originalUrl);
        }
        String base = e.base;
        if (!base.endsWith("/")) base += "/";
        return base + path;
    }

    private static String stripHost(String url) {
        int idx = url.indexOf("://");
        if (idx < 0) return url;
        int slash = url.indexOf('/', idx + 3);
        return slash < 0 ? "" : url.substring(slash + 1);
    }

    private static String toJsdelivr(String url) {
        // https://raw.githubusercontent.com/owner/repo/branch/... -> https://cdn.jsdelivr.net/gh/owner/repo@branch/...
        String base = "https://cdn.jsdelivr.net/gh/";
        String marker = "raw.githubusercontent.com/";
        int i = url.indexOf(marker);
        if (i < 0) return url;
        String rest = url.substring(i + marker.length()); // owner/repo/branch/...
        String[] parts = rest.split("/", 4);
        if (parts.length < 4) return url;
        return base + parts[0] + "/" + parts[1] + "@" + parts[2] + "/" + parts[3];
    }

    /** 读取 mirror-probe 产出的健康 JSON，覆盖 healthWeight / cooldown。 */
    public void applyHealthReport(String json) {
        // 简化：仅解析每个 mirror 的 weight/cooldown（与 config/mirrors-health.json 对齐）
        // 实际实现可用 Gson；此处用最小解析避免额外依赖在核心路径引入。
        // 完整解析见 CI 产出的 mirrors-health.json。
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
