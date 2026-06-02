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
     * 消息正文。可空（纯图消息时只填 image 即可）。
     */
    private String content;

    /**
     * 消息附带的图片 URL，可空。
     * 服务端会下载后以 base64 + mimeType 形式内嵌进发给 LLM 的 UserMessage 中；
     * 下载失败不阻塞主流程，仅在 system prompt 文本里以占位标注「已附图：{url}」。
     * 一条消息仅支持 1 张图；若同时提供 content，则文本 + 图片一并送给 LLM。
     */
    private String image;

    /**
     * 消息时间，字符串格式 yyyy-MM-dd HH:mm:ss
     */
    private String time;
}
