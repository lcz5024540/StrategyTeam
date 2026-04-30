-- =====================================================================
-- Polymarket Copy 订单账本整体归档并清空
-- =====================================================================
-- 目标：
--   1) 备份所有订单运行态数据，方便恢复；
--   2) 删除所有订单相关数据，策略按初始资金 500 重新开始开仓；
--   3) 保留 wallet profile、market_info、manual allowlist 等配置/元数据。
--
-- 清理表：
--   smart_wallet_follow_task
--   smart_wallet_trade_event
--   smart_wallet_order
--   smart_wallet_fill
--   smart_wallet_position
--   smart_wallet_cash_ledger
--   smart_wallet_daily_report
--   smart_wallet_settlement_run / smart_wallet_settlement_retry
--   smart_wallet_claim_run / smart_wallet_claim_retry
--   smart_wallet_order_analysis
--
-- 默认 dry-run，只备份/删除后 ROLLBACK。正式执行：
--   SET @dry_run = 0;
--   SOURCE sql/polymarket/archive_and_reset_smart_wallet_orders.sql;
-- =====================================================================

USE coin_strategy;

SET @dry_run = IFNULL(@dry_run, 1);
SET @reset_run_id = DATE_FORMAT(NOW(6), 'archive_reset_orders_%Y%m%d%H%i%s');

SELECT '== archive reset run ==' AS step, @reset_run_id AS reset_run_id, @dry_run AS dry_run;

SELECT '== pre-reset counts ==' AS step;
SELECT 'smart_wallet_follow_task' AS table_name, COUNT(*) AS rows_to_archive FROM smart_wallet_follow_task;
SELECT 'smart_wallet_trade_event' AS table_name, COUNT(*) AS rows_to_archive FROM smart_wallet_trade_event;
SELECT 'smart_wallet_order' AS table_name, COUNT(*) AS rows_to_archive FROM smart_wallet_order;
SELECT 'smart_wallet_fill' AS table_name, COUNT(*) AS rows_to_archive FROM smart_wallet_fill;
SELECT 'smart_wallet_position' AS table_name, COUNT(*) AS rows_to_archive FROM smart_wallet_position;
SELECT 'smart_wallet_cash_ledger' AS table_name, COUNT(*) AS rows_to_archive FROM smart_wallet_cash_ledger;
SELECT 'smart_wallet_daily_report' AS table_name, COUNT(*) AS rows_to_archive FROM smart_wallet_daily_report;
SELECT 'smart_wallet_settlement_run' AS table_name, COUNT(*) AS rows_to_archive FROM smart_wallet_settlement_run;
SELECT 'smart_wallet_settlement_retry' AS table_name, COUNT(*) AS rows_to_archive FROM smart_wallet_settlement_retry;
SELECT 'smart_wallet_claim_run' AS table_name, COUNT(*) AS rows_to_archive FROM smart_wallet_claim_run;
SELECT 'smart_wallet_claim_retry' AS table_name, COUNT(*) AS rows_to_archive FROM smart_wallet_claim_retry;
SELECT 'smart_wallet_order_analysis' AS table_name, COUNT(*) AS rows_to_archive FROM smart_wallet_order_analysis;

-- Backup table creation uses DDL, so it intentionally sits outside the data transaction.
CREATE TABLE IF NOT EXISTS smart_wallet_follow_task_archive_reset_bak AS
SELECT CAST(NULL AS CHAR(64)) AS reset_run_id, NOW() AS backed_up_at, t.*
FROM smart_wallet_follow_task t WHERE 1 = 0;

CREATE TABLE IF NOT EXISTS smart_wallet_trade_event_archive_reset_bak AS
SELECT CAST(NULL AS CHAR(64)) AS reset_run_id, NOW() AS backed_up_at, t.*
FROM smart_wallet_trade_event t WHERE 1 = 0;

CREATE TABLE IF NOT EXISTS smart_wallet_order_archive_reset_bak AS
SELECT CAST(NULL AS CHAR(64)) AS reset_run_id, NOW() AS backed_up_at, t.*
FROM smart_wallet_order t WHERE 1 = 0;

CREATE TABLE IF NOT EXISTS smart_wallet_fill_archive_reset_bak AS
SELECT CAST(NULL AS CHAR(64)) AS reset_run_id, NOW() AS backed_up_at, t.*
FROM smart_wallet_fill t WHERE 1 = 0;

