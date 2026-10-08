-- Runtime position-mark evidence for trend research and exit attribution.
-- Purpose: append one immutable mark-to-market observation per position and completed decision bar.
-- Related: LEDGER-03 and MODEL-HOLD-01. This script does not enable a model, alter v4 exits, or submit orders.
-- Deployment: execute manually before deploying the matching JAR; no service startup migration is permitted.
-- Downtime: none expected; this is an additive table, but run during a low-write window if the database is constrained.
-- Rollback: stop the matching JAR first, then DROP TABLE htx_trend_position_mark_snapshot after retaining an export.

CREATE TABLE IF NOT EXISTS htx_trend_position_mark_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Surrogate primary key',
    snapshot_key VARCHAR(192) NOT NULL COMMENT 'Position plus completed decision bar identity',
    position_id BIGINT NOT NULL COMMENT 'Managed trend position',
    run_id VARCHAR(128) NULL COMMENT 'Entry decision run identity',
    account_id BIGINT NULL COMMENT 'Trading account identity',
    account_label VARCHAR(32) NOT NULL DEFAULT 'primary' COMMENT 'Trading account label',
    contract_code VARCHAR(32) NOT NULL COMMENT 'HTX linear swap contract',
    side VARCHAR(8) NOT NULL COMMENT 'LONG or SHORT',
    primary_model VARCHAR(64) NULL COMMENT 'Entry model identity',
    timeframe_profile VARCHAR(32) NULL COMMENT 'Entry/holding timeframe profile',
    bar_time BIGINT NOT NULL COMMENT 'Completed entry-timeframe bar open epoch-second',
    reference_price DECIMAL(36,18) NOT NULL COMMENT 'Exchange mark or realtime reference price',
    unrealized_net_pnl DECIMAL(36,18) NULL COMMENT 'Estimated current net PnL after known costs',
    mfe_net_pnl DECIMAL(36,18) NULL COMMENT 'Maximum favorable net PnL observed so far',
    mae_net_pnl DECIMAL(36,18) NULL COMMENT 'Maximum adverse net PnL observed so far',
    giveback_rate DECIMAL(18,8) NULL COMMENT 'Fraction of MFE returned by current mark',
    protection_price DECIMAL(36,18) NULL COMMENT 'Current v4 or compatible protective price',
    trend_state VARCHAR(32) NULL COMMENT 'Holding trend assessment at this bar',
    exit_phase VARCHAR(32) NULL COMMENT 'Exit-engine phase at this bar',
    suggested_action VARCHAR(32) NULL COMMENT 'Observed HOLD, REDUCE or CLOSE decision',
    execution_mode VARCHAR(32) NOT NULL COMMENT 'OBSERVE_ONLY, EXIT_ENGINE_INPUT or LEGACY_PATH',
    evidence_json LONGTEXT NULL COMMENT 'Append-only model and exit evidence payload',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Snapshot append time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_htx_trend_position_mark_snapshot_key (snapshot_key),
    KEY idx_htx_trend_position_mark_position_time (position_id, bar_time),
    KEY idx_htx_trend_position_mark_run_time (run_id, created_at),
    KEY idx_htx_trend_position_mark_account_time (account_label, created_at)
) COMMENT='Append-only trend position mark-to-market evidence for replay and attribution';
