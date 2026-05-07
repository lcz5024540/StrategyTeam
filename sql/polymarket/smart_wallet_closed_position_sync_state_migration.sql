-- 用途：
-- 为 smart_wallet_profile 增加 closed-positions 增量同步状态字段。
-- 方案：基于 Polymarket /closed-positions 接口的 sortBy=TIMESTAMP&sortDirection=ASC 能力，
--      用 closed_at 业务高水位做增量同步，不依赖 offset 拐点。
-- 设计说明详见 docs/polymarket/smart-wallet-follow-address-api-vs-db-2026-05-07.md 第 9 节。
--
-- 字段含义：
--   closed_sync_max_closed_at       已落库的最大 closed_at(ms)，增量同步业务高水位
--   closed_sync_total_count         已落库 closed-position 总数，用于一致性校验
--   closed_sync_data_quality        数据质量标签：TRUTH / PARTIAL / UNTRUSTED / STUCK
--   closed_sync_last_full_at        上次完整全量复扫时间

DROP PROCEDURE IF EXISTS `sp_smart_wallet_closed_position_sync_state_migration`;

DELIMITER $
CREATE PROCEDURE `sp_smart_wallet_closed_position_sync_state_migration`()
BEGIN
    DECLARE v_exists INT DEFAULT 0;
    DECLARE v_schema_name VARCHAR(128) DEFAULT DATABASE();

    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = v_schema_name
      AND table_name = 'smart_wallet_profile'
      AND column_name = 'closed_sync_max_closed_at';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_profile`
            ADD COLUMN `closed_sync_max_closed_at` BIGINT DEFAULT NULL
                COMMENT '已抓到的最大 closed_at(ms)，作为增量同步业务高水位';
    END IF;

    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = v_schema_name
      AND table_name = 'smart_wallet_profile'
      AND column_name = 'closed_sync_total_count';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_profile`
            ADD COLUMN `closed_sync_total_count` INT NOT NULL DEFAULT 0
                COMMENT '已落库 closed-position 总数（一致性校验用）';
    END IF;

    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = v_schema_name
      AND table_name = 'smart_wallet_profile'
      AND column_name = 'closed_sync_data_quality';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_profile`
            ADD COLUMN `closed_sync_data_quality` VARCHAR(32) DEFAULT NULL
                COMMENT '增量同步数据质量：TRUTH/PARTIAL/UNTRUSTED/STUCK';
    END IF;

    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = v_schema_name
      AND table_name = 'smart_wallet_profile'
      AND column_name = 'closed_sync_last_full_at';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_profile`
            ADD COLUMN `closed_sync_last_full_at` DATETIME DEFAULT NULL
                COMMENT '上次完整全量复扫时间（HOT 周 1 次 / 其他月 1 次）';
    END IF;

    -- 便于按"最久未同步 / 质量差"优先调度
    SELECT COUNT(1) INTO v_exists
    FROM information_schema.statistics
    WHERE table_schema = v_schema_name
      AND table_name = 'smart_wallet_profile'
      AND index_name = 'idx_sw_profile_closed_sync_dispatch';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_profile`
            ADD INDEX `idx_sw_profile_closed_sync_dispatch`
            (`closed_sync_data_quality`, `closed_sync_last_full_at`);
    END IF;
END $
DELIMITER ;

CALL `sp_smart_wallet_closed_position_sync_state_migration`();

DROP PROCEDURE IF EXISTS `sp_smart_wallet_closed_position_sync_state_migration`;