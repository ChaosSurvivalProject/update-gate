package com.updategate.update;

import java.time.Duration;

/** 更新模块配置（由插件读取 config.yml 注入）。 */
public final class UpdateConfig {
    /** GitHub 组织名。 */
    public final String org;
    /** GitHub 仓库名。 */
    public final String repo;
    /** 监听渠道：stable / beta。 */
    public final String channel;
    /** 定时检查间隔（小时），默认 6。 */
    public final long intervalHours;
    /** 插件基础名（如 XxxPlugin），用于文件命名与替换。 */
    public final String pluginName;
    /** 当前插件版本（manifest 中 current）。 */
    public final String currentVersion;
    /** 服务端实际 api-version（如 1.21.11）。 */
    public final String serverApiVersion;
    /** manifest 固定拉取地址（gh-pages /updates/latest.json）。 */
    public final String manifestUrl;
    /** 原始 GitHub Release 基址（用于构造各镜像下载 URL）。 */
    public final String releaseBaseUrl;
    /** 可观测上报地址（空则仅本地日志）。 */
    public final String reportEndpoint;

    public UpdateConfig(String org, String repo, String channel, long intervalHours,
                        String pluginName, String currentVersion, String serverApiVersion,
                        String manifestUrl, String releaseBaseUrl, String reportEndpoint) {
        this.org = org;
        this.repo = repo;
        this.channel = channel;
        this.intervalHours = intervalHours;
        this.pluginName = pluginName;
        this.currentVersion = currentVersion;
        this.serverApiVersion = serverApiVersion;
        this.manifestUrl = manifestUrl;
        this.releaseBaseUrl = releaseBaseUrl;
        this.reportEndpoint = reportEndpoint;
    }

    public static UpdateConfig defaultOf(String pluginName, String currentVersion, String serverApiVersion) {
        return new UpdateConfig(
                "ChaosSurvivalProject", "update-gate", "stable", 6,
                pluginName, currentVersion, serverApiVersion,
                "https://gh-proxy.com/https://raw.githubusercontent.com/ChaosSurvivalProject/update-gate/gh-pages/updates/latest.json",
                "https://github.com/ChaosSurvivalProject/update-gate/releases/download/",
                "");
    }

    public Duration interval() { return Duration.ofHours(Math.max(1, intervalHours)); }
}
