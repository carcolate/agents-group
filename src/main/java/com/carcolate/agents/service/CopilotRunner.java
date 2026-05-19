package com.carcolate.agents.service;

import com.alibaba.fastjson2.JSON;
import com.carcolate.agents.config.LangChainConfig;
import com.carcolate.agents.domain.Agent;
import com.carcolate.agents.domain.enums.StepType;
import com.carcolate.agents.domain.enums.TaskStatus;
import com.carcolate.agents.dto.CopilotRequest;
import com.carcolate.agents.dto.StepRecord;
import com.carcolate.agents.dto.TaskSnapshot;
import com.carcolate.agents.response.utils.RedisKeys;
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
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(buildSystemPrompt(agent, req)));
        messages.add(UserMessage.from(req.getCurrentMessage()));

        // 按 Agent 配置动态构建 ChatModel，未设置时回退到全局默认（available-models 第一项）
        Double agentTemp = agent.getTemperature() == null ? null : agent.getTemperature().doubleValue();
        ChatModel chatModel = langChainConfig.buildChatModel(agent.getModelName(), agentTemp);

        int maxSteps = agent.getMaxSteps() == null || agent.getMaxSteps() <= 0 ? 6 : agent.getMaxSteps();
        for (int i = 0; i < maxSteps; i++) {
            llmRoundRef[0] = i + 1;
            ChatRequest chatRequest = ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(List.of(ragSearchTool.spec()))
                    .build();

            ChatResponse resp = chatModel.chat(chatRequest);
            AiMessage ai = resp.aiMessage();
            messages.add(ai);

            boolean hasToolCall = ai.hasToolExecutionRequests();
            String thinkText = ai.text() == null ? "" : ai.text();
            String thinkContent = hasToolCall
                    ? thinkText + "\n[计划调用工具: " + JSON.toJSONString(ai.toolExecutionRequests()) + "]"
                    : thinkText;
            appendStep(uuid, StepRecord.of(StepType.LLM_THINK.getCode(), thinkContent));

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
            String polished = resp.aiMessage() == null ? null : resp.aiMessage().text();
            if (polished == null || polished.isBlank()) {
                appendStep(uuid, StepRecord.of(StepType.STYLE_REWRITE.getCode(),
                        "风格润色返回为空，回退草稿。草稿=" + draftReply));
                return draftReply;
            }
            polished = polished.trim();
            appendStep(uuid, StepRecord.of(StepType.STYLE_REWRITE.getCode(),
                    "草稿：" + draftReply + "\n----\n润色后：" + polished));
            return polished;
        } catch (Exception e) {
            log.warn("[Copilot] uuid={} 风格润色失败，回退草稿", uuid, e);
            appendStep(uuid, StepRecord.of(StepType.STYLE_REWRITE.getCode(),
                    "风格润色异常，回退草稿：" + e.getMessage()));
            return draftReply;
        }
    }

    private String buildSystemPrompt(Agent agent, CopilotRequest req) {
        StringBuilder sb = new StringBuilder();
        sb.append(agent.getPrePrompt() == null ? "" : agent.getPrePrompt());
        sb.append("\n\n## Agent 身份与使命\n").append(agent.getMission() == null ? "" : agent.getMission());
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
        sb.append("- 当需要产品资料、销售技巧、政策细节等具体知识时，必须调用 search_knowledge_base 工具检索，禁止凭空捏造。\n");
        sb.append("- 不需要调用工具时，直接给出最终回复（输出客户实际看到的那句话即可，不要再附思考过程）。\n");
        return sb.toString();
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
