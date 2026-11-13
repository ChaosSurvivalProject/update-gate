package com.updategate.update;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 故障注入测试（验收 9.3）。
 *
 * <p>模拟四类故障：断网 / 半断网 / 镜像返回 403 / 返回错误 sha256，
 * 断言下载引擎进入正确的熔断与兜底路径。</p>
 */
class FaultInjectionTest {

    private HttpServer server;
    private int port;
    private final byte[] payload = "UPDATEGATE-FAULT-INJECTION-PAYLOAD-".repeat(4096).getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        registerHandlers();
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private void registerHandlers() {
        // /ok：正确处理 Range，返回完整且正确的内容
        server.createContext("/ok", ex -> {
            String range = ex.getRequestHeaders().getFirst("Range");
            long start = 0, end = payload.length - 1;
            if (range != null && range.startsWith("bytes=")) {
                String[] p = range.substring(6).split("-");
                start = Long.parseLong(p[0]);
                if (!p[1].isEmpty()) end = Long.parseLong(p[1]);
            }
            long len = end - start + 1;
            byte[] slice = new byte[(int) len];
            System.arraycopy(payload, (int) start, slice, 0, (int) len);
            ex.sendResponseHeaders(206, len);
            try (OutputStream os = ex.getResponseBody()) { os.write(slice); }
        });
        // /forbidden：返回 403
        server.createContext("/forbidden", ex -> {
            ex.sendResponseHeaders(403, -1);
            ex.close();
        });
        // /partial：半断网——发送部分字节后直接断开
        server.createContext("/partial", ex -> {
            String range = ex.getRequestHeaders().getFirst("Range");
            long start = 0;
            if (range != null && range.startsWith("bytes=")) {
                start = Long.parseLong(range.substring(6).split("-")[0]);
            }
            int sent = (int) Math.min(payload.length - start, 4096);
            ex.sendResponseHeaders(206, sent);
            OutputStream os = ex.getResponseBody();
            os.write(payload, (int) start, sent);
            os.flush();
            ex.close(); // 不发送剩余字节，模拟半断网
        });
    }

    // ---- 辅助：构造资产 ----
    private UpdateManifest.Asset asset(String url, String sha) {
        UpdateManifest.Asset a = new UpdateManifest.Asset();
        a.platform = "paper-plugin";
        a.fileName = "X.jar";
        a.size = payload.length;
        a.sha256 = sha;
        a.downloads = List.of(); // 全部走代理池构造的 URL
        return a;
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(md.digest(data));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private HttpClient http() {
        return HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(2))
                .build();
    }

    // ---- 故障 1：断网（端口无服务）----
    @Test
    void fault_networkDown_allMirrorsFailed() throws Exception {
        // 指向一个未监听的端口，所有镜像连接被拒
        TestMirrorPool pool = new TestMirrorPool("http://127.0.0.1:1/ok");
        Path part = Files.createTempFile("ug-down", ".part");
        Files.deleteIfExists(part);
        ChunkDownloader dl = new ChunkDownloader(pool, http(), part.getParent());
        ChunkDownloader.DownloadFailedException ex = assertThrows(
                ChunkDownloader.DownloadFailedException.class,
                () -> dl.download(asset("http://127.0.0.1:1/ok", sha256(payload)), part, "http://127.0.0.1:1/ok"));
        assertEquals(ResultCode.NETWORK_ALL_MIRRORS_FAILED, ex.code);
    }

    // ---- 故障 2：镜像返回 403 ----
    @Test
    void fault_http403_allMirrorsFailed() throws Exception {
        TestMirrorPool pool = new TestMirrorPool("http://127.0.0.1:" + port + "/forbidden");
        Path part = Files.createTempFile("ug-down", ".part");
        Files.deleteIfExists(part);
        ChunkDownloader dl = new ChunkDownloader(pool, http(), part.getParent());
        ChunkDownloader.DownloadFailedException ex = assertThrows(
                ChunkDownloader.DownloadFailedException.class,
                () -> dl.download(asset("http://127.0.0.1:" + port + "/forbidden", sha256(payload)),
                        part, "http://127.0.0.1:" + port + "/forbidden"));
        assertEquals(ResultCode.NETWORK_ALL_MIRRORS_FAILED, ex.code);
    }

    // ---- 故障 3：半断网（只收到部分字节）----
    @Test
    void fault_halfNetwork_incomplete() throws Exception {
        TestMirrorPool pool = new TestMirrorPool("http://127.0.0.1:" + port + "/partial");
        Path part = Files.createTempFile("ug-down", ".part");
        Files.deleteIfExists(part);
        ChunkDownloader dl = new ChunkDownloader(pool, http(), part.getParent());
        ChunkDownloader.DownloadFailedException ex = assertThrows(
                ChunkDownloader.DownloadFailedException.class,
                () -> dl.download(asset("http://127.0.0.1:" + port + "/partial", sha256(payload)),
                        part, "http://127.0.0.1:" + port + "/partial"));
        assertEquals(ResultCode.NETWORK_ALL_MIRRORS_FAILED, ex.code);
    }

    // ---- 故障 4：返回错误 sha256（内容被篡改）----
    @Test
    void fault_wrongSha256_detected() throws Exception {
        // 服务端返回内容，但断言的 sha256 与实际不符 → 校验必须失败
        TestMirrorPool pool = new TestMirrorPool("http://127.0.0.1:" + port + "/ok");
        Path part = Files.createTempFile("ug-down", ".part");
        Files.deleteIfExists(part);
        ChunkDownloader dl = new ChunkDownloader(pool, http(), part.getParent());
        ChunkDownloader.DownloadResult res = dl.download(
                asset("http://127.0.0.1:" + port + "/ok", sha256(payload)), part, "http://127.0.0.1:" + port + "/ok");
        assertTrue(Files.exists(part), "下载应已落盘");
        // 故意用错误 sha 校验：必须返回 false（触发换镜像重下，最多 2 次）
        IntegrityVerifier verifier = new IntegrityVerifier();
        assertFalse(verifier.verifySha256(part, "deadbeef".repeat(8)),
                "错误 sha256 必须被检出");
        // 正确 sha 则应通过
        assertTrue(verifier.verifySha256(part, sha256(payload)), "正确 sha256 应通过");
    }

    // ---- 基线：正常网络可成功下载并校验 ----
    @Test
    void baseline_successfulDownloadAndVerify() throws Exception {
        TestMirrorPool pool = new TestMirrorPool("http://127.0.0.1:" + port + "/ok");
        Path part = Files.createTempFile("ug-down", ".part");
        Files.deleteIfExists(part);
        ChunkDownloader dl = new ChunkDownloader(pool, http(), part.getParent());
        ChunkDownloader.DownloadResult res = dl.download(
                asset("http://127.0.0.1:" + port + "/ok", sha256(payload)), part, "http://127.0.0.1:" + port + "/ok");
        assertEquals(payload.length, res.bytes);
        IntegrityVerifier verifier = new IntegrityVerifier();
        assertTrue(verifier.verifySha256(part, sha256(payload)));
    }

    /** 测试用代理池：把所有候选映射到同一个本地故障地址。 */
    static class TestMirrorPool extends MirrorPool {
        private final String baseUrl;
        TestMirrorPool(String baseUrl) { this.baseUrl = baseUrl; }
        @Override
        public List<String> orderedCandidates() { return List.of("t1", "t2"); }
        @Override
        public String buildUrl(String id, String originalUrl) { return baseUrl; }
    }
}
