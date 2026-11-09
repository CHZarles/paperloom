-- 2026-11-08 智能探索：助手历史自带本轮检索词
-- 正常 chatbot 范式：对话记录即状态。助手消息行补记当轮工具动作（检索词与命中数），
-- 组装历史时随候选摘要点回给模型，续轮不需要任何派生台账。
--
-- 依赖：2026-11-07-discovery-agent-chat-and-memory.sql

ALTER TABLE `discovery_chat_message`
    ADD COLUMN `search_log` TEXT NULL
        COMMENT '当轮检索动作 JSON 数组 [{"q":"...","hits":12}]（ASSISTANT 行）'
        AFTER `import_results`;
