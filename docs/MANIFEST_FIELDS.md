# manifest 字段表

契约文件：`schemas/update-manifest-v1.schema.json`（唯一真相源）。下文为每个字段给出含义与示例。

## 顶层

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `schemaVersion` | integer | 是 | 契约版本，当前固定 `1` |
| `generatedAt` | string(date-time) | 是 | 生成时间，RFC3339，如 `2026-11-13T00:00:00Z` |
| `channels` | array<Channel> | 是 | 渠道列表，至少 1 项 |

## Channel（渠道）

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `id` | enum | 是 | `stable` / `beta`；beta 仅对开启 preRelease 的客户端可见 |
| `target` | Target | 是 | 目标版本信息 |
| `minSupported` | MinSupported | 是 | 仍被支持的最低版本，低于则 `BLOCKED_UPGRADE_REQUIRED` |
| `minClientVersion` | string | 是 | 客户端最低版本，用于 `BLOCKED_CLIENT_TOO_OLD` |
| `mode` | enum | 是 | `OPTIONAL` / `REQUIRED`（强更不可跳过） |
| `rolloutPercent` | integer(0-100) | 是 | 灰度放量百分比 |
| `assets` | array<Asset> | 是 | 资产列表（jar / apk / 补丁） |

## Target（目标版本）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `version` | string | 目标语义化版本，如 `1.4.2` |
| `versionCode` | integer | 目标 versionCode（仅 Android，如 `142`） |
| `apiVersion` | string | 目标要求的服务端 api-version，如 `1.21.11` |

## MinSupported

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `version` | string | 最低支持版本，如 `1.0.0` |
| `apiVersion` | string | 最低支持 api-version，如 `1.21.4` |

## Asset（资产）

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `platform` | enum | 是 | `paper-plugin` / `android` |
| `abi` | string | 否 | Android 专用：`arm64-v8a` / `armeabi-v7a` / `x86_64` |
| `fileName` | string | 是 | 文件名（含版本），如 `XxxPlugin-1.4.2.jar` |
| `size` | integer | 是 | 文件字节大小 |
| `sha256` | string(64 hex) | 是 | 全量 sha256（小写十六进制） |
| `signatureFingerprint` | string | 否 | Android 专用：APK 签名指纹（合成补丁后校验） |
| `patch` | Patch | 否 | 增量补丁（见下） |
| `downloads` | array<Download> | 是 | 各镜像下载地址 |

## Patch（增量补丁）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `fromVersionCode` | integer | 补丁所基于的起始 versionCode；仅当本地与之**完全一致**时可用 |
| `fileName` | string | 补丁文件名，如 `app-141-142.patch` |
| `size` | integer | 补丁字节大小 |
| `sha256` | string | 补丁 sha256 |
| `algorithm` | enum | 当前仅 `bsdiff` |

## Download（下载地址）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `mirror` | string | 镜像标识，如 `gh-proxy` |
| `url` | string(uri) | 完整下载 URL（仅公开资源，不得携带凭证） |

## 示例

```json
{
  "schemaVersion": 1,
  "generatedAt": "2026-11-13T00:00:00Z",
  "channels": [{
    "id": "stable",
    "target": { "version": "1.4.2", "versionCode": 142, "apiVersion": "1.21.11" },
    "minSupported": { "version": "1.0.0", "apiVersion": "1.21.4" },
    "minClientVersion": "2.3.0",
    "mode": "OPTIONAL",
    "rolloutPercent": 100,
    "assets": [
      { "platform": "paper-plugin", "fileName": "XxxPlugin-1.4.2.jar",
        "size": 0, "sha256": "", "downloads": [{ "mirror": "gh-proxy", "url": "" }] },
      { "platform": "android", "abi": "arm64-v8a", "fileName": "app-arm64-v8a-1.4.2.apk",
        "size": 0, "sha256": "", "signatureFingerprint": "",
        "patch": { "fromVersionCode": 141, "fileName": "app-141-142.patch",
                   "size": 0, "sha256": "", "algorithm": "bsdiff" } }
    ]
  }]
}
```
