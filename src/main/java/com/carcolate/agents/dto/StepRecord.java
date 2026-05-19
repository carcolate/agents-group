package com.carcolate.agents.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class StepRecord {

    /**
     * 步骤类型：USER_INPUT / LLM_THINK / TOOL_CALL / TOOL_RESULT / FINAL_REPLY / ERROR
     */
    private String type;

    /**
     * 步骤内容（自然语言/JSON 字符串）
     */
    private String content;

    /**
     * 工具名（仅 TOOL_CALL / TOOL_RESULT）
     */
    private String toolName;

    /**
     * 工具入参 JSON（仅 TOOL_CALL）
     */
    private String toolArgs;

    /**
     * 时间戳
     */
    private Instant time;

    public static StepRecord of(String type, String content) {
        StepRecord s = new StepRecord();
        s.type = type;
        s.content = content;
        s.time = Instant.now();
        return s;
    }

    public static StepRecord ofTool(String type, String toolName, String toolArgs, String content) {
        StepRecord s = new StepRecord();
        s.type = type;
        s.toolName = toolName;
        s.toolArgs = toolArgs;
        s.content = content;
        s.time = Instant.now();
        return s;
    }
}
