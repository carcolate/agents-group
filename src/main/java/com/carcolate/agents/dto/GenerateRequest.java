package com.carcolate.agents.dto;

import java.util.List;

/** 已由调用方组装完成的单次模型请求。 */
public record GenerateRequest(Long agentId, String userId, String systemPrompt, String userPrompt,
                              List<Attachment> attachments) {
    public record Attachment(String url, String caption) {}
}
