-- 1. 给 smart_wallet_profile 增加近7日胜率与近7/30日样本数字段
-- 2. 用于让评分逻辑同时参考全量 / 30d / 7d 表现，而不是仅看 30d

DROP PROCEDURE IF EXISTS `sp_smart_wallet_profile_recent_metrics_migration`;

DELIMITER $$
CREATE PROCEDURE `sp_smart_wallet_profile_recent_metrics_migration`()
BEGIN
  IF NOT EXISTS (
      SELECT 1
      FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'smart_wallet_profile'
        AND column_name = 'recent_win_rate_7d'
  ) THEN
    ALTER TABLE `smart_wallet_profile`
      ADD COLUMN `recent_win_rate_7d` DECIMAL(10,4) DEFAULT NULL COMMENT '近7日胜率' AFTER `active_hour_histogram_json`;
  END IF;

  IF NOT EXISTS (
      SELECT 1
      FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'smart_wallet_profile'
        AND column_name = 'recent_win_rate_30d'
  ) THEN
    ALTER TABLE `smart_wallet_profile`
      ADD COLUMN `recent_win_rate_30d` DECIMAL(10,4) DEFAULT NULL COMMENT '近30日胜率' AFTER `recent_win_rate_7d`;
  END IF;

  IF NOT EXISTS (
      SELECT 1
      FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'smart_wallet_profile'
        AND column_name = 'recent_trade_count_7d'
  ) THEN
    ALTER TABLE `smart_wallet_profile`
      ADD COLUMN `recent_trade_count_7d` INT NOT NULL DEFAULT 0 COMMENT '近7日交易样本数' AFTER `recent_win_rate_7d`;
  END IF;

  IF NOT EXISTS (
      SELECT 1
      FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'smart_wallet_profile'
        AND column_name = 'recent_trade_count_30d'
  ) THEN
    ALTER TABLE `smart_wallet_profile`
      ADD COLUMN `recent_trade_count_30d` INT NOT NULL DEFAULT 0 COMMENT '近30日交易样本数' AFTER `recent_win_rate_30d`;
  END IF;

  IF NOT EXISTS (
      SELECT 1
      FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'smart_wallet_profile'
        AND column_name = 'observe_cooldown_until'
  ) THEN
    ALTER TABLE `smart_wallet_profile`
      ADD COLUMN `observe_cooldown_until` TIMESTAMP NULL COMMENT '近期退化观察冷却截止时间' AFTER `recent_trade_count_30d`;
  END IF;

  IF NOT EXISTS (
      SELECT 1
      FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'smart_wallet_profile'
        AND column_name = 'observe_cooldown_reason'
  ) THEN
    ALTER TABLE `smart_wallet_profile`
      ADD COLUMN `observe_cooldown_reason` VARCHAR(255) DEFAULT NULL COMMENT '近期退化观察原因' AFTER `observe_cooldown_until`;
  END IF;
END$$
DELIMITER ;

CALL `sp_smart_wallet_profile_recent_metrics_migration`();

DROP PROCEDURE IF EXISTS `sp_smart_wallet_profile_recent_metrics_migration`;
