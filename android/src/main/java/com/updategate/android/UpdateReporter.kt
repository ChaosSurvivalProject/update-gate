package com.updategate.android

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 可观测上报（对齐第 7 节）。
 *
 * <p>上报字段：instanceId(匿名 UUID)、from→to、result_code、mirror_used、
 * duration_ms、bytes、fail_reason；<b>不上报 IP 与账号信息</b>。</p>
 *
 * <p>result_code 枚举同服务端。设计为「尽力而为、失败静默」。</p>
 */
class UpdateReporter(
    private val context: Context,
    private val http: OkHttpClient,
    private val endpoint: String = ""
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("ug_report", Context.MODE_PRIVATE)
    val instanceId: String = prefs.getString("instance_id", null) ?: run {
        val id = UUID.randomUUID().toString()
        prefs.edit().putString("instance_id", id).apply()
        id
    }

    /** 灰度命中判定（基于 instanceId 哈希，避免上报 IP）。 */
    fun isInRollout(rolloutPercent: Int): Boolean {
        if (rolloutPercent >= 100) return true
        if (rolloutPercent <= 0) return false
        return (instanceId.hashCode().let { if (it < 0) -it else it } % 100) < rolloutPercent
    }

    /** 上报（异步、非阻塞）。 */
    suspend fun report(
        from: String, to: String, code: ResultCode,
        mirrorUsed: String, durationMs: Long, bytes: Long, failReason: String
    ) = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put("instanceId", instanceId)
            put("from", from)
            put("to", to)
            put("result_code", code.name)
            put("mirror_used", mirrorUsed)
            put("duration_ms", durationMs)
            put("bytes", bytes)
            put("fail_reason", failReason)
        }.toString()
        if (endpoint.isEmpty()) {
            Log.i("UpdateReporter", json)
            return@withContext
        }
        try {
            val req = Request.Builder().url(endpoint)
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { /* 丢弃结果 */ }
        } catch (_: Exception) { /* 静默 */ }
    }
}
