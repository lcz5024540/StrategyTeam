-- Corrects the first LEDGER-01 rollout without changing execution or financial facts.
-- Scope: populate only missing execution identity, or replace the temporary legacy model
--        placeholder when the position already preserves a concrete primary_model.

START TRANSACTION;

UPDATE htx_trend_position p
LEFT JOIN htx_trade_account a ON a.account_label = p.account_label
SET p.account_id = COALESCE(p.account_id, a.id),
    p.strategy_family = COALESCE(NULLIF(p.strategy_family, ''), 'TREND'),
    p.strategy_name = COALESCE(NULLIF(p.strategy_name, ''), 'HTX_TREND_V1'),
    p.model_name = CASE
        WHEN (p.model_name IS NULL OR p.model_name = '' OR p.model_name = 'LEGACY_TREND_SIGNAL')
             AND NULLIF(p.primary_model, '') IS NOT NULL THEN p.primary_model
        WHEN p.model_name IS NULL OR p.model_name = '' THEN 'LEGACY_TREND_SIGNAL'
        ELSE p.model_name
    END
WHERE p.account_id IS NULL
   OR p.strategy_family IS NULL OR p.strategy_family = ''
   OR p.strategy_name IS NULL OR p.strategy_name = ''
   OR p.model_name IS NULL OR p.model_name = ''
   OR (p.model_name = 'LEGACY_TREND_SIGNAL' AND NULLIF(p.primary_model, '') IS NOT NULL);

UPDATE htx_trend_order o
LEFT JOIN htx_trade_account a ON a.account_label = o.account_label
LEFT JOIN htx_trend_position p ON p.id = o.position_id
SET o.account_id = COALESCE(o.account_id, a.id),
    o.strategy_family = COALESCE(NULLIF(o.strategy_family, ''), p.strategy_family, 'TREND'),
    o.strategy_name = COALESCE(NULLIF(o.strategy_name, ''), p.strategy_name, 'HTX_TREND_V1'),
    o.model_name = CASE
        WHEN (o.model_name IS NULL OR o.model_name = '' OR o.model_name = 'LEGACY_TREND_SIGNAL')
             AND NULLIF(p.model_name, '') IS NOT NULL THEN p.model_name
        WHEN o.model_name IS NULL OR o.model_name = '' THEN 'LEGACY_TREND_SIGNAL'
        ELSE o.model_name
    END,
    o.run_id = COALESCE(o.run_id, p.run_id),
    o.factor_snapshot_id = COALESCE(o.factor_snapshot_id, p.factor_snapshot_id)
WHERE o.account_id IS NULL
   OR o.strategy_family IS NULL OR o.strategy_family = ''
   OR o.strategy_name IS NULL OR o.strategy_name = ''
   OR o.model_name IS NULL OR o.model_name = ''
   OR (o.model_name = 'LEGACY_TREND_SIGNAL' AND NULLIF(p.model_name, '') IS NOT NULL)
   OR (o.run_id IS NULL AND p.run_id IS NOT NULL)
   OR (o.factor_snapshot_id IS NULL AND p.factor_snapshot_id IS NOT NULL);

UPDATE coin_strategy_ledger_event l
LEFT JOIN htx_trade_account a ON a.account_label = l.account_label
LEFT JOIN htx_trend_position p ON p.id = l.position_id
SET l.account_id = COALESCE(l.account_id, a.id, p.account_id),
    l.strategy_family = COALESCE(NULLIF(l.strategy_family, ''), p.strategy_family, 'TREND'),
    l.strategy_name = COALESCE(NULLIF(l.strategy_name, ''), p.strategy_name, 'HTX_TREND_V1'),
    l.model_name = CASE
        WHEN (l.model_name IS NULL OR l.model_name = '' OR l.model_name = 'LEGACY_TREND_SIGNAL')
             AND NULLIF(p.model_name, '') IS NOT NULL THEN p.model_name
        WHEN l.model_name IS NULL OR l.model_name = '' THEN 'LEGACY_TREND_SIGNAL'
        ELSE l.model_name
    END,
    l.run_id = COALESCE(l.run_id, p.run_id),
    l.factor_snapshot_id = COALESCE(l.factor_snapshot_id, p.factor_snapshot_id)
WHERE l.account_id IS NULL
   OR l.strategy_family IS NULL OR l.strategy_family = ''
   OR l.strategy_name IS NULL OR l.strategy_name = ''
   OR l.model_name IS NULL OR l.model_name = ''
   OR (l.model_name = 'LEGACY_TREND_SIGNAL' AND NULLIF(p.model_name, '') IS NOT NULL)
   OR (l.run_id IS NULL AND p.run_id IS NOT NULL)
   OR (l.factor_snapshot_id IS NULL AND p.factor_snapshot_id IS NOT NULL);

COMMIT;
