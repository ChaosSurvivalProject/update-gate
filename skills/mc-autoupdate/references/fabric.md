# Fabric 1.21.11 客户端模组：内置更新模块

Fabric 客户端模组更新与服务端不同：运行在玩家客户端，**不能静默替换正在使用的 jar**（类已加载，替换会出问题），也不应打扰玩家。默认策略：**启动自检 + 每周四中午 12:00 检查 + 发现新版本下载到临时目录 + 提示玩家重启生效**。

> 安全红线（客户端尤其重要）：
> - **绝不内置 Token**；manifest 从固定地址拉取（如项目 GitHub Pages 的 `updates/latest.json`），元数据直连，不走第三方代理。
> - **只走 HTTPS**；第三方镜像不带任何凭证；日志不打印完整 URL 的签名参数。
> - **不静默安装/替换**：下载到 `mods/.update/`，校验通过后提示玩家，由玩家重启游戏时生效（可选自动移动到 `mods/` 并明确告知）。
> - 目标 MC 版本为 **1.21.11**；门禁只提示，禁止自动跨 minor/major 强升。

## 1. Gradle 配置（fabric-loom，Minecraft 1.21.11）

```kotlin
// build.gradle.kts
plugins {
    id("fabric-loom") version "1.9-SNAPSHOT"
    id("java")
}
version = "1.4.2"
group = "com.example.mymod"

repositories { mavenCentral() }

dependencies {
    minecraft("com.mojang:minecraft:1.21.11")
    mappings("net.fabricmc:yarn:1.21.11+build.1:v2")
    modImplementation("net.fabricmc:fabric-loader:0.16.9")
    implementation("com.google.code.gson:gson:2.11.0") // manifest 解析
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}
```

`fabric.mod.json`（确认版本域含 1.21.11）：

```json
{
  "schemaVersion": 1,
  "id": "mymod",
  "version": "1.4.2",
  "name": "MyMod",
  "environment": "client",
  "entrypoints": { "client": ["com.example.mymod.MyModClient"] },
  "depends": { "minecraft": ">=1.21.11 <1.22", "fabricloader": ">=0.16" }
}
```

## 2. ClientUpdateChecker.java（@Environment(CLIENT)）

