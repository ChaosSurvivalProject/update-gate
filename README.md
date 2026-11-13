# UpdateGate · 版本门禁 + 多加速源竞速下载 + 原子替换

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Org](https://img.shields.io/badge/org-ChaosSurvivalProject-blue)](https://github.com/ChaosSurvivalProject/update-gate)

一套从零落地的更新模块，覆盖 **版本门禁（唯一真相源）→ 多加速源竞速下载（代理池竞速 / 分片 / 续传 / 熔断 / 兜底）→ 完整性校验（sha256 + Ed25519 验签）→ 原子替换（重启生效 / 回滚）** 的完整链路，同时提供 **Paper 服务端插件（Java 21）** 与 **Android 客户端（Kotlin + Compose）** 两套实现，并配套 **GitHub Releases + CDN 加速 + 灰度发布** 的 CI。

> 仓库：`https://github.com/ChaosSurvivalProject/update-gate`（公开，启用 Actions）

## 模块总览

| 组件 | 位置 | 说明 |
| --- | --- | --- |
| 版本契约（唯一真相源） | `schemas/update-manifest-v1.schema.json` | manifest 字段与校验规则 |
| 服务端插件（Paper, Java 21） | `server/` | `update` 包：UpdateManager / VersionGate / MirrorPool / ChunkDownloader / IntegrityVerifier / Relocator / UpdateReporter |
| Android 客户端（Kotlin + Compose） | `android/` | Application + WorkManager + 前台服务 + 竞速下载 + bsdiff 增量 + 安装引导 |
| 发布流水线 | `.github/workflows/release.yml` | 打 tag `v*` → 构建 → 生成并签名 manifest → 创建 Release → 回写 `gh-pages/updates/latest.json` |
| 镜像健康探测 | `.github/workflows/mirror-probe.yml` | 每 6h 探测各镜像 raw/release/api 三路线，产出 `config/mirrors-health.json` |
| 远端开关 | `config/channels.yaml` | kill-switch：`enabled=false` 一键停更 |
| 文档 | `docs/` | 接入 3 步 / manifest 字段表 / 镜像池自维护 / 回滚手册 |

## 安全红线（务必遵守）

- **客户端不内置任何 Token**；私有仓库必须由自有中继转发，禁止把 PAT 编译进 APK / jar。
- 仅走 HTTPS；第三方镜像仅用于公开资源，请求不得带任何凭证。
- 日志不打印完整 URL 中的签名参数。
- 所有对外文案提供中英双语资源（服务端为中文硬编码 + 英文，客户端为 `values/strings.xml` + `values-zh/strings.xml`）。
- 全部注释与文档使用**简体中文**，变量与类名使用**英文**。

## 快速开始

见 [`docs/QUICKSTART.md`](docs/QUICKSTART.md) —— 服务端接入 3 步、客户端接入 3 步、本地构建与发布流程。

## 验收对照

- [x] 服务端 `./gradlew :server:build` 零警告，产出 `UpdateGate-1.0.0.jar`
- [x] 门禁矩阵单元测试（低/等/高 minor、高 patch、apiVersion 过高、versionCode 回退、minClientVersion 不足）断言返回码与文案
- [x] 故障注入测试（断网 / 半断网 / 403 / 错误 sha256）
- [x] 文档齐全（接入 3 步、manifest 字段表、镜像池自维护、回滚手册）
- [~] Android 客户端源码完整；因沙箱无 Android SDK 未本地编译，由 CI 构建（详见文档）

> 注：服务端采用默认值（核心 Paper 1.21.11 / 最低 1.21.4 / CI 生成 Ed25519 / 无自有 CDN）。如需调整，见 `docs/` 与 `config/`。
