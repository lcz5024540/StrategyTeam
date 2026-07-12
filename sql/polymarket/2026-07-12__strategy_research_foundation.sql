-- Polymarket strategy research foundation.
-- Point-in-time rule: both feature_as_of and feature_available_at must be <= decision_at.

CREATE TABLE IF NOT EXISTS `smart_wallet_feature_snapshot` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `dataset_version` VARCHAR(64) NOT NULL,
    `wallet_address` VARCHAR(128) NOT NULL,
    `feature_as_of` DATETIME(3) NOT NULL,
    `feature_available_at` DATETIME(3) NOT NULL,
    `source_profile_updated_at` DATETIME(3) DEFAULT NULL,
    `win_rate` DECIMAL(10,8) DEFAULT NULL,
    `credible_win_rate` DECIMAL(10,8) DEFAULT NULL,
    `shrunk_win_rate` DECIMAL(10,8) DEFAULT NULL,
    `decisive_trades` INT DEFAULT NULL,
    `recent_win_rate_7d` DECIMAL(10,8) DEFAULT NULL,
    `recent_win_rate_30d` DECIMAL(10,8) DEFAULT NULL,
    `recent_trade_count_7d` INT DEFAULT NULL,
    `recent_trade_count_30d` INT DEFAULT NULL,
    `realized_pnl_usdc` DECIMAL(24,8) DEFAULT NULL,
    `total_trades` INT DEFAULT NULL,
    `average_roi_rate` DECIMAL(10,8) DEFAULT NULL,
    `max_drawdown_rate` DECIMAL(10,8) DEFAULT NULL,
    `wallet_score` DECIMAL(10,8) DEFAULT NULL,
    `qualified` TINYINT(1) DEFAULT NULL,
    `watch_enabled` TINYINT(1) DEFAULT NULL,
    `observe_cooldown_until` DATETIME(3) DEFAULT NULL,
    `archetype` VARCHAR(64) DEFAULT NULL,
    `replicability_score` DECIMAL(10,8) DEFAULT NULL,
    `quality_score` DECIMAL(10,8) DEFAULT NULL,
    `stability_score` DECIMAL(10,8) DEFAULT NULL,
    `behavior_composite` DECIMAL(10,8) DEFAULT NULL,
    `recommended_exit_lane` VARCHAR(64) DEFAULT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sw_feature_snapshot` (`dataset_version`, `wallet_address`, `feature_as_of`),
    KEY `idx_sw_feature_snapshot_lookup` (`dataset_version`, `wallet_address`, `feature_available_at`, `feature_as_of`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Point-in-time leader features for leakage-safe research';

CREATE TABLE IF NOT EXISTS `strategy_experiment` (
    `id` VARCHAR(64) NOT NULL,
    `strategy_key` VARCHAR(64) NOT NULL,
    `dataset_version` VARCHAR(64) NOT NULL,
    `feature_version` VARCHAR(64) NOT NULL,
    `model_version` VARCHAR(64) NOT NULL,
    `champion_policy_tag` VARCHAR(64) NOT NULL,
    `candidate_policy_json` JSON NOT NULL,
    `cost_scenario_json` JSON NOT NULL,
    `random_seed` BIGINT NOT NULL,
    `status` VARCHAR(24) NOT NULL,
    `spec_json` JSON NOT NULL,
    `result_json` JSON DEFAULT NULL,
    `result_hash` VARCHAR(64) DEFAULT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `started_at` DATETIME(3) DEFAULT NULL,
    `completed_at` DATETIME(3) DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_strategy_experiment_status` (`strategy_key`, `status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Immutable strategy experiment registry';

CREATE TABLE IF NOT EXISTS `strategy_decision_audit` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `experiment_id` VARCHAR(64) NOT NULL,
    `source_event_id` BIGINT UNSIGNED NOT NULL,
    `lane` VARCHAR(24) NOT NULL,
    `decision_at` DATETIME(3) NOT NULL,
    `feature_snapshot_id` BIGINT UNSIGNED DEFAULT NULL,
    `model_version` VARCHAR(64) NOT NULL,
    `accepted` TINYINT(1) NOT NULL,
    `recommended_stake_usdc` DECIMAL(24,8) NOT NULL DEFAULT 0,
    `p_win` DECIMAL(10,8) DEFAULT NULL,
    `p_win_lower_bound` DECIMAL(10,8) DEFAULT NULL,
    `estimated_net_ev_usdc` DECIMAL(24,8) DEFAULT NULL,
    `reason_json` JSON NOT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_strategy_decision_lane` (`experiment_id`, `source_event_id`, `lane`),
    KEY `idx_strategy_decision_event` (`source_event_id`, `decision_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Champion/challenger decision audit';

CREATE TABLE IF NOT EXISTS `strategy_promotion_state` (
    `strategy_key` VARCHAR(64) NOT NULL,
    `champion_model_version` VARCHAR(64) NOT NULL,
    `challenger_model_version` VARCHAR(64) DEFAULT NULL,
    `stage` VARCHAR(24) NOT NULL,
    `risk_budget_pct` DECIMAL(8,6) NOT NULL DEFAULT 0,
    `stage_started_at` DATETIME(3) NOT NULL,
    `settled_trade_count` INT NOT NULL DEFAULT 0,
    `gate_status_json` JSON NOT NULL,
    `last_rollback_reason` VARCHAR(512) DEFAULT NULL,
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`strategy_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Auditable shadow and live promotion state';
