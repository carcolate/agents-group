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
     * 客户的历史消息（按时间正序排列）。每条包含：角色、内容、时间（yyyy-MM-dd HH:mm:ss）
     */
    private List<HistoryMessage> historyMessages;

    /**
     * 更早的历史消息总结
     */
    private String historySummary;
}
