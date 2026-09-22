-- HTX trend exit diagnostics snapshot.
-- This migration is intentionally additive: it structures the existing exitSnapshot message fields
-- without changing trading behavior or existing close/stop rules.
ALTER TABLE htx_trend_position
    ADD COLUMN IF NOT EXISTS exit_snapshot_state VARCHAR(32) NULL COMMENT 'Latest exit diagnostics state: LOSS_ZONE/FEE_BREAKEVEN_ZONE/RUNNER_ZONE/EXHAUSTION_ZONE/PROFIT_ZONE/UNKNOWN' AFTER momentum_quality_score,
    ADD COLUMN IF NOT EXISTS exit_snapshot_net DECIMAL(36,18) NULL COMMENT 'Estimated close net PnL after fees at latest mark/reference price' AFTER exit_snapshot_state,
    ADD COLUMN IF NOT EXISTS exit_snapshot_mfe DECIMAL(36,18) NULL COMMENT 'Estimated maximum favorable excursion after fees' AFTER exit_snapshot_net,
    ADD COLUMN IF NOT EXISTS exit_snapshot_mae DECIMAL(36,18) NULL COMMENT 'Estimated maximum adverse excursion after fees' AFTER exit_snapshot_mfe,
    ADD COLUMN IF NOT EXISTS exit_snapshot_giveback DECIMAL(18,8) NULL COMMENT 'Profit giveback ratio from MFE to current net' AFTER exit_snapshot_mae,
    ADD COLUMN IF NOT EXISTS exit_snapshot_bars BIGINT NULL COMMENT 'Approximate bars elapsed since position creation' AFTER exit_snapshot_giveback,
    ADD COLUMN IF NOT EXISTS exit_snapshot_candidates VARCHAR(1024) NULL COMMENT 'Latest candidate exit trigger states' AFTER exit_snapshot_bars,
    ADD COLUMN IF NOT EXISTS exit_snapshot_updated_at DATETIME(3) NULL COMMENT 'Latest exit diagnostics refresh time' AFTER exit_snapshot_candidates;
