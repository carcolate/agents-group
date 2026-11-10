-- AgentGroup 客户信息提取（extract）任务增量迁移。
ALTER TABLE tb_agent ADD COLUMN IF NOT EXISTS extract_prompt MEDIUMTEXT NULL COMMENT '客户信息提取 Prompt' AFTER summarize_language;
