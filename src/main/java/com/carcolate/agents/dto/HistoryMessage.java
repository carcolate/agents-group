package com.carcolate.agents.dto;

import lombok.Data;

/**
 * 历史对话消息
 */
@Data
public class HistoryMessage {

    /**
     * 角色，例如：客户 / 客服 / system
     */
    private String role;

    /**
     * 消息正文
     */
    private String content;

    /**
     * 消息时间，字符串格式 yyyy-MM-dd HH:mm:ss
     */
    private String time;
}
