-- Explicit production migration. Do not execute from worker startup.
-- EMA/VWAP momentum is an intraday acceleration model. It may enter only
-- when the candidate carries both EMA_STACK and MOMENTUM confirmations.
-- The existing execution gate still applies after this policy selection.

UPDATE htx_trend_strategy_policy
SET enabled = 1,
    primary_model = 'EMA_VWAP_MOMENTUM_CONTINUATION',
    confirmation_models = 'EMA_STACK,MOMENTUM',
    base_notional_usdt = 190,
    max_notional_usdt = 285,
    score_threshold = 85,
    box_window_bars = 32,
    updated_by = 'system',
    change_note = 'Enable EMA/VWAP momentum continuation for intraday acceleration candidates',
    updated_at = CURRENT_TIMESTAMP(3)
WHERE policy_code = 'EMA_VWAP_MOMENTUM'
  AND policy_version = 'combo-v2';

INSERT INTO htx_trend_strategy_policy (
    policy_code, policy_version, primary_model, confirmation_models, enabled,
    base_notional_usdt, max_notional_usdt, score_threshold, box_window_bars,
    updated_by, change_note
)
SELECT 'EMA_VWAP_MOMENTUM', 'combo-v2', 'EMA_VWAP_MOMENTUM_CONTINUATION',
       'EMA_STACK,MOMENTUM', 1, 190, 285, 85, 32, 'system',
       'Enable EMA/VWAP momentum continuation for intraday acceleration candidates'
WHERE NOT EXISTS (
    SELECT 1 FROM htx_trend_strategy_policy
    WHERE policy_code = 'EMA_VWAP_MOMENTUM' AND policy_version = 'combo-v2'
);

INSERT INTO htx_trend_strategy_policy_audit (
    policy_id, policy_code, policy_version, action, enabled, changed_by, change_note
)
SELECT id, policy_code, policy_version, 'ENABLE', enabled, 'system', change_note
FROM htx_trend_strategy_policy
WHERE policy_code = 'EMA_VWAP_MOMENTUM'
  AND policy_version = 'combo-v2';
