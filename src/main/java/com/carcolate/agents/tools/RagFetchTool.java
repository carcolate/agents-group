package com.carcolate.agents.tools;

import com.alibaba.fastjson2.JSONObject;
import com.carcolate.agents.domain.RagBase;
import com.carcolate.agents.service.RagBaseService;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 按 ragId 直接查阅指定知识库文档（全量文本 + 概览），由 LangChain4j 以 Tool Call 方式调用。
 * 适用场景：LLM 已经在 systemPrompt 的【可用知识库目录】里看到某条文档与问题强相关，
 * 想看完整正文而不是片段时，直接传 ragId 拉全文。
 */
@Slf4j
@Component
public class RagFetchTool {

    public static final String TOOL_NAME = "get_knowledge_document";

    @Autowired
    private RagBaseService ragBaseService;

    /**
     * 构造 Tool 规范，提供给 LLM 做 Tool Call
     */
    public ToolSpecification spec() {
        JsonObjectSchema params = JsonObjectSchema.builder()
                .addIntegerProperty("ragId", "知识库文档 ID（来自 systemPrompt 中【可用知识库目录】的 [ID=xxx]）")
                .required("ragId")
                .build();
        return ToolSpecification.builder()
                .name(TOOL_NAME)
                .description("根据知识库文档 ID 直接获取该文档的全量文本与概览。"
                        + "当需要某条文档的完整内容（而不是关键字片段）时调用，例如完整产品手册、完整政策条款。")
                .parameters(params)
                .build();
    }

    /**
     * 执行获取，返回供 LLM 消费的纯文本。
     * 严格校验：文档必须存在、属于当前 Agent、状态为启用。
     */
    public String invoke(Long agentId, String argumentsJson) {
        Long ragId;
        try {
            if (argumentsJson == null || argumentsJson.isBlank()) {
                return "工具调用失败：参数为空，必须传入 ragId";
            }
            JSONObject jo = JSONObject.parseObject(argumentsJson);
            ragId = jo.getLong("ragId");
        } catch (Exception e) {
            log.warn("[Tool] get_knowledge_document 参数解析失败：{}", argumentsJson, e);
            return "工具调用失败：参数解析异常 " + e.getMessage();
        }
        if (ragId == null || ragId <= 0) {
            return "工具调用失败：ragId 不能为空且必须为正整数";
        }

        RagBase rb;
        try {
            rb = ragBaseService.getById(ragId);
        } catch (Exception e) {
            log.warn("[Tool] get_knowledge_document 查询失败 ragId={}", ragId, e);
            return "工具调用失败：查询知识库异常 " + e.getMessage();
        }
        if (rb == null) {
            return "工具调用失败：未找到 ragId=" + ragId + " 的知识库文档";
        }
        // 防止跨 Agent 越权读取其他 Agent 的资料
        if (agentId == null || rb.getAgentId() == null || !agentId.equals(rb.getAgentId())) {
            return "工具调用失败：ragId=" + ragId + " 不属于当前 Agent";
        }
        if (rb.getStatus() != null && rb.getStatus() != 1) {
            return "工具调用失败：ragId=" + ragId + " 已禁用，无法读取";
        }

        String title = rb.getTitle() == null ? "(无标题)" : rb.getTitle();
        String summary = rb.getSummary() == null || rb.getSummary().isBlank()
                ? "(暂无摘要)" : rb.getSummary();
        String content = rb.getContent() == null ? "" : rb.getContent();

        StringBuilder sb = new StringBuilder();
        sb.append("【知识库文档 ID=").append(ragId).append("】\n");
        sb.append("标题：").append(title).append("\n");
        sb.append("概览：").append(summary).append("\n");
        sb.append("----- 全量正文 -----\n");
        sb.append(content);
        return sb.toString();
    }
}
