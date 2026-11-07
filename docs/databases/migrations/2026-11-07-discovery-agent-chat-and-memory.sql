-- 2026-11-07 智能探索 v3：聊天单例 + 记忆系统
-- PRD：research/design/discovery-agent-prd-2026-11-06.md（v3）
-- discovery_run（2026-11-06）未上线即弃：本迁移直接移除，不留兼容。

CREATE TABLE IF NOT EXISTS `discovery_chat_message` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID（同时是窗口排序键）',
    `user_id` VARCHAR(64) NOT NULL COMMENT '用户 ID（每用户一个单例 thread）',
    `role` VARCHAR(16) NOT NULL COMMENT 'USER/ASSISTANT/SYSTEM(反馈行)/CUTOFF(/clear 标记行)',
    `kind` VARCHAR(16) DEFAULT NULL COMMENT 'turn 种类：CHAT/DISTILL（仅 ASSISTANT turn 行）',
    `turn_id` VARCHAR(64) DEFAULT NULL COMMENT 'turn 唯一标识',
    `turn_status` VARCHAR(16) DEFAULT NULL COMMENT 'RUNNING/COMPLETED/FAILED/CANCELLED（ASSISTANT turn 行的轮状态）',
    `reason_code` VARCHAR(64) DEFAULT NULL COMMENT 'FAILED 的原因码',
    `content` LONGTEXT COMMENT '消息文本（USER 原文 / ASSISTANT 回复 / SYSTEM 反馈）',
    `cards` LONGTEXT COMMENT '候选卡片 JSON 数组（ASSISTANT 行；入库门的事实源窗口部分）',
    `import_results` LONGTEXT COMMENT '入库结果 JSON（ASSISTANT 行）',
    `token_usage` TEXT COMMENT '用量 JSON {"inputTokens":0,"outputTokens":0}',
    `model` VARCHAR(64) DEFAULT NULL COMMENT '实际使用的模型',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_dchat_user` (`user_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能探索聊天（单例 thread；CUTOFF 行 = /clear 记忆分界）';

CREATE TABLE IF NOT EXISTS `discovery_memory` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `user_id` VARCHAR(64) NOT NULL COMMENT '用户 ID',
    `kind` VARCHAR(16) NOT NULL COMMENT 'ROLE 角色定位 / PREF 研究偏好 / INSIGHT 沉淀洞察',
    `content` VARCHAR(2000) NOT NULL COMMENT '记忆内容（≤2000 字）',
    `source` VARCHAR(16) NOT NULL COMMENT 'USER 面板手写 / DISTILLED 沉淀生成',
    `active` TINYINT NOT NULL DEFAULT 1 COMMENT '停用即不参与组装',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_dmem_user_kind` (`user_id`, `kind`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能探索长期记忆（用户可编辑，UI 面板增删改）';

DROP TABLE IF EXISTS `discovery_run`;
