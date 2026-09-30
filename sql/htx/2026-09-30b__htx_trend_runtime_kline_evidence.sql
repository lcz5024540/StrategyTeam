-- Runtime K-line evidence ledger.
-- This is separate from htx_trend_research_*: it records the public payload actually supplied to live calculations.
-- Additive only. It does not alter order placement, position management, or strategy switches.

CREATE TABLE IF NOT EXISTS htx_trend_runtime_kline_candle (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Surrogate primary key',
    candle_hash CHAR(64) NOT NULL COMMENT 'SHA-256 of canonical HTX public bar content',
    contract_code VARCHAR(32) NOT NULL,
    period VARCHAR(16) NOT NULL,
    bar_time BIGINT NOT NULL COMMENT 'UTC epoch-second bar open time',
    open_price DECIMAL(36,18) NULL,
    high_price DECIMAL(36,18) NULL,
    low_price DECIMAL(36,18) NULL,
    close_price DECIMAL(36,18) NULL,
    amount DECIMAL(36,18) NULL,
    volume DECIMAL(36,18) NULL,
    turnover DECIMAL(36,18) NULL,
    trade_count BIGINT NULL,
    source_kind VARCHAR(32) NOT NULL COMMENT 'HTX_PUBLIC_RUNTIME',
    captured_at DATETIME(3) NOT NULL COMMENT 'When this public bar version was observed',
    PRIMARY KEY (id),
    UNIQUE KEY uk_htx_trend_runtime_kline_candle_hash (candle_hash),
    KEY idx_htx_trend_runtime_kline_candle_lookup (contract_code, period, bar_time, captured_at)
) COMMENT='Content-addressed public K-line versions observed by the running strategy';

CREATE TABLE IF NOT EXISTS htx_trend_runtime_kline_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Surrogate primary key',
    snapshot_key VARCHAR(255) NOT NULL COMMENT 'Immutable calculation-window identity',
    run_id VARCHAR(128) NULL COMMENT 'Candidate run when one exists',
    factor_snapshot_id BIGINT NULL COMMENT 'Related multi-timeframe factor snapshot when one exists',
    position_id BIGINT NULL COMMENT 'Related managed position during exit evaluation',
    capture_stage VARCHAR(32) NOT NULL COMMENT 'ENTRY_SCAN, ENTRY_GATE, MARKET_ANCHOR, FACTOR_SNAPSHOT or POSITION_MANAGEMENT',
    contract_code VARCHAR(32) NOT NULL,
    period VARCHAR(16) NOT NULL,
    target_bar_time BIGINT NOT NULL COMMENT 'Decision bar used by the calculation',
    window_start_bar_time BIGINT NOT NULL,
    window_end_bar_time BIGINT NOT NULL,
    expected_window_size INT NOT NULL,
    actual_window_size INT NOT NULL,
    complete_window TINYINT(1) NOT NULL COMMENT 'Whether the requested history window was returned in full',
    window_sha256 CHAR(64) NOT NULL COMMENT 'Ordered SHA-256 over the observed candle versions',
    source_kind VARCHAR(32) NOT NULL COMMENT 'HTX_PUBLIC_RUNTIME',
    source_reference VARCHAR(128) NOT NULL COMMENT 'Public endpoint path',
    data_integrity VARCHAR(32) NOT NULL COMMENT 'COMPLETE or PARTIAL relative to requested window size',
    captured_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_htx_trend_runtime_kline_snapshot_key (snapshot_key),
    KEY idx_htx_trend_runtime_kline_snapshot_lookup (contract_code, period, target_bar_time, capture_stage),
    KEY idx_htx_trend_runtime_kline_snapshot_run (run_id, factor_snapshot_id, position_id, captured_at)
) COMMENT='Immutable runtime calculation windows, reconstructible from observed candle versions';
