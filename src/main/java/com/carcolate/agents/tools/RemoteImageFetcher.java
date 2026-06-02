package com.carcolate.agents.tools;

import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * 远程图片下载器：把 historyMessages 里给的 image URL 拉下来，
 * 转成 base64 + mimeType，由 CopilotRunner 注入给多模态 LLM。
 *
 * <p>所有边界情况（超时、非图片 Content-Type、超大、非白名单 MIME、HTTP 非 2xx）
 * 都统一抛 {@link IOException}，由上层捕获后走「不阻塞主流程，仅文本占位」策略。</p>
 */
@Slf4j
@Component
public class RemoteImageFetcher {

    @Value("${copilot.image.timeout-seconds:10}")
    private int timeoutSeconds;

    @Value("${copilot.image.max-bytes:5242880}")
    private long maxBytes;

    /**
     * 允许的 MIME 白名单。逗号分隔自动映射为 List；统一小写比对。
     */
    @Value("${copilot.image.allowed-mime:image/png,image/jpeg,image/webp,image/gif}")
    private List<String> allowedMime;

    /**
     * 本地文件缓存目录。命中后跳过 HTTP 下载，直接读盘。
     * 默认 {@code ./cache}（app.jar 同级的运行目录下）；Docker 部署在 Dockerfile 中通过 ENV 覆盖到 /app/cache。
     */
    @Value("${copilot.image.cache-dir:./cache}")
    private String cacheDir;

    @PostConstruct
    public void initCacheDir() {
        try {
            Path dir = Paths.get(cacheDir);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            log.info("[ImageCache] 缓存目录就绪 dir={}", dir.toAbsolutePath());
        } catch (Exception e) {
            log.warn("[ImageCache] 缓存目录创建失败 dir={}，将降级为每次都下载", cacheDir, e);
        }
    }

