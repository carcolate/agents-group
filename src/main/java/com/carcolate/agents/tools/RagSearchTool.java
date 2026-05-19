package com.carcolate.agents.tools;

import com.alibaba.fastjson2.JSONObject;
import com.carcolate.agents.service.RagVectorService;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RAG 知识库检索 Tool（由 LangChain4j 以 Tool Call 方式调用）
 */
@Slf4j
@Component
public class RagSearchTool {

    public static final String TOOL_NAME = "search_knowledge_base";

    @Autowired
    private RagVectorService ragVectorService;

    @Value("${copilot.rag.default-top-k:3}")
    private int defaultTopK;

    /**
     * 构造 Tool 规范，提供给 LLM 做 Tool Call
     */
    public ToolSpecification spec() {
        JsonObjectSchema params = JsonObjectSchema.builder()
                .addStringProperty("query", "需要检索的关键词或问题")
                .addIntegerProperty("topK", "返回结果数量，可选，默认 3")
                .required("query")
                .build();
        return ToolSpecification.builder()
                .name(TOOL_NAME)
                .description("按语义检索当前 Agent 的知识库，返回最相关的若干文档片段。当需要产品资料、销售技巧、政策细节等知识时调用。")
                .parameters(params)
                .build();
    }

    /**
     * 执行检索，返回供 LLM 消费的纯文本
     */
    public String invoke(Long agentId, String argumentsJson) {
        String query;
        int topK = defaultTopK;
        try {
            JsonObject jo = parse(argumentsJson);
            query = jo.query;
            if (jo.topK != null && jo.topK > 0) {
                topK = jo.topK;
            }
        } catch (Exception e) {
            log.warn("[Tool] 参数解析失败：{}", argumentsJson, e);
            return "工具调用失败：参数解析异常 " + e.getMessage();
        }
        if (query == null || query.isBlank()) {
            return "工具调用失败：query 不能为空";
        }
        List<EmbeddingMatch<TextSegment>> matches = ragVectorService.search(agentId, query, topK);
        if (matches.isEmpty()) {
            return "未在知识库中检索到相关内容。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("共检索到 ").append(matches.size()).append(" 条相关片段：\n\n");
        int idx = 1;
        for (EmbeddingMatch<TextSegment> m : matches) {
            TextSegment seg = m.embedded();
            String title = seg.metadata().getString("title");
            sb.append("【片段 ").append(idx++).append("】")
                    .append(title == null ? "" : ("(" + title + ")"))
                    .append(" 相关度=")
                    .append(String.format("%.3f", m.score()))
                    .append("\n")
                    .append(seg.text())
                    .append("\n\n");
        }
        return sb.toString().trim();
    }

    private JsonObject parse(String json) {
        if (json == null || json.isBlank()) {
            return new JsonObject();
        }
        com.alibaba.fastjson2.JSONObject jo = JSONObject.parseObject(json);
        JsonObject out = new JsonObject();
        out.query = jo.getString("query");
        out.topK = jo.getInteger("topK");
        return out;
    }

    private static class JsonObject {
        String query;
        Integer topK;
    }
}
