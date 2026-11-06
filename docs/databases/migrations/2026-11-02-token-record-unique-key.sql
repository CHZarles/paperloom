-- 目的：给消耗行加唯一键，让记账可以并发累加。
--
-- 变更内容：
--   1. 新增列 consume_day。只有 CONSUME 行写值，取值等于 record_date。
--   2. 历史 CONSUME 行回填 consume_day。
--   3. 合并历史重复的 CONSUME 行。
--   4. 加唯一键 uk_consume_day_type (user_id, consume_day, token_type)。
--
-- 为什么不用 (user_id, record_date, token_type, change_type) 做唯一键：
--   INCREASE 行每次追加额度写一行，同一天可能有多行。唯一键必须只约束消耗行。
--   consume_day 在 INCREASE 行为 NULL，而唯一索引允许多个 NULL，所以不冲突。
--
-- 执行顺序：先执行本脚本，再部署新版本。
--   反过来做的话，Hibernate 建唯一键时会因为历史重复行失败。
--
-- 背景：旧代码用「先查后写」累加当天记录。并发结算时两个事务读到同一旧值，
--   后写的覆盖先写的。所以历史数据里同一 (用户, 日期, 类型) 可能有多行消耗记录。

-- 1. 新增列
ALTER TABLE user_token_record
    ADD COLUMN consume_day DATE DEFAULT NULL
        COMMENT '消耗行唯一桶，取值等于 record_date；增加行留空' AFTER record_date;

-- 2. 回填历史消耗行
UPDATE user_token_record
SET consume_day = record_date
WHERE change_type = 'CONSUME';

-- 3. 找出需要合并的消耗行分组
CREATE TEMPORARY TABLE token_record_merge AS
SELECT user_id,
       record_date,
       token_type,
       MIN(id) AS keep_id,
       MAX(id) AS last_id,
       SUM(amount) AS total_amount,
       SUM(request_count) AS total_requests
FROM user_token_record
WHERE change_type = 'CONSUME'
GROUP BY user_id, record_date, token_type
HAVING COUNT(*) > 1;

-- 4. 把数量和请求次数合并到保留行，余额快照取最后一行的值
UPDATE user_token_record r
    JOIN token_record_merge m ON r.id = m.keep_id
    JOIN user_token_record last ON last.id = m.last_id
SET r.amount = m.total_amount,
    r.request_count = m.total_requests,
    r.balance_after = last.balance_after;

-- 5. 删除被合并的其余消耗行
DELETE r
FROM user_token_record r
    JOIN token_record_merge m
        ON r.user_id = m.user_id
        AND r.record_date = m.record_date
        AND r.token_type = m.token_type
WHERE r.change_type = 'CONSUME'
  AND r.id <> m.keep_id;

DROP TEMPORARY TABLE token_record_merge;

-- 6. 加唯一键
ALTER TABLE user_token_record
    ADD UNIQUE KEY uk_consume_day_type (user_id, consume_day, token_type);
