-- =================================================================
-- Reset Polymarket copy-strategy runtime state while keeping wallets.
--
-- Goal:
--   * Clear current strategy progress so the copy strategy can restart from
--     the initial 500 USDC portfolio budget.
--   * Keep follow-address/profile/manual allowlist/blocklist/strategy wallet
--     configuration intact.
--
-- Keep tables:
--   smart_wallet_profile
--   smart_wallet_manual_allowlist
--   smart_wallet_manual_blocklist
--   strategy_wallet
--   smart_wallet_market_info
--   strategy_runtime_config
--
-- Execute against database: coin_strategy
-- =================================================================

SET FOREIGN_KEY_CHECKS = 0;

TRUNCATE TABLE `smart_wallet_claim_retry`;
TRUNCATE TABLE `smart_wallet_claim_run`;
TRUNCATE TABLE `smart_wallet_settlement_retry`;
TRUNCATE TABLE `smart_wallet_settlement_run`;
TRUNCATE TABLE `smart_wallet_daily_report`;
TRUNCATE TABLE `smart_wallet_cash_ledger`;
TRUNCATE TABLE `smart_wallet_fill`;
TRUNCATE TABLE `smart_wallet_order`;
TRUNCATE TABLE `smart_wallet_trade_order`;
TRUNCATE TABLE `smart_wallet_position`;
TRUNCATE TABLE `smart_wallet_follow_task`;
TRUNCATE TABLE `smart_wallet_trade_event`;

-- Risk/reporting artifacts derived from runtime trading data.
TRUNCATE TABLE `smart_wallet_signal_shadow`;
TRUNCATE TABLE `smart_wallet_treatment_control_sample`;
TRUNCATE TABLE `smart_wallet_trade_effect_label`;
TRUNCATE TABLE `smart_wallet_wallet_feature_daily`;
TRUNCATE TABLE `smart_wallet_market_snapshot`;
TRUNCATE TABLE `smart_wallet_order_analysis`;
TRUNCATE TABLE `smart_wallet_order_stats_snapshot`;
TRUNCATE TABLE `smart_wallet_leader_trade_outcome`;
TRUNCATE TABLE `smart_wallet_behavior_profile`;
TRUNCATE TABLE `smart_wallet_profile_diagnosis_snapshot`;
TRUNCATE TABLE `wallet_follow_candidate`;
TRUNCATE TABLE `smart_wallet_market_concentration_snapshot`;

-- Operational/audit runtime tables for a clean dashboard and trigger queue.
TRUNCATE TABLE `strategy_alert`;
TRUNCATE TABLE `strategy_health_snapshot`;
TRUNCATE TABLE `strategy_run_log`;
TRUNCATE TABLE `strategy_registry_state`;
DELETE FROM `strategy_manual_trigger`;

SET FOREIGN_KEY_CHECKS = 1;

-- Restore the intended initial portfolio budget.
INSERT INTO `strategy_runtime_config`
    (`strategy_code`, `config_key`, `config_value`, `value_type`, `group_name`, `editable`, `description`, `updated_by`)
VALUES
    ('POLYMARKET_COPY',
     'smart-wallet.signal-risk.max-total-open-cost-usdc',
     '500',
     'DECIMAL',
     'entry-risk',
     1,
     '组合层打开仓位成本预算。',
     'reset-copy-runtime-2026-06-17')
ON DUPLICATE KEY UPDATE
    `config_value` = VALUES(`config_value`),
    `value_type` = VALUES(`value_type`),
    `group_name` = VALUES(`group_name`),
    `editable` = VALUES(`editable`),
    `description` = VALUES(`description`),
    `updated_by` = VALUES(`updated_by`),
    `updated_at` = CURRENT_TIMESTAMP;

-- Verification.
SELECT 'smart_wallet_profile' AS table_name, COUNT(*) AS rows_kept FROM `smart_wallet_profile`
UNION ALL SELECT 'smart_wallet_manual_allowlist', COUNT(*) FROM `smart_wallet_manual_allowlist`
UNION ALL SELECT 'smart_wallet_manual_blocklist', COUNT(*) FROM `smart_wallet_manual_blocklist`
UNION ALL SELECT 'strategy_wallet', COUNT(*) FROM `strategy_wallet`
UNION ALL SELECT 'smart_wallet_position', COUNT(*) FROM `smart_wallet_position`
UNION ALL SELECT 'smart_wallet_order', COUNT(*) FROM `smart_wallet_order`
UNION ALL SELECT 'smart_wallet_follow_task', COUNT(*) FROM `smart_wallet_follow_task`
UNION ALL SELECT 'smart_wallet_cash_ledger', COUNT(*) FROM `smart_wallet_cash_ledger`;

SELECT `strategy_code`, `config_key`, `config_value`
FROM `strategy_runtime_config`
WHERE `strategy_code` = 'POLYMARKET_COPY'
  AND `config_key` = 'smart-wallet.signal-risk.max-total-open-cost-usdc';
