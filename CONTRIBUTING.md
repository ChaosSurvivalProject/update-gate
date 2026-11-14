# 贡献指南（Contributing）

感谢你关注 **UpdateGate**！本仓库提供「版本门禁 + 多加速源竞速下载 + 原子替换」的更新模块（Paper 插件 + Android 客户端）。

## 行为准则
参与本仓库即表示你同意遵守 [CODE_OF_CONDUCT.md](./CODE_OF_CONDUCT.md)。

## 开发环境
- **服务端**：JDK 21（Gradle 9.3 toolchain 自动拉取）、Paper API 1.21.11
- **客户端**：Android SDK（compileSdk 35，Android Studio / CI 自带）
- 本地构建服务端：`./gradlew :server:build`（零警告要求已开启 `-Xlint:all -Werror`）

## 提交规范
- 分支策略：`main` 为保护分支，功能开发请用 `feat/xxx` 分支并发 PR
- Commit 前缀：`feat:` / `fix:` / `docs:` / `test:` / `refactor:` / `chore:`
- **所有注释与文档使用简体中文，变量与类名使用英文**

## PR 要求
1. 服务端改动需保证 `:server:build` 零警告且单元测试全绿（门禁矩阵 + 故障注入 + 加密闭环）
2. 门禁算法（第 2 节）属于最高优先级，**新增分支必须补充对应单测断言返回码与文案**
3. 涉及安全红线（密钥、Token、代理池、HTTPS）的改动需显式说明影响面
4. 通过 PR 模板勾选项自检

## 发布流程
- 打 tag `v*`（如 `v1.4.2`）即触发 `release.yml`：构建 → 生成 manifest → Ed25519 签名 → 发布 Release → 回写 `gh-pages/updates/latest.json`
- 镜像健康由 `mirror-probe.yml` 每 6h 自动探测并写回 `config/mirrors-health.json`

## 安全漏洞
请勿在 Issue 中披露安全漏洞，按 [SECURITY.md](./SECURITY.md) 流程私报告知维护者。
