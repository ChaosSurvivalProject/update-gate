# 版本契约与门禁（唯一真相源）

本文件是内置更新模块的「契约」：manifest 字段、版本门禁逐条判定、返回码文案、result_code 枚举。Paper 与 Fabric 两侧实现必须与此一致。

## 1. manifest 最小契约（GitHub Releases latest 的 latest.json）

放在固定地址（如 `gh-pages` 分支的 `updates/latest.json`），客户端启动时拉取。**元数据直连 `api.github.com` 或该固定地址，禁止走第三方代理池。**

```json
{
  "schemaVersion": 1,
  "generatedAt": "2026-11-20T00:00:00Z",
  "channels": [{
    "id": "stable",
    "target": { "version": "1.4.2", "versionCode": 142, "apiVersion": "1.21.11",
                "minecraft": "1.21.11" },
    "minSupported": { "version": "1.0.0", "apiVersion": "1.21.4" },
    "minClientVersion": "2.3.0",
    "mode": "OPTIONAL",
    "rolloutPercent": 100,
    "assets": [{
      "platform": "paper-plugin",
      "fileName": "MyPlugin-1.4.2.jar",
      "size": 0,
      "sha256": "<64 位小写十六进制>",
      "downloadUrl": "https://github.com/<org>/<repo>/releases/download/v1.4.2/MyPlugin-1.4.2.jar"
    },{
      "platform": "fabric-mod",
      "minecraft": "1.21.11",
      "fileName": "mymod-1.4.2+1.21.11.jar",
      "size": 0,
      "sha256": "<64 位小写十六进制>",
      "downloadUrl": "https://github.com/<org>/<repo>/releases/download/v1.4.2/mymod-1.4.2+1.21.11.jar"
    }]
  }]
}
```

| 字段 | 含义 | 必填 |
| --- | --- | --- |
| `schemaVersion` | 契约版本，当前为 1 | 是 |
| `channels[].id` | 通道：`stable` / `beta`（preRelease 仅 beta 可见） | 是 |
| `target.version` | 目标版本 `major.minor.patch`，支持 preRelease / build metadata | 是 |
| `target.versionCode` | 单调递增整数（Android/客户端用） | 否 |
| `target.apiVersion` | 服务端 API 版本（Paper 用，如 `1.21.11`） | 是（服务端） |
| `target.minecraft` | 支持的 MC 版本（Fabric 用） | 是（Fabric） |
| `minSupported` | 允许升级的最低版本，低于它必须强制升级 | 是 |
| `minClientVersion` | 客户端最低版本，低于它阻塞更新 | 否 |
| `mode` | `OPTIONAL`（可跳过）/ `REQUIRED`（强更不可跳过） | 是 |
| `assets[].platform` | `paper-plugin` / `fabric-mod` / `android` | 是 |
| `assets[].sha256` | 全文件 sha256，校验失败删除重下（≤2 次） | 是 |
| `assets[].downloadUrl` | 直链（GitHub Release 原始 URL），元数据不从此域名探测版本 | 是 |

## 2. 版本门禁（判定顺序，命中即返回，不得跳过）

输入：`current`、`target`、`minSupported`、`minClientVersion`、`platform`、服务端实际 `apiVersion`。

```
1) target == current                         → UP_TO_DATE
2) current < minSupported                    → BLOCKED_UPGRADE_REQUIRED
   （版本过旧，请更新客户端/服务端）
3) target > current（逐段 major→minor→patch）
   - 服务端：target.apiVersion > 实际 apiVersion → BLOCKED_API_TOO_NEW
   - 客户端：target.minClientVersion > 客户端版本 → BLOCKED_CLIENT_TOO_OLD
   - 否则                                    → UPDATE_REQUIRED / UPDATE_OPTIONAL（按 mode）
4) target < current                          → BLOCKED_DOWNGRADE_FORBIDDEN
```

**语义版本比较**：先比 major，再 minor，再 patch；preRelease 低于正式版；build metadata（`+xxx`）忽略。例如 `1.21.11 < 1.22.0`，`1.4.2-beta < 1.4.2`。

### 硬性约束（必须逐条满足）

- 禁止任何“自动跨 minor/major 强升”代码路径；需要升版本时**只返回提示文案与下载地址**。
- 服务端：清单要求 `1.21.11`、服务器 `1.21.10` → 仅提示「请更新服务端至 1.21.11」，**不下载任何 jar、不降级到 1.21.10 的包**。
- preRelease / build metadata 支持；preRelease 只在 `channel=beta` 可见。

### 文案（中英双语，按返回码）

| 返回码 | 中文 | English |
| --- | --- | --- |
| `UP_TO_DATE` | 已是最新版本 | You are up to date |
| `BLOCKED_UPGRADE_REQUIRED` | 当前版本过旧，必须先升级 | Your version is too old; upgrade first |
| `BLOCKED_API_TOO_NEW` | 当前插件要求 Paper 1.21.11，你的服务器为 1.21.10，请先升级服务端，不会自动跨版本更新 | This plugin requires Paper 1.21.11 but the server is 1.21.10; upgrade the server first. No cross-version auto update. |
| `BLOCKED_CLIENT_TOO_OLD` | 客户端版本过低，请先更新客户端 | Client is too old; please update the client first |
| `UPDATE_REQUIRED` | 有新版本，必须更新 | A new version is available and required |
| `UPDATE_OPTIONAL` | 有新版本，可选更新 | A new version is available (optional) |
| `BLOCKED_DOWNGRADE_FORBIDDEN` | 禁止降级 | Downgrade is forbidden |

## 3. result_code 枚举（上报/日志）

`SUCCESS` / `VERSION_GATE_BLOCKED` / `NETWORK_ALL_MIRRORS_FAILED` / `SHA256_MISMATCH` / `SIGNATURE_INVALID` / `DISK_FULL` / `INSTALL_REJECTED` / `ROLLED_BACK`

上报字段：`instanceId`（匿名 UUID）、`from→to`、`result_code`、`duration_ms`、`bytes`、`fail_reason`。**不上报 IP 与账号信息**；日志不打印完整 URL 的签名参数。

## 4. 远程开关（kill-switch）

`config/channels.yaml` 中 `enabled: false` 可一键停更；`period: "thursday-12:00"` + `timezone: Asia/Shanghai` 为默认调度，可被覆盖。
