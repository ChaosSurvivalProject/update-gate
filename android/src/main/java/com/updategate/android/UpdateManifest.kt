package com.updategate.android

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 版本契约数据模型（对齐 schemas/update-manifest-v1.schema.json）。
 * 由 CI 用 Ed25519 私钥签名，客户端验签后解析。
 */
object UpdateManifest {

    @Serializable
    data class Root(
        @SerialName("schemaVersion") val schemaVersion: Int,
        @SerialName("generatedAt") val generatedAt: String = "",
        @SerialName("channels") val channels: List<Channel>
    ) {
        fun channel(id: String): Channel? = channels.firstOrNull { it.id.equals(id, true) }
    }

    @Serializable
    data class Channel(
        @SerialName("id") val id: String,
        @SerialName("target") val target: Target,
        @SerialName("minSupported") val minSupported: MinSupported,
        @SerialName("minClientVersion") val minClientVersion: String = "",
        @SerialName("mode") val mode: String = "OPTIONAL",
        @SerialName("rolloutPercent") val rolloutPercent: Int = 100,
        @SerialName("assets") val assets: List<Asset>
    ) {
        fun updateMode(): UpdateMode = if (mode == "REQUIRED") UpdateMode.REQUIRED else UpdateMode.OPTIONAL
        fun asset(platform: String, abi: String?): Asset? = assets.firstOrNull {
            it.platform.equals(platform, true) && (abi == null || it.abi.equals(abi, true))
        }
    }

    @Serializable
    data class Target(
        @SerialName("version") val version: String,
        @SerialName("versionCode") val versionCode: Int,
        @SerialName("apiVersion") val apiVersion: String
    )

    @Serializable
    data class MinSupported(
        @SerialName("version") val version: String,
        @SerialName("apiVersion") val apiVersion: String
    )

    @Serializable
    data class Asset(
        @SerialName("platform") val platform: String,
        @SerialName("abi") val abi: String? = null,
        @SerialName("fileName") val fileName: String,
        @SerialName("size") val size: Long,
        @SerialName("sha256") val sha256: String,
        @SerialName("signatureFingerprint") val signatureFingerprint: String? = null,
        @SerialName("patch") val patch: Patch? = null,
        @SerialName("downloads") val downloads: List<Download>
    ) {
        fun firstDownload(): Download? = downloads.firstOrNull()
    }

    @Serializable
    data class Patch(
        @SerialName("fromVersionCode") val fromVersionCode: Int,
        @SerialName("fileName") val fileName: String,
        @SerialName("size") val size: Long,
        @SerialName("sha256") val sha256: String,
        @SerialName("algorithm") val algorithm: String // bsdiff
    )

    @Serializable
    data class Download(
        @SerialName("mirror") val mirror: String,
        @SerialName("url") val url: String
    )

    fun fromJson(json: String): Root {
        val root = Json { ignoreUnknownKeys = true }.decodeFromString<Root>(json)
        require(root.schemaVersion == 1) { "不支持的 schemaVersion：${root.schemaVersion}" }
        require(root.channels.isNotEmpty()) { "manifest 缺少 channels" }
        return root
    }
}
