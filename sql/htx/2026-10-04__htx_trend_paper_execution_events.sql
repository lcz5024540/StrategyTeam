-- Append-only lifecycle evidence for paper execution. Do not run automatically on a shared database.
CREATE TABLE IF NOT EXISTS htx_trend_paper_execution_event (
    id                         BIGINT         PRIMARY KEY AUTO_INCREMENT,
    event_key                  VARCHAR(192)   NOT NULL,
    execution_key              VARCHAR(160)   NOT NULL,
    run_id                     VARCHAR(128)   NOT NULL,
    state_from                 VARCHAR(32)    NOT NULL,
    state_to                   VARCHAR(32)    NOT NULL,
    event_type                 VARCHAR(32)    NOT NULL,
    requested_contracts        BIGINT         NOT NULL,
    cumulative_filled_contracts BIGINT        NOT NULL DEFAULT 0,
    fill_price                 DECIMAL(36,18),
    reason                     VARCHAR(1024),
    occurred_at                DATETIME(3)    NOT NULL,
    created_at                 DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_htx_trend_paper_execution_event_key (event_key),
    INDEX idx_htx_trend_paper_execution_event_execution (execution_key, id),
    INDEX idx_htx_trend_paper_execution_event_run (run_id, occurred_at)
);
