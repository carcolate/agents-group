package com.carcolate.agents.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.carcolate.agents.config.LangChainConfig;
import com.carcolate.agents.domain.Agent;
import com.carcolate.agents.domain.RagBase;
import com.carcolate.agents.domain.enums.StepType;
import com.carcolate.agents.domain.enums.TaskStatus;
import com.carcolate.agents.dto.CopilotRequest;
import com.carcolate.agents.dto.StepRecord;
import com.carcolate.agents.dto.TaskSnapshot;
import com.carcolate.agents.response.utils.RedisKeys;
import com.carcolate.agents.tools.RagFetchTool;
import com.carcolate.agents.tools.RagSearchTool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Copilot 核心 ReAct 编排（独立 Bean，便于 @Async 代理生效）
 */
@Slf4j
@Component
public class CopilotRunner {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private LangChainConfig langChainConfig;

    @Autowired
    private RagSearchTool ragSearchTool;

    @Autowired
    private RagFetchTool ragFetchTool;

    @Autowired
    private RagBaseService ragBaseService;

    @Autowired
    private CopilotHistoryService copilotHistoryService;

    @Value("${copilot.task.ttl-seconds:86400}")
    private long ttlSeconds;

    @Async("copilotExecutor")
    public void run(String uuid, Agent agent, CopilotRequest req) {
        long startMs = System.currentTimeMillis();
        int[] llmRoundRef = new int[]{0};
        try {
            doRun(uuid, agent, req, startMs, llmRoundRef);
        } catch (Exception e) {
            log.error("[Copilot] uuid={} 执行异常", uuid, e);
            TaskSnapshot snap = load(uuid);
            if (snap == null) return;
            snap.getSteps().add(StepRecord.of(StepType.ERROR.getCode(), e.getMessage()));
            snap.setStatus(TaskStatus.FAILED.getCode());
            snap.setErrorMsg(e.getMessage());
            snap.setFinishedAt(Instant.now());
            save(snap);
            persistHistory(snap, req, llmRoundRef[0], startMs);
        }
    }

    private void doRun(String uuid, Agent agent, CopilotRequest req, long startMs, int[] llmRoundRef) {
        String systemPrompt = buildSystemPrompt(agent, req);
        TaskSnapshot snap = load(uuid);
        if (snap != null) {
            snap.setSystemPrompt(systemPrompt);
            save(snap);
        }
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        messages.add(UserMessage.from(req.getCurrentMessage()));

        // 按 Agent 配置动态构建 ChatModel，未设置时回退到全局默认（available-models 第一项）
        Double agentTemp = agent.getTemperature() == null ? null : agent.getTemperature().doubleValue();
        ChatModel chatModel = langChainConfig.buildChatModel(agent.getModelName(), agentTemp);

        int maxSteps = agent.getMaxSteps() == null || agent.getMaxSteps() <= 0 ? 6 : agent.getMaxSteps();
        for (int i = 0; i < maxSteps; i++) {
            llmRoundRef[0] = i + 1;
            ChatRequest chatRequest = ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(List.of(ragSearchTool.spec(), ragFetchTool.spec()))
                    .build();

            ChatResponse resp = chatModel.chat(chatRequest);
            AiMessage ai = resp.aiMessage();
            messages.add(ai);

            boolean hasToolCall = ai.hasToolExecutionRequests();
            String thinkText = ai.text() == null ? "" : ai.text();
            String thinkContent = hasToolCall
                    ? thinkText + "\n[计划调用工具: " + JSON.toJSONString(ai.toolExecutionRequests()) + "]"
                    : thinkText;
            String reasoning = safeThinking(ai);
            appendStep(uuid, StepRecord.ofThink(StepType.LLM_THINK.getCode(), thinkContent, reasoning));

            if (!hasToolCall) {
                String finalReply = applyStylePolish(uuid, chatModel, agent, thinkText);
                appendStep(uuid, StepRecord.of(StepType.FINAL_REPLY.getCode(), finalReply));
                markDone(uuid, finalReply, req, llmRoundRef[0], startMs);
                return;
            }

            for (ToolExecutionRequest call : ai.toolExecutionRequests()) {
                appendStep(uuid, StepRecord.ofTool(
                        StepType.TOOL_CALL.getCode(),
                        call.name(),
                        call.arguments(),
                        "调用工具：" + call.name()
                ));
                String toolResult;
                if (RagSearchTool.TOOL_NAME.equals(call.name())) {
                    toolResult = ragSearchTool.invoke(agent.getId(), call.arguments());
                } else if (RagFetchTool.TOOL_NAME.equals(call.name())) {
                    toolResult = ragFetchTool.invoke(agent.getId(), call.arguments());
                } else {
                    toolResult = "未知工具：" + call.name();
                }
                appendStep(uuid, StepRecord.ofTool(
                        StepType.TOOL_RESULT.getCode(),
                        call.name(),
                        call.arguments(),
                        toolResult
                ));
                messages.add(ToolExecutionResultMessage.from(call, toolResult));
            }
        }
        markFailed(uuid, "超过最大决策轮数(" + maxSteps + ")，未产出最终回复",
                req, llmRoundRef[0], startMs);
    }

