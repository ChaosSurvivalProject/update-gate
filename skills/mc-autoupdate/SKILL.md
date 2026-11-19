---
name: mc-autoupdate
description: 为 Minecraft 服务端插件（Paper/Spigot，Java 21）或 Fabric 1.21.11 客户端模组「内置自动更新」模块。当用户做好一个 Paper 插件 / Fabric 模组，要求“内置更新 / 自动更新 / 开机自检 / 定时更新 / 从 GitHub Releases 拉取新版本”，或明确说“做好插件后直接要求内置更新”时使用。默认策略：开机自检一次 + 每周四中午 12:00 定时检查，来源为 GitHub Releases latest，带版本门禁与 sha256 校验。含中英双语文案与简体中文注释。
---

# MC 内置自动更新（mc-autoupdate）

## 概述

给一个**已经写完的** Paper 服务端插件或 Fabric 1.21.11 客户端模组，快速加上「开机自检 + 定时检查 + 下载替换」的更新能力。目标是**简单、能落地**：默认约定优先，只在必要时问用户，避免让用户做一堆选择题。

> 默认策略（未特别说明时直接用，不要反问）：
> - **触发时机**：启动自检一次（Paper `onEnable` / Fabric 客户端初始化）+ **每周四中午 12:00** 定时检查。
> - **更新来源**：GitHub Releases `latest`（固定地址，元数据直连 `api.github.com`，**绝不走代理池、客户端绝不内置 Token**）。
> - **版本门禁**：语义版本逐段比较（major→minor→patch），命中即返回，详见 `references/contract.md`。
> - **完整性**：sha256 必校验；可选 Ed25519 验签（有签名密钥时开启）。
> - **替换策略**：下载到临时文件，校验通过后原子替换；服务端**重启生效**，客户端**重启游戏生效**，不热加载。
> - 全部注释与文档用**简体中文**，变量与类名用**英文**；对外文案提供中英双语。

## 决策树（先判断场景）

1. **目标是服务端插件（Paper/Spigot, Java 21）？**
   → 走「Paper 插件流程」，读 `references/paper.md`。
2. **目标是 Fabric 1.21.11 客户端模组？**
   → 走「Fabric 客户端流程」，读 `references/fabric.md`。
3. **两者都要？**
   → 先 Paper 后 Fabric，共用 `references/contract.md` 的版本契约与门禁。
4. **用户只说“加上内置更新”，没说平台？**
   → 看项目构建文件：`build.gradle.kts` 含 `paperweight`/`paper-api` → Paper；含 `fabric-loom` / `fabric.mod.json` → Fabric。仍无法判断才问。

## Paper 插件流程（简述，细节见 references/paper.md）

1. 读项目当前的 `version`、`api-version`、`plugin.yml`/`paper-plugin.yml`，确定本地版本与目标地址。
2. 按 `references/contract.md` 生成/读取更新清单（manifest），确认字段与门禁返回码。
3. 从 `references/paper.md` 复制最小可用类到项目 `update/` 包：`UpdateManager`（编排）、`VersionGate`（门禁）、`UpdateDownloader`（下载+sha256）、`UpdateScheduler`（开机自检 + 周四 12:00）。
4. 在 `onEnable` 调用 `updateManager.checkAndSchedule()`；注册 `/xxx update check|apply` 命令。
5. 构建验证零警告，输出改动清单。

## Fabric 客户端流程（简述，细节见 references/fabric.md）

1. 确认 `fabric.mod.json` 的 `id`/`version`、`minecraft` 版本域含 `1.21.11`。
2. 客户端模组只做**提示 + 下载 + 校验 + 重启生效**（不静默替换运行中的 mod jar）。
3. 从 `references/fabric.md` 复制 `ClientUpdateChecker`（`@Environment(CLIENT)`），在 `onInitializeClient` 启动自检 + 周四 12:00 调度。
4. 下载到 `mods/.update/` 临时目录，校验 sha256 后提示玩家“重启生效”并可选自动移动到 `mods/`。
5. 构建验证（`./gradlew build`），输出改动清单。

## 关键约束（不可违反）

- **禁止自动跨 minor/major 强升**：门禁只返回提示文案与下载地址，不自动下载跨大版本包（见 contract.md 第 2 节）。
- **服务端场景示例**：清单要求 Paper 1.21.11、服务器为 1.21.10 → 仅提示「请先升级服务端至 1.21.11」，**不下载任何 jar、不降级**。
- **客户端安全红线**：不内置 Token；私有仓库必须走自有中继；只走 HTTPS；第三方镜像不带任何凭证；日志不打印完整 URL 签名参数。
- **完整性**：manifest 的 sha256 不匹配立即删除重下（最多 2 次）；有签名则验签失败一律终止。
- **原子替换**：下载到 `.part`，校验通过后 `ATOMIC_MOVE` 覆盖；禁止热加载（会抛 `LinkageError`）。
- **可观测**：上报字段用匿名 instanceId，不含 IP/账号；result_code 用 contract.md 枚举。

## 按需加载参考

| 文件 | 何时读 |
| --- | --- |
| `references/contract.md` | 需要 manifest 字段表、版本门禁逐条判定、返回码/文案、result_code 枚举时 |
| `references/paper.md` | 目标为 Paper 服务端插件：完整类模板、调度、命令、双清单与替换策略 |
| `references/fabric.md` | 目标为 Fabric 1.21.11 客户端模组：客户端 check + 下载 + 提示重启 + Gradle 配置 |

## 交付自检（完成前逐条确认）

- [ ] 开机自检 + 每周四 12:00 定时已接入（时区默认 Asia/Shanghai，可在配置覆盖）
- [ ] 版本门禁按逐条顺序实现，且不会自动跨 minor/major 强升
- [ ] 服务端 apiVersion 过高时只提示升级服务端、不下载不降级
- [ ] sha256 校验 + 临时文件 + 原子替换；服务端重启生效 / 客户端重启生效
- [ ] 客户端无 Token、只走 HTTPS、文案中英双语、注释简体中文
- [ ] 构建零警告 / 零错误，已列出改动文件清单
