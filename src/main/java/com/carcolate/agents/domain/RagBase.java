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
     * 参与时机：1=AI 自检索（向量化+工具按需调用） 2=前置知识库（不向量化，全文自动拼入 systemPrompt）
     */
    private Integer engageType;

    /**
     * 状态：1启用 0禁用
     */
    private Integer status;

    private Instant createdAt;
    private Instant updatedAt;

    /** 参与时机：AI 自检索（默认） */
    public static final int ENGAGE_RAG_SEARCH = 1;
    /** 参与时机：前置知识库（全量注入 systemPrompt） */
    public static final int ENGAGE_PREPEND = 2;
}