CREATE TABLE IF NOT EXISTS smart_wallet_position_archive_reset_bak AS
SELECT CAST(NULL AS CHAR(64)) AS reset_run_id, NOW() AS backed_up_at, t.*
FROM smart_wallet_position t WHERE 1 = 0;

CREATE TABLE IF NOT EXISTS smart_wallet_cash_ledger_archive_reset_bak AS
SELECT CAST(NULL AS CHAR(64)) AS reset_run_id, NOW() AS backed_up_at, t.*
FROM smart_wallet_cash_ledger t WHERE 1 = 0;

CREATE TABLE IF NOT EXISTS smart_wallet_daily_report_archive_reset_bak AS
SELECT CAST(NULL AS CHAR(64)) AS reset_run_id, NOW() AS backed_up_at, t.*
FROM smart_wallet_daily_report t WHERE 1 = 0;

CREATE TABLE IF NOT EXISTS smart_wallet_settlement_run_archive_reset_bak AS
SELECT CAST(NULL AS CHAR(64)) AS reset_run_id, NOW() AS backed_up_at, t.*
FROM smart_wallet_settlement_run t WHERE 1 = 0;

CREATE TABLE IF NOT EXISTS smart_wallet_settlement_retry_archive_reset_bak AS
SELECT CAST(NULL AS CHAR(64)) AS reset_run_id, NOW() AS backed_up_at, t.*
FROM smart_wallet_settlement_retry t WHERE 1 = 0;

CREATE TABLE IF NOT EXISTS smart_wallet_claim_run_archive_reset_bak AS
SELECT CAST(NULL AS CHAR(64)) AS reset_run_id, NOW() AS backed_up_at, t.*
FROM smart_wallet_claim_run t WHERE 1 = 0;

CREATE TABLE IF NOT EXISTS smart_wallet_claim_retry_archive_reset_bak AS
SELECT CAST(NULL AS CHAR(64)) AS reset_run_id, NOW() AS backed_up_at, t.*
FROM smart_wallet_claim_retry t WHERE 1 = 0;

CREATE TABLE IF NOT EXISTS smart_wallet_order_analysis_archive_reset_bak AS
SELECT CAST(NULL AS CHAR(64)) AS reset_run_id, NOW() AS backed_up_at, t.*
FROM smart_wallet_order_analysis t WHERE 1 = 0;

START TRANSACTION;

INSERT INTO smart_wallet_follow_task_archive_reset_bak SELECT @reset_run_id, NOW(), t.* FROM smart_wallet_follow_task t;
SELECT ROW_COUNT() AS backed_up_follow_tasks;
INSERT INTO smart_wallet_trade_event_archive_reset_bak SELECT @reset_run_id, NOW(), t.* FROM smart_wallet_trade_event t;
SELECT ROW_COUNT() AS backed_up_trade_events;
INSERT INTO smart_wallet_order_archive_reset_bak SELECT @reset_run_id, NOW(), t.* FROM smart_wallet_order t;
SELECT ROW_COUNT() AS backed_up_orders;
INSERT INTO smart_wallet_fill_archive_reset_bak SELECT @reset_run_id, NOW(), t.* FROM smart_wallet_fill t;
SELECT ROW_COUNT() AS backed_up_fills;
INSERT INTO smart_wallet_position_archive_reset_bak SELECT @reset_run_id, NOW(), t.* FROM smart_wallet_position t;
SELECT ROW_COUNT() AS backed_up_positions;
INSERT INTO smart_wallet_cash_ledger_archive_reset_bak SELECT @reset_run_id, NOW(), t.* FROM smart_wallet_cash_ledger t;
SELECT ROW_COUNT() AS backed_up_cash_ledger_rows;
INSERT INTO smart_wallet_daily_report_archive_reset_bak SELECT @reset_run_id, NOW(), t.* FROM smart_wallet_daily_report t;
SELECT ROW_COUNT() AS backed_up_daily_reports;
INSERT INTO smart_wallet_settlement_run_archive_reset_bak SELECT @reset_run_id, NOW(), t.* FROM smart_wallet_settlement_run t;
SELECT ROW_COUNT() AS backed_up_settlement_runs;
INSERT INTO smart_wallet_settlement_retry_archive_reset_bak SELECT @reset_run_id, NOW(), t.* FROM smart_wallet_settlement_retry t;
SELECT ROW_COUNT() AS backed_up_settlement_retries;
INSERT INTO smart_wallet_claim_run_archive_reset_bak SELECT @reset_run_id, NOW(), t.* FROM smart_wallet_claim_run t;
SELECT ROW_COUNT() AS backed_up_claim_runs;
INSERT INTO smart_wallet_claim_retry_archive_reset_bak SELECT @reset_run_id, NOW(), t.* FROM smart_wallet_claim_retry t;
SELECT ROW_COUNT() AS backed_up_claim_retries;
INSERT INTO smart_wallet_order_analysis_archive_reset_bak SELECT @reset_run_id, NOW(), t.* FROM smart_wallet_order_analysis t;
SELECT ROW_COUNT() AS backed_up_order_analysis_rows;

