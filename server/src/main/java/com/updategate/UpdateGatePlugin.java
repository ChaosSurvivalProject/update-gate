package com.updategate;

import com.updategate.update.UpdateConfig;
import com.updategate.update.UpdateManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;

/**
 * 更新模块插件主类（Paper，Java 21）。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>onLoad：构造 {@link UpdateManager} 并做首检（此时尚未替换）；</li>
 *   <li>onEnable：启动定时检查（默认 6h）；</li>
 *   <li>onDisable：执行真正替换（移动 plugins/update 暂存文件到 plugins/），提示「重启生效」，
 *       禁止热加载（避免 LinkageError）；</li>
 *   <li>注册命令 {@code /updategate check|apply|rollback}。</li>
 * </ul>
 */
public final class UpdateGatePlugin extends JavaPlugin {

    public static final String PLUGIN_NAME = "UpdateGate";
    public static final String CURRENT_VERSION = "1.0.0";
    public static final String SERVER_API_VERSION = "1.21.11";

    private UpdateManager manager;

    @Override
    public void onLoad() {
        UpdateConfig cfg = UpdateConfig.defaultOf(PLUGIN_NAME, CURRENT_VERSION, SERVER_API_VERSION);
        Path pluginsDir = getDataFolder().getParentFile().toPath();
        manager = new UpdateManager(cfg, pluginsDir);
        getLogger().info("更新模块已加载，onLoad 首检开始");
        manager.check();
    }

    @Override
    public void onEnable() {
        manager.start();
        getCommand("updategate").setExecutor(new UpdateCommand(this, manager));
        getLogger().info("更新模块已启用，定时检查已启动（默认 6h）");
    }

    @Override
    public void onDisable() {
        getLogger().info("插件正在禁用，执行原子替换（若有待应用更新）");
        manager.applyOnDisable();
        manager.shutdown();
        getLogger().info("如需生效，请重启服务器");
    }

    public UpdateManager getManager() { return manager; }
}
