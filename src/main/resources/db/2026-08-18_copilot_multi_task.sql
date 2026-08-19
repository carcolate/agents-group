-- AgentGroup 多功能 Copilot 增量迁移。
-- 兼容已有数据库：保留历史 style_prompt 字段，但运行时不再读取或写入。
ALTER TABLE tb_agent ADD COLUMN IF NOT EXISTS reback_prompt MEDIUMTEXT NULL COMMENT '客户回访 Prompt';
ALTER TABLE tb_agent ADD COLUMN IF NOT EXISTS compact_prompt MEDIUMTEXT NULL COMMENT '会话压缩 Prompt';
ALTER TABLE tb_agent ADD COLUMN IF NOT EXISTS summarize_prompt MEDIUMTEXT NULL COMMENT '客户信息总结 Prompt';
ALTER TABLE tb_agent ADD COLUMN IF NOT EXISTS summarize_language VARCHAR(16) NOT NULL DEFAULT 'zh' COMMENT 'zh / zh_en';
ALTER TABLE tb_agent ADD COLUMN IF NOT EXISTS tag_stage_keys TEXT NULL COMMENT '允许输出的 tb_stage key JSON 数组';
ALTER TABLE tb_agent ADD COLUMN IF NOT EXISTS tag_stage_prompts TEXT NULL COMMENT '各 tb_stage 的条件 Prompt JSON 对象';
ALTER TABLE tb_agent ADD COLUMN IF NOT EXISTS response_format MEDIUMTEXT NULL COMMENT '历史兼容字段，运行时固定使用系统输出格式';
ALTER TABLE tb_agent ADD COLUMN IF NOT EXISTS other_param_keys VARCHAR(1024) NULL COMMENT '动态参数 Key 列表';
