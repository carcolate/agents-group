package com.carcolate.agents.service;

import com.carcolate.agents.config.LangChainConfig;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库文档摘要生成：调用全局默认 ChatModel，将文档内容浓缩为 100 字以内的中文一句话摘要。
 */
@Slf4j
@Service
public class RagSummaryService {

    /**
     * 最大字符上限（防止单条摘要过长）
     */
    private static final int MAX_LEN = 200;

    /**
     * 截给 LLM 看的正文最大字符数（防止 token 爆掉）
     */
    private static final int CONTENT_TRUNCATE = 6000;

    @Autowired
    private LangChainConfig langChainConfig;

    /**
     * 生成摘要。失败/为空时返回 null，由调用方决定是否回退。
     */
    public String summarize(String title, String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String src = content.length() > CONTENT_TRUNCATE
                ? content.substring(0, CONTENT_TRUNCATE) + "……(后文略)"
                : content;
        try {
            ChatModel model = langChainConfig.buildChatModel(null);
            String sys = "你是文档摘要助手。请严格用一句话中文概括下方文档的核心主题与覆盖范围，"
                    + "禁止编造未出现的事实，禁止加任何前后缀（如\"本文档介绍了\"、\"摘要：\"），"
                    + "总长度严格控制在 100 字以内，只输出摘要正文。";
            String usr = "【文档标题】" + (title == null ? "" : title) + "\n\n【文档正文】\n" + src;
            List<ChatMessage> msgs = new ArrayList<>();
            msgs.add(SystemMessage.from(sys));
            msgs.add(UserMessage.from(usr));

            ChatRequest req = ChatRequest.builder().messages(msgs).build();
            ChatResponse resp = model.chat(req);
            String text = resp.aiMessage() == null ? null : resp.aiMessage().text();
            if (text == null) {
                return null;
            }
            text = text.trim().replaceAll("\\s+", " ");
            if (text.length() > MAX_LEN) {
                text = text.substring(0, MAX_LEN);
            }
            return text.isBlank() ? null : text;
        } catch (Exception e) {
            log.warn("[RagSummary] 生成摘要失败 title={}", title, e);
            return null;
        }
    }
}
