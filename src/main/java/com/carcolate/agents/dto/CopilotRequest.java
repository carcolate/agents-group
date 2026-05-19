package com.carcolate.agents.dto;

import lombok.Data;

import java.util.List;

@Data
public class CopilotRequest {

    /**
     * Agent ID
     */
    private Long agentId;

    /**
     * 客户当前发送的消息
     */
    private String currentMessage;

    /**
     * 客户的历史消息（按时间正序排列，每条已含角色前缀更好，如 "客户: xxx" / "客服: xxx"）
     */
    private List<String> historyMessages;

    /**
     * 更早的历史消息总结
     */
    private String historySummary;
}
