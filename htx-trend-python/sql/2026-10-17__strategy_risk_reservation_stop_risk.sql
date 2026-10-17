-- Python LIVE risk reservations must account for stop risk, not only notional.
ALTER TABLE strategy_risk_reservation
  ADD COLUMN risk_usdt DECIMAL(36,18) NOT NULL DEFAULT 0 AFTER notional_usdt;

CREATE INDEX idx_strategy_reservation_account_risk
  ON strategy_risk_reservation(account_id, status, risk_usdt);
