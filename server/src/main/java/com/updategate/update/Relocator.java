package com.updategate.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 替换策略（Relocator）：负责「下载暂存 → 备份 → 原子替换 → 回滚」。
 *
 * <p>对齐第 4 节：</p>
 * <ul>
 *   <li>下载到 {@code plugins/update/<name>-<ver>.jar}；</li>
 *   <li><b>禁止热加载替换</b>（会抛 LinkageError），真正替换发生在 {@code onDisable} 中移动
 *       到 {@code plugins/} 并提示「重启生效」，由管理员重启；</li>
 *   <li>保留上一版本 {@code backup/<name>-<ver>.jar}，失败自动回滚；</li>
 *   <li>兼容同时存在 plugin.yml 与 paper-plugin.yml 的 jar，api-version 必须声明且带引号（由构建保证）。</li>
 * </ul>
 */
public final class Relocator {

    private final Path pluginsDir;

    public Relocator(Path pluginsDir) {
        this.pluginsDir = pluginsDir;
    }

    /** 暂存目录 plugins/update/。 */
    public Path updateDir() {
        return pluginsDir.resolve("update");
    }

    /** 备份目录 plugins/../backup/（与 plugins 同级，避免被插件目录扫描加载）。 */
    public Path backupDir() {
        return pluginsDir.getParent() == null
                ? pluginsDir.resolve("backup")
                : pluginsDir.getParent().resolve("backup");
    }

    /** 已下载的暂存文件路径：plugins/update/<name>-<ver>.jar。 */
    public Path stagedFile(String name, String ver) {
        return updateDir().resolve(name + "-" + ver + ".jar");
    }

    /** 当前生效的插件文件：plugins/<name>.jar。 */
    public Path activeFile(String name) {
        return pluginsDir.resolve(name + ".jar");
    }

    /** 备份文件路径：backup/<name>-<ver>.jar。 */
    public Path backupFile(String name, String ver) {
        return backupDir().resolve(name + "-" + ver + ".jar");
    }

    /**
     * 把当前生效的插件备份到 backup/（在替换前调用）。
     *
     * @return 被备份的文件路径，若当前无插件则返回 null
     */
    public Path backupCurrent(String name, String currentVer) throws IOException {
        Path active = activeFile(name);
        if (!Files.exists(active)) return null;
        Files.createDirectories(backupDir());
        Path dest = backupFile(name, currentVer);
        Files.copy(active, dest, StandardCopyOption.REPLACE_EXISTING);
        return dest;
    }

    /**
     * 在 onDisable 阶段执行真正的替换：把暂存文件移动到 plugins/ 覆盖生效文件。
     *
     * <p>该方法必须在插件禁用（服务器关闭）时调用，<b>不得</b>在运行时热替换。</p>
     *
     * @param name    插件基础名（如 XxxPlugin）
     * @param ver     新版本（如 1.4.2）
     * @param currentVer 当前版本（用于备份命名）
     */
    public void applyOnDisable(String name, String ver, String currentVer) throws IOException {
        Path staged = stagedFile(name, ver);
        if (!Files.exists(staged)) {
            throw new IOException("暂存文件不存在，无法替换：" + staged);
        }
        backupCurrent(name, currentVer);
        Path active = activeFile(name);
        Files.createDirectories(pluginsDir);
        try {
            Files.move(staged, active, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.move(staged, active, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 回滚：用备份覆盖回当前生效文件。失败时抛出。
     *
     * @param name 插件基础名
     * @param ver  需回滚到的版本（备份文件名）
     */
    public void rollback(String name, String ver) throws IOException {
        Path backup = backupFile(name, ver);
        if (!Files.exists(backup)) {
            throw new IOException("找不到备份，无法回滚：" + backup);
        }
        Path active = activeFile(name);
        Files.move(backup, active, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 暂存文件是否存在（用于判断是否已下载完成、可 apply）。 */
    public boolean isStaged(String name, String ver) {
        return Files.exists(stagedFile(name, ver));
    }
}
