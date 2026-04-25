-- 用途：
-- 1. 给 smart_wallet_profile 增加人工覆盖相关字段
-- 2. 支持“只观察 / 人工退出 lane / 备注”等后台管理能力

DROP PROCEDURE IF EXISTS `sp_smart_wallet_profile_manual_override_migration`;

DELIMITER $$
CREATE PROCEDURE `sp_smart_wallet_profile_manual_override_migration`()
BEGIN
    DECLARE v_exists INT DEFAULT 0;
    DECLARE v_schema_name VARCHAR(128) DEFAULT DATABASE();

    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = v_schema_name
      AND table_name = 'smart_wallet_profile'
      AND column_name = 'manual_watch_enabled';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_profile`
            ADD COLUMN `manual_watch_enabled` TINYINT(1) NULL COMMENT '人工覆盖是否允许进入 executable watchlist' AFTER `watch_enabled`;
    END IF;

    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = v_schema_name
      AND table_name = 'smart_wallet_profile'
      AND column_name = 'manual_recommended_exit_lane';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_profile`
            ADD COLUMN `manual_recommended_exit_lane` VARCHAR(64) NULL COMMENT '人工覆盖的退出风格 lane' AFTER `manual_watch_enabled`;
    END IF;

    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = v_schema_name
      AND table_name = 'smart_wallet_profile'
      AND column_name = 'manual_override_note';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_profile`
            ADD COLUMN `manual_override_note` VARCHAR(255) NULL COMMENT '人工覆盖备注' AFTER `manual_recommended_exit_lane`;
    END IF;

    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = v_schema_name
      AND table_name = 'smart_wallet_profile'
      AND column_name = 'manual_override_updated_at';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_profile`
            ADD COLUMN `manual_override_updated_at` DATETIME NULL COMMENT '人工覆盖更新时间' AFTER `manual_override_note`;
    END IF;
END $$
DELIMITER ;

CALL `sp_smart_wallet_profile_manual_override_migration`();

DROP PROCEDURE IF EXISTS `sp_smart_wallet_profile_manual_override_migration`;
