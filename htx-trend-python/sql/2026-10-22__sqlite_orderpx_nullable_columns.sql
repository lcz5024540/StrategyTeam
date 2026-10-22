-- 未执行；执行须另批装机口令 / DO NOT RUN ON PROD
-- ORDERPX-PERSIST | Author: Codex | Date: 2026-10-22
-- Purpose: nullable new-order observations; no historical backfill.
-- Downtime: assess locks/table size in a separately authorized rollout.
-- Prerequisite: inspect existing columns first; do NOT rerun blindly.
-- MySQL/SQLite ADD COLUMN has no portable IF NOT EXISTS guard.
-- Runtime never runs this script. New writer requires these columns first.
-- Signal price; never inferred from fills
ALTER TABLE trend_order ADD COLUMN signal_px TEXT;
-- Signal time in UTC
ALTER TABLE trend_order ADD COLUMN signal_ts TEXT;
-- Submission price: limit or contemporaneous mid/last
ALTER TABLE trend_order ADD COLUMN order_px TEXT;
-- Only limit, mid, last; NULL when unknown
ALTER TABLE trend_order ADD COLUMN order_px_source TEXT CHECK (order_px_source IN ('limit','mid','last'));
-- Actual submission invocation time in UTC
ALTER TABLE trend_order ADD COLUMN order_ts TEXT;
-- NULL means unknown, not zero
ALTER TABLE trend_order ADD COLUMN cancel_count INTEGER;
-- NULL means unknown, not zero
ALTER TABLE trend_order ADD COLUMN amend_count INTEGER;

-- Rollback draft: after separate approval/backup, rebuild trend_order with
-- the prior schema, copying only prior columns and restoring indexes/FKs.
-- No rollback or copy is authorized by this file.
