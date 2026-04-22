-- weather_arbitrage_migration.sql
-- Run once against the target database

-- 1. Add source column to smart_wallet_position
ALTER TABLE `smart_wallet_position`
    ADD COLUMN `source` VARCHAR(32) NOT NULL DEFAULT 'FOLLOW_TRADE'
        COMMENT 'FOLLOW_TRADE / WEATHER'
        AFTER `status`;

-- 2. Create weather arbitrage signal log table
CREATE TABLE IF NOT EXISTS `weather_arbitrage_signal_log` (
    `id`           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `market_id`    VARCHAR(128) NOT NULL COMMENT 'Polymarket conditionId',
    `city`         VARCHAR(64)  NOT NULL,
    `market_price` DECIMAL(20, 8) NOT NULL DEFAULT 0,
    `fair_value`   DECIMAL(20, 8) NOT NULL DEFAULT 0,
    `edge`         DECIMAL(20, 8) NOT NULL DEFAULT 0,
    `action`       VARCHAR(16)  NOT NULL COMMENT 'PASS / BUY_YES / BUY_NO',
    `reason`       VARCHAR(512) NOT NULL DEFAULT '',
    `scanned_at`   DATETIME     NOT NULL,
    `created_at`   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_signal_log_city_scanned` (`city`, `scanned_at`),
    INDEX `idx_signal_log_market`       (`market_id`, `scanned_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='天气套利信号扫描日志';
