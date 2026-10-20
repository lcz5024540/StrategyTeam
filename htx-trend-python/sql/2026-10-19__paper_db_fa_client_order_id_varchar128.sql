-- 禁现网执行直至热更口令 / DO NOT RUN ON PROD until separate hot-update order
-- 仅编码阶段签入；收到独立「可以再热更 PAPER」口令之前，禁止在生产执行。
-- Encoding-phase only: checked in only; production execution is forbidden until
-- the separate “可以再热更 PAPER” hot-update order. Candidate is not installable.
-- Preserve uk_trend_order_client; MODIFY does not drop its UNIQUE index.
ALTER TABLE htx_trend_order
    MODIFY COLUMN client_order_id VARCHAR(128) NOT NULL;
ALTER TABLE coin_strategy_ledger_event
    MODIFY COLUMN client_order_id VARCHAR(128) NULL;
