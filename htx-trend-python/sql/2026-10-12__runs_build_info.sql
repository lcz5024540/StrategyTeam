-- Purpose: persist package/_build_info identity on each research run.
-- Feature: 0.1.0-rc.10 freeze merge + strategy-manifest hash gate.
-- Date: 2026-10-12
-- Runtime: SQLite research ledger (LedgerManager._migrate_schema also applies this).
-- Downtime: none. New columns are nullable so older writers keep working.
-- Rollback: ALTER TABLE runs DROP COLUMN build_info_commit;
--           ALTER TABLE runs DROP COLUMN build_info_version;
--           ALTER TABLE runs DROP COLUMN build_info_branch;
--           ALTER TABLE runs DROP COLUMN package_content_hash;
--           (SQLite 3.35+). Restore from ledger backup if DROP is unavailable.

ALTER TABLE runs ADD COLUMN build_info_commit TEXT;
ALTER TABLE runs ADD COLUMN build_info_version TEXT;
ALTER TABLE runs ADD COLUMN build_info_branch TEXT;
ALTER TABLE runs ADD COLUMN package_content_hash TEXT;
ALTER TABLE runs ADD COLUMN integrity_verified INTEGER;
