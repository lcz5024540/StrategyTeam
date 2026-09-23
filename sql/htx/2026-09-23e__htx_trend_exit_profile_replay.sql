-- Purpose: persist comparable full-exit, staged-exit and runner outcomes for the same trend replay input.
-- Safety: additive ledger migration. It does not enable partial take profit or submit any HTX order.
-- Prerequisite: 2026-09-23c__htx_trend_replay.sql has created the v1 replay tables.
-- This versioned script intentionally targets that v1 schema once; it does not use MySQL 8-only IF NOT EXISTS syntax.

ALTER TABLE htx_trend_replay_run
    ADD COLUMN comparison_summary_json MEDIUMTEXT NULL AFTER profit_factor;

ALTER TABLE htx_trend_replay_trade
    ADD COLUMN exit_profile VARCHAR(32) NOT NULL DEFAULT 'STAGED_RUNNER' AFTER replay_run_id;

ALTER TABLE htx_trend_replay_trade
    DROP INDEX uk_htx_trend_replay_trade,
    ADD UNIQUE KEY uk_htx_trend_replay_trade (replay_run_id, source_candidate_id, exit_profile),
    ADD INDEX idx_htx_trend_replay_trade_profile (replay_run_id, exit_profile, data_integrity, net_profit);
