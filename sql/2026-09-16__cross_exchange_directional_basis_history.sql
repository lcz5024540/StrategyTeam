-- Purpose: persist executable directional basis samples so the admission gate survives restarts.
-- Related feature: cross-exchange funding paper directional-basis admission.
-- Author: Codex
-- Date: 2026-09-16
-- Downtime required: no; additive nullable column, but execute during a low-write window.
-- Rollback: after rolling the application back, back up the table and run
--   ALTER TABLE cross_exchange_arb_opportunity DROP COLUMN directional_basis;
--   Dropping the column is destructive and must not be executed without explicit approval.

SET @has_directional_basis := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'cross_exchange_arb_opportunity'
    AND COLUMN_NAME = 'directional_basis'
);
SET @ddl := IF(@has_directional_basis = 0,
  'ALTER TABLE cross_exchange_arb_opportunity ADD COLUMN directional_basis DECIMAL(28,16) NULL COMMENT ''(short executable entry - long executable entry) / long executable entry'' AFTER executable_notional',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
