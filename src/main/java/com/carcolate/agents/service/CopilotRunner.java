package com.carcolate.agents.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.carcolate.agents.config.LangChainConfig;
import com.carcolate.agents.domain.Agent;
import com.carcolate.agents.domain.RagBase;
import com.carcolate.agents.domain.enums.StepType;
import com.carcolate.agents.domain.enums.TaskStatus;
import com.carcolate.agents.domain.enums.CopilotTaskType;
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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    public void run(String uuid, Agent agent, CopilotRequest req, CopilotTaskType taskType) {
        if (taskType == null) taskType = CopilotTaskType.CHAT;
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
            doRun(uuid, agent, req, taskType, actualModel, startMs, llmRoundRef);
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

    private void doRun(String uuid, Agent agent, CopilotRequest req, CopilotTaskType taskType, String actualModel,
                       long startMs, int[] llmRoundRef) {
        // 只有实时回复和客户回访可以使用知识库；其他功能不读取、也不刷新知识库。
        if (supportsKnowledgeBase(taskType)) {
            // 刷新属于 Copilot 异步任务的一部分，完成后才读取知识库并开始模型调用。
            ragRefreshService.refreshForAgent(agent.getId());
        }
        String systemPrompt = buildSystemPrompt(agent, req, taskType);
        TaskSnapshot snap = load(uuid);
        if (snap != null) {
            snap.setSystemPrompt(systemPrompt);
            save(snap);
        }
        if (taskType != CopilotTaskType.CHAT && taskType != CopilotTaskType.REBACK) {
            runSingleCall(uuid, agent, req, taskType, actualModel, startMs, llmRoundRef, systemPrompt);
            return;
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
                String finalReply = normalizeTaskReply(taskType, thinkText, req, agent);
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

    /** compact/summarize/tag 使用单次模型调用，仍然复用同一个异步任务快照。 */
    private void runSingleCall(String uuid, Agent agent, CopilotRequest req, CopilotTaskType taskType,
                               String actualModel, long startMs, int[] llmRoundRef, String systemPrompt) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        appendHistoryImageMessages(uuid, req, messages);
        String input = req == null || req.getCurrentMessage() == null ? "" : req.getCurrentMessage();
        if (input.isBlank() && req != null && req.getHistoryMessages() != null) {
            input = "请根据上方任务要求处理历史对话。";
        }
        messages.add(UserMessage.from(input));
        ChatModel chatModel = langChainConfig.buildChatModel(agent.getModelName());
        llmRoundRef[0]++;
        ChatResponse response = chatModel.chat(ChatRequest.builder().messages(messages).build());
        AiMessage ai = response == null ? null : response.aiMessage();
        String raw = ai == null || ai.text() == null ? "" : ai.text();
        appendStep(uuid, StepRecord.ofThink(StepType.LLM_THINK.getCode(), raw, safeThinking(ai)));
        String reply = normalizeTaskReply(taskType, raw, req, agent);
        appendStep(uuid, StepRecord.of(StepType.FINAL_REPLY.getCode(), reply));
        markDone(uuid, reply, req, actualModel, llmRoundRef[0], startMs);
    }

    private String normalizeTaskReply(CopilotTaskType taskType, String raw, CopilotRequest req, Agent agent) {
        String text = raw == null ? "" : raw.trim();
        String jsonText = text.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
        try {
            if (taskType == CopilotTaskType.TAG) {
                JSONArray source;
                if (jsonText.startsWith("[")) {
                    source = JSON.parseArray(jsonText);
                } else {
                    JSONObject object = JSON.parseObject(jsonText);
                    Object value = object == null ? null : object.get("tags");
                    source = value instanceof JSONArray ? (JSONArray) value : JSON.parseArray(String.valueOf(value));
                }
                Set<String> allowed = allowedTagKeys(req, agent);
                Set<String> result = new LinkedHashSet<>();
                if (source != null) {
                    for (Object value : source) {
                        String key = String.valueOf(value).trim();
                        if (allowed.contains(key)) result.add(key);
                    }
                }
                return JSON.toJSONString(result);
            }
            JSONObject object = jsonText.startsWith("{") ? JSON.parseObject(jsonText) : null;
            if (taskType == CopilotTaskType.CHAT || taskType == CopilotTaskType.REBACK) {
                String msg = object == null ? text : object.getString("msg");
                JSONObject result = new JSONObject();
                result.put("msg", msg == null ? "" : msg);
                return result.toJSONString();
            }
            if (taskType == CopilotTaskType.COMPACT) {
                String rsp = object == null ? text : object.getString("rsp");
                JSONObject result = new JSONObject();
                result.put("rsp", rsp == null ? "" : rsp);
                return result.toJSONString();
            }
            JSONObject result = new JSONObject();
            String zh = object == null ? text : object.getString("zh");
            result.put("zh", zh == null ? "" : zh);
            if ("zh_en".equalsIgnoreCase(agent.getSummarizeLanguage())) {
                String en = object == null ? "" : object.getString("en");
                result.put("en", en == null ? "" : en);
            }
            return result.toJSONString();
        } catch (Exception e) {
            if (taskType == CopilotTaskType.TAG) return "[]";
            JSONObject result = new JSONObject();
            if (taskType == CopilotTaskType.COMPACT) result.put("rsp", text);
            else if (taskType == CopilotTaskType.SUMMARIZE) {
                result.put("zh", text);
                if ("zh_en".equalsIgnoreCase(agent.getSummarizeLanguage())) result.put("en", "");
            }
            else result.put("msg", text);
            return result.toJSONString();
        }
    }

    private Set<String> allowedTagKeys(CopilotRequest req, Agent agent) {
        Set<String> configured = new LinkedHashSet<>();
        if (agent != null && agent.getTagStageKeys() != null && !agent.getTagStageKeys().isBlank()) {
            try {
                JSONArray values = JSON.parseArray(agent.getTagStageKeys());
                if (values != null) values.forEach(x -> configured.add(String.valueOf(x)));
            } catch (Exception ignored) { }
        }
        JSONObject tagPrompts = parseTagStagePrompts(agent == null ? null : agent.getTagStagePrompts());
        Set<String> conditioned = new LinkedHashSet<>();
        for (String key : configured) {
            String prompt = tagPrompts.getString(key);
            if (prompt != null && !prompt.isBlank()) conditioned.add(key);
        }
        Set<String> allowed = new LinkedHashSet<>();
        if (req != null && req.getTagOptions() != null) {
            req.getTagOptions().forEach(x -> {
                if (x != null && x.getKey() != null && conditioned.contains(x.getKey())) {
                    allowed.add(x.getKey());
                }
            });
        } else {
            allowed.addAll(conditioned);
        }
        return allowed;
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

    private String buildSystemPrompt(Agent agent, CopilotRequest req, CopilotTaskType taskType) {
        StringBuilder sb = new StringBuilder();
        String taskPrompt = switch (taskType) {
            case CHAT -> agent.getPrePrompt();
            case REBACK -> agent.getRebackPrompt();
            case COMPACT -> (agent.getCompactPrompt() == null || agent.getCompactPrompt().isBlank())
                    ? "压缩以下历史对话，只保留未来继续沟通所需的信息：客户身份、车型、预算、需求、痛点、异议、承诺、预约信息和未完成事项。不要编造，不要输出分析过程。"
                    : agent.getCompactPrompt();
            case SUMMARIZE -> agent.getSummarizePrompt();
            case TAG -> null;
        };
        if (taskPrompt != null && !taskPrompt.isBlank()) {
            sb.append("\n\n## 当前功能 Prompt\n").append(renderPromptVars(taskPrompt, agent, req));
        }
        if (taskType == CopilotTaskType.SUMMARIZE) {
            sb.append("\n\n## 总结语言\n").append("zh_en".equalsIgnoreCase(agent.getSummarizeLanguage())
                    ? "同时返回中文 zh 和英文 en。" : "只返回中文 zh。");
        }
        if (taskType == CopilotTaskType.TAG) {
            sb.append("\n\n## 标签选择规则\n只能从以下允许标签中选择，可返回一个或多个，没有符合项返回 []，只返回 JSON 数组：\n");
            Set<String> allowed = allowedTagKeys(req, agent);
            JSONObject tagPrompts = parseTagStagePrompts(agent.getTagStagePrompts());
            if (req != null && req.getTagOptions() != null) {
                req.getTagOptions().forEach(x -> {
                    if (x != null && x.getKey() != null && allowed.contains(x.getKey())) {
                        sb.append("- ").append(x.getKey()).append("：")
                                .append(x.getNameCn() == null ? "" : x.getNameCn()).append(" ")
                                .append(x.getNameEn() == null ? "" : x.getNameEn()).append("\n")
                                .append("  允许条件：")
                                .append(tagPrompts.getString(x.getKey()) == null
                                        ? "未配置条件，不能仅凭模糊意向标记。"
                                        : tagPrompts.getString(x.getKey()))
                                .append("\n");
                    }
                });
            } else {
                allowed.forEach(x -> sb.append("- ").append(x).append("\n")
                        .append("  允许条件：").append(tagPrompts.getString(x) == null
                                ? "未配置条件，不能仅凭模糊意向标记。" : tagPrompts.getString(x)).append("\n"));
            }
        }

        if (supportsKnowledgeBase(taskType)) {
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
            sb.append("- 前置知识库可直接引用，目录文档按需调用工具。\n");
            sb.append("- [ID=xxx] 是文档 ragId；可用 search_knowledge_base 和 get_knowledge_document。\n");
            sb.append("- 严禁凭空捏造知识库中没有的事实、参数、价格。\n");
        }
        if (req.getHistorySummary() != null && !req.getHistorySummary().isBlank()) {
            sb.append("\n\n## 更早历史消息总结\n").append(req.getHistorySummary());
        }
        if (req.getHistoryMessages() != null && !req.getHistoryMessages().isEmpty()) {
            sb.append("\n\n## 历史对话说明\n以下是完整历史对话，请结合上下文处理当前功能。\n");
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
        if (req.getOtherParams() != null && !req.getOtherParams().isEmpty()) {
            sb.append("\n\n## 客户信息\n").append(JSON.toJSONString(req.getOtherParams()));
        }
        sb.append("\n\n## 系统固定输出格式\n");
        switch (taskType) {
            case CHAT, REBACK -> sb.append("只返回合法 JSON 对象：{\"msg\":\"客户实际看到的回复\"}。");
            case COMPACT -> sb.append("只返回合法 JSON 对象：{\"rsp\":\"压缩后的会话摘要\"}。");
            case SUMMARIZE -> sb.append("只返回合法 JSON 对象：").append("zh_en".equalsIgnoreCase(agent.getSummarizeLanguage())
                    ? "{\"zh\":\"中文总结\",\"en\":\"英文总结\"}。"
                    : "{\"zh\":\"中文总结\"}。");
            case TAG -> sb.append("只返回 JSON 数组，例如 [\"ice_breaking\"]，不能返回未允许的标签。");
        }
        return sb.toString();
    }

    private boolean supportsKnowledgeBase(CopilotTaskType taskType) {
        return taskType == CopilotTaskType.CHAT || taskType == CopilotTaskType.REBACK;
    }

    private JSONObject parseTagStagePrompts(String value) {
        if (value == null || value.isBlank()) return new JSONObject();
        try {
            JSONObject prompts = JSON.parseObject(value);
            return prompts == null ? new JSONObject() : prompts;
        } catch (Exception ignored) {
            return new JSONObject();
        }
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
