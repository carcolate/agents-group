package com.carcolate.agents.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.carcolate.agents.domain.RagBase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 知识库 URL 自动刷新。
 *
 * <p>管理保存时由调用方同步触发；Copilot 运行时在异步任务内部触发，
 * 刷新失败只记录日志并继续使用上一次成功内容，避免远程 URL 短暂不可用阻断客服回复。</p>
 */
@Slf4j
@Service
public class RagRefreshService {

    private final RestTemplate restTemplate;
    private final ConcurrentHashMap<Long, Object> locks = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    private RagBaseService ragBaseService;

    @org.springframework.beans.factory.annotation.Autowired
    private RagVectorService ragVectorService;

    @org.springframework.beans.factory.annotation.Autowired
    private RagSummaryService ragSummaryService;

    public RagRefreshService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(15_000);
        factory.setReadTimeout(60_000);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 保存前同步刷新：只负责拉取并填充对象，保存和向量重建由管理控制器继续完成。
     */
    public void refreshBeforeSave(RagBase ragBase) {
        if (!isConfigured(ragBase)) {
            return;
        }
        String content = fetchContent(ragBase.getUrl());
        ragBase.setContent(content);
        ragBase.setLastRefreshTime(Instant.now());
    }

    /**
     * Copilot 异步任务内刷新当前 Agent 的启用知识库。
     */
    public void refreshForAgent(Long agentId) {
        if (agentId == null || agentId <= 0) {
            return;
        }
        LambdaQueryWrapper<RagBase> query = new LambdaQueryWrapper<>();
        query.eq(RagBase::getAgentId, agentId)
                .eq(RagBase::getStatus, 1)
                .eq(RagBase::getAutoRefresh, 1)
                .isNotNull(RagBase::getUrl)
                .ne(RagBase::getUrl, "");
        List<RagBase> docs = ragBaseService.list(query);
        boolean changed = false;
        for (RagBase doc : docs) {
            if (!shouldRefresh(doc, Instant.now())) {
                continue;
            }
            Object lock = locks.computeIfAbsent(doc.getId(), ignored -> new Object());
            synchronized (lock) {
                RagBase current = ragBaseService.getById(doc.getId());
                if (!shouldRefresh(current, Instant.now())) {
                    continue;
                }
                try {
                    String content = fetchContent(current.getUrl());
                    current.setContent(content);
                    if (!Integer.valueOf(RagBase.ENGAGE_PREPEND).equals(current.getEngageType())) {
                        String summary = ragSummaryService.summarize(current.getTitle(), content);
                        if (summary != null) {
                            current.setSummary(summary);
                        }
                    }
                    current.setLastRefreshTime(Instant.now());
                    current.setUpdatedAt(Instant.now());
                    if (!ragBaseService.updateById(current)) {
                        throw new IllegalStateException("保存刷新内容失败");
                    }
                    changed = true;
                } catch (Exception e) {
                    log.warn("[RAG] URL 刷新失败，继续使用旧内容：ragId={}, agentId={}, error={}",
                            current.getId(), current.getAgentId(), e.getMessage());
                }
            }
        }
        if (changed) {
            ragVectorService.rebuildByAgent(agentId);
        }
    }

    private boolean shouldRefresh(RagBase ragBase, Instant now) {
        if (!isConfigured(ragBase)) {
            return false;
        }
        Integer interval = ragBase.getRefreshIntervalMinutes() == null ? 60 : ragBase.getRefreshIntervalMinutes();
        if (interval <= 0 || ragBase.getLastRefreshTime() == null) {
            return true;
        }
        return !now.isBefore(ragBase.getLastRefreshTime().plusSeconds(interval.longValue() * 60L));
    }

    private boolean isConfigured(RagBase ragBase) {
        return ragBase != null
                && Integer.valueOf(1).equals(ragBase.getAutoRefresh())
                && ragBase.getUrl() != null
                && !ragBase.getUrl().isBlank();
    }

    private String fetchContent(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.TEXT_PLAIN, MediaType.TEXT_HTML, MediaType.APPLICATION_JSON, MediaType.ALL));
        ResponseEntity<String> response = restTemplate.exchange(
                url.trim(), HttpMethod.GET, new HttpEntity<>(headers), String.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("URL 返回 HTTP " + response.getStatusCode().value());
        }
        String content = response.getBody();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("URL 返回内容为空");
        }
        return content;
    }
}
