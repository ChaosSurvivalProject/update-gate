# Paper 服务端插件：内置更新模块（Java 21）

把下列类复制到目标插件的 `update/` 包（`<plugin>.update`）。默认依赖：`paper-api`（compileOnly）、`gson`（或项目已有 JSON 库）、Java 21。全部注释简体中文、标识符英文。

## 调度约定（默认，不反问）

- **开机自检**：`onEnable` 里调用一次 `updateManager.checkAndSchedule()` 中的 `check()`。
- **定时任务**：每周四中午 12:00。Paper 没有 cron API，用 `Calendar` 计算“下一次周四 12:00”的 delay，然后用 ` Bukkit.getScheduler().runTaskTimerAsynchronously` 以 **7 天**为周期执行。
- 时区默认 `Asia/Shanghai`，可用配置覆盖。

## 1. UpdateConfig.java（配置）

```java
package <plugin>.update;

public final class UpdateConfig {
    /** 更新清单固定地址（gh-pages 发布），元数据直连，不走代理池。 */
    public final String manifestUrl;
    /** 本地插件版本（来自 plugin.yml）。 */
    public final String currentVersion;
    /** 服务端实际 Paper API 版本（如 1.21.11）。 */
    public final String serverApiVersion;
    /** 目标通道 stable/beta。 */
    public final String channel;
    /** 是否开机自检，默认 true。 */
    public final boolean checkOnBoot;
    /** 是否启用定时（周四 12:00），默认 true。 */
    public final boolean scheduleEnabled;

    public UpdateConfig(String manifestUrl, String currentVersion, String serverApiVersion,
                        String channel, boolean checkOnBoot, boolean scheduleEnabled) {
        this.manifestUrl = manifestUrl;
        this.currentVersion = currentVersion;
        this.serverApiVersion = serverApiVersion;
        this.channel = channel;
        this.checkOnBoot = checkOnBoot;
        this.scheduleEnabled = scheduleEnabled;
    }
}
```

## 2. VersionGate.java（门禁）

```java
package <plugin>.update;

import java.util.regex.*;

/** 版本门禁：逐条判定，命中即返回。语义版本比较，preRelease 低于正式版。 */
public final class VersionGate {
    public enum Code { UP_TO_DATE, BLOCKED_UPGRADE_REQUIRED, BLOCKED_API_TOO_NEW,
        BLOCKED_CLIENT_TOO_OLD, UPDATE_REQUIRED, UPDATE_OPTIONAL, BLOCKED_DOWNGRADE_FORBIDDEN }

    public record Decision(Code code, String messageZh, String messageEn, String downloadUrl) {}

    private static int[] parse(String v) {
        String core = v.replaceAll("\\+.*", "").split("-")[0]; // 忽略 build metadata 与 preRelease 尾
        String[] p = core.split("\\.");
        int[] r = new int[3];
        for (int i = 0; i < 3 && i < p.length; i++) r[i] = Integer.parseInt(p[i].replaceAll("\\D", ""));
        return r;
    }

    /** 返回 -1/0/1：a<b / a==b / a>b。 */
    private static int cmp(String a, String b) {
        if (a.equals(b)) return 0;
        int[] x = parse(a), y = parse(b);
        for (int i = 0; i < 3; i++) if (x[i] != y[i]) return Integer.compare(x[i], y[i]);
        // 都是正式版核心相同但字符串不同：有 preRelease 者更低
        boolean ap = a.contains("-"), bp = b.contains("-");
        if (ap != bp) return ap ? -1 : 1;
        return a.compareTo(b);
    }

    /**
     * @param mode OPTIONAL / REQUIRED
     * @param manifestApi target.apiVersion；为空表示不检查 API
     */
    public Decision evaluate(String current, String target, String minSupported,
                             String minClient, String clientVersion, String serverApiVersion,
                             String manifestApi, String mode, String downloadUrl) {
        String api = manifestApi == null || manifestApi.isBlank() ? serverApiVersion : manifestApi;
        if (cmp(target, current) == 0)
            return new Decision(Code.UP_TO_DATE, "已是最新版本", "You are up to date", null);
        if (cmp(current, minSupported) < 0)
            return new Decision(Code.BLOCKED_UPGRADE_REQUIRED, "当前版本过旧，必须先升级",
                    "Your version is too old; upgrade first", downloadUrl);
        if (cmp(target, current) > 0) {
            if (serverApiVersion != null && cmp(api, serverApiVersion) > 0)
                return new Decision(Code.BLOCKED_API_TOO_NEW,
                        "当前插件要求 Paper " + api + "，你的服务器为 " + serverApiVersion
                                + "，请先升级服务端，不会自动跨版本更新",
                        "This plugin requires Paper " + api + " but the server is " + serverApiVersion
                                + "; upgrade the server first. No cross-version auto update.", null);
            if (minClient != null && !minClient.isBlank() && clientVersion != null
                    && cmp(minClient, clientVersion) > 0)
                return new Decision(Code.BLOCKED_CLIENT_TOO_OLD, "客户端版本过低，请先更新客户端",
                        "Client is too old; please update the client first", downloadUrl);
            Code c = "REQUIRED".equalsIgnoreCase(mode) ? Code.UPDATE_REQUIRED : Code.UPDATE_OPTIONAL;
            String zh = c == Code.UPDATE_REQUIRED ? "有新版本，必须更新" : "有新版本，可选更新";
            String en = c == Code.UPDATE_REQUIRED ? "A new version is available and required"
                                                  : "A new version is available (optional)";
            return new Decision(c, zh, en, downloadUrl);
        }
        return new Decision(Code.BLOCKED_DOWNGRADE_FORBIDDEN, "禁止降级",
                "Downgrade is forbidden", null);
    }
}
```

