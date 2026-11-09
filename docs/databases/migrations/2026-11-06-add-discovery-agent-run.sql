-- 2026-11-06 智能探索（Discovery Agent）运行记录表
-- 对应 PRD：research/design/discovery-agent-prd-2026-11-06.md
-- shortlist 即事实源：批量入库的 arxivIds 必须 ⊆ 本次 run 的 shortlist。

CREATE TABLE IF NOT EXISTS `discovery_run` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `run_id` VARCHAR(64) NOT NULL COMMENT 'Run 唯一标识（UUID，对外暴露的 ID）',
    `user_id` VARCHAR(64) NOT NULL COMMENT '发起用户 ID',
    `goal` VARCHAR(1000) NOT NULL COMMENT '研究目标（用户原文）',
    `max_papers` INT NOT NULL DEFAULT 5 COMMENT '目标篇数',
    `target_collection_id` BIGINT DEFAULT NULL COMMENT '意向集合（记录用；集合加入仍由前端调用）',
    `year_from` INT DEFAULT NULL COMMENT '年份过滤起点',
    `year_to` INT DEFAULT NULL COMMENT '年份过滤终点',
    `status` VARCHAR(16) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/COMPLETED/FAILED/CANCELLED',
    `reason_code` VARCHAR(64) DEFAULT NULL COMMENT '终态原因：hard_deadline_exceeded/context.../runner_lost/quota 等',
    `steps` LONGTEXT COMMENT '步骤行 JSON 数组（JSON string 数组）',
    `shortlist` LONGTEXT COMMENT '短名单 JSON 数组（arxivId/title/reason/... ，入库事实源）',
    `usage` TEXT COMMENT '用量 JSON：{"inputTokens":0,"outputTokens":0}',
    `import_results` LONGTEXT COMMENT '批量入库结果 JSON 数组（确认入库后回填）',
    `final_message` TEXT COMMENT '模型最后一条文本（best effort，不作为产品事实）',
    `model` VARCHAR(64) DEFAULT NULL COMMENT '实际使用的模型标识',
    `observed_count` INT NOT NULL DEFAULT 0 COMMENT '观察集条数',
    `search_count` INT NOT NULL DEFAULT 0 COMMENT '检索次数',
    `submit_attempts` INT NOT NULL DEFAULT 0 COMMENT '提交门尝试次数',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `terminal_at` DATETIME DEFAULT NULL COMMENT '终态时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_discovery_run_id` (`run_id`),
    KEY `idx_discovery_run_user_status` (`user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能探索（Discovery Agent）运行记录表';
