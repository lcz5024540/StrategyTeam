-- Purpose: Add execution price and contract detail fields to the unified HTX strategy ledger.
-- Related feature: HTX trend capital/risk ledger V1.
-- Author: Codex
-- Date: 2026-09-21
-- Downtime: Not expected. Adds nullable columns only; run during a quiet window if the table becomes large.
-- Rollback: ALTER TABLE htx_trade_strategy.coin_strategy_ledger_event DROP COLUMN <column>; drop only after confirming no deployed code reads/writes these fields.

DROP PROCEDURE IF EXISTS sp_coin_strategy_ledger_execution_prices;
DELIMITER $$
CREATE PROCEDURE sp_coin_strategy_ledger_execution_prices()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = 'htx_trade_strategy'
          AND TABLE_NAME = 'coin_strategy_ledger_event'
          AND COLUMN_NAME = 'execution_price_usdt'
    ) THEN
        ALTER TABLE htx_trade_strategy.coin_strategy_ledger_event
            ADD COLUMN execution_price_usdt DECIMAL(36,18) NULL COMMENT 'Execution average price for this ledger event' AFTER side;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = 'htx_trade_strategy'
          AND TABLE_NAME = 'coin_strategy_ledger_event'
          AND COLUMN_NAME = 'entry_price_usdt'
    ) THEN
        ALTER TABLE htx_trade_strategy.coin_strategy_ledger_event
            ADD COLUMN entry_price_usdt DECIMAL(36,18) NULL COMMENT 'Position entry average price when available' AFTER execution_price_usdt;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = 'htx_trade_strategy'
          AND TABLE_NAME = 'coin_strategy_ledger_event'
          AND COLUMN_NAME = 'close_price_usdt'
    ) THEN
        ALTER TABLE htx_trade_strategy.coin_strategy_ledger_event
            ADD COLUMN close_price_usdt DECIMAL(36,18) NULL COMMENT 'Position close average price when available' AFTER entry_price_usdt;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = 'htx_trade_strategy'
          AND TABLE_NAME = 'coin_strategy_ledger_event'
          AND COLUMN_NAME = 'filled_contracts'
    ) THEN
        ALTER TABLE htx_trade_strategy.coin_strategy_ledger_event
            ADD COLUMN filled_contracts DECIMAL(36,18) NULL COMMENT 'Filled contract count represented by this event' AFTER close_price_usdt;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = 'htx_trade_strategy'
          AND TABLE_NAME = 'coin_strategy_ledger_event'
          AND COLUMN_NAME = 'contract_size'
    ) THEN
        ALTER TABLE htx_trade_strategy.coin_strategy_ledger_event
            ADD COLUMN contract_size DECIMAL(36,18) NULL COMMENT 'USDT swap contract size used to derive event notional' AFTER filled_contracts;
    END IF;
END$$
DELIMITER ;
CALL sp_coin_strategy_ledger_execution_prices();
DROP PROCEDURE IF EXISTS sp_coin_strategy_ledger_execution_prices;
