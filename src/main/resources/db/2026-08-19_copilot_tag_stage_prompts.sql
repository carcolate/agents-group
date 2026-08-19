-- AgentGroup 客户标签条件 Prompt 增量迁移。
-- JSON 对象示例：{"invite_visit":"当对话中提到明确的到店时间后，经客服确认，才可以标记该标签。"}
ALTER TABLE tb_agent ADD COLUMN IF NOT EXISTS tag_stage_prompts TEXT NULL COMMENT '各 tb_stage 的条件 Prompt JSON 对象';
