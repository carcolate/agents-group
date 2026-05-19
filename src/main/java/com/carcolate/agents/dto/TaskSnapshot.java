package com.carcolate.agents.dto;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
public class TaskSnapshot {

    private String uuid;
    private Long agentId;
    private String agentName;

    /**
     * 本次实际调用的模型名（Agent 自定义 → 取自定义；空 → 回落全局默认）
     */
    private String modelName;

    /**
     * RUNNING / SUCCESS / FAILED
     */
    private String status;

    /**
     * 最终回复
     */
    private String finalReply;

    /**
     * 失败原因
     */
    private String errorMsg;

    private Instant createdAt;
    private Instant finishedAt;

    private List<StepRecord> steps = new ArrayList<>();

    /**
     * 最终发给 LLM 的 System Prompt 原文
     */
    private String systemPrompt;
}
