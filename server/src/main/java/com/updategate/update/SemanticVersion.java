package com.updategate.update;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义化版本解析与比较（遵循 Semantic Versioning 2.0.0 的优先级规则）。
 *
 * <p>支持 preRelease 与 build metadata：preRelease 仅在 channel=beta 时可见；
 * 比较时 build metadata 不参与优先级判定；带 preRelease 的版本优先级低于同核心版本的非预发布版本。</p>
 *
 * <p>本类为纯函数式工具，不依赖任何 Paper/Android 运行时，便于单元测试。</p>
 */
public final class SemanticVersion implements Comparable<SemanticVersion> {

    private final int major;
    private final int minor;
    private final int patch;
    /** 预发布标识（如 "beta.1"），无则为空列表。 */
    private final List<String> preRelease;
    /** 构建元数据（如 "20261113"），不参与优先级判定。 */
    private final String buildMetadata;

    private SemanticVersion(int major, int minor, int patch,
                            List<String> preRelease, String buildMetadata) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.preRelease = List.copyOf(preRelease);
        this.buildMetadata = buildMetadata == null ? "" : buildMetadata;
    }

    /**
     * 解析版本字符串。允许 "1.21.11"、"1.4.2-beta.1"、"1.0.0+20261113" 等形式。
     *
     * @throws IllegalArgumentException 格式非法时抛出
     */
    public static SemanticVersion parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("版本字符串为空");
        }
        String core = text;
        String build = "";
        int plus = core.indexOf('+');
        if (plus >= 0) {
            build = core.substring(plus + 1);
            core = core.substring(0, plus);
        }
        String pre = "";
        int dash = core.indexOf('-');
        if (dash >= 0) {
            pre = core.substring(dash + 1);
            core = core.substring(0, dash);
        }
        String[] parts = core.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("版本核心必须是 major.minor.patch：[" + text + "]");
        }
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            int patch = Integer.parseInt(parts[2]);
            List<String> preList = new ArrayList<>();
            if (!pre.isEmpty()) {
                for (String p : pre.split("\\.")) {
                    preList.add(p);
                }
            }
            return new SemanticVersion(major, minor, patch, preList, build);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("版本号段必须为整数：[" + text + "]", e);
        }
    }

    public int getMajor() { return major; }
    public int getMinor() { return minor; }
    public int getPatch() { return patch; }
    public boolean isPreRelease() { return !preRelease.isEmpty(); }

    /** 仅比较核心版本（major.minor.patch），忽略 preRelease/build。 */
    public int compareCore(SemanticVersion o) {
        int c = Integer.compare(major, o.major);
        if (c != 0) return c;
        c = Integer.compare(minor, o.minor);
        if (c != 0) return c;
        return Integer.compare(patch, o.patch);
    }

    @Override
    public int compareTo(SemanticVersion o) {
        int c = compareCore(o);
        if (c != 0) return c;
        // 核心相同：无预发布 > 有预发布
        if (preRelease.isEmpty() && o.preRelease.isEmpty()) return 0;
        if (preRelease.isEmpty()) return 1;
        if (o.preRelease.isEmpty()) return -1;
        int n = Math.min(preRelease.size(), o.preRelease.size());
        for (int i = 0; i < n; i++) {
            int cmp = comparePreReleaseId(preRelease.get(i), o.preRelease.get(i));
            if (cmp != 0) return cmp;
        }
        return Integer.compare(preRelease.size(), o.preRelease.size());
    }

    /** preRelease 标识比较：数字按数值，非数字按字典序，数字 < 非数字。 */
    private static int comparePreReleaseId(String a, String b) {
        boolean aNum = a.matches("\\d+");
        boolean bNum = b.matches("\\d+");
        if (aNum && bNum) return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
        if (aNum) return -1;
        if (bNum) return 1;
        return a.compareTo(b);
    }

    public boolean equalsCore(SemanticVersion o) {
        return compareCore(o) == 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SemanticVersion)) return false;
        return compareTo((SemanticVersion) o) == 0;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(major) * 31 * 31
                + Integer.hashCode(minor) * 31
                + Integer.hashCode(patch);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(major + "." + minor + "." + patch);
        if (!preRelease.isEmpty()) {
            sb.append('-').append(String.join(".", preRelease));
        }
        if (!buildMetadata.isEmpty()) {
            sb.append('+').append(buildMetadata);
        }
        return sb.toString();
    }
}
