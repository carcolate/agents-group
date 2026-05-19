package com.carcolate.agents.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.carcolate.agents.domain.RagBase;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * RAG 向量库生命周期管理：
 * - 启动时全量向量化
 * - 知识库增删改后按 agentId 局部重建
 * - 提供按 agentId 过滤的 topK 检索
 */
@Slf4j
@Service
public class RagVectorService {

    private static final String META_AGENT_ID = "agentId";
    private static final String META_RAG_ID = "ragId";
    private static final String META_TITLE = "title";

    @Autowired
    private RagBaseService ragBaseService;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    @Value("${copilot.rag.chunk-size:500}")
    private int chunkSize;

    @Value("${copilot.rag.chunk-overlap:50}")
    private int chunkOverlap;

    @PostConstruct
    public void init() {
        try {
            // 只向量化 engageType=1（或为空，兼容历史数据）的启用文档；engageType=2 走前置注入不入向量
            LambdaQueryWrapper<RagBase> qw = new LambdaQueryWrapper<>();
            qw.eq(RagBase::getStatus, 1);
            qw.and(w -> w.eq(RagBase::getEngageType, RagBase.ENGAGE_RAG_SEARCH).or().isNull(RagBase::getEngageType));
            List<RagBase> all = ragBaseService.list(qw);
            log.info("[RAG] 启动向量化：共 {} 条 AI 自检索文档", all.size());
            for (RagBase rb : all) {
                indexOne(rb);
            }
            log.info("[RAG] 启动向量化完成");
        } catch (Exception e) {
            log.error("[RAG] 启动向量化失败", e);
        }
    }

    /**
     * 按 agentId 重建向量（先删后建）
     */
    public synchronized void rebuildByAgent(Long agentId) {
        if (agentId == null || agentId <= 0) {
            return;
        }
        try {
            Filter f = metadataKey(META_AGENT_ID).isEqualTo(agentId.toString());
            embeddingStore.removeAll(f);
            LambdaQueryWrapper<RagBase> qw = new LambdaQueryWrapper<>();
            qw.eq(RagBase::getAgentId, agentId);
            qw.eq(RagBase::getStatus, 1);
            qw.and(w -> w.eq(RagBase::getEngageType, RagBase.ENGAGE_RAG_SEARCH).or().isNull(RagBase::getEngageType));
            List<RagBase> list = ragBaseService.list(qw);
            for (RagBase rb : list) {
                indexOne(rb);
            }
            log.info("[RAG] 重建 agentId={} 完成，共 {} 条 AI 自检索文档", agentId, list.size());
        } catch (Exception e) {
            log.error("[RAG] 重建 agentId={} 失败", agentId, e);
        }
    }

    /**
     * 按 agentId 语义检索 topK
     */
    public List<EmbeddingMatch<TextSegment>> search(Long agentId, String query, int topK) {
        if (query == null || query.isBlank() || agentId == null || agentId <= 0) {
            return List.of();
        }
        Embedding q = embeddingModel.embed(query).content();
        EmbeddingSearchRequest req = EmbeddingSearchRequest.builder()
                .queryEmbedding(q)
                .maxResults(Math.max(1, topK))
                .minScore(0.5)
                .filter(metadataKey(META_AGENT_ID).isEqualTo(agentId.toString()))
                .build();
        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(req);
        return result.matches();
    }

    private void indexOne(RagBase rb) {
        if (rb == null || rb.getContent() == null || rb.getContent().isBlank()) {
            return;
        }
        Map<String, Object> meta = new HashMap<>();
        meta.put(META_AGENT_ID, String.valueOf(rb.getAgentId()));
        meta.put(META_RAG_ID, String.valueOf(rb.getId()));
        meta.put(META_TITLE, rb.getTitle() == null ? "" : rb.getTitle());
        Document doc = Document.from(rb.getContent(), Metadata.from(meta));
        DocumentSplitter splitter = DocumentSplitters.recursive(chunkSize, chunkOverlap);
        List<TextSegment> segments = splitter.split(doc);
        if (segments.isEmpty()) {
            return;
        }
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        embeddingStore.addAll(embeddings, segments);
        log.debug("[RAG] 索引文档 ragId={} title={} 分片数={}", rb.getId(), rb.getTitle(), segments.size());
    }
}
