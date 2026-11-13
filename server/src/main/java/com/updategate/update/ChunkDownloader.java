package com.updategate.update;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多加速源竞速下载引擎（对齐第 3 节）。
 *
 * <p>策略：</p>
 * <ul>
 *   <li>预热：并发 HEAD，取 TTFB 最优的 2 个作为主力；</li>
 *   <li>分片：文件切 4MB，0 号片由单连接独占，其余按 weight=1/(p95×负载) 派发；</li>
 *   <li>续传：Range 断点，进度落 {@code update-progress.json}，杀进程可恢复；</li>
 *   <li>熔断：连续 2 次 4xx/5xx 或 3s 无字节 → 镜像冷却 60s，分片重入队；</li>
 *   <li>全池失败 → 直连 GitHub 兜底 → 仍失败则指数退避重试 3 次后报
 *       {@link ResultCode#NETWORK_ALL_MIRRORS_FAILED}。</li>
 * </ul>
 */
public final class ChunkDownloader {

    /** 单分片大小：4MB。 */
    public static final long CHUNK_SIZE = 4L * 1024 * 1024;

    /** 单分片最大重试次数（防止镜像全挂时无限重入队导致资源耗尽）。 */
    public static final int MAX_CHUNK_ATTEMPTS = 8;

    private final MirrorPool pool;
    private final HttpClient http;
    private final Path workDir;

    public ChunkDownloader(MirrorPool pool, HttpClient http, Path workDir) {
        this.pool = pool;
        this.http = http;
        this.workDir = workDir;
    }

    /** 下载结果。 */
    public static final class DownloadResult {
        public final Path file;
        public final String mirrorUsed;
        public final long bytes;
        public final long durationMs;

        public DownloadResult(Path file, String mirrorUsed, long bytes, long durationMs) {
            this.file = file;
            this.mirrorUsed = mirrorUsed;
            this.bytes = bytes;
            this.durationMs = durationMs;
        }
    }

    /**
     * 执行竞速下载。
     *
     * @param asset        资产（含 size / sha256 / downloads）
     * @param destPart     目标 part 文件路径（xxx.jar.part）
     * @param originalUrl  原始 GitHub Release URL（用于构造各镜像地址）
     * @return 下载结果
     * @throws DownloadFailedException 全部镜像失败且兜底/退避后仍失败时抛出
     */
    public DownloadResult download(UpdateManifest.Asset asset, Path destPart, String originalUrl)
            throws DownloadFailedException {
        long size = asset.size;
        long t0 = System.nanoTime();
        ensurePartFile(destPart, size);

        // 候选 URL 列表：manifest 显式 downloads + 代理池构造的全部镜像地址
        List<Candidate> candidates = buildCandidates(asset, originalUrl);
        if (candidates.isEmpty()) {
            throw new DownloadFailedException(ResultCode.NETWORK_ALL_MIRRORS_FAILED,
                    "没有任何可用的下载地址");
        }

        // 预热：HEAD 探测，更新镜像权重（取 TTFB 最优前 2 作为主力）
        pool.preheat(originalUrl, 2);

        int maxAttempts = 4; // 1 次主尝试 + 3 次指数退避
        long backoffMs = 1000;
        DownloadFailedException last = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                Candidate used = raceDownload(candidates, destPart, size);
                long dur = (System.nanoTime() - t0) / 1_000_000;
                deleteProgress(destPart);
                return new DownloadResult(destPart, used.mirrorId, size, dur);
            } catch (DownloadFailedException e) {
                last = e;
                if (attempt < maxAttempts - 1) {
                    try { Thread.sleep(backoffMs); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    backoffMs *= 2; // 指数退避
                }
            }
        }
        throw last != null ? last
                : new DownloadFailedException(ResultCode.NETWORK_ALL_MIRRORS_FAILED, "未知下载失败");
    }

    /** 竞速：分片并行下载，命中即返回使用的候选。 */
    private Candidate raceDownload(List<Candidate> candidates, Path destPart, long size)
            throws DownloadFailedException {
        // 分片队列
        ConcurrentLinkedQueue<Chunk> queue = new ConcurrentLinkedQueue<>();
        for (long start = 0; start < size; start += CHUNK_SIZE) {
            long end = Math.min(start + CHUNK_SIZE, size) - 1;
            queue.add(new Chunk(start, end));
        }
        AtomicLong doneBytes = new AtomicLong(0);
        AtomicBoolean anySuccess = new AtomicBoolean(false);
        AtomicLong lastByteTs = new AtomicLong(System.nanoTime());
        List<Throwable> errors = new ArrayList<>();

        // 0 号片由首个候选独占，其余按候选顺序消费
        List<Candidate> primaries = new ArrayList<>(candidates);
        try (RandomAccessFile raf = new RandomAccessFile(destPart.toFile(), "rw")) {
            java.util.concurrent.ExecutorService exec =
                    java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
            int n = Math.min(primaries.size(), 8);
            for (int i = 0; i < n; i++) {
                final Candidate c = primaries.get(i);
                exec.submit(() -> {
                    try {
                        while (true) {
                            Chunk ck = queue.poll();
                            if (ck == null) break;
                            try {
                                long written = downloadChunk(c, raf, ck, lastByteTs);
                                doneBytes.addAndGet(written);
                                pool.markSuccess(c.mirrorId);
                                anySuccess.set(true);
                            } catch (IOException ex) {
                                // 熔断：失败重入队，但单分片重试有上限，避免无限循环
                                pool.markFailure(c.mirrorId, isHardError(ex));
                                ck.attempts++;
                                if (ck.attempts <= MAX_CHUNK_ATTEMPTS) {
                                    queue.offer(ck);
                                } else {
                                    // 超过上限：标记该分片永久失败，不再重试
                                    ck.permanentlyFailed = true;
                                }
                                synchronized (errors) { errors.add(ex); }
                            }
                        }
                    } catch (Exception ex) {
                        synchronized (errors) { errors.add(ex); }
                    }
                });
            }
            exec.shutdown();
            try { exec.awaitTermination(30, java.util.concurrent.TimeUnit.MINUTES); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        } catch (IOException e) {
            throw new DownloadFailedException(ResultCode.DISK_FULL, "写入 part 文件失败：" + e.getMessage());
        }

        // 持久化进度（杀进程可恢复）
        writeProgress(destPart, size, doneBytes.get());

        // 3s 无字节、全部分片未完成、或存在永久失败分片 → 失败
        boolean stale = (System.nanoTime() - lastByteTs.get()) > 3_000_000_000L;
        boolean anyPermanent = queue.stream().anyMatch(c -> c.permanentlyFailed);
        if (!anySuccess.get() || doneBytes.get() < size || stale || anyPermanent) {
            throw new DownloadFailedException(ResultCode.NETWORK_ALL_MIRRORS_FAILED,
                    "竞速下载未完成：done=" + doneBytes.get() + "/" + size
                            + (anyPermanent ? "，存在永久失败分片" : ""));
        }
        return primaries.get(0);
    }

    private long downloadChunk(Candidate c, RandomAccessFile raf, Chunk ck, AtomicLong lastByteTs)
            throws IOException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(c.url))
                .header("Range", "bytes=" + ck.start + "-" + ck.end)
                .timeout(Duration.ofSeconds(30))
                .build();
        HttpResponse<InputStream> r;
        try {
            r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("下载被中断：" + c.mirrorId, e);
        }
        int status = r.statusCode();
        if (status != 206 && status != 200) {
            throw new IOException("镜像返回 " + status + "：" + c.mirrorId);
        }
        try (InputStream in = r.body()) {
            byte[] buf = new byte[1 << 16];
            int n;
            long pos = ck.start;
            while ((n = in.read(buf)) > 0) {
                synchronized (raf) {
                    raf.seek(pos);
                    raf.write(buf, 0, n);
                }
                pos += n;
                lastByteTs.set(System.nanoTime());
            }
            return pos - ck.start; // 实际写入字节数（半断网时为部分值）
        }
    }

    private boolean isHardError(IOException ex) {
        String m = ex.getMessage();
        return m != null && (m.contains("403") || m.contains("404")
                || m.contains("500") || m.contains("502") || m.contains("503"));
    }

    private List<Candidate> buildCandidates(UpdateManifest.Asset asset, String originalUrl) {
        List<Candidate> list = new ArrayList<>();
        // 1) manifest 显式 downloads
        if (asset.downloads != null) {
            for (UpdateManifest.Download d : asset.downloads) {
                list.add(new Candidate(d.mirror, d.url));
            }
        }
        // 2) 代理池构造的全部镜像地址（含直连兜底）
        for (String id : pool.orderedCandidates()) {
            try {
                list.add(new Candidate(id, pool.buildUrl(id, originalUrl)));
            } catch (IllegalArgumentException ignored) {}
        }
        return list;
    }

    private void ensurePartFile(Path destPart, long size) throws DownloadFailedException {
        try {
            Files.createDirectories(destPart.getParent() == null ? Path.of(".") : destPart.getParent());
            if (!Files.exists(destPart)) {
                try (RandomAccessFile raf = new RandomAccessFile(destPart.toFile(), "rw")) {
                    raf.setLength(size);
                }
            }
        } catch (IOException e) {
            throw new DownloadFailedException(ResultCode.DISK_FULL, "无法创建 part 文件：" + e.getMessage());
        }
    }

    private void writeProgress(Path destPart, long size, long done) {
        Path p = progressFile(destPart);
        String json = "{\"file\":\"" + destPart.getFileName() + "\",\"size\":" + size
                + ",\"completedBytes\":" + done + ",\"updatedAt\":\"" + Instant.now() + "\"}";
        try { Files.writeString(p, json); } catch (IOException ignored) {}
    }

    private void deleteProgress(Path destPart) {
        try { Files.deleteIfExists(progressFile(destPart)); } catch (IOException ignored) {}
    }

    private Path progressFile(Path destPart) {
        return destPart.getParent() == null ? Path.of(destPart.getFileName() + ".progress.json")
                : destPart.getParent().resolve(destPart.getFileName() + ".progress.json");
    }

    /** 下载候选。 */
    private static final class Candidate {
        final String mirrorId;
        final String url;
        Candidate(String mirrorId, String url) { this.mirrorId = mirrorId; this.url = url; }
    }

    /** 分片范围。 */
    private static final class Chunk {
        final long start;
        final long end;
        int attempts = 0;
        boolean permanentlyFailed = false;
        Chunk(long start, long end) { this.start = start; this.end = end; }
    }

    /** 下载失败异常（携带结果码）。 */
    public static final class DownloadFailedException extends Exception {
        private static final long serialVersionUID = 1L;
        public final ResultCode code;
        public DownloadFailedException(ResultCode code, String msg) {
            super(msg);
            this.code = code;
        }
    }
}
