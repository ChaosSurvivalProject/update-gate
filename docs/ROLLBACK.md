# 回滚手册

本模块对**服务端**与**客户端**采用不同回滚策略，均遵循「不自动跨版本、失败即回退」的硬性约束。

## 一、服务端回滚

服务端替换发生在插件 **`onDisable`（服务器关闭/重启）** 阶段，禁止热加载（会抛 `LinkageError`）。

### 自动回滚（apply 失败）
`Relocator.applyOnDisable()` 在移动暂存文件前会先把当前生效的插件备份到 `backup/<name>-<ver>.jar`。
若移动失败，自动执行 `rollback()` 用备份覆盖回当前版本，并上报 `ROLLED_BACK`。

### 手动回滚（命令）
```text
/updategate rollback
```
该命令调用 `Relocator.rollback()`，从 `backup/` 恢复上一版本，随后需重启服务器生效。
返回「回滚成功 / 失败」。

### 流程要点
1. 下载：`/updategate apply` → 落到 `plugins/update/<name>-<ver>.jar`。
2. 备份：`onDisable` 时先把 `plugins/<name>.jar` 复制到 `backup/<name>-<oldver>.jar`。
3. 替换：原子移动到 `plugins/<name>.jar`，提示「重启生效」。
4. 失败：自动回滚到 `backup/`，并上报。

> 兼容同时存在 `plugin.yml` 与 `paper-plugin.yml` 的 jar；`api-version` 必须声明且**带引号**。

## 二、客户端回滚

客户端**不自动安装**，更不静默回滚；所有安装必须由用户确认（Android 15 已全面封杀静默安装）。

- **增量合成失败**（签名不一致 / APK 损坏）：自动降级为全量包，最多重试 1 次；仍失败则报 `SHA256_MISMATCH` / `SIGNATURE_INVALID`，**不强制**。
- **安装被拒**（`INSTALL_REJECTED`、用户取消）：保持当前版本，等待下一次「回到前台」检查或用户手动触发；强更（`UPDATE_OPTIONAL=false`）不可跳过，会再次提示。
- **版本门禁拦截**：`BLOCKED_DOWNGRADE_FORBIDDEN` 等结果码明确禁止降级，客户端不会尝试旧版本包。

## 三、一键停更（kill-switch）

编辑 `config/channels.yaml` 将 `enabled: false`（或某渠道 `enabled: false`），客户端与服务端读取后立即停止更新。
该文件为公开资源，**严禁写入任何 Token**。

## 四、应急清单

| 现象 | 处置 |
| --- | --- |
| 新版本有问题 | 服务端：`/updategate rollback` + 重启；客户端：勿安装，等待下个 manifest 修正 |
| 全部镜像 403/超时 | 查看 `config/mirrors-health.json`；必要时在 `channels.yaml` 调低权重或临时 `enabled:false` |
| manifest 验签失败 | 确认 `UPDATE_SIGNING_KEY` 与客户端硬编码公钥一致；不一致则全量失败并终止（安全优先） |
| 误发强更 | 立即发布修复版本并回写 `gh-pages/updates/latest.json`；必要时 kill-switch 停更 |
