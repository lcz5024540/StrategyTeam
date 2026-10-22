-- 未执行；执行须另批装机口令 / DO NOT RUN ON PROD
-- ORDERPX-PERSIST | Author: Codex | Date: 2026-10-22
-- Purpose: nullable new-order observations; no historical backfill.
-- Downtime: assess locks/table size in a separately authorized rollout.
-- Prerequisite: inspect existing columns first; do NOT rerun blindly.
-- MySQL/SQLite ADD COLUMN has no portable IF NOT EXISTS guard.
-- Runtime never runs this script. New writer requires these columns first.
ALTER TABLE htx_trend_order
    ADD COLUMN signal_px DECIMAL(36,18) NULL COMMENT 'Signal price; never inferred from fills',
    ADD COLUMN signal_ts DATETIME(6) NULL COMMENT 'Signal time in UTC',
    ADD COLUMN order_px DECIMAL(36,18) NULL COMMENT 'Submission price: limit or contemporaneous mid/last',
    ADD COLUMN order_px_source VARCHAR(16) NULL COMMENT 'Only limit, mid, last; NULL when unknown',
    ADD COLUMN order_ts DATETIME(6) NULL COMMENT 'Actual submission invocation time in UTC',
    ADD COLUMN cancel_count INT NULL COMMENT 'NULL means unknown, not zero',
    ADD COLUMN amend_count INT NULL COMMENT 'NULL means unknown, not zero',
    ADD CONSTRAINT chk_htx_order_px_source CHECK (order_px_source IN ('limit','mid','last'));

-- Rollback draft ONLY; separately authorized backup/review required:
-- ALTER TABLE htx_trend_order DROP CHECK chk_htx_order_px_source,
-- DROP COLUMN signal_px, DROP COLUMN signal_ts, DROP COLUMN order_px, DROP COLUMN order_px_source, DROP COLUMN order_ts, DROP COLUMN cancel_count, DROP COLUMN amend_count;