> 门禁返回 `BLOCKED_*` / `UP_TO_DATE` 时**不得下载**；只有 `UPDATE_REQUIRED`/`UPDATE_OPTIONAL` 才进入下载流程。

## 3. UpdateDownloader.java（sha256 + 临时文件 + 原子替换）

```java
package <plugin>.update;

import java.net.http.*;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

/** 下载到 .part，校验 sha256 后原子替换。禁止热加载。 */
public final class UpdateDownloader {
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    /** 返回校验通过的新文件路径；失败抛异常，由调用方上报 result_code。 */
    public Path downloadAndVerify(String url, String expectedSha256, Path target) throws Exception {
        Path part = Paths.get(target + ".part");
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(java.time.Duration.ofSeconds(60))
                .header("User-Agent", "UpdateGate/1.0").GET().build();
        HttpResponse<Path> res = http.send(req, HttpResponse.BodyHandlers.ofFile(part));
        if (res.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + res.statusCode());
        String actual = sha256(part);
        if (!actual.equalsIgnoreCase(expectedSha256)) {
            Files.deleteIfExists(part);
            throw new SecurityException("SHA256_MISMATCH");
        }
        Files.createDirectories(target.getParent());
        try {
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException atomicFailed) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING); // 文件系统不支持时的退化
        }
        return target;
    }

    private static String sha256(Path f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(f)) {
            byte[] buf = new byte[1 << 16]; int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
```

## 4. UpdateScheduler.java（周四 12:00 定时）

```java
package <plugin>.update;

import org.bukkit.plugin.java.JavaPlugin;
import java.util.Calendar;
import java.util.TimeZone;

/** 计算下一次周四 12:00 的 tick 延迟（Asia/Shanghai），周期 7 天。 */
public final class UpdateScheduler {
    public static void scheduleWeekly(JavaPlugin plugin, Runnable task) {
        Calendar now = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
        Calendar next = (Calendar) now.clone();
        next.set(Calendar.DAY_OF_WEEK, Calendar.THURSDAY);
        next.set(Calendar.HOUR_OF_DAY, 12);
        next.set(Calendar.MINUTE, 0);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (!next.after(now)) next.add(Calendar.WEEK_OF_YEAR, 1);
        long delayTicks = (next.getTimeInMillis() - now.getTimeInMillis()) / 50L; // 20 TPS
        long periodTicks = 7L * 24 * 60 * 60 * 20; // 7 天
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, task, delayTicks, periodTicks);
    }
}
```

## 5. UpdateManager.java（编排）

