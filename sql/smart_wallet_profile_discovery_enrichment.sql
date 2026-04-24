ALTER TABLE `smart_wallet_profile`
    ADD COLUMN `last_discovered_at` TIMESTAMP NULL COMMENT '最近一次 discovery 命中时间' AFTER `last_evaluated_at`,
    ADD COLUMN `last_discovery_query` VARCHAR(128) DEFAULT NULL COMMENT '最近命中的 leaderboard 查询' AFTER `last_discovered_at`,
    ADD COLUMN `leaderboard_hit_count_30d` INT NOT NULL DEFAULT 0 COMMENT '近30日 leaderboard 命中次数' AFTER `last_discovery_query`,
    ADD COLUMN `dominant_session` VARCHAR(16) DEFAULT NULL COMMENT 'ASIA/EU/US/MIXED' AFTER `leaderboard_hit_count_30d`,
    ADD COLUMN `asia_trade_ratio_30d` DECIMAL(10, 4) NOT NULL DEFAULT 0 COMMENT '近30日亚洲时段成交占比' AFTER `dominant_session`,
    ADD COLUMN `eu_trade_ratio_30d` DECIMAL(10, 4) NOT NULL DEFAULT 0 COMMENT '近30日欧洲时段成交占比' AFTER `asia_trade_ratio_30d`,
    ADD COLUMN `us_trade_ratio_30d` DECIMAL(10, 4) NOT NULL DEFAULT 0 COMMENT '近30日美洲时段成交占比' AFTER `eu_trade_ratio_30d`,
    ADD COLUMN `active_hour_histogram_json` TEXT DEFAULT NULL COMMENT '近30日按UTC小时聚合的成交直方图' AFTER `us_trade_ratio_30d`,
    ADD COLUMN `recent_win_rate_7d` DECIMAL(10, 4) DEFAULT NULL COMMENT '近7日胜率' AFTER `active_hour_histogram_json`,
    ADD COLUMN `recent_win_rate_30d` DECIMAL(10, 4) DEFAULT NULL COMMENT '近30日胜率' AFTER `recent_win_rate_7d`;

ALTER TABLE `smart_wallet_profile`
    ADD COLUMN `recent_trade_count_7d` INT NOT NULL DEFAULT 0 COMMENT '近7日交易样本数' AFTER `recent_win_rate_7d`,
    ADD COLUMN `recent_trade_count_30d` INT NOT NULL DEFAULT 0 COMMENT '近30日交易样本数' AFTER `recent_win_rate_30d`,
    ADD COLUMN `observe_cooldown_until` TIMESTAMP NULL COMMENT '近期退化观察冷却截止时间' AFTER `recent_trade_count_30d`,
    ADD COLUMN `observe_cooldown_reason` VARCHAR(255) DEFAULT NULL COMMENT '近期退化观察原因' AFTER `observe_cooldown_until`;
