-- ===============================================================
-- Carcolate AgentGroup 初始化 SQL
-- database: car_agents
-- ===============================================================

-- ============================
-- Agent 定义表
-- ============================
DROP TABLE IF EXISTS `tb_agent`;
CREATE TABLE `tb_agent` (
  `id` BIGINT NOT NULL COMMENT '雪花ID（YitIdHelper.nextId）',
  `name` VARCHAR(64) NOT NULL COMMENT 'Agent 名称，如：极石汽车销售专家',
  `mission` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '身份与使命，一句话定位',
  `pre_prompt` MEDIUMTEXT NOT NULL COMMENT '前置 System Prompt：任务目标/操作步骤/语气/注意事项 等完整提示词',
  `style_prompt` MEDIUMTEXT NULL COMMENT '语言风格 Prompt：主 Agent 不感知，仅在生成最终回复后用于润色风格',
  `model_name` VARCHAR(64) DEFAULT NULL COMMENT '可选：覆盖全局模型名',
  `temperature` DECIMAL(3,2) DEFAULT NULL COMMENT '可选：覆盖全局温度',
  `max_steps` INT NOT NULL DEFAULT 6 COMMENT 'Agent 单次最多决策轮数，防死循环',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1启用 0禁用',
  `remark` VARCHAR(255) DEFAULT NULL COMMENT '备注',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 定义表';

-- ============================
-- RAG 知识库表
-- ============================
DROP TABLE IF EXISTS `tb_rag_base`;
CREATE TABLE `tb_rag_base` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `agent_id` BIGINT NOT NULL COMMENT '所属 Agent ID（tb_agent.id）',
  `title` VARCHAR(255) NOT NULL COMMENT '文档标题',
  `content` LONGTEXT NOT NULL COMMENT '文档内容（Markdown/纯文本）',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1启用 0禁用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_agent_status` (`agent_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 知识库（启动时向量化到内存）';

-- ============================
-- 示例数据：极石汽车销售专家
-- ============================
INSERT INTO `tb_agent`(`id`,`name`,`mission`,`pre_prompt`,`max_steps`,`status`,`remark`)
VALUES (
  1,
  '极石汽车销售专家',
  '以极石汽车线上销售的身份与客户在线沟通，实现客户转化至到店看车',
  '# 角色\n你是极石汽车线上销售顾问，能力包含：客户情况分析、销售策略设计、销售话术生成。\n\n# 操作步骤\n1. 分析客户消息：结合历史摘要与近期对话，理解客户当前诉求。\n2. 必要时调用 search_knowledge_base 工具，按需检索销售技巧或产品资料。\n3. 制定销售回复策略，融合产品知识与销售技巧。\n4. 优化回复语气：专业、简洁(中文≤100字)、无AI味、不要铺垫废话。\n5. 每轮对话尽可能引导客户到店看车。\n\n# 注意事项\n- 严禁编造未在知识库中出现的产品参数或价格。\n- 拿到知识库片段后必须用自己的话整合，不要直接复制大段文字。\n- 输出最终回复时，不要再附加思考过程，只给客户实际看到的那一句。',
  6,
  1,
  '示例 Agent，可在管理后台编辑'
);