    /**
     * 用 agent.stylePrompt 对主 Agent 产出的草稿做风格润色，生成终稿。
     * - stylePrompt 为空：直接返回草稿，不调用 LLM。
     * - 润色调用失败：记录错误步骤，回退草稿，保证主流程可用。
     */
    private String applyStylePolish(String uuid, ChatModel chatModel, Agent agent, String draftReply) {
        String stylePrompt = agent.getStylePrompt();
        if (stylePrompt == null || stylePrompt.isBlank()) {
            return draftReply;
        }
        if (draftReply == null || draftReply.isBlank()) {
            return draftReply;
        }
        try {
            String sys = "你是文本风格改写助手。严格按照下方【风格要求】对【原始回复】做语气、措辞、长度的润色，"
                    + "禁止增删事实信息、禁止编造数据、禁止改变核心结论。只输出改写后的终稿正文，不要任何解释、前后缀、引号或代码块。\n\n"
                    + "【风格要求】\n" + stylePrompt;
            String usr = "【原始回复】\n" + draftReply;

            List<ChatMessage> msgs = new ArrayList<>();
            msgs.add(SystemMessage.from(sys));
            msgs.add(UserMessage.from(usr));

            ChatRequest req = ChatRequest.builder().messages(msgs).build();
            ChatResponse resp = chatModel.chat(req);
            AiMessage polishAi = resp.aiMessage();
            String polished = polishAi == null ? null : polishAi.text();
            String polishThinking = safeThinking(polishAi);
            if (polished == null || polished.isBlank()) {
                appendStep(uuid, StepRecord.ofThink(StepType.STYLE_REWRITE.getCode(),
                        "风格润色返回为空，回退草稿。草稿=" + draftReply, polishThinking));
                return draftReply;
            }
            polished = polished.trim();
            appendStep(uuid, StepRecord.ofThink(StepType.STYLE_REWRITE.getCode(),
                    "草稿：" + draftReply + "\n----\n润色后：" + polished, polishThinking));
            return polished;
        } catch (Exception e) {
            log.warn("[Copilot] uuid={} 风格润色失败，回退草稿", uuid, e);
            appendStep(uuid, StepRecord.of(StepType.STYLE_REWRITE.getCode(),
                    "风格润色异常，回退草稿：" + e.getMessage()));
            return draftReply;
        }
    }

    /**
     * 安全读取 AiMessage 的思维链。reasoning 模型才有；普通模型返回 null。
     * 用反射兜底，防止个别老版本 LangChain4j 缺方法导致主流程崩溃。
     */
    private String safeThinking(AiMessage ai) {
        if (ai == null) return null;
        try {
            String t = ai.thinking();
            return (t == null || t.isBlank()) ? null : t;
        } catch (Throwable ignore) {
            return null;
        }
    }

    private String buildSystemPrompt(Agent agent, CopilotRequest req) {
        StringBuilder sb = new StringBuilder();
        sb.append(agent.getPrePrompt() == null ? "" : agent.getPrePrompt());
        sb.append("\n\n## Agent 身份与使命\n").append(agent.getMission() == null ? "" : agent.getMission());

        // 拉一次启用状态文档，按 engageType 分两路使用：
        //   1 → 进入「可用知识库目录」，等 LLM 通过工具按需检索
        //   2 → 全文拼到「前置知识库」章节，主 Agent 直接可读，不走向量库
        KnowledgeBundle kb = loadKnowledgeBundle(agent.getId());

        if (kb.prependText != null && !kb.prependText.isEmpty()) {
            sb.append("\n\n## 前置知识库（已全量提供，可直接引用）\n").append(kb.prependText);
        }
        if (kb.catalog != null && !kb.catalog.isEmpty()) {
            sb.append("\n\n## 可用知识库目录（如需详情请调用工具）\n").append(kb.catalog);
        }

        if (req.getHistorySummary() != null && !req.getHistorySummary().isBlank()) {
            sb.append("\n\n## 更早历史消息总结\n").append(req.getHistorySummary());
        }
        if (req.getHistoryMessages() != null && !req.getHistoryMessages().isEmpty()) {
            sb.append("\n\n## 近期对话\n");
            for (String m : req.getHistoryMessages()) {
                sb.append("- ").append(m).append("\n");
            }
        }
        sb.append("\n\n## 工具调用规则\n");
        sb.append("- 上方【前置知识库】已提供完整正文，回答时可直接引用，无需调用工具。\n");
        sb.append("- 上方【可用知识库目录】每条记录前的 [ID=xxx] 是该文档的 ragId。\n");
        sb.append("- 可用工具：\n");
        sb.append("  · search_knowledge_base(query, topK?)：按语义关键字检索片段，适合不确定答案落在哪个文档时使用。\n");
        sb.append("  · get_knowledge_document(ragId)：直接拉取指定文档的全量正文+概览，适合已经在目录里锁定目标文档、需要完整内容时使用。\n");
        sb.append("- 决策顺序：前置知识库够用 → 直接回答；目录里能精准锁定某文档 → 用 get_knowledge_document 取全文；只有模糊关键字 → 用 search_knowledge_base 检索片段。\n");
        sb.append("- 严禁凭空捏造未在【前置知识库】或工具返回结果中出现的事实、参数、价格。\n");
        sb.append("- 不需要调用工具时，直接给出最终回复（输出客户实际看到的那句话即可，不要再附思考过程）。\n");
        return sb.toString();
    }