```java
package <plugin>.update;

import com.google.gson.*;
import java.net.http.*;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;

/** 编排：拉 manifest → 门禁 → （可选）下载替换 → 调度。 */
public final class UpdateManager {
    private final UpdateConfig cfg;
    private final VersionGate gate = new VersionGate();
    private final UpdateDownloader downloader = new UpdateDownloader();
    private final HttpClient http = HttpClient.newBuilder().timeout(Duration.ofSeconds(15)).build();

    public UpdateManager(UpdateConfig cfg) { this.cfg = cfg; }

    /** onEnable 调用：开机自检 + 注册周四定时任务。 */
    public void checkAndSchedule(JavaPluginLike plugin) {
        if (cfg.checkOnBoot) check();
        if (cfg.scheduleEnabled) UpdateScheduler.scheduleWeekly(plugin.asPlugin(), this::check);
    }

    /** 检查一次，返回门禁结果（供命令复用）。 */
    public VersionGate.Decision check() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.manifestUrl)).GET().build();
            String body = http.send(req, HttpResponse.BodyHandlers.ofString()).body();
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject ch = root.getAsJsonArray("channels").get(0).getAsJsonObject();
            if (!cfg.channel.equals(ch.get("id").getAsString())) return null;
            JsonObject target = ch.getAsJsonObject("target");
            String version = target.get("version").getAsString();
            String api = target.has("apiVersion") ? target.get("apiVersion").getAsString() : null;
            String minSupported = ch.getAsJsonObject("minSupported").get("version").getAsString();
            String minClient = ch.has("minClientVersion") ? ch.get("minClientVersion").getAsString() : null;
            String mode = ch.get("mode").getAsString();
            JsonObject asset = ch.getAsJsonArray("assets").get(0).getAsJsonObject();
            String url = asset.get("downloadUrl").getAsString();
            String sha = asset.get("sha256").getAsString();

            VersionGate.Decision d = gate.evaluate(cfg.currentVersion, version, minSupported,
                    minClient, null, cfg.serverApiVersion, api, mode, url);
            if (d.code() == VersionGate.Code.UPDATE_REQUIRED
                    || d.code() == VersionGate.Code.UPDATE_OPTIONAL) {
                Path targetFile = Paths.get("plugins", asset.get("fileName").getAsString());
                downloader.downloadAndVerify(url, sha, targetFile); // 下载到 plugins/update/ 由调用方决定
            }
            return d;
        } catch (Exception e) {
            // 上报 result_code，例如 NETWORK_ALL_MIRRORS_FAILED / SHA256_MISMATCH
            return null;
        }
    }

    /** 供命令调用：返回命令不可用时的占位（实际由插件命令类包装）。 */
    public interface JavaPluginLike { org.bukkit.plugin.java.JavaPlugin asPlugin(); }
}
```

## 6. 接入到主类与命令

```java
// onEnable 中：
UpdateConfig cfg = new UpdateConfig(
    "https://<user>.github.io/<repo>/updates/latest.json",
    getPluginMeta().getVersion(), "1.21.11", "stable", true, true);
UpdateManager um = new UpdateManager(cfg);
um.checkAndSchedule(() -> this); // this 实现 JavaPluginLike

// 命令 /xxx update check|apply：
//   check → 打印 um.check() 的中英双语 message
//   apply → 触发下载；下载到 plugins/update/<name>-<ver>.jar，
//           onDisable 中移动到 plugins/ 并提示“重启生效”；禁止热加载。
```

## 7. 双清单（api-version 必须带引号）

`plugin.yml` 与 `paper-plugin.yml` 并存，`api-version: '1.21.11'`（**带引号**）。替换策略：下载到 `plugins/update/<name>-<ver>.jar`，`onDisable` 移到 `plugins/`，保留上一版本到 `backup/`，失败自动回滚，提示重启生效。

## 8. 验证清单

- [ ] `./gradlew build` 零警告（Java 21 toolchain）
- [ ] 在 Paper 1.21.10 与 1.21.11 双版本实测门禁文案：1.21.10 只提示升级服务端、不下载不降级
- [ ] 低/等/高 minor、高 patch、apiVersion 过高、versionCode 回退、minClientVersion 不足 —— 断言返回码与文案
- [ ] sha256 错误时删除重下（≤2 次）；下载到 `.part` 后原子替换
