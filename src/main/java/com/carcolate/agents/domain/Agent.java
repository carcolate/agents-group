package com.carcolate.agents.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.Instant;

/**
 * Agent 定义表
 * @TableName tb_agent
 */
@Data
@TableName(value = "tb_agent")
public class Agent implements Serializable {

    @TableId(type = IdType.INPUT)
    private Long id;

    /**
     * Agent 名称
     */
    private String name;

    /**
     * 身份与使命
     */
    private String mission;

    /**
     * 前置 System Prompt（完整提示词）
     */
    private String prePrompt;

    /**
     * 回复格式约束：用于要求 LLM 最终输出的回复格式，
     * 自动拼接到前置 Prompt 的后面，参与主 Agent 决策
     */
    private String responseFormat;

    /**
     * 语言风格 Prompt：主 Agent 决策时不感知，
     * 仅在生成最终回复后用于风格润色，输出终稿
     */
    private String stylePrompt;

    /**
     * 额外参数Key列表，英文逗号分隔，如：uuid,user.age
     * 对应 CopilotRequest.otherParams 传入的字段路径，
     * 前置 Prompt / 回复格式约束 / 语言风格 Prompt 中可用 {{key}} 占位引用
     */
    private String otherParamKeys;

    /**
     * 可选：覆盖全局模型名
     */
    private String modelName;

    /**
     * Agent 单次最多决策轮数
     */
    private Integer maxSteps;

    /**
     * 状态：1启用 0禁用
     */
    private Integer status;

    /**
     * 备注
     */
    private String remark;

    private Instant createdAt;
    private Instant updatedAt;
}
