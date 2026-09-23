CREATE TABLE IF NOT EXISTS htx_trend_paper_execution (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, execution_key VARCHAR(160) NOT NULL, run_id VARCHAR(128) NOT NULL,
    contract_code VARCHAR(32) NOT NULL, outcome VARCHAR(32) NOT NULL, fill_price DECIMAL(36,18) NULL,
    filled_contracts BIGINT NULL, reason VARCHAR(1024) NULL, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_htx_trend_paper_execution_key (execution_key), INDEX idx_htx_trend_paper_execution_run (run_id, created_at)
);
