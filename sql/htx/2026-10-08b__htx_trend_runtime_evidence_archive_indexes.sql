-- Runtime evidence cold-archive lookup indexes.
-- Execute manually before running htx_trend_runtime_evidence_archive.py on production.
-- This migration is additive only: no data mutation, strategy switch, or service restart.

SET @archive_index_schema = DATABASE();

SET @archive_index_name = 'idx_htx_trend_runtime_kline_candle_archive';
SET @archive_index_sql = IF(
    EXISTS(SELECT 1 FROM information_schema.statistics
           WHERE table_schema = @archive_index_schema AND table_name = 'htx_trend_runtime_kline_candle'
             AND index_name = @archive_index_name),
    'SELECT 1',
    'ALTER TABLE htx_trend_runtime_kline_candle ADD INDEX idx_htx_trend_runtime_kline_candle_archive (captured_at, id)');
PREPARE archive_index_statement FROM @archive_index_sql;
EXECUTE archive_index_statement;
DEALLOCATE PREPARE archive_index_statement;

SET @archive_index_name = 'idx_htx_trend_runtime_kline_snapshot_archive';
SET @archive_index_sql = IF(
    EXISTS(SELECT 1 FROM information_schema.statistics
           WHERE table_schema = @archive_index_schema AND table_name = 'htx_trend_runtime_kline_snapshot'
             AND index_name = @archive_index_name),
    'SELECT 1',
    'ALTER TABLE htx_trend_runtime_kline_snapshot ADD INDEX idx_htx_trend_runtime_kline_snapshot_archive (captured_at, id)');
PREPARE archive_index_statement FROM @archive_index_sql;
EXECUTE archive_index_statement;
DEALLOCATE PREPARE archive_index_statement;

SET @archive_index_name = 'idx_htx_trend_position_mark_snapshot_archive';
SET @archive_index_sql = IF(
    EXISTS(SELECT 1 FROM information_schema.statistics
           WHERE table_schema = @archive_index_schema AND table_name = 'htx_trend_position_mark_snapshot'
             AND index_name = @archive_index_name),
    'SELECT 1',
    'ALTER TABLE htx_trend_position_mark_snapshot ADD INDEX idx_htx_trend_position_mark_snapshot_archive (created_at, id)');
PREPARE archive_index_statement FROM @archive_index_sql;
EXECUTE archive_index_statement;
DEALLOCATE PREPARE archive_index_statement;
