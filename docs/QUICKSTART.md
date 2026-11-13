# 接入 3 步

## 一、服务端（Paper 插件）接入 3 步

1. **引入产物**：将 CI 构建出的 `UpdateGate-<ver>.jar` 放入服务端的 `plugins/` 目录。
   插件在 `onLoad` 阶段执行首检，`onEnable` 启动每 6h 定时检查，`onDisable` 原子替换并提示重启。
2. **配置来源（可选）**：默认从 `gh-pages/updates/latest.json` 拉取 manifest（公开资源走 `gh-proxy.com`，绝不带 Token）。
   若你的组织/仓库名不同，修改 `server/src/main/java/com/updategate/UpdateConfig.java` 中的 `org / repo / manifestUrl / releaseBaseUrl` 后重新构建。
3. **使用命令**：
   - `/updategate check` —— 检查更新并打印门禁结果
   - `/updategate apply` —— 下载并暂存到 `plugins/update/`，提示重启生效
   - `/updategate rollback` —— 回滚到 `backup/` 中的上一版本

> 禁止热加载替换：插件不会在运行时替换自身（会抛 LinkageError），必须由管理员重启生效。

## 二、客户端（Android）接入 3 步

1. **依赖模块**：将 `android/` 作为独立 Gradle 工程（`cd android && ./gradlew assembleRelease`），
   或将 `com.updategate.android` 包下的 `update/` 源码与资源并入你的 App Module。
2. **清单与权限**：确保 `AndroidManifest.xml` 已声明
   `INTERNET / ACCESS_NETWORK_STATE / REQUEST_INSTALL_PACKAGES / FOREGROUND_SERVICE / FOREGROUND_SERVICE_DATA_SYNC`，
   并配置 `FileProvider`（`authorities=${applicationId}.fileprovider`，仅暴露 `updates/` 目录）。
3. **启动检查**：在 `Application.onCreate` 初始化 `UpdateApplication`（已内置 `ProcessLifecycleOwner` 6h 节流与 WorkManager 周期任务），
   主界面使用 `UpdateManager.check()` 拉取并验签 manifest，按 `GateDecision` 展示更新对话框。

## 三、发布 3 步

1. 修改版本号：服务端 `UpdateGatePlugin.CURRENT_VERSION`；客户端 `android/build.gradle.kts` 的 `versionName/versionCode`。
2. 打 tag：`git tag v1.4.2 && git push origin v1.4.2`。
3. `release.yml` 自动构建 jar + 各 ABI apk → 生成并 **Ed25519 签名** `manifest.json` → 创建 Release → 回写 `gh-pages/updates/latest.json`。
   客户端随后即可从固定地址拉取新 manifest。

## 本地构建

```bash
# 服务端（需 JDK 21）
./gradlew :server:build          # 零警告编译 + 单元测试 + 产出 jar
./gradlew :server:test           # 仅运行门禁矩阵与故障注入测试

# Android（需 Android SDK + JDK 21）
cd android && ./gradlew assembleRelease
```

> 密钥：Ed25519 私钥已写入仓库 Actions Secret `UPDATE_SIGNING_KEY`，公钥硬编码于
> `server/.../IntegrityVerifier.java` 与 `android/.../Crypto.kt`。更换密钥时两者需同步更新。
