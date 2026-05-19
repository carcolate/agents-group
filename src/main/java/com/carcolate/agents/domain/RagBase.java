package com.carcolate.agents.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.Instant;

/**
 * Agent 知识库表
 * @TableName tb_rag_base
 */
@Data
@TableName(value = "tb_rag_base")
public class RagBase implements Serializable {

    @TableId(type = IdType.INPUT)
    private Long id;

    /**
     * 所属 Agent ID
     */
    private Long agentId;

    /**
     * 文档标题
     */
    private String title;

    /**
     * 文档内容
     */
    private String content;

    /**
     * LLM 自动生成的文档摘要（100 字以内），供 Agent 决策时作为知识库目录使用
     */
    private String summary;

    /**
     * 状态：1启用 0禁用
     */
    private Integer status;

    private Instant createdAt;
    private Instant updatedAt;
}