    /**
     * 一次性查出 Agent 启用文档，按 engageType 分成 catalog（AI 自检索目录）和 prependText（前置全文）两份。
     */
    private KnowledgeBundle loadKnowledgeBundle(Long agentId) {
        KnowledgeBundle kb = new KnowledgeBundle();
        if (agentId == null) return kb;
        try {
            LambdaQueryWrapper<RagBase> qw = new LambdaQueryWrapper<>();
            qw.eq(RagBase::getAgentId, agentId);
            qw.eq(RagBase::getStatus, 1);
            qw.select(RagBase::getId, RagBase::getTitle, RagBase::getSummary,
                    RagBase::getEngageType, RagBase::getContent);
            qw.orderByAsc(RagBase::getCreatedAt);
            List<RagBase> docs = ragBaseService.list(qw);
            if (docs == null || docs.isEmpty()) {
                kb.catalog = "（当前 Agent 暂无知识库文档）";
                return kb;
            }
            StringBuilder catalogSb = new StringBuilder();
            StringBuilder prependSb = new StringBuilder();
            int prependCount = 0;
            for (RagBase d : docs) {
                String title = d.getTitle() == null ? "(无标题)" : d.getTitle();
                Integer et = d.getEngageType();
                if (et != null && et == RagBase.ENGAGE_PREPEND) {
                    String content = d.getContent() == null ? "" : d.getContent();
                    if (content.isBlank()) continue;
                    if (prependCount > 0) prependSb.append("\n\n");
                    prependSb.append("### ").append(title).append("\n").append(content);
                    prependCount++;
                } else {
                    String summary = d.getSummary() == null || d.getSummary().isBlank()
                            ? "(暂无摘要)" : d.getSummary();
                    catalogSb.append("- [ID=").append(d.getId()).append("] 《")
                            .append(title).append("》：").append(summary).append("\n");
                }
            }
            kb.prependText = prependSb.length() == 0 ? null : prependSb.toString();
            kb.catalog = catalogSb.length() == 0
                    ? (prependCount > 0 ? null : "（当前 Agent 暂无可检索知识库文档）")
                    : catalogSb.toString();
            return kb;
        } catch (Exception e) {
            log.warn("[Copilot] 构建知识库包失败 agentId={}", agentId, e);
            return kb;
        }
    }

    /** 知识库注入包：前置全文 + 自检索目录 */
    private static class KnowledgeBundle {
        String prependText;
        String catalog;
    }

    private TaskSnapshot load(String uuid) {
        Object o = redisTemplate.opsForValue().get(RedisKeys.copilotTask(uuid));
        if (o == null) return null;
        return JSON.parseObject(JSON.toJSONString(o), TaskSnapshot.class);
    }

    private void appendStep(String uuid, StepRecord step) {
        TaskSnapshot snap = load(uuid);
        if (snap == null) return;
        snap.getSteps().add(step);
        save(snap);
    }

    private void markDone(String uuid, String finalReply, CopilotRequest req, int llmRound, long startMs) {
        TaskSnapshot snap = load(uuid);
        if (snap == null) return;
        snap.setStatus(TaskStatus.SUCCESS.getCode());
        snap.setFinalReply(finalReply);
        snap.setFinishedAt(Instant.now());
        save(snap);
        persistHistory(snap, req, llmRound, startMs);
    }

    private void markFailed(String uuid, String reason, CopilotRequest req, int llmRound, long startMs) {
        TaskSnapshot snap = load(uuid);
        if (snap == null) return;
        snap.getSteps().add(StepRecord.of(StepType.ERROR.getCode(), reason));
        snap.setStatus(TaskStatus.FAILED.getCode());
        snap.setErrorMsg(reason);
        snap.setFinishedAt(Instant.now());
        save(snap);
        persistHistory(snap, req, llmRound, startMs);
    }

    private void persistHistory(TaskSnapshot snap, CopilotRequest req, int llmRound, long startMs) {
        String userMsg = req == null ? null : req.getCurrentMessage();
        long costMs = System.currentTimeMillis() - startMs;
        copilotHistoryService.recordFromSnapshot(snap, userMsg, llmRound, costMs);
    }

    private void save(TaskSnapshot snap) {
        redisTemplate.opsForValue().set(
                RedisKeys.copilotTask(snap.getUuid()),
                snap,
                ttlSeconds,
                TimeUnit.SECONDS
        );
    }
}
