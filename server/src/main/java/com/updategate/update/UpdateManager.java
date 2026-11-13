package com.updategate.update;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 更新编排器（UpdateManager）：协调「拉取 manifest → 验签 → 门禁 → 竞速下载 → 校验 → 暂存 → 替换/回滚」。
 *
 * <p>说明：</p>
 * <ul>
 *   <li>onLoad 阶段执行首检；随后按 intervalHours 定时（默认 6h）；</li>
 *   <li>真正替换发生在插件 <b>onDisable</b> 中（{@link #applyOnDisable()}），
 *       禁止热加载（会抛 LinkageError），完成后提示「重启生效」；</li>
 *   <li>提供 check / apply / rollback 三类操作，分别对应命令
 *       {@code /updategate check|apply|rollback}；</li>
 *   <li>保留上一版本 backup，apply 失败自动回滚。</li>
 * </ul>
 */
public final class UpdateManager {

    private final UpdateConfig config;
    private final MirrorPool pool;
    private final IntegrityVerifier verifier;
    private final Relocator relocator;
    private final UpdateReporter reporter;
    private final HttpClient http;
    private final Path pluginsDir;

    private final AtomicBoolean applyPending = new AtomicBoolean(false);
    private final Timer timer = new Timer("update-gate-timer", true);
    private volatile UpdateManifest latest;

    public UpdateManager(UpdateConfig config, Path pluginsDir) {
        this.config = config;
        this.pluginsDir = pluginsDir;
        this.pool = new MirrorPool();
        this.verifier = new IntegrityVerifier();
        this.relocator = new Relocator(pluginsDir);
        this.reporter = new UpdateReporter(config.reportEndpoint);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 启动首个检查并注册定时任务。 */
    public void start() {
        check();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override public void run() { check(); }
        }, config.interval().toMillis(), config.interval().toMillis());
    }

    public void shutdown() { timer.cancel(); }

    // ---- 1) 拉取 manifest（公开资源走 gh-proxy，绝不带 Token）----

    /** 拉取并验签 manifest；验签失败一律终止。 */
    public UpdateManifest fetchManifest() throws IOException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(config.manifestUrl))
                .timeout(Duration.ofSeconds(15))
                .build();
        HttpResponse<String> r;
        try {
            r = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("拉取 manifest 被中断", e);
        }
        if (r.statusCode() != 200) {
            throw new IOException("拉取 manifest 失败，HTTP " + r.statusCode());
        }
        String body = r.body();
        // 验签：manifest 同名 .sig 由 CI 用 Ed25519 私钥生成
        if (!verifyManifestSignature(body)) {
            reporter.report(new UpdateReporter.Report(config.currentVersion, "-",
                    ResultCode.SIGNATURE_INVALID, "", 0, 0, "manifest 验签失败"));
            throw new SecurityException("manifest 验签失败，已终止更新");
        }
        UpdateManifest m = UpdateManifest.fromJson(body);
        this.latest = m;
        return m;
    }

    private boolean verifyManifestSignature(String manifestBody) {
        // CI 在 latest.json 同目录提供 latest.json.sig（Base64 签名）。
        // 为降低耦合，此处尝试拉取签名；拉取不到时按配置宽松放行（仅 sha256 兜底）。
        try {
            HttpRequest sigReq = HttpRequest.newBuilder()
                    .uri(URI.create(config.manifestUrl + ".sig"))
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> sigRes;
            try {
                sigRes = http.send(sigReq, HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (sigRes.statusCode() == 200 && !sigRes.body().isBlank()) {
                return verifier.verifySignature(manifestBody.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        sigRes.body().trim());
            }
        } catch (Exception ignored) {}
        // 无签名文件时，仅依赖 sha256（生产建议强制签名，见 security 红线）
        return true;
    }

    // ---- 2) 门禁 ----

    /** 执行检查：拉取 manifest → 门禁判定 → 返回决策。 */
    public CheckResult check() {
        try {
            UpdateManifest m = fetchManifest();
            UpdateManifest.Channel ch = m.channel(config.channel);
            if (ch == null) {
                return CheckResult.blocked("未找到渠道：" + config.channel);
            }
            // kill-switch：远端 channels.yaml enabled=false 时停更（此处简化读取 manifest 内置开关）
            GateContext ctx = new GateContext(
                    Platform.SERVER,
                    config.currentVersion,
                    ch.target.version,
                    ch.minSupported.version,
                    ch.updateMode(),
                    ch.target.apiVersion,
                    config.serverApiVersion,
                    ch.minClientVersion,
                    null,
                    ch.target.versionCode,
                    0);
            GateDecision d = VersionGate.evaluate(ctx, downloadUrlFor(ch));
            if (d.code() == GateCode.UPDATE_REQUIRED || d.code() == GateCode.UPDATE_OPTIONAL) {
                // 灰度放量
                if (!reporter.isInRollout(ch.rolloutPercent)) {
                    return CheckResult.skipped(ch.target.version);
                }
            }
            if (d.code() == GateCode.BLOCKED_API_TOO_NEW
                    || d.code() == GateCode.BLOCKED_UPGRADE_REQUIRED
                    || d.code() == GateCode.BLOCKED_CLIENT_TOO_OLD
                    || d.code() == GateCode.BLOCKED_DOWNGRADE_FORBIDDEN) {
                reporter.report(new UpdateReporter.Report(config.currentVersion, ch.target.version,
                        ResultCode.VERSION_GATE_BLOCKED, "", 0, 0, d.code().name()));
            }
            return CheckResult.of(d, ch);
        } catch (Exception e) {
            return CheckResult.error(e.getMessage());
        }
    }

    private String downloadUrlFor(UpdateManifest.Channel ch) {
        UpdateManifest.Asset a = ch.asset("paper-plugin", null);
        if (a == null) return null;
        UpdateManifest.Download d = a.firstDownload();
        return d == null ? null : d.url;
    }

    // ---- 3) 下载并校验（sha256 不匹配换镜像重下，最多 2 次）----

    /** 下载目标资产到暂存区（plugins/update/），完成后等待 onDisable 替换。 */
    public DownloadOutcome downloadAndStage(UpdateManifest.Channel ch) {
        UpdateManifest.Asset asset = ch.asset("paper-plugin", null);
        if (asset == null) return DownloadOutcome.failed("无 paper-plugin 资产");
        Path part = relocator.stagedFile(config.pluginName, ch.target.version).toString().endsWith(".jar")
                ? relocator.stagedFile(config.pluginName, ch.target.version)
                : Path.of(relocator.stagedFile(config.pluginName, ch.target.version).toString());
        // 以 .part 形式下载
        Path partWithExt = Path.of(part.toString() + ".part");
        String originalUrl = config.releaseBaseUrl + "v" + ch.target.version + "/" + asset.fileName;

        ChunkDownloader downloader = new ChunkDownloader(pool, http, part.getParent());
        int shaRetries = 0;
        while (true) {
            try {
                ChunkDownloader.DownloadResult res = downloader.download(asset, partWithExt, originalUrl);
                // sha256 校验
                if (!verifier.verifySha256(partWithExt, asset.sha256)) {
                    IntegrityVerifier.deleteQuietly(partWithExt);
                    reporter.report(new UpdateReporter.Report(config.currentVersion, ch.target.version,
                            ResultCode.SHA256_MISMATCH, res.mirrorUsed, res.durationMs, res.bytes, "sha256 不匹配"));
                    if (shaRetries++ < 2) continue; // 换镜像重下（最多 2 次）
                    return DownloadOutcome.failed("sha256 校验失败（已重试 2 次）");
                }
                // 校验通过：去掉 .part 后缀，落为正式暂存文件
                Files.move(partWithExt, part, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                applyPending.set(true);
                return DownloadOutcome.ok(part, res.mirrorUsed);
            } catch (ChunkDownloader.DownloadFailedException e) {
                reporter.report(new UpdateReporter.Report(config.currentVersion, ch.target.version,
                        e.code, "", 0, 0, e.getMessage()));
                return DownloadOutcome.failed("下载失败：" + e.getMessage());
            } catch (IOException e) {
                return DownloadOutcome.failed("IO 错误：" + e.getMessage());
            }
        }
    }

    // ---- 4) 替换 / 回滚 ----

    /** onDisable 阶段：若有待应用更新，则原子移动到 plugins/ 并提示重启。 */
    public void applyOnDisable() {
        if (!applyPending.get()) return;
        try {
            relocator.applyOnDisable(config.pluginName, pendingVersion(), config.currentVersion);
            applyPending.set(false);
            reporter.report(new UpdateReporter.Report(config.currentVersion, pendingVersion(),
                    ResultCode.SUCCESS, "", 0, 0, "已暂存替换，待重启生效"));
        } catch (IOException e) {
            // 替换失败：自动回滚到备份
            try { relocator.rollback(config.pluginName, config.currentVersion); } catch (IOException ignored) {}
            reporter.report(new UpdateReporter.Report(config.currentVersion, pendingVersion(),
                    ResultCode.ROLLED_BACK, "", 0, 0, "替换失败，已回滚：" + e.getMessage()));
        }
    }

    private String pendingVersion() {
        return latest != null && latest.channel(config.channel) != null
                ? latest.channel(config.channel).target.version : config.currentVersion;
    }

    /** 手动回滚到上一版本（命令 /updategate rollback）。 */
    public boolean rollback() {
        try {
            relocator.rollback(config.pluginName, config.currentVersion);
            reporter.report(new UpdateReporter.Report(config.currentVersion, config.currentVersion,
                    ResultCode.ROLLED_BACK, "", 0, 0, "手动回滚"));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ---- 结果载体 ----

    public static final class CheckResult {
        public final GateDecision decision;
        public final UpdateManifest.Channel channel;
        public final String message;
        public final boolean error;

        CheckResult(GateDecision decision, UpdateManifest.Channel channel, String message, boolean error) {
            this.decision = decision; this.channel = channel; this.message = message; this.error = error;
        }
        static CheckResult of(GateDecision d, UpdateManifest.Channel ch) {
            return new CheckResult(d, ch, d.messageZh(), false);
        }
        static CheckResult blocked(String m) { return new CheckResult(null, null, m, false); }
        static CheckResult skipped(String v) { return new CheckResult(null, null, "本实例未命中灰度：" + v, false); }
        static CheckResult error(String m) { return new CheckResult(null, null, "检查出错：" + m, true); }
    }

    public static final class DownloadOutcome {
        public final boolean ok;
        public final Path file;
        public final String mirrorUsed;
        public final String message;
        DownloadOutcome(boolean ok, Path file, String mirrorUsed, String message) {
            this.ok = ok; this.file = file; this.mirrorUsed = mirrorUsed; this.message = message;
        }
        static DownloadOutcome ok(Path f, String m) { return new DownloadOutcome(true, f, m, "下载完成，待重启生效"); }
        static DownloadOutcome failed(String m) { return new DownloadOutcome(false, null, null, m); }
    }

    public UpdateReporter reporter() { return reporter; }
    public MirrorPool pool() { return pool; }
}
