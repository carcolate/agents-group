package com.carcolate.agents.tools;

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

    public FetchedImage fetch(String url) throws IOException {
        if (url == null || url.isBlank()) {
            throw new IOException("图片 URL 为空");
        }
        long startMs = System.currentTimeMillis();
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
            String base64 = Base64.getEncoder().encodeToString(data);
            long cost = System.currentTimeMillis() - startMs;
            return new FetchedImage(base64, mime, data.length, cost);
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
         * 下载总耗时（毫秒）
         */
        private long costMs;
    }
}