DELETE FROM smart_wallet_claim_retry;
SELECT ROW_COUNT() AS deleted_claim_retries;
DELETE FROM smart_wallet_claim_run;
SELECT ROW_COUNT() AS deleted_claim_runs;
DELETE FROM smart_wallet_settlement_retry;
SELECT ROW_COUNT() AS deleted_settlement_retries;
DELETE FROM smart_wallet_settlement_run;
SELECT ROW_COUNT() AS deleted_settlement_runs;
DELETE FROM smart_wallet_order_analysis;
SELECT ROW_COUNT() AS deleted_order_analysis_rows;
DELETE FROM smart_wallet_daily_report;
SELECT ROW_COUNT() AS deleted_daily_reports;
DELETE FROM smart_wallet_cash_ledger;
SELECT ROW_COUNT() AS deleted_cash_ledger_rows;
DELETE FROM smart_wallet_fill;
SELECT ROW_COUNT() AS deleted_fills;
DELETE FROM smart_wallet_order;
SELECT ROW_COUNT() AS deleted_orders;
DELETE FROM smart_wallet_position;
SELECT ROW_COUNT() AS deleted_positions;
DELETE FROM smart_wallet_trade_event;
SELECT ROW_COUNT() AS deleted_trade_events;
DELETE FROM smart_wallet_follow_task;
SELECT ROW_COUNT() AS deleted_follow_tasks;

SELECT '== verification inside transaction ==' AS step;
SELECT 'smart_wallet_follow_task' AS table_name, COUNT(*) AS remaining_rows FROM smart_wallet_follow_task;
SELECT 'smart_wallet_trade_event' AS table_name, COUNT(*) AS remaining_rows FROM smart_wallet_trade_event;
SELECT 'smart_wallet_order' AS table_name, COUNT(*) AS remaining_rows FROM smart_wallet_order;
SELECT 'smart_wallet_fill' AS table_name, COUNT(*) AS remaining_rows FROM smart_wallet_fill;
SELECT 'smart_wallet_position' AS table_name, COUNT(*) AS remaining_rows FROM smart_wallet_position;
SELECT 'smart_wallet_cash_ledger' AS table_name, COUNT(*) AS remaining_rows FROM smart_wallet_cash_ledger;
SELECT 'smart_wallet_daily_report' AS table_name, COUNT(*) AS remaining_rows FROM smart_wallet_daily_report;
SELECT 'smart_wallet_settlement_run' AS table_name, COUNT(*) AS remaining_rows FROM smart_wallet_settlement_run;
SELECT 'smart_wallet_settlement_retry' AS table_name, COUNT(*) AS remaining_rows FROM smart_wallet_settlement_retry;
SELECT 'smart_wallet_claim_run' AS table_name, COUNT(*) AS remaining_rows FROM smart_wallet_claim_run;
SELECT 'smart_wallet_claim_retry' AS table_name, COUNT(*) AS remaining_rows FROM smart_wallet_claim_retry;
SELECT 'smart_wallet_order_analysis' AS table_name, COUNT(*) AS remaining_rows FROM smart_wallet_order_analysis;

SELECT '== expected capital after commit ==' AS step, '500 USDC from signal-risk maxTotalOpenCostUsdc config' AS capital_source;

SELECT '== dry run guard ==' AS step,
       CASE WHEN @dry_run = 1 THEN 'ROLLBACK will be executed' ELSE 'COMMIT will be executed' END AS action;

SET @finish_sql = CASE WHEN @dry_run = 1 THEN 'ROLLBACK' ELSE 'COMMIT' END;
PREPARE finish_stmt FROM @finish_sql;
EXECUTE finish_stmt;
DEALLOCATE PREPARE finish_stmt;

SELECT '== finished ==' AS step, @reset_run_id AS reset_run_id, @dry_run AS dry_run;
