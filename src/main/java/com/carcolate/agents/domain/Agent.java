package com.carcolate.agents.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
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
     * 可选：覆盖全局模型名
     */
    private String modelName;

    /**
     * 可选：覆盖全局温度
     */
    private BigDecimal temperature;

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
