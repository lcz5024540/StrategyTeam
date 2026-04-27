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
    `confidence`   DECIMAL(20, 8) NOT NULL DEFAULT 0,
    `forecast_age_minutes` INT NOT NULL DEFAULT 0,
    `lead_hours`   INT NOT NULL DEFAULT 0,
    `calibration_version` VARCHAR(64) NOT NULL DEFAULT '',
    `mode`         VARCHAR(16) NOT NULL DEFAULT 'OFF' COMMENT 'OFF / SHADOW / PAPER / LIVE',
    `result_label` VARCHAR(16) NOT NULL DEFAULT '' COMMENT 'YES / NO / UNKNOWN',
    `action`       VARCHAR(16)  NOT NULL COMMENT 'PASS / BUY_YES / BUY_NO',
    `reason`       VARCHAR(512) NOT NULL DEFAULT '',
    `scanned_at`   DATETIME     NOT NULL,
    `created_at`   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_signal_log_city_scanned` (`city`, `scanned_at`),
    INDEX `idx_signal_log_market`       (`market_id`, `scanned_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='天气套利信号扫描日志';

-- 3. Create forecast snapshots table
CREATE TABLE IF NOT EXISTS `weather_forecast_snapshot` (
    `id`              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `city`            VARCHAR(64) NOT NULL,
    `forecast_date`   DATE NOT NULL COMMENT '预报发布日期',
    `target_date`     DATE NOT NULL COMMENT '目标结算日期',
    `temp_max_f`      DECIMAL(10, 4) NOT NULL DEFAULT 0,
    `temp_min_f`      DECIMAL(10, 4) NOT NULL DEFAULT 0,
    `temp_avg_f`      DECIMAL(10, 4) NOT NULL DEFAULT 0,
    `provider`        VARCHAR(64) NOT NULL DEFAULT '',
    `lead_hours`      INT NOT NULL DEFAULT 0,
    `data_source_count` INT NOT NULL DEFAULT 1,
    `fetched_at`      DATETIME NOT NULL,
    `created_at`      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_weather_forecast_city_target` (`city`, `target_date`, `fetched_at`),
    INDEX `idx_weather_forecast_provider` (`provider`, `target_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='天气预报快照';

-- 4. Create actual weather observation table
CREATE TABLE IF NOT EXISTS `weather_observation` (
    `id`              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `city`            VARCHAR(64) NOT NULL,
    `target_date`     DATE NOT NULL COMMENT '实际观测日期',
    `actual_max_f`    DECIMAL(10, 4) NOT NULL DEFAULT 0,
    `actual_min_f`    DECIMAL(10, 4) NOT NULL DEFAULT 0,
    `source`          VARCHAR(64) NOT NULL DEFAULT '',
    `resolved_at`     DATETIME NOT NULL,
    `created_at`      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_weather_observation_city_date` (`city`, `target_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='天气实际观测结果';

-- 5. Create calibration profile table
CREATE TABLE IF NOT EXISTS `weather_calibration_profile` (
    `id`              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `city`            VARCHAR(64) NOT NULL,
    `month`           INT NOT NULL,
    `lead_hour_bucket` INT NOT NULL,
    `sigma_max_f`     DECIMAL(10, 4) NOT NULL DEFAULT 0,
    `sigma_min_f`     DECIMAL(10, 4) NOT NULL DEFAULT 0,
    `sample_size`     INT NOT NULL DEFAULT 0,
    `version`         VARCHAR(64) NOT NULL DEFAULT '',
    `updated_at`      DATETIME NOT NULL,
    `created_at`      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_weather_calibration_profile` (`city`, `month`, `lead_hour_bucket`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='天气模型校准画像';

-- 6. Create parse log table for unsupported or low-confidence markets
CREATE TABLE IF NOT EXISTS `weather_market_parse_log` (
    `id`              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `market_id`       VARCHAR(128) NOT NULL,
    `question`        VARCHAR(512) NOT NULL DEFAULT '',
    `parse_status`    VARCHAR(32) NOT NULL DEFAULT 'PASS' COMMENT 'PARSED / REJECTED / LOW_CONFIDENCE',
    `reason`          VARCHAR(512) NOT NULL DEFAULT '',
    `confidence`      DECIMAL(20, 8) NOT NULL DEFAULT 0,
    `created_at`      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_weather_parse_market` (`market_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='天气市场解析日志';

-- 7. Create paper position table to isolate simulated runs from real positions
CREATE TABLE IF NOT EXISTS `weather_paper_position` (
    `id`              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `market_id`       VARCHAR(128) NOT NULL,
    `token_id`        VARCHAR(128) NOT NULL DEFAULT '',
    `city`            VARCHAR(64) NOT NULL,
    `question`        VARCHAR(512) NOT NULL DEFAULT '',
    `side`            VARCHAR(16) NOT NULL DEFAULT '',
    `entry_price`     DECIMAL(20, 8) NOT NULL DEFAULT 0,
    `quantity`        DECIMAL(20, 8) NOT NULL DEFAULT 0,
    `stake_usdc`      DECIMAL(20, 8) NOT NULL DEFAULT 0,
    `fair_value`      DECIMAL(20, 8) NOT NULL DEFAULT 0,
    `confidence`      DECIMAL(20, 8) NOT NULL DEFAULT 0,
    `market_end_at`   DATETIME NOT NULL,
    `status`          VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    `opened_at`       DATETIME NOT NULL,
    `closed_at`       DATETIME NULL,
    `exit_reason`     VARCHAR(128) NOT NULL DEFAULT '',
    `created_at`      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_weather_paper_status` (`status`, `opened_at`),
    INDEX `idx_weather_paper_market` (`market_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='天气策略纸面持仓';
