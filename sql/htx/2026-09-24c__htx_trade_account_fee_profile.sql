-- HTX trend Ledger-02: versioned account fee schedules and order provenance.
-- Historical orders retain their original fee_rate_used. This migration adds no synthetic fee profile references.
-- Safe to rerun on the same MySQL schema.

CREATE TABLE IF NOT EXISTS htx_trade_account_fee_profile (
    id                   BIGINT         PRIMARY KEY AUTO_INCREMENT,
    account_id           BIGINT         NOT NULL,
    account_label        VARCHAR(32)    NOT NULL,
    exchange             VARCHAR(16)    NOT NULL DEFAULT 'HTX',
    market_type          VARCHAR(16)    NOT NULL DEFAULT 'USDT_SWAP',
    contract_code        VARCHAR(32)    NOT NULL,
    open_maker_rate      DECIMAL(36,18) NOT NULL,
    open_taker_rate      DECIMAL(36,18) NOT NULL,
    close_maker_rate     DECIMAL(36,18) NOT NULL,
    close_taker_rate     DECIMAL(36,18) NOT NULL,
    effective_taker_rate DECIMAL(36,18) NOT NULL,
    source               VARCHAR(32)    NOT NULL,
    fallback             TINYINT(1)     NOT NULL DEFAULT 0,
    current_profile_key  VARCHAR(128),
    effective_from       DATETIME(3)    NOT NULL,
    effective_to         DATETIME(3),
    last_observed_at     DATETIME(3)    NOT NULL,
    created_at           DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at           DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_trade_account_fee_profile_current (current_profile_key),
    INDEX idx_trade_account_fee_profile_history (account_id, market_type, contract_code, effective_from)
);

DROP PROCEDURE IF EXISTS add_htx_fee_profile_column;
DELIMITER $$
CREATE PROCEDURE add_htx_fee_profile_column(
    IN p_table_name VARCHAR(64), IN p_column_name VARCHAR(64), IN p_column_definition TEXT, IN p_after_column VARCHAR(64)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table_name AND COLUMN_NAME = p_column_name
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE ', p_table_name, ' ADD COLUMN ', p_column_name,
                ' ', p_column_definition, ' AFTER ', p_after_column);
        PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

CALL add_htx_fee_profile_column('htx_trend_position', 'entry_fee_profile_id', 'BIGINT NULL', 'entry_fee_rate');
CALL add_htx_fee_profile_column('htx_trend_order', 'fee_profile_id', 'BIGINT NULL', 'fee_rate_used');
DROP PROCEDURE IF EXISTS add_htx_fee_profile_column;
