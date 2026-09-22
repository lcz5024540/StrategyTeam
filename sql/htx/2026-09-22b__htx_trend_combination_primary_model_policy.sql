-- v2: the entry trigger is the primary model; other structures are confirmations.
-- Apply after 2026-09-22__htx_trend_combination_strategy.sql.
UPDATE htx_trend_strategy_policy
SET enabled = 0, updated_by = 'system',
    change_note = 'superseded: v2 primary model follows entry trigger', updated_at = CURRENT_TIMESTAMP(3)
WHERE policy_version = 'combo-v1' AND enabled = 1;

INSERT INTO htx_trend_strategy_policy (
    policy_code, policy_version, primary_model, confirmation_models, enabled,
    base_notional_usdt, max_notional_usdt, score_threshold, box_window_bars, updated_by, change_note
) VALUES
('DONCHIAN_BOX', 'combo-v2', 'DONCHIAN_BREAKOUT', 'BOX_BREAKOUT', 1, 190, 285, 85, 32, 'system', 'fresh breakout trigger'),
('FIB_BOX', 'combo-v2', 'FIB_PULLBACK_CONTINUATION', 'BOX_BREAKOUT', 1, 190, 285, 85, 32, 'system', 'pullback trigger with box source'),
('FIB_DONCHIAN', 'combo-v2', 'FIB_PULLBACK_CONTINUATION', 'DONCHIAN_BREAKOUT', 1, 190, 285, 85, 32, 'system', 'pullback trigger with channel source'),
('FIB_BOX_DONCHIAN', 'combo-v2', 'FIB_PULLBACK_CONTINUATION', 'BOX_BREAKOUT,DONCHIAN_BREAKOUT', 1, 190, 285, 90, 32, 'system', 'pullback trigger with dual structure source');
