package com.carcolate.agents.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.Instant;

/**
 * Copilot 调用历史
 * @TableName tb_copilot_history
 */
@Data
@TableName(value = "tb_copilot_history")
public class CopilotHistory implements Serializable {

    @TableId(type = IdType.INPUT)
    private Long id;

    /**
     * 任务 UUID（与 Redis 中 TaskSnapshot 对应）
     */
    private String uuid;

    /**
     * Agent ID
     */
    private Long agentId;

    /**
     * Agent 名称（冗余便于列表展示）
     */
    private String agentName;

    /**
     * 用户当前消息（仅 currentMessage，不含历史消息和总结）
     */
    private String userMessage;

    /**
     * Agent 最终回复（终稿）
     */
    private String finalReply;

    /**
     * 任务状态：SUCCESS / FAILED
     */
    private String status;

    /**
     * 失败原因
     */
    private String errorMsg;

    /**
     * 步骤数
     */
    private Integer stepCount;

    /**
     * 主 ReAct 调用 LLM 的轮次（不含风格润色那次额外调用）
     */
    private Integer llmRound;

    /**
     * 总耗时（毫秒）
     */
    private Long costMs;

    private Instant createdAt;
    private Instant finishedAt;
}
