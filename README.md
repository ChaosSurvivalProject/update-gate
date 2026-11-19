# UpdateGate · 版本门禁 + 多加速源竞速下载 + 原子替换

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Org](https://img.shields.io/badge/org-ChaosSurvivalProject-blue)](https://github.com/ChaosSurvivalProject/update-gate)
[![Build Server](https://github.com/ChaosSurvivalProject/update-gate/actions/workflows/release.yml/badge.svg)](https://github.com/ChaosSurvivalProject/update-gate/actions/workflows/release.yml)
[![Mirror Probe](https://github.com/ChaosSurvivalProject/update-gate/actions/workflows/mirror-probe.yml/badge.svg)](https://github.com/ChaosSurvivalProject/update-gate/actions/workflows/mirror-probe.yml)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](server/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF.svg)](android/)
[![Platform](https://img.shields.io/badge/Platform-Paper%201.21%20%2B%20Android-blue.svg)](https://papermc.io)

> **想要最简单的方式？直接用 AI Skill：[`skills/mc-autoupdate`](skills/mc-autoupdate/)。** 一份指南 + 可复制的简洁模块模板，给做好 Paper 插件 / Fabric 1.21.11 客户端模组的 AI 使用，默认「开机自检 + 每周四中午 12:00 更新」。下面的 `server/` `android/` 是完整工程参考，按需取用。

一套从零落地的更新模块，覆盖 **版本门禁（唯一真相源）→ 多加速源竞速下载（代理池竞速 / 分片 / 续传 / 熔断 / 兜底）→ 完整性校验（sha256 + Ed25519 验签）→ 原子替换（重启生效 / 回滚）** 的完整链路，同时提供 **Paper 服务端插件（Java 21）** 与 **Android 客户端（Kotlin + Compose）** 两套实现，并配套 **GitHub Releases + CDN 加速 + 灰度发布** 的 CI。

## 推荐入口：AI Skill（简单方案）

[`skills/mc-autoupdate`](skills/mc-autoupdate/) 是本仓库首选的轻量交付：

- **一份指南**（`SKILL.md`）：告诉 AI 在做好插件/模组后如何直接内置更新，默认不反问。
- **简单模块模板**：`references/paper.md`（Paper 插件）、`references/fabric.md`（Fabric 1.21.11 客户端模组）、`references/contract.md`（版本契约与门禁）。
- **默认策略**：开机自检一次 + **每周四中午 12:00** 定时检查（Asia/Shanghai），来源 GitHub Releases，带版本门禁与 sha256 校验，服务端重启生效 / 客户端重启生效。
- 安装：复制该目录到 `~/.codebuddy/skills/mc-autoupdate`（或 `.claude/skills/`）即可。

下面的完整工程（竞速下载、分片续传、Android 客户端、CI）是进阶参考，简单场景无需使用。

> 仓库：`https://github.com/ChaosSurvivalProject/update-gate`（公开，启用 Actions）

## 架构总览

```
                         ┌─────────────────────────────────────────┐
                         │           发布侧 (release.yml)            │
   git tag v*  ─────────▶│  构建 jar/apk → 生成 manifest.json        │
                         │  → Ed25519 签名 → 创建 Release            │
                         │  → 回写 gh-pages/updates/latest.json      │
                         └───────────────┬───────────────┬──────────┘
                                         │ 元数据         │ 二进制
                         直连 api.github  │                │ proxyPrefix + 原始 URL
                                         ▼                ▼
                    ┌────────────────────────────────────────────────┐
                    │        多加速源代理池（优先级可配，全 HTTPS）      │
                    │  gh-proxy / ghfast / ghproxy / gitmirror …       │
                    │  全失败 → 直连 GitHub 兜底 → 指数退避 ×3          │
                    └───────────────┬───────────────────┬────────────┘
                                    │                   │
                    ┌───────────────▼──────┐   ┌─────────▼──────────────┐
                    │  Paper 服务端插件     │   │  Android 客户端         │
                    │  (Java 21)          │   │  (Kotlin+Compose)      │
                    │  VersionGate 门禁    │   │  VersionGate 门禁       │
                    │  ChunkDownloader    │   │  DownloadEngine 竞速    │
                    │  IntegrityVerifier  │   │  bsdiff 增量            │
                    │  Relocator 原子替换  │   │  FileProvider 安装      │
                    │  /update apply/rollback│ │  WorkManager+前台服务   │
                    └─────────────────────┘   └────────────────────────┘
```

## 模块总览

| 组件 | 位置 | 说明 |
| --- | --- | --- |
| 版本契约（唯一真相源） | `schemas/update-manifest-v1.schema.json` | manifest 字段与校验规则（JSON Schema draft 2020-12） |
| 服务端插件（Paper, Java 21） | `server/` | `update` 包：UpdateManager / VersionGate / MirrorPool / ChunkDownloader / IntegrityVerifier / Relocator / UpdateReporter |
| Android 客户端（Kotlin + Compose） | `android/` | Application + ProcessLifecycleOwner 节流 + WorkManager + 前台服务 + 竞速下载 + bsdiff 增量 + 安装引导 |
| 发布流水线 | `.github/workflows/release.yml` | 打 tag `v*` → 构建 → 生成并 **Ed25519 签名** manifest → 创建 Release → 回写 `gh-pages/updates/latest.json` |
| 镜像健康探测 | `.github/workflows/mirror-probe.yml` | 每 6h 探测各镜像 raw/release/api 三路线，产出 `config/mirrors-health.json` |
| 远端开关 | `config/channels.yaml` | kill-switch：`enabled=false` 一键停更 |
| 文档 | `docs/` | 接入 3 步 / manifest 字段表 / 镜像池自维护 / 回滚手册 |

## 版本门禁（最高优先级，唯一真相源）

判定顺序（命中即返回，不得跳过）：

1. `target == current` → `UP_TO_DATE`
2. `current < minSupported` → `BLOCKED_UPGRADE_REQUIRED`
3. `target > current`（逐段比较 major→minor→patch）
   - 服务端：`target.apiVersion > 实际 apiVersion` → `BLOCKED_API_TOO_NEW`（仅提示升级服务端，**不下载、不降级**）
   - 客户端：`target.minClientVersion > 客户端版本` → `BLOCKED_CLIENT_TOO_OLD`
   - 否则 → `UPDATE_REQUIRED`（不可跳过）/ `UPDATE_OPTIONAL`
4. `target < current` → `BLOCKED_DOWNGRADE_FORBIDDEN`

> 服务端场景示例：客户端要求 Paper 1.21.11，你的服务端为 1.21.10 → 仅提示「请先升级服务端至 1.21.11」，不允许下载任何 jar，也不允许降级到 1.21.10 的包。

## 安全红线（务必遵守）

- **客户端不内置任何 Token**；私有仓库必须由自有中继转发，禁止把 PAT 编译进 APK / jar。
- 仅走 HTTPS；第三方镜像仅用于公开资源，请求不得带任何凭证。
- manifest 使用 **Ed25519 验签**，公钥硬编码进客户端并支持远端轮换；验签失败一律终止；CI 发布时会用源码公钥回验签名闭环。
- 日志不打印完整 URL 中的签名参数。
- 禁止**静默安装**（Android 15 已全面封杀），必须用户确认。
- 所有对外文案提供中英双语资源；全部注释与文档使用**简体中文**，变量与类名使用**英文**。

## 快速开始

见 [`docs/QUICKSTART.md`](docs/QUICKSTART.md) —— 服务端接入 3 步、客户端接入 3 步、本地构建与发布流程。

## 验收对照

- [x] 服务端 `./gradlew :server:build` **零警告**，产出 `UpdateGate-1.0.0.jar`
- [x] 门禁矩阵单元测试（低/等/高 minor、高 patch、apiVersion 过高、versionCode 回退、minClientVersion 不足）断言返回码与文案
- [x] 故障注入测试（断网 / 半断网 / 403 / 错误 sha256）四类均进入熔断与兜底路径
- [x] Ed25519 验签代码路径测试（合法通过 / 篡改失败 / 错钥失败 / sha256 误判拦截）
- [x] 文档齐全（接入 3 步、manifest 字段表、镜像池自维护、回滚手册）
- [~] Android 客户端源码完整，且已具备独立 Gradle 工程与 wrapper；因沙箱无 Android SDK 未本地编译，由 CI 在打 tag 时构建

> 注：服务端采用默认值（核心 Paper 1.21.11 / 最低 1.21.4 / CI 生成 Ed25519 / 无自有 CDN）。如需调整，见 `docs/` 与 `config/`。

## 贡献与反馈

- 贡献指南见 [CONTRIBUTING.md](CONTRIBUTING.md)，行为准则见 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)
- 安全漏洞请按 [SECURITY.md](SECURITY.md) 私报告知，**勿在公开 Issue 披露**