```java
package com.example.mymod.update;

import net.fabricmc.api.Environment;
import net.fabricmc.api.EnvType;
import com.google.gson.*;
import java.net.http.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Timer;
import java.util.TimerTask;

/** Fabric 客户端更新检查：启动自检 + 每周四 12:00 + 下载校验 + 提示重启。 */
@Environment(EnvType.CLIENT)
public final class ClientUpdateChecker {
    private final String manifestUrl;   // 固定地址，直连，无 Token
    private final String currentVersion;
    private final Path modsDir;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL).timeout(Duration.ofSeconds(15)).build();

    public ClientUpdateChecker(String manifestUrl, String currentVersion, Path modsDir) {
        this.manifestUrl = manifestUrl;
        this.currentVersion = currentVersion;
        this.modsDir = modsDir;
    }

    /** onInitializeClient 调用：自检一次 + 注册周四 12:00 定时任务。 */
    public void start() {
        checkOnce();
        scheduleWeeklyThursdayNoon();
    }

    /** 计算下一次周四 12:00 的毫秒延迟，周期 7 天（Timer 使用本地时区，按需改 Asia/Shanghai）。 */
    private void scheduleWeeklyThursdayNoon() {
        java.util.Calendar now = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        java.util.Calendar next = (java.util.Calendar) now.clone();
        next.set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.THURSDAY);
        next.set(java.util.Calendar.HOUR_OF_DAY, 12);
        next.set(java.util.Calendar.MINUTE, 0);
        next.set(java.util.Calendar.SECOND, 0);
        next.set(java.util.Calendar.MILLISECOND, 0);
        if (!next.after(now)) next.add(java.util.Calendar.WEEK_OF_YEAR, 1);
        long delay = next.getTimeInMillis() - now.getTimeInMillis();
        long week = 7L * 24 * 60 * 60 * 1000;
        new Timer("mymod-update", true).scheduleAtFixedRate(new TimerTask() {
            @Override public void run() { checkOnce(); }
        }, delay, week);
    }

    /** 检查一次：拉 manifest → 版本门禁 → 下载校验 → 提示。 */
    public void checkOnce() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(manifestUrl)).GET().build();
            String body = http.send(req, HttpResponse.BodyHandlers.ofString()).body();
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject ch = root.getAsJsonArray("channels").get(0).getAsJsonObject();
            JsonObject target = ch.getAsJsonObject("target");
            String version = target.get("version").getAsString();
            String minecraft = target.has("minecraft") ? target.get("minecraft").getAsString() : "1.21.11";
            // 简化门禁：仅同 major.minor 内允许更新；跨 minor/major 只提示，不自动下载
            if (!isSameMinor(currentVersion, version) || !isSameMinor(minecraft, "1.21.11")) {
                System.out.println("[MyMod] 新版本 " + version + " 需要不同 MC/大版本，请手动更新，不会自动跨版本。");
                return;
            }
            if (!isGreater(version, currentVersion)) return; // 已是最新或更低
            JsonObject asset = ch.getAsJsonArray("assets").get(0).getAsJsonObject();
            String url = asset.get("downloadUrl").getAsString();
            String sha = asset.get("sha256").getAsString();
            String fileName = asset.get("fileName").getAsString();

            Path stageDir = modsDir.resolve(".update");
            Files.createDirectories(stageDir);
            Path part = stageDir.resolve(fileName + ".part");
            HttpResponse<Path> res = http.send(
                    HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofFile(part));
            if (res.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + res.statusCode());
            String actual = sha256(part);
            if (!actual.equalsIgnoreCase(sha)) {
                Files.deleteIfExists(part);
                System.out.println("[MyMod] SHA256_MISMATCH，已删除，稍后重试。");
                return;
            }
            Path ready = stageDir.resolve(fileName);
            try { Files.move(part, ready, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.io.IOException e) { Files.move(part, ready, StandardCopyOption.REPLACE_EXISTING); }
            // 提示玩家（实际接入项目聊天/提示 UI）；不静默替换 mods/ 下运行中的 jar
            System.out.println("[MyMod] 新版本 " + version + " 已下载并校验，重启游戏后生效。");
            // 如需自动移动到 mods/：Files.move(ready, modsDir.resolve(fileName), REPLACE_EXISTING);
            // 但必须明确告知玩家“重启生效”，且仅在游戏关闭时安全。
        } catch (Exception e) {
            System.out.println("[MyMod] 更新检查失败（NETWORK/SHA256），下次再说：" + e.getMessage());
        }
    }

    // --- 版本工具（简化版，仅比较 major/minor/patch） ---
    private static int[] parse(String v) {
        String core = v.replaceAll("\\+.*", "").split("-")[0];
        String[] p = core.split("\\."); int[] r = new int[3];
        for (int i = 0; i < 3 && i < p.length; i++) r[i] = Integer.parseInt(p[i].replaceAll("\\D", ""));
        return r;
    }
    private static boolean isSameMinor(String a, String b) {
        int[] x = parse(a), y = parse(b); return x[0] == y[0] && x[1] == y[1];
    }
    private static boolean isGreater(String a, String b) {
        int[] x = parse(a), y = parse(b);
        for (int i = 0; i < 3; i++) if (x[i] != y[i]) return x[i] > y[i];
        return false;
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

## 3. 接入主类

```java
// com.example.mymod.MyModClient
@Environment(EnvType.CLIENT)
public class MyModClient implements ModInitializer {
    @Override public void onInitializeClient() {
        Path modsDir = Paths.get("mods");
        new ClientUpdateChecker(
            "https://<user>.github.io/<repo>/updates/latest.json",
            "1.4.2", modsDir).start();
    }
}
```

## 4. 提示玩家（中英双语）

接入目标项目的聊天/提示 UI 时，用如下文案（资源键或直接字符串）：
- 发现可更新：`发现新版本 %s，已下载，重启游戏后生效。` / `New version %s downloaded; restart the game to apply.`
- 跨版本：`新版本需要不同游戏版本，请手动更新（不会自动跨版本）。` / `New version targets a different Minecraft version; update manually (no cross-version auto update).`
- 校验失败：`更新包校验失败，已删除，稍后重试。` / `Update checksum failed; removed and will retry.`

## 5. 验证清单

- [ ] `./gradlew build` 零警告（Java 21 toolchain + fabric-loom）
- [ ] `fabric.mod.json` 版本域含 `>=1.21.11 <1.22`，`environment: client`
- [ ] 启动自检一次 + 每周四 12:00 定时（`Asia/Shanghai`）
- [ ] 跨 minor/major 只提示、不自动下载；同 minor 内才更新
- [ ] sha256 校验失败删除；下载到 `.part` 后原子移动；不静默替换运行中的 jar
- [ ] 无 Token、只走 HTTPS、文案中英双语、注释简体中文
