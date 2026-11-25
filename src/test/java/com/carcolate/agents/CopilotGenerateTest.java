package com.carcolate.agents;

import com.alibaba.fastjson2.JSON;
import com.carcolate.agents.config.LangChainConfig;
import com.carcolate.agents.controller.CopilotController;
import com.carcolate.agents.domain.Agent;
import com.carcolate.agents.domain.enums.CopilotTaskType;
import com.carcolate.agents.dto.CopilotRequest;
import com.carcolate.agents.dto.GenerateRequest;
import com.carcolate.agents.dto.HistoryMessage;
import com.carcolate.agents.dto.TaskSnapshot;
import com.carcolate.agents.response.utils.RedisKeys;
import com.carcolate.agents.service.CopilotHistoryService;
import com.carcolate.agents.service.CopilotRunner;
import com.carcolate.agents.service.CopilotService;
import com.carcolate.agents.service.RagRefreshService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CopilotGenerateTest {
    @Test
    void exposesGenerateAndSummarizeAsDistinctRoutes() throws Exception {
        CopilotService service = mock(CopilotService.class);
        when(service.generate(new GenerateRequest(12L, null, "system", "user", null))).thenReturn("uuid");
        when(service.summarize(any(CopilotRequest.class))).thenReturn("summary-uuid");
        CopilotController controller = new CopilotController();
        ReflectionTestUtils.setField(controller, "copilotService", service);
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/copilot/generate").contentType("application/json").content("""
                {"agentId":12,"systemPrompt":"system","userPrompt":"user"}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.data.uuid").value("uuid"));
        mvc.perform(post("/copilot/summarize").contentType("application/json").content("""
                {"agentId":12,"historyMessages":[{"role":"客户","content":"想预约试驾"}]}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.data.uuid").value("summary-uuid"));
        for (String task : List.of("tag", "extract", "compact")) {
            mvc.perform(post("/copilot/" + task).contentType("application/json").content("{}"))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void forwardsPromptsInOneCallAndStoresTheRawReply() {
        String raw = "  model output  ";
        RunResult result = run(CopilotTaskType.GENERATE, raw, null);
        assertEquals(List.of(SystemMessage.from("system"), UserMessage.from("user")), result.request().messages());
        assertEquals(raw, result.snapshot().getFinalReply());
        assertEquals("SUCCESS", result.snapshot().getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"zh", "zh_en"})
    void summarizeUsesItsOwnPromptAndReturnsConfiguredLanguages(String language) {
        RunResult result = run(CopilotTaskType.SUMMARIZE,
                "```json\n{\"zh\":\"想试驾\",\"en\":\"Requests a test drive\",\"unrelated\":\"extra\"}\n```", language);
        var messages = result.request().messages();
        String system = ((SystemMessage) messages.getFirst()).text();
        assertTrue(system.contains("总结 MA 客户"));
        assertFalse(system.contains("unrelated chat instruction"));
        assertTrue(((UserMessage) messages.getLast()).singleText().contains("想预约试驾"));
        assertEquals("SUCCESS", result.snapshot().getStatus());
        Map<String, String> expected = "zh_en".equals(language)
                ? Map.of("zh", "想试驾", "en", "Requests a test drive") : Map.of("zh", "想试驾");
        assertEquals(expected, JSON.parseObject(result.snapshot().getFinalReply()));
    }

    @Test
    void rejectsIncompleteBilingualSummary() {
        RunResult result = run(CopilotTaskType.SUMMARIZE, "{\"zh\":\"想试驾\"}", "zh_en");
        assertEquals("FAILED", result.snapshot().getStatus());
        assertNull(result.snapshot().getFinalReply());
    }

    @SuppressWarnings("unchecked")
    private RunResult run(CopilotTaskType type, String raw, String language) {
        String uuid = "uuid";
        TaskSnapshot initial = new TaskSnapshot();
        initial.setUuid(uuid);
        AtomicReference<TaskSnapshot> snapshot = new AtomicReference<>(initial);
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(RedisKeys.copilotTask(uuid))).thenAnswer(call -> snapshot.get());
        doAnswer(call -> {
            snapshot.set(call.getArgument(1));
            return null;
        }).when(values).set(anyString(), any(TaskSnapshot.class), anyLong(), any(TimeUnit.class));

        LangChainConfig config = mock(LangChainConfig.class);
        ChatModel model = mock(ChatModel.class);
        when(config.buildChatModel("test-model")).thenReturn(model);
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(raw)).build());

        CopilotRunner runner = new CopilotRunner();
        RagRefreshService rag = mock(RagRefreshService.class);
        ReflectionTestUtils.setField(runner, "redisTemplate", redis);
        ReflectionTestUtils.setField(runner, "langChainConfig", config);
        ReflectionTestUtils.setField(runner, "ragRefreshService", rag);
        ReflectionTestUtils.setField(runner, "copilotHistoryService", mock(CopilotHistoryService.class));
        Agent agent = new Agent();
        agent.setId(12L);
        agent.setModelName("test-model");
        agent.setPrePrompt("unrelated chat instruction");
        agent.setSummarizePrompt("总结 {{userArea}} 客户");
        agent.setOtherParamKeys("userArea");
        agent.setSummarizeLanguage(language);
        CopilotRequest context = new CopilotRequest();
        HistoryMessage history = new HistoryMessage();
        history.setRole("客户");
        history.setContent("想预约试驾");
        context.setHistoryMessages(List.of(history));
        context.setOtherParams(Map.of("userArea", "MA"));
        GenerateRequest generation = type == CopilotTaskType.GENERATE
                ? new GenerateRequest(12L, null, "system", "user", null) : null;
        runner.run(uuid, agent, context, type, generation);

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(request.capture());
        verifyNoInteractions(rag);
        return new RunResult(request.getValue(), snapshot.get());
    }

    private record RunResult(ChatRequest request, TaskSnapshot snapshot) {}
}
