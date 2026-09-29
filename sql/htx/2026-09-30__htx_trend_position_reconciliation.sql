-- HTX trend position reconciliation evidence.
-- A missing current position is never enough to invent a close price or realized PnL.  This ledger records
-- the authoritative HTX absence and whether a close can be linked through an exchange/client order identifier.

CREATE TABLE IF NOT EXISTS htx_trend_position_reconciliation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    reconciliation_key VARCHAR(180) NOT NULL,
    position_id BIGINT NOT NULL,
    account_label VARCHAR(32) NOT NULL DEFAULT 'primary',
    contract_code VARCHAR(32) NOT NULL,
    prior_status VARCHAR(32) NOT NULL,
    exchange_position_state VARCHAR(32) NOT NULL,
    close_order_id BIGINT NULL COMMENT 'Local htx_trend_order.id when a close intent exists',
    close_client_order_id VARCHAR(64) NULL,
    exchange_close_order_id BIGINT NULL,
    resolution VARCHAR(64) NOT NULL,
    reason VARCHAR(512) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_htx_trend_position_reconciliation_key (reconciliation_key),
    KEY idx_htx_trend_position_reconciliation_position_time (position_id, created_at),
    KEY idx_htx_trend_position_reconciliation_account_time (account_label, created_at)
) COMMENT='Immutable HTX current-position and close-order reconciliation evidence';
