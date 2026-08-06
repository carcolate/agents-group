package com.carcolate.agents.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.carcolate.agents.config.LangChainConfig;
import com.carcolate.agents.domain.Agent;
import com.carcolate.agents.domain.RagBase;
import com.carcolate.agents.domain.enums.StepType;
import com.carcolate.agents.domain.enums.TaskStatus;
import com.carcolate.agents.dto.CopilotRequest;
import com.carcolate.agents.dto.HistoryMessage;
import com.carcolate.agents.dto.StepRecord;
import com.carcolate.agents.dto.TaskSnapshot;
import com.carcolate.agents.response.utils.RedisKeys;
import com.carcolate.agents.tools.RagFetchTool;
import com.carcolate.agents.tools.RagSearchTool;
import com.carcolate.agents.tools.RemoteImageFetcher;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
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

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private RagRefreshService ragRefreshService;

    @Autowired
    private CopilotHistoryService copilotHistoryService;

    @Autowired
    private RemoteImageFetcher remoteImageFetcher;

    @Value("${copilot.task.ttl-seconds:86400}")
    private long ttlSeconds;

    @Async("copilotExecutor")
    public void run(String uuid, Agent agent, CopilotRequest req) {
        long startMs = System.currentTimeMillis();
        int[] llmRoundRef = new int[]{0};
        // 提前解析"实际调用的模型名"，落历史时一并存
        String actualModel = resolveActualModelName(agent);
        // 将模型名写入快照，详情页可直接展示
        TaskSnapshot initSnap = load(uuid);
        if (initSnap != null) {
            initSnap.setModelName(actualModel);
            save(initSnap);
        }
        try {
            doRun(uuid, agent, req, actualModel, startMs, llmRoundRef);
        } catch (Exception e) {
            log.error("[Copilot] uuid={} 执行异常", uuid, e);
            TaskSnapshot snap = load(uuid);
            if (snap == null) return;
            snap.getSteps().add(StepRecord.of(StepType.ERROR.getCode(), e.getMessage()));
            snap.setStatus(TaskStatus.FAILED.getCode());
            snap.setErrorMsg(e.getMessage());
            snap.setFinishedAt(Instant.now());
            save(snap);
            persistHistory(snap, req, actualModel, llmRoundRef[0], startMs);
        }
    }

    /**
     * 解析本次实际会用的模型：Agent 显式指定 → 用 Agent 的；否则回落到 LangChainConfig 默认（available-models 第一项）。
     * 解析失败（默认列表也没配）时返回 null，避免主流程崩溃。
     */
    private String resolveActualModelName(Agent agent) {
        if (agent != null && agent.getModelName() != null && !agent.getModelName().isBlank()) {
            return agent.getModelName();
        }
        try {
            return langChainConfig.getDefaultModelName();
        } catch (Exception e) {
            return null;
        }
    }

    private void doRun(String uuid, Agent agent, CopilotRequest req, String actualModel,
                       long startMs, int[] llmRoundRef) {
        // 刷新属于 Copilot 异步任务的一部分，完成后才读取知识库并开始模型调用。
        ragRefreshService.refreshForAgent(agent.getId());
        String systemPrompt = buildSystemPrompt(agent, req);
        TaskSnapshot snap = load(uuid);
        if (snap != null) {
            snap.setSystemPrompt(systemPrompt);
            save(snap);
        }
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        // 把含图的历史消息逐条下载，注入为独立的多模态 UserMessage
        appendHistoryImageMessages(uuid, req, messages);
        if (req.getCurrentMessage()!=null) {
            messages.add(UserMessage.from(req.getCurrentMessage()));
        }


        // 按 Agent 配置动态选择模型，使用模型默认采样参数。
        ChatModel chatModel = langChainConfig.buildChatModel(agent.getModelName());

        int maxSteps = agent.getMaxSteps() == null || agent.getMaxSteps() <= 0 ? 6 : agent.getMaxSteps();
        for (int i = 0; i < maxSteps; i++) {
            // 每轮开始前优先检查中断标记：当前轮 LLM 调用无法被强行打断，最坏会等当前轮结束再退出
            if (isCancelled(uuid)) {
                markCancelled(uuid, "用户主动中断（已运行 " + i + " 轮）",
                        req, actualModel, llmRoundRef[0], startMs);
                return;
            }
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
                String finalReply = applyStylePolish(uuid, chatModel, agent, req, thinkText);
                appendStep(uuid, StepRecord.of(StepType.FINAL_REPLY.getCode(), finalReply));
                markDone(uuid, finalReply, req, actualModel, llmRoundRef[0], startMs);
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
                req, actualModel, llmRoundRef[0], startMs);
    }

    /**
     * 用 agent.stylePrompt 对主 Agent 产出的草稿做风格润色，生成终稿。
     * - stylePrompt 为空：直接返回草稿，不调用 LLM。
     * - 润色调用失败：记录错误步骤，回退草稿，保证主流程可用。
     */
    private String applyStylePolish(String uuid, ChatModel chatModel, Agent agent, CopilotRequest req, String draftReply) {
        String stylePrompt = renderPromptVars(agent.getStylePrompt(), agent, req);
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

            ChatRequest polishReq = ChatRequest.builder().messages(msgs).build();
            ChatResponse resp = chatModel.chat(polishReq);
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
     * 遍历 historyMessages，对每条含 image 的记录单独下载图片并注入为多模态 UserMessage。
     * <ul>
     *   <li>下载成功：追加一条 {@code UserMessage(TextContent + ImageContent)}，并记录 IMAGE_FETCH 成功步骤。</li>
     *   <li>下载失败：不注入 UserMessage（system prompt 文本里已有「已附图：{url}」占位），只记录 IMAGE_FETCH 失败步骤。</li>
     * </ul>
     * 单张图失败不影响其他图，更不阻塞 ReAct 主流程。
     */
    private void appendHistoryImageMessages(String uuid, CopilotRequest req, List<ChatMessage> messages) {
        if (req == null || req.getHistoryMessages() == null || req.getHistoryMessages().isEmpty()) {
            return;
        }
        for (HistoryMessage m : req.getHistoryMessages()) {
            if (m == null) continue;
            String url = m.getImage();
            if (url == null || url.isBlank()) continue;

            String role = m.getRole() == null || m.getRole().isBlank() ? "未知角色" : m.getRole();
            String content = m.getContent() == null ? "" : m.getContent();
            String timeLabel = formatTimeWithWeek(m.getTime());
            String prefix = "[历史"
                    + (timeLabel.isEmpty() ? "" : " " + timeLabel)
                    + " " + role + "] "
                    + (content.isBlank() ? "（图片）" : content);

            try {
                RemoteImageFetcher.FetchedImage img = remoteImageFetcher.fetch(url);
                Image image = Image.builder()
                        .base64Data(img.getBase64())
                        .mimeType(img.getMimeType())
                        .build();
                messages.add(UserMessage.from(TextContent.from(prefix), ImageContent.from(image)));
                String source;
                if (img.isFromCache()) {
                    source = "缓存命中";
                } else if (img.isCached()) {
                    source = "下载成功(已落盘)";
                } else {
                    source = "下载成功(落盘失败)";
                }
                appendStep(uuid, StepRecord.of(StepType.IMAGE_FETCH.getCode(),
                        source + " url=" + url
                                + " mime=" + img.getMimeType()
                                + " bytes=" + img.getBytes()
                                + " cost=" + img.getCostMs() + "ms"));
            } catch (Exception e) {
                log.warn("[Copilot] uuid={} 历史图片下载失败 url={}", uuid, url, e);
                appendStep(uuid, StepRecord.of(StepType.IMAGE_FETCH.getCode(),
                        "下载失败 url=" + url + " 原因=" + e.getMessage()));
            }
        }
    }

    private static final DateTimeFormatter HISTORY_TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String[] CN_WEEK = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    /**
     * 把入参 yyyy-MM-dd HH:mm:ss 时间字符串格式化为 "yyyy-MM-dd HH:mm:ss 周X"。
     * 入参为空 / 解析失败时回退原样输出（不挂主流程）。
     */
    private String formatTimeWithWeek(String raw) {
        if (raw == null || raw.isBlank()) return "";
        try {
            LocalDateTime ldt = LocalDateTime.parse(raw.trim(), HISTORY_TIME_FMT);
            DayOfWeek dow = ldt.getDayOfWeek();
            String week = CN_WEEK[dow.getValue() - 1];
            return ldt.format(HISTORY_TIME_FMT) + " " + week;
        } catch (Exception e) {
            return raw;
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
        sb.append(renderPromptVars(agent.getPrePrompt() == null ? "" : agent.getPrePrompt(), agent, req));
        String responseFormat = renderPromptVars(agent.getResponseFormat(), agent, req);
        if (responseFormat != null && !responseFormat.isBlank()) {
            sb.append("\n\n## 回复格式约束\n").append(responseFormat);
        }
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
        sb.append("\n\n## 工具调用规则\n");
        sb.append("- 上方【前置知识库】已提供完整正文，回答时可直接引用，无需调用工具。\n");
        sb.append("- 上方【可用知识库目录】每条记录前的 [ID=xxx] 是该文档的 ragId。\n");
        sb.append("- 可用工具：\n");
        sb.append("  · search_knowledge_base(query, topK?)：按语义关键字检索片段，适合不确定答案落在哪个文档时使用。\n");
        sb.append("  · get_knowledge_document(ragId)：直接拉取指定文档的全量正文+概览，适合已经在目录里锁定目标文档、需要完整内容时使用。\n");
        sb.append("- 决策顺序：前置知识库够用 → 直接回答；目录里能精准锁定某文档 → 用 get_knowledge_document 取全文；只有模糊关键字 → 用 search_knowledge_base 检索片段。\n");
        sb.append("- 严禁凭空捏造未在【前置知识库】或工具返回结果中出现的事实、参数、价格。\n");
        sb.append("- 不需要调用工具时，直接给出最终回复（输出客户实际看到的那句话即可，不要再附思考过程）。\n");
        if (req.getHistorySummary() != null && !req.getHistorySummary().isBlank()) {
            sb.append("\n\n## 更早历史消息总结\n").append(req.getHistorySummary());
        }
        if (req.getHistoryMessages() != null && !req.getHistoryMessages().isEmpty()) {
            sb.append("\n\n## 历史对话说明\n");
            sb.append("以下是历史对话记录，客户可能连续发多条消息，请理解完整上下文后再回复。\n");
            sb.append("特别注意：不要仅基于最后一条消息回复，要结合前面的对话内容。\n");
            for (HistoryMessage m : req.getHistoryMessages()) {
                if (m == null) continue;
                String role = m.getRole() == null || m.getRole().isBlank() ? "未知角色" : m.getRole();
                String content = m.getContent() == null ? "" : m.getContent();
                String image = m.getImage();
                if (content.isBlank() && (image == null || image.isBlank())) {
                    continue;
                }
                String timeLabel = formatTimeWithWeek(m.getTime());
                sb.append("- ");
                if (!timeLabel.isEmpty()) sb.append("[").append(timeLabel).append("] ");
                sb.append(role).append(": ").append(content);
                if (image != null && !image.isBlank()) {
                    sb.append("（已附图：").append(image).append("）");
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 用 req.otherParams 渲染 Prompt 模板中的 {{key}} 动态变量。
     * <ul>
     *   <li>替换范围以 agent.otherParamKeys（逗号分隔）声明的 key 为准，未声明的占位符原样保留。</li>
     *   <li>key 支持点号路径（如 user.age）：先按整键直查 otherParams，查不到再逐级下钻嵌套 Map。</li>
     *   <li>声明了但请求未传值的 key，占位符替换为空字符串，避免把 {{xxx}} 漏给 LLM。</li>
     * </ul>
     */
    private String renderPromptVars(String template, Agent agent, CopilotRequest req) {
        if (template == null || template.isBlank()) {
            return template;
        }
        String keysCfg = agent == null ? null : agent.getOtherParamKeys();
        if (keysCfg == null || keysCfg.isBlank()) {
            return template;
        }
        Map<String, Object> params = req == null ? null : req.getOtherParams();
        String result = template;
        for (String rawKey : keysCfg.split("[,，]")) {
            String key = rawKey.trim();
            if (key.isEmpty()) continue;
            Object val = resolveParamValue(params, key);
            result = result.replace("{{" + key + "}}", paramValueToString(val));
        }
        return result;
    }

    /**
     * 按 key 从 otherParams 取值：整键直查优先（兼容客户端直接传扁平 key "user.age"），
     * 未命中再按点号逐级下钻嵌套 Map。任一级缺失或类型不是 Map 时返回 null。
     */
    private Object resolveParamValue(Map<String, Object> params, String key) {
        if (params == null) return null;
        if (params.containsKey(key)) {
            return params.get(key);
        }
        Object cur = params;
        for (String part : key.split("\\.")) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<?, ?>) cur).get(part);
            if (cur == null) return null;
        }
        return cur;
    }

    /** 占位符取值转文本：基础类型直出，对象/数组转 JSON，null 转空串 */
    private String paramValueToString(Object val) {
        if (val == null) return "";
        if (val instanceof CharSequence || val instanceof Number || val instanceof Boolean) {
            return String.valueOf(val);
        }
        try {
            return JSON.toJSONString(val);
        } catch (Exception e) {
            return String.valueOf(val);
        }
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

    private void markDone(String uuid, String finalReply, CopilotRequest req, String actualModel,
                          int llmRound, long startMs) {
        TaskSnapshot snap = load(uuid);
        if (snap == null) return;
        snap.setStatus(TaskStatus.SUCCESS.getCode());
        snap.setFinalReply(finalReply);
        snap.setFinishedAt(Instant.now());
        save(snap);
        persistHistory(snap, req, actualModel, llmRound, startMs);
    }

    private void markFailed(String uuid, String reason, CopilotRequest req, String actualModel,
                            int llmRound, long startMs) {
        TaskSnapshot snap = load(uuid);
        if (snap == null) return;
        snap.getSteps().add(StepRecord.of(StepType.ERROR.getCode(), reason));
        snap.setStatus(TaskStatus.FAILED.getCode());
        snap.setErrorMsg(reason);
        snap.setFinishedAt(Instant.now());
        save(snap);
        persistHistory(snap, req, actualModel, llmRound, startMs);
    }

    private void markCancelled(String uuid, String reason, CopilotRequest req, String actualModel,
                               int llmRound, long startMs) {
        TaskSnapshot snap = load(uuid);
        if (snap == null) return;
        snap.getSteps().add(StepRecord.of(StepType.ERROR.getCode(), "任务被中断：" + reason));
        snap.setStatus(TaskStatus.CANCELLED.getCode());
        snap.setErrorMsg(reason);
        snap.setFinishedAt(Instant.now());
        save(snap);
        // 清理 cancel 标记，避免后续误判（key 自身有 TTL，也可以不删；这里显式删除更干净）
        try {
            redisTemplate.delete(RedisKeys.copilotCancel(uuid));
        } catch (Exception ignore) {
        }
        persistHistory(snap, req, actualModel, llmRound, startMs);
    }

    /**
     * 检查 Redis 中是否有该任务的中断标记。
     */
    private boolean isCancelled(String uuid) {
        try {
            Boolean has = redisTemplate.hasKey(RedisKeys.copilotCancel(uuid));
            return Boolean.TRUE.equals(has);
        } catch (Exception e) {
            return false;
        }
    }

    private void persistHistory(TaskSnapshot snap, CopilotRequest req, String actualModel,
                                int llmRound, long startMs) {
        String userMsg = req == null ? null : req.getCurrentMessage();
        String userId = req == null ? null : req.getUserId();
        long costMs = System.currentTimeMillis() - startMs;
        copilotHistoryService.recordFromSnapshot(snap, userMsg, actualModel, llmRound, costMs, userId);
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
