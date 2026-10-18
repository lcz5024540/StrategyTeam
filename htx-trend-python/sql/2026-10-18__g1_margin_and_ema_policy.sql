-- Additive migration; old reservations use notional as conservative margin until reconciled.
ALTER TABLE strategy_risk_reservation ADD COLUMN margin_usdt DECIMAL(36,18) NULL AFTER risk_usdt;
UPDATE htx_trend_strategy_policy SET primary_model='EMA_MOMENTUM_CONTINUATION', policy_code='EMA_MOMENTUM'
WHERE primary_model='EMA_VWAP_MOMENTUM_CONTINUATION';
-- Active positions must remain manageable after the API rename; historical closed rows retain provenance.
UPDATE htx_trend_position SET primary_model='EMA_MOMENTUM_CONTINUATION'
WHERE primary_model='EMA_VWAP_MOMENTUM_CONTINUATION'
  AND status IN ('INTENT','ENTRY_SENT','ACTIVE','PARTIAL_CLOSE_SENT','CLOSE_SENT','UNKNOWN');
