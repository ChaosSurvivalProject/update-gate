# mc-autoupdate · Minecraft 内置更新 AI Skill

一份给 AI 看的指南：当你**做好一个 Paper 插件或 Fabric 1.21.11 客户端模组**后，按它即可直接为项目内置自动更新模块。

## 它解决什么

把「内置更新」这件容易做过头的事，收敛成**一套默认约定 + 三个按需参考文件**：

- **默认策略**：开机自检一次 + **每周四中午 12:00** 定时检查；来源 GitHub Releases latest；带版本门禁与 sha256 校验；服务端重启生效 / 客户端重启生效。
- **不反问**：平台、版本、来源、调度都有合理默认值，只有必要时才问用户。
- **平台双方案**：Paper 服务端插件（Java 21）与 Fabric 1.21.11 客户端模组各有完整可复制模板。

## 目录结构

```
mc-autoupdate/
├── SKILL.md                 # 主指南：触发条件、默认策略、决策树、流程、自检清单
└── references/
    ├── contract.md          # manifest 字段表、版本门禁逐条判定、返回码/文案、result_code
    ├── paper.md             # Paper 插件：UpdateConfig/Gate/Downloader/Scheduler/Manager + 命令 + 双清单
    └── fabric.md            # Fabric 1.21.11：Gradle、fabric.mod.json、ClientUpdateChecker + 双语提示
```

## 安装

- **本地（CodeBuddy/Claude）**：把本目录放到 `~/.codebuddy/skills/mc-autoupdate`（或 `.claude/skills/`），重启会话即可。
- **仓库内**：本目录也在本仓库 `skills/mc-autoupdate`，可直接克隆后复制。

## 用法示例

对 AI 说：

> “插件写好了，加上内置更新，从我的 GitHub Releases 拉最新版本。”

> “给这个 Fabric 1.21.11 客户端 mod 加自动更新，每周四中午检查一次。”

AI 会加载本 skill，按决策树识别平台，读取对应 `references/*.md`，把最小可用类复制进项目并验证构建。

## 约定默认值

| 项 | 默认值 |
| --- | --- |
| 检查时机 | 开机自检 + 每周四 12:00（Asia/Shanghai） |
| 来源 | GitHub Releases `latest`（固定地址，直连） |
| 通道 | `stable` |
| 门禁 | 语义版本 major→minor→patch，禁止自动跨 minor/major 强升 |
| 完整性 | sha256 必校验，可选 Ed25519 验签 |
| 替换 | 临时文件 + 原子替换，重启生效，不热加载 |
| 文案/注释 | 中英双语 / 简体中文注释，标识符英文 |

## 安全红线

客户端不内置 Token；私有仓库走自有中继；只走 HTTPS；第三方镜像不带凭证；日志不打印签名参数；不静默替换运行中的 jar。
