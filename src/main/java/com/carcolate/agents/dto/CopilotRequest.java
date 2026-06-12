package com.carcolate.agents.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

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

    /**
     * 额外参数，如 {"userArea": "MA"}（客户国家地区代码），支持嵌套对象。
     * 与 Agent 配置的「额外参数Key」配合，在各 Prompt 中以 {{key}} 占位符注入，
     * 如 {{uuid}}、{{user.age}}
     */
    private Map<String, Object> otherParams;
}
