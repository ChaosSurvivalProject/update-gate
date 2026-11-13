# 镜像池自维护说明

代理池位于 `server/.../MirrorPool.java`（服务端）与 `android/.../MirrorPool.kt`（客户端），两者内置镜像一致，优先级如下（越靠前权重越高）：

| 优先级 | 镜像 id | base | 备注 |
| --- | --- | --- | --- |
| 1 | `gh-proxy` | `https://gh-proxy.com/` | 实测 Release/raw/api 三路线全通，**置顶**；唯一获准代理 `api.github.com` 的镜像 |
| 2 | `ghfast` | `https://ghfast.top/` | |
| 3 | `ghproxy-net` | `https://ghproxy.net/` | |
| 4 | `mirror-ghproxy` | `https://mirror.ghproxy.com/` | |
| 5 | `hub-gitmirror` | `https://hub.gitmirror.com/` | |
| 6 | `moeyy` | `https://moeyy.cn/gh-proxy/` | |
| 7 | `gh-llkk` | `https://gh.llkk.cc/` | |
| 8 | `gh-jasonzeng` | `https://gh.jasonzeng.dev/` | 末位候选，运行时探活 |
| 9 | `direct` | `https://github.com/` | 兜底直连（全池失败时使用） |
| 10 | `jsdelivr` | `https://cdn.jsdelivr.net/gh/` | 仅 raw，不能下 Release |

## 竞速与熔断策略

- **预热**：并发 HEAD，取 TTFB 最优的 2 个作为主力（权重提升）。
- **分片**：文件切 4MB，单连接独占 0 号片，其余按 `weight=1/(p95×负载)` 派发。
- **续传**：Range 断点，进度落 `update-progress.json`，杀进程可恢复。
- **熔断**：连续 2 次 4xx/5xx 或 3s 无字节 → 该镜像冷却 60s，分片重入队（单分片重试上限 8 次，防止死循环）。
- **兜底**：全池失败 → 直连 GitHub → 仍失败则指数退避重试 3 次后报 `NETWORK_ALL_MIRRORS_FAILED`。

## 自动健康探测（mirror-probe.yml）

`.github/workflows/mirror-probe.yml` 每 6h 探测每个镜像的 `raw / release / api` 三路线，
产出 `config/mirrors-health.json`（含 `routes`、`ttfbMs`、`weight`、`cooldownUntil`）。
客户端可读取该文件动态调整权重；`api` 路线多数镜像返回 403，元数据禁止走代理池。

## 如何新增 / 调整镜像

1. 在服务端与客户端两处 `DEFAULT_MIRRORS` 同步加入 `id -> base`（务必同步，否则两端行为不一致）。
2. 在 `config/mirrors-health.json` 与 `config/channels.yaml` 的 `mirrorWeights` 中补充权重（可选）。
3. 提交后由 `mirror-probe.yml` 持续校准实际可用性。

> 红线：所有镜像请求均为**公开资源**，绝不附加任何 Cookie / Token；私有仓库请走自有中继，不要将 PAT 编译进产物。
