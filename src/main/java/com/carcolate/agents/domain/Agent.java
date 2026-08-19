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
     * 前置 System Prompt（完整提示词）
     */
    private String prePrompt;

    /**
     * 回复格式约束：用于要求 LLM 最终输出的回复格式，
     * 自动拼接到前置 Prompt 的后面，参与主 Agent 决策
     */
    private String responseFormat;

    /** 客户回访专用 Prompt。 */
    private String rebackPrompt;

    /** 会话压缩 Prompt；为空时使用系统默认值。 */
    private String compactPrompt;

    /** 客户信息总结 Prompt。 */
    private String summarizePrompt;

    /** 客户信息总结语言：zh / zh_en。 */
    private String summarizeLanguage;

    /** 允许 tag 任务输出的 tb_stage key JSON 数组。 */
    private String tagStageKeys;

    /** 每个允许标签的条件 Prompt，JSON 对象：{ "invite_visit": "..." }。 */
    private String tagStagePrompts;

    /**
     * 额外参数Key列表，英文逗号分隔，如：uuid,user.age
     * 对应 CopilotRequest.otherParams 传入的字段路径，
     * 各类任务 Prompt 中可用 {{key}} 占位引用
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
