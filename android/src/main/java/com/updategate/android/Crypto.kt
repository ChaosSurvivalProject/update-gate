package com.updategate.android

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.security.KeyFactory
import android.util.Log

/**
 * 完整性与签名校验（对齐第 3.4 / 第 8 节）。
 * - sha256 全量校验，不匹配立即删除并换镜像重下（最多 2 次）；
 * - Ed25519 验签 manifest，公钥硬编码 + 可选远端轮换，验签失败一律终止。
 */
object Crypto {

    /** 硬编码的 Ed25519 验签公钥（X.509 PEM），与仓库 Actions Secret UPDATE_SIGNING_KEY 配对。 */
    const val UPDATES_PUBLIC_KEY_PEM = """
        -----BEGIN PUBLIC KEY-----
        MCowBQYDK2VwAyEAwqGjNZOW3HCgo/+jsVP14f9AEYJsbQcIHiS2mb7IPvI=
        -----END PUBLIC KEY-----
    """.trimIndent()

    /** 计算文件 sha256（小写十六进制 64 位）。 */
    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins: InputStream ->
            val buf = ByteArray(1 shl 16)
            var n: Int
            while (ins.read(buf).also { n = it } > 0) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun verifySha256(file: File, expectedHex: String): Boolean {
        if (expectedHex.isBlank()) return false
        return sha256(file).equals(expectedHex, ignoreCase = true)
    }

    /** Ed25519 验签 manifest（验签失败一律终止）。 */
    fun verifySignature(manifestBytes: ByteArray, signatureBase64: String): Boolean {
        return try {
            val b64 = UPDATES_PUBLIC_KEY_PEM
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\\s+".toRegex(), "")
            val der = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            val kf = KeyFactory.getInstance("Ed25519")
            val pub = kf.generatePublic(X509EncodedKeySpec(der))
            val sig = Signature.getInstance("Ed25519")
            sig.initVerify(pub)
            sig.update(manifestBytes)
            sig.verify(android.util.Base64.decode(signatureBase64, android.util.Base64.DEFAULT))
        } catch (e: Exception) {
            Log.w("Crypto", "Ed25519 验签异常", e)
            false
        }
    }
}
