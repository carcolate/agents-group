package com.carcolate.agents.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

@Configuration
public class LangChainConfig {

    @Value("${copilot.llm.base-url}")
    private String baseUrl;

    @Value("${copilot.llm.api-key}")
    private String apiKey;

    /**
     * 可用模型列表，逗号分隔。第一项作为默认模型。
     */
    @Value("${copilot.llm.available-models}")
    private List<String> availableModels;

    @Value("${copilot.llm.temperature:0.3}")
    private Double temperature;

    @Value("${copilot.llm.timeout-seconds:60}")
    private long timeoutSeconds;

    @Value("${copilot.llm.log-requests:false}")
    private boolean logRequests;

    @Value("${copilot.llm.log-responses:false}")
    private boolean logResponses;

    /**
     * 返回配置中的可用模型名列表（不可变）。第一项即默认模型。
     */
    public List<String> getAvailableModels() {
        if (availableModels == null || availableModels.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(availableModels);
    }

    /**
     * 默认模型名 = 可用模型列表的第一项。
     */
    public String getDefaultModelName() {
        if (availableModels == null || availableModels.isEmpty()) {
            throw new IllegalStateException("copilot.llm.available-models 未配置");
        }
        return availableModels.get(0);
    }

    /**
     * 根据 Agent 偏好动态构建 ChatModel。
     * 传入参数为空时回退到全局默认（模型名取列表第一项，温度取 copilot.llm.temperature）。
     */
    public ChatModel buildChatModel(String modelName, Double temperatureOverride) {
        String finalModel = (modelName == null || modelName.isBlank()) ? getDefaultModelName() : modelName;
        Double finalTemp = temperatureOverride == null ? this.temperature : temperatureOverride;
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(finalModel)
                .temperature(finalTemp)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .logRequests(logRequests)
                .logResponses(logResponses)
                .build();
    }

    /**
     * 默认 ChatModel Bean，使用列表第一项 + 全局温度。
     */
    @Bean
    public ChatModel chatModel() {
        return buildChatModel(null, null);
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        return new AllMiniLmL6V2QuantizedEmbeddingModel();
    }

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        return new InMemoryEmbeddingStore<>();
    }
}
