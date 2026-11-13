package com.updategate.android

/**
 * 语义化版本解析与比较（遵循 Semantic Versioning 2.0.0 优先级规则）。
 * 支持 preRelease 与 build metadata；preRelease 仅在 channel=beta 可见。
 */
data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: List<String> = emptyList(),
    val buildMetadata: String = ""
) : Comparable<SemanticVersion> {

    fun isPreRelease(): Boolean = preRelease.isNotEmpty()

    fun compareCore(other: SemanticVersion): Int {
        var c = major.compareTo(other.major)
        if (c != 0) return c
        c = minor.compareTo(other.minor)
        if (c != 0) return c
        return patch.compareTo(other.patch)
    }

    override fun compareTo(other: SemanticVersion): Int {
        val c = compareCore(other)
        if (c != 0) return c
        if (preRelease.isEmpty() && other.preRelease.isEmpty()) return 0
        if (preRelease.isEmpty()) return 1
        if (other.preRelease.isEmpty()) return -1
        val n = minOf(preRelease.size, other.preRelease.size)
        for (i in 0 until n) {
            val cmp = comparePreReleaseId(preRelease[i], other.preRelease[i])
            if (cmp != 0) return cmp
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    private fun comparePreReleaseId(a: String, b: String): Int {
        val aNum = a.matches("\\d+".toRegex())
        val bNum = b.matches("\\d+".toRegex())
        if (aNum && bNum) return a.toInt().compareTo(b.toInt())
        if (aNum) return -1
        if (bNum) return 1
        return a.compareTo(b)
    }

    override fun toString(): String {
        val sb = StringBuilder("$major.$minor.$patch")
        if (preRelease.isNotEmpty()) sb.append("-").append(preRelease.joinToString("."))
        if (buildMetadata.isNotEmpty()) sb.append("+").append(buildMetadata)
        return sb.toString()
    }

    companion object {
        /** 解析 "1.21.11"、"1.4.2-beta.1"、"1.0.0+20261113" 等形式。 */
        fun parse(text: String): SemanticVersion {
            require(text.isNotBlank()) { "版本字符串为空" }
            var core = text
            var build = ""
            val plus = core.indexOf('+')
            if (plus >= 0) {
                build = core.substring(plus + 1)
                core = core.substring(0, plus)
            }
            var pre = ""
            val dash = core.indexOf('-')
            if (dash >= 0) {
                pre = core.substring(dash + 1)
                core = core.substring(0, dash)
            }
            val parts = core.split(".")
            require(parts.size == 3) { "版本核心必须是 major.minor.patch：[$text]" }
            val preList = if (pre.isEmpty()) emptyList() else pre.split(".")
            return SemanticVersion(
                major = parts[0].toInt(),
                minor = parts[1].toInt(),
                patch = parts[2].toInt(),
                preRelease = preList,
                buildMetadata = build
            )
        }
    }
}
