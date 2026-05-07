ALTER TABLE `smart_wallet_follow_task`
    ADD COLUMN `primary_skip_reason` VARCHAR(64) DEFAULT NULL COMMENT '主拒绝原因：NON_TRADABLE/PRICE/PROFILE_BEHAVIOR/CAPACITY/STALE/SIZE/OTHER' AFTER `error_message`,
    ADD COLUMN `skip_reason_tags` VARCHAR(512) DEFAULT NULL COMMENT '全部拒绝原因标签，逗号分隔' AFTER `primary_skip_reason`,
    ADD COLUMN `would_have_skipped_reason` TEXT DEFAULT NULL COMMENT 'PAPER_SHADOW 观测记录对应的原始软门控拒绝原因' AFTER `skip_reason_tags`,
    ADD INDEX `idx_follow_shadow_reason` (`status`, `primary_skip_reason`, `wallet_address`);
