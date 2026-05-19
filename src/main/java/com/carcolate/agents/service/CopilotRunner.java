package com.carcolate.agents.service;

import com.alibaba.fastjson2.JSON;
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
    private ChatModel chatModel;

    @Autowired
    private RagSearchTool ragSearchTool;

    @Value("${copilot.task.ttl-seconds:86400}")
    private long ttlSeconds;

    @Async("copilotExecutor")
    public void run(String uuid, Agent agent, CopilotRequest req) {
        try {
            doRun(uuid, agent, req);
        } catch (Exception e) {
            log.error("[Copilot] uuid={} 执行异常", uuid, e);
            TaskSnapshot snap = load(uuid);
            if (snap == null) return;
            snap.getSteps().add(StepRecord.of(StepType.ERROR.getCode(), e.getMessage()));
            snap.setStatus(TaskStatus.FAILED.getCode());
            snap.setErrorMsg(e.getMessage());
            snap.setFinishedAt(Instant.now());
            save(snap);
        }
    }

    private void doRun(String uuid, Agent agent, CopilotRequest req) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(buildSystemPrompt(agent, req)));
        messages.add(UserMessage.from(req.getCurrentMessage()));

        int maxSteps = agent.getMaxSteps() == null || agent.getMaxSteps() <= 0 ? 6 : agent.getMaxSteps();
        for (int i = 0; i < maxSteps; i++) {
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
                appendStep(uuid, StepRecord.of(StepType.FINAL_REPLY.getCode(), thinkText));
                markDone(uuid, thinkText);
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
        markFailed(uuid, "超过最大决策轮数(" + maxSteps + ")，未产出最终回复");
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

    private void markDone(String uuid, String finalReply) {
        TaskSnapshot snap = load(uuid);
        if (snap == null) return;
        snap.setStatus(TaskStatus.SUCCESS.getCode());
        snap.setFinalReply(finalReply);
        snap.setFinishedAt(Instant.now());
        save(snap);
    }

    private void markFailed(String uuid, String reason) {
        TaskSnapshot snap = load(uuid);
        if (snap == null) return;
        snap.getSteps().add(StepRecord.of(StepType.ERROR.getCode(), reason));
        snap.setStatus(TaskStatus.FAILED.getCode());
        snap.setErrorMsg(reason);
        snap.setFinishedAt(Instant.now());
        save(snap);
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