    public FetchedImage fetch(String url) throws IOException {
        if (url == null || url.isBlank()) {
            throw new IOException("图片 URL 为空");
        }
        long startMs = System.currentTimeMillis();

        // 1) 先查本地缓存：{md5}.dat + {md5}.mime 同时存在即命中
        String md5 = md5Hex(url);
        Path dataFile = Paths.get(cacheDir, md5 + ".dat");
        Path mimeFile = Paths.get(cacheDir, md5 + ".mime");
        if (Files.isRegularFile(dataFile) && Files.isRegularFile(mimeFile)) {
            try {
                byte[] data = Files.readAllBytes(dataFile);
                String mime = Files.readString(mimeFile, StandardCharsets.UTF_8).trim();
                if (data.length > 0 && !mime.isBlank() && isAllowed(mime)) {
                    String base64 = Base64.getEncoder().encodeToString(data);
                    long cost = System.currentTimeMillis() - startMs;
                    return new FetchedImage(base64, mime, data.length, cost, true, true);
                }
                log.warn("[ImageCache] 缓存文件不可用，回退下载 md5={} mime={} bytes={}",
                        md5, mime, data.length);
            } catch (Exception e) {
                log.warn("[ImageCache] 读取缓存失败，回退下载 md5={}", md5, e);
            }
        }

        // 2) 未命中：HTTP 下载
        int timeoutMs = (int) Math.min((long) timeoutSeconds * 1000, Integer.MAX_VALUE);
        RequestConfig cfg = RequestConfig.custom()
                .setConnectTimeout(timeoutMs)
                .setSocketTimeout(timeoutMs)
                .setConnectionRequestTimeout(timeoutMs)
                .build();
        try (CloseableHttpClient client = HttpClients.custom().setDefaultRequestConfig(cfg).build();
             CloseableHttpResponse rsp = client.execute(new HttpGet(url))) {
            int status = rsp.getStatusLine().getStatusCode();
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status);
            }
            HttpEntity entity = rsp.getEntity();
            if (entity == null) {
                throw new IOException("响应体为空");
            }
            String rawCt = entity.getContentType() == null ? null : entity.getContentType().getValue();
            byte[] data = readWithLimit(entity, maxBytes);
            String mime = resolveMime(rawCt, url);
            if (!isAllowed(mime)) {
                throw new IOException("MIME 不在白名单: " + mime);
            }
            // 3) 写入本地缓存（先 .tmp 再 rename，避免半成品被命中）
            boolean cached = writeCache(md5, data, mime);
            String base64 = Base64.getEncoder().encodeToString(data);
            long cost = System.currentTimeMillis() - startMs;
            return new FetchedImage(base64, mime, data.length, cost, false, cached);
        }
    }

    /**
     * 计算 URL 的 MD5（hex 小写）。失败时回退用 URL 的 hashCode 兜底，保证总能算出文件名。
     */
    private String md5Hex(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "fallback_" + Integer.toHexString(url.hashCode());
        }
    }

    /**
     * 把下载好的图片落盘到 {@code {md5}.dat + {md5}.mime}。
     * @return true = 落盘成功（两个文件都已就位）；false = 任何环节失败
     */
    private boolean writeCache(String md5, byte[] data, String mime) {
        Path dir = Paths.get(cacheDir);
        Path dataFile = dir.resolve(md5 + ".dat");
        Path mimeFile = dir.resolve(md5 + ".mime");
        Path dataTmp = dir.resolve(md5 + ".dat.tmp");
        Path mimeTmp = dir.resolve(md5 + ".mime.tmp");
        try {
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            Files.write(dataTmp, data);
            Files.writeString(mimeTmp, mime, StandardCharsets.UTF_8);
            moveReplace(dataTmp, dataFile);
            moveReplace(mimeTmp, mimeFile);
            if (log.isInfoEnabled()) {
                log.info("[ImageCache] 已写入 md5={} bytes={} mime={} path={}",
                        md5, data.length, mime, dataFile.toAbsolutePath());
            }
            return true;
        } catch (Exception e) {
            // 用 error 级别 + 完整 stack，方便排查权限 / 跨文件系统 / 磁盘满等问题
            log.error("[ImageCache] 写缓存失败 md5={} dir={}（本次请求仍可用）",
                    md5, dir.toAbsolutePath(), e);
            // 清掉残留 .tmp，免得目录里全是垃圾
            safeDelete(dataTmp);
            safeDelete(mimeTmp);
            return false;
        }
    }

    /**
     * 优先原子重命名；底层文件系统（如 Docker overlayfs + bind mount / NFS）不支持时，
     * 降级为普通 move + REPLACE。
     */
    private void moveReplace(Path src, Path dst) throws IOException {
        try {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ame) {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void safeDelete(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (Exception ignore) {
        }
    }

    private byte[] readWithLimit(HttpEntity entity, long limit) throws IOException {
        try (InputStream is = entity.getContent()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = is.read(buf)) > 0) {
                total += n;
                if (total > limit) {
                    throw new IOException("图片超过最大尺寸 " + limit + " bytes");
                }
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    /**
     * 解析 MIME：响应头 Content-Type 优先（剥离 charset 等参数）→ URL 后缀映射 → 默认 image/jpeg。
     */
    private String resolveMime(String contentType, String url) {
        if (contentType != null && !contentType.isBlank()) {
            String ct = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (ct.startsWith("image/")) {
                return ct;
            }
        }
        String ext = extOf(url);
        switch (ext) {
            case "png":
                return "image/png";
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "webp":
                return "image/webp";
            case "gif":
                return "image/gif";
            default:
                return "image/jpeg";
        }
    }

    private String extOf(String url) {
        try {
            String path = URI.create(url).getPath();
            if (path == null) return "";
            int dot = path.lastIndexOf('.');
            if (dot < 0 || dot == path.length() - 1) return "";
            return path.substring(dot + 1).toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    private boolean isAllowed(String mime) {
        if (allowedMime == null || allowedMime.isEmpty()) return true;
        for (String m : allowedMime) {
            if (m != null && m.trim().equalsIgnoreCase(mime)) {
                return true;
            }
        }
        return false;
    }

    @Data
    @AllArgsConstructor
    public static class FetchedImage {
        /**
         * 图片字节流的 base64（不含 data: 前缀）
         */
        private String base64;
        /**
         * MIME 类型，如 image/jpeg
         */
        private String mimeType;
        /**
         * 原始字节数（base64 前）
         */
        private int bytes;
        /**
         * 总耗时（毫秒）：命中缓存仅含读盘耗时，未命中包含网络下载 + 写盘耗时
         */
        private long costMs;
        /**
         * 是否命中本地缓存：true = 直接读盘；false = 走 HTTP 下载
         */
        private boolean fromCache;
        /**
         * 本次结束时缓存是否就绪。
         * <ul>
         *   <li>fromCache=true → 必然 true</li>
         *   <li>fromCache=false → true 表示刚下载并已成功写盘，false 表示写盘失败（仅本次内存可用）</li>
         * </ul>
         */
        private boolean cached;
    }
}
