package com.updategate.update;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.EdECPrivateKeySpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ed25519 验签代码路径测试（端到端闭环）。
 *
 * <p>使用测试独立密钥对验证 {@link IntegrityVerifier} 的验签实现，
 * 覆盖「正确签名通过 / 篡改原文失败 / 错误签名失败」三态；
 * 另验证源码中硬编码的生产公钥可被正常加载（保证 CI 签名可被客户端验签）。</p>
 */
class CryptoRoundTripTest {

    private KeyPair generateEd25519() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("Ed25519");
        return g.generateKeyPair();
    }

    private String sign(java.security.PrivateKey key, byte[] data) throws Exception {
        // 与 CI 中 openssl pkeyutl -sign -rawin 语义一致：Ed25519 内部对原文做哈希后签名
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(key);
        s.update(data);
        return Base64.getEncoder().encodeToString(s.sign());
    }

    @Test
    void verifyValidSignaturePasses() throws Exception {
        KeyPair kp = generateEd25519();
        byte[] manifest = "{\"schemaVersion\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sig = sign(kp.getPrivate(), manifest);

        IntegrityVerifier verifier = new IntegrityVerifier(kp.getPublic());
        assertTrue(verifier.verifySignature(manifest, sig), "合法 Ed25519 签名应验签通过");
    }

    @Test
    void verifyTamperedPayloadFails() throws Exception {
        KeyPair kp = generateEd25519();
        byte[] manifest = "{\"schemaVersion\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sig = sign(kp.getPrivate(), manifest);

        IntegrityVerifier verifier = new IntegrityVerifier(kp.getPublic());
        byte[] tampered = "{\"schemaVersion\":2}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(verifier.verifySignature(tampered, sig), "原文被篡改应验签失败");
    }

    @Test
    void verifyWrongSignatureFails() throws Exception {
        KeyPair kp = generateEd25519();
        KeyPair other = generateEd25519();
        byte[] manifest = "payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sig = sign(other.getPrivate(), manifest); // 用另一把私钥签名

        IntegrityVerifier verifier = new IntegrityVerifier(kp.getPublic());
        assertFalse(verifier.verifySignature(manifest, sig), "非配对私钥签名应验签失败");
    }

    @Test
    void hardcodedProductionPublicKeyLoads() {
        // 加载源码中硬编码的生产公钥不应抛异常（保证客户端可验签 CI 发布的包）
        IntegrityVerifier verifier = new IntegrityVerifier();
        org.junit.jupiter.api.Assertions.assertNotNull(verifier, "硬编码生产公钥应可被正常加载");
    }

    @Test
    void sha256MismatchDetected() throws Exception {
        java.nio.file.Path p = java.nio.file.Path.of(
                System.getProperty("java.io.tmpdir"), "ug-sha-" + System.nanoTime() + ".bin");
        java.nio.file.Files.write(p, new byte[]{1, 2, 3, 4});
        try {
            IntegrityVerifier verifier = new IntegrityVerifier();
            String real = IntegrityVerifier.sha256(p);
            assertTrue(verifier.verifySha256(p, real), "正确 sha256 应通过");
            assertFalse(verifier.verifySha256(p, "00"), "错误 sha256 应被拒绝");
        } finally {
            java.nio.file.Files.deleteIfExists(p);
        }
    }
}
