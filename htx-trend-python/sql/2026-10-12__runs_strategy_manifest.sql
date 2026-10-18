-- Purpose: persist the strategy condition manifest on each research run.
-- Feature: 0.1.0-rc.9 strategy_manifest.yaml (C01–C09 catalog + sha256).
-- Date: 2026-10-12
-- Runtime: SQLite research ledger (LedgerManager._migrate_schema also applies this).
-- Downtime: none. New columns are nullable so older writers keep working.
-- Rollback: ALTER TABLE runs DROP COLUMN strategy_manifest_sha256;
--           ALTER TABLE runs DROP COLUMN strategy_manifest;
--           (SQLite 3.35+). Restore from ledger backup if DROP is unavailable.

ALTER TABLE runs ADD COLUMN strategy_manifest_sha256 TEXT;
ALTER TABLE runs ADD COLUMN strategy_manifest TEXT;
