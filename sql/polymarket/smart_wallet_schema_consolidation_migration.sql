-- =================================================================
-- Smart wallet schema consolidation migration
--
-- Purpose:
-- 1. Align the canonical arbitrage_schema.sql with runtime entity fields.
-- 2. Keep existing databases compatible without relying on service startup DDL.
-- 3. Avoid destructive cleanup: unused/research tables are documented separately.
-- =================================================================

DROP PROCEDURE IF EXISTS `sp_smart_wallet_schema_consolidation`;

DELIMITER $$

CREATE PROCEDURE `sp_smart_wallet_schema_consolidation`()
BEGIN
    -- smart_wallet_trade_event.size_shares
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'smart_wallet_trade_event'
          AND column_name = 'size_shares'
    ) THEN
        ALTER TABLE `smart_wallet_trade_event`
            ADD COLUMN `size_shares` DECIMAL(20,8) DEFAULT NULL COMMENT '成交份额数量' AFTER `price`;
    END IF;

    -- smart_wallet_profile manual override fields
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'smart_wallet_profile'
          AND column_name = 'manual_watch_enabled'
    ) THEN
        ALTER TABLE `smart_wallet_profile`
            ADD COLUMN `manual_watch_enabled` TINYINT(1) NULL COMMENT '人工覆盖是否允许进入 executable watchlist' AFTER `observe_cooldown_reason`;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'smart_wallet_profile'
          AND column_name = 'manual_recommended_exit_lane'
    ) THEN
        ALTER TABLE `smart_wallet_profile`
            ADD COLUMN `manual_recommended_exit_lane` VARCHAR(64) NULL COMMENT '人工覆盖的退出风格 lane' AFTER `manual_watch_enabled`;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'smart_wallet_profile'
          AND column_name = 'manual_override_note'
    ) THEN
        ALTER TABLE `smart_wallet_profile`
            ADD COLUMN `manual_override_note` VARCHAR(255) NULL COMMENT '人工覆盖备注' AFTER `manual_recommended_exit_lane`;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'smart_wallet_profile'
          AND column_name = 'manual_override_updated_at'
    ) THEN
        ALTER TABLE `smart_wallet_profile`
            ADD COLUMN `manual_override_updated_at` DATETIME NULL COMMENT '人工覆盖更新时间' AFTER `manual_override_note`;
    END IF;

    -- smart_wallet_market_info.market_slug
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'smart_wallet_market_info'
          AND column_name = 'market_slug'
    ) THEN
        ALTER TABLE `smart_wallet_market_info`
            ADD COLUMN `market_slug` VARCHAR(512) DEFAULT NULL COMMENT 'Polymarket market URL slug' AFTER `market_id`;
    END IF;

    -- smart_wallet_order.version
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'smart_wallet_order'
          AND column_name = 'version'
    ) THEN
        ALTER TABLE `smart_wallet_order`
            ADD COLUMN `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号' AFTER `updated_at`;
    END IF;

    -- smart_wallet_position.source / version
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'smart_wallet_position'
          AND column_name = 'source'
    ) THEN
        ALTER TABLE `smart_wallet_position`
            ADD COLUMN `source` VARCHAR(32) NOT NULL DEFAULT 'FOLLOW_TRADE' COMMENT '仓位来源：FOLLOW_TRADE/WEATHER' AFTER `status`;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'smart_wallet_position'
          AND column_name = 'version'
    ) THEN
        ALTER TABLE `smart_wallet_position`
            ADD COLUMN `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号' AFTER `updated_at`;
    END IF;

    -- Align runtime-created operations tables with canonical schema.
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = 'smart_wallet_settlement_run'
    ) THEN
        ALTER TABLE `smart_wallet_settlement_run`
            MODIFY COLUMN `error_message` VARCHAR(2048) DEFAULT NULL COMMENT '整体错误';
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = 'smart_wallet_settlement_retry'
    ) THEN
        ALTER TABLE `smart_wallet_settlement_retry`
            MODIFY COLUMN `last_error_message` TEXT DEFAULT NULL COMMENT '最近错误';
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = 'smart_wallet_claim_run'
    ) THEN
        ALTER TABLE `smart_wallet_claim_run`
            MODIFY COLUMN `error_message` TEXT DEFAULT NULL COMMENT '整体错误';
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = 'smart_wallet_claim_retry'
    ) THEN
        ALTER TABLE `smart_wallet_claim_retry`
            MODIFY COLUMN `last_error_message` VARCHAR(2048) DEFAULT NULL COMMENT '最近错误';
    END IF;
END$$

DELIMITER ;

CALL `sp_smart_wallet_schema_consolidation`();

DROP PROCEDURE IF EXISTS `sp_smart_wallet_schema_consolidation`;
