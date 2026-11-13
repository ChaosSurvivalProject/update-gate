package com.updategate;

import com.updategate.update.UpdateManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/**
 * 更新命令执行器：{@code /updategate check|apply|rollback}。
 *
 * <p>注意：apply 仅把下载好的暂存文件标记为「待替换」，真正的原子移动发生在插件
 * onDisable（服务器关闭/重启）时，由管理员重启生效——禁止热加载替换。</p>
 */
public final class UpdateCommand implements CommandExecutor {

    private final UpdateGatePlugin plugin;
    private final UpdateManager manager;

    public UpdateCommand(UpdateGatePlugin plugin, UpdateManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("用法：/updategate check | apply | rollback");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "check" -> {
                UpdateManager.CheckResult r = manager.check();
                sender.sendMessage("[UpdateGate] " + r.message);
                if (r.decision != null) {
                    sender.sendMessage("[UpdateGate] 门禁结果：" + r.decision.code());
                }
            }
            case "apply" -> {
                UpdateManager.CheckResult r = manager.check();
                if (r.channel == null) {
                    sender.sendMessage("[UpdateGate] 当前无可用更新");
                    return true;
                }
                UpdateManager.DownloadOutcome o = manager.downloadAndStage(r.channel);
                sender.sendMessage("[UpdateGate] " + o.message);
                if (o.ok) {
                    sender.sendMessage("[UpdateGate] 已暂存，请重启服务器生效（/reload 或重启）");
                }
            }
            case "rollback" -> {
                boolean ok = manager.rollback();
                sender.sendMessage("[UpdateGate] 回滚" + (ok ? "成功" : "失败"));
            }
            default -> sender.sendMessage("未知子命令：" + args[0]);
        }
        return true;
    }
}
