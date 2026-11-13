package com.updategate.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.util.HexFormat;
import java.util.Base64;

/**
 * 完整性与签名校验 + 原子替换。
 *
 * <p>完整性（缺一不可，对齐第 3.4 节）：</p>
 * <ul>
 *   <li>sha256 全量校验，不匹配立即删除并换镜像重下（最多 2 次，由调用方控制）；</li>
 *   <li>Ed25519 验签 manifest，公钥硬编码 + 可选远端轮换，验签失败一律终止；</li>
 *   <li>下载到 xxx.jar.part，校验通过后 {@code Files.move(ATOMIC_MOVE)} 覆盖。</li>
 * </ul>
 *
 * <p>安全红线：客户端不内置任何 Token；仅走 HTTPS；日志不打印完整 URL 中的签名参数。</p>
 */
public final class IntegrityVerifier {

    /**
     * 硬编码的 Ed25519 验签公钥（X.509 SubjectPublicKeyInfo，PEM）。
     *
     * <p>本公钥与仓库 Actions Secret {@code UPDATE_SIGNING_KEY}（Ed25519 私钥）配对，
     * 由 release.yml 用该私钥对 manifest 签名。生产环境请替换为本组织真实公钥，
     * 并支持通过远端 latest.json 中的 {@code signerPublicKey} 字段做密钥轮换。</p>
     */
    public static final String UPDATES_PUBLIC_KEY_PEM =
            "-----BEGIN PUBLIC KEY-----\n"
          + "MCowBQYDK2VwAyEAwqGjNZOW3HCgo/+jsVP14f9AEYJsbQcIHiS2mb7IPvI=\n"
          + "-----END PUBLIC KEY-----";

    private final PublicKey publicKey;

    public IntegrityVerifier() {
        this(decodePublicKeyPem(UPDATES_PUBLIC_KEY_PEM));
    }

    public IntegrityVerifier(PublicKey publicKey) {
        this.publicKey = publicKey;
    }

    /** 计算文件 sha256（小写十六进制 64 位）。 */
    public static String sha256(Path file) throws IOException {
        MessageDigest md = digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 校验 sha256，不匹配返回 false（由调用方决定是否删除重下）。 */
    public boolean verifySha256(Path file, String expectedHex) throws IOException {
        if (expectedHex == null || expectedHex.isBlank()) return false;
        return sha256(file).equalsIgnoreCase(expectedHex);
    }

    /** 删除文件（校验失败时使用）。 */
    public static void deleteQuietly(Path file) {
        try { Files.deleteIfExists(file); } catch (IOException ignored) {}
    }

    /**
     * Ed25519 验签 manifest。
     *
     * @param manifestBytes 待验签的 manifest 原文
     * @param signatureBase64 Base64 编码的签名
     * @return 验签是否通过（失败一律终止流程）
     */
    public boolean verifySignature(byte[] manifestBytes, String signatureBase64) {
        try {
            byte[] sig = Base64.getDecoder().decode(signatureBase64);
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(manifestBytes);
            return verifier.verify(sig);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 原子替换：把 part 文件移动到目标位置。
     *
     * @param part    已下载并校验通过的分片文件（xxx.jar.part）
     * @param target  目标文件（plugins/XxxPlugin.jar）
     */
    public void atomicReplace(Path part, Path target) throws IOException {
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        try {
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicFailed) {
            // 退化方案：部分文件系统不支持 ATOMIC_MOVE，退化为普通覆盖
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 从 X.509 PEM 解码 Ed25519 公钥。 */
    private static PublicKey decodePublicKeyPem(String pem) {
        try {
            String b64 = pem.replaceAll("-----BEGIN PUBLIC KEY-----", "")
                    .replaceAll("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] der = Base64.getDecoder().decode(b64);
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            return kf.generatePublic(new java.security.spec.X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("硬编码公钥非法", e);
        }
    }
}
