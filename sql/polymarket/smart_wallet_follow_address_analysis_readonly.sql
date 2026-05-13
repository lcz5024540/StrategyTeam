-- Polymarket smart-wallet 跟单地址胜率与失误率分析只读 SQL
-- 用法：
--   mysql -h49.51.250.174 -P3306 -uroot -p coin_strategy < sql/polymarket/smart_wallet_follow_address_analysis_readonly.sql
--
-- 注意：
--   1. 本文件只包含 SELECT / SHOW，不修改数据库。
--   2. 已跟单订单以 smart_wallet_trade_order 为生命周期主口径。
--   3. smart_wallet_position 仅用于数据质量检查。

-- 1. 总体表现
SELECT
  COUNT(*) AS trades,
  SUM(status <> 'OPEN') AS closed_trades,
  SUM(status = 'OPEN') AS open_trades,
  SUM(status <> 'OPEN' AND total_pnl_usdc > 0) AS wins,
  SUM(status <> 'OPEN' AND total_pnl_usdc < 0) AS losses,
  SUM(status <> 'OPEN' AND total_pnl_usdc = 0) AS breakeven,
  ROUND(SUM(status <> 'OPEN' AND total_pnl_usdc > 0) / NULLIF(SUM(status <> 'OPEN'), 0), 4) AS win_rate,
  ROUND(SUM(status <> 'OPEN' AND total_pnl_usdc < 0) / NULLIF(SUM(status <> 'OPEN'), 0), 4) AS mistake_rate,
  ROUND(SUM(total_pnl_usdc), 4) AS total_pnl,
  ROUND(SUM(entry_cost_usdc), 4) AS entry_cost,
  ROUND(SUM(total_pnl_usdc) / NULLIF(SUM(entry_cost_usdc), 0), 4) AS roi,
  MIN(opened_at) AS min_opened,
  MAX(COALESCE(last_exit_at, settled_at, opened_at)) AS max_event
FROM smart_wallet_trade_order;

SELECT
  status,
  COUNT(*) AS trades,
  SUM(total_pnl_usdc > 0) AS wins,
  SUM(total_pnl_usdc < 0) AS losses,
  SUM(total_pnl_usdc = 0) AS breakeven,
  ROUND(SUM(total_pnl_usdc), 4) AS pnl,
  ROUND(SUM(entry_cost_usdc), 4) AS cost
FROM smart_wallet_trade_order
GROUP BY status
ORDER BY pnl ASC;

-- 2. 跟单地址级复盘
SELECT
  tro.wallet_address,
  COUNT(*) AS trades,
  SUM(tro.status <> 'OPEN') AS closed_trades,
  SUM(tro.status = 'OPEN') AS open_trades,
  SUM(tro.status <> 'OPEN' AND tro.total_pnl_usdc > 0) AS wins,
  SUM(tro.status <> 'OPEN' AND tro.total_pnl_usdc < 0) AS losses,
  SUM(tro.status <> 'OPEN' AND tro.total_pnl_usdc = 0) AS breakeven,
  ROUND(SUM(tro.status <> 'OPEN' AND tro.total_pnl_usdc > 0) / NULLIF(SUM(tro.status <> 'OPEN'), 0), 4) AS win_rate,
  ROUND(SUM(tro.status <> 'OPEN' AND tro.total_pnl_usdc < 0) / NULLIF(SUM(tro.status <> 'OPEN'), 0), 4) AS mistake_rate,
  ROUND(SUM(tro.total_pnl_usdc), 4) AS pnl,
  ROUND(SUM(tro.entry_cost_usdc), 4) AS cost,
  ROUND(SUM(tro.total_pnl_usdc) / NULLIF(SUM(tro.entry_cost_usdc), 0), 4) AS roi,
  ROUND(AVG(tro.entry_avg_price), 4) AS avg_entry_price,
  ROUND(AVG(CASE
    WHEN tro.status <> 'OPEN' THEN TIMESTAMPDIFF(MINUTE, tro.opened_at, COALESCE(tro.last_exit_at, tro.settled_at))
  END) / 60, 2) AS avg_hold_hours,
  p.win_rate AS profile_win_rate,
  p.recent_win_rate_7d,
  p.recent_trade_count_7d,
  p.recent_win_rate_30d,
  p.recent_trade_count_30d,
  p.wallet_score,
  b.archetype,
  b.follow_lane,
  b.replicability_score,
  b.recommended_exit_lane
FROM smart_wallet_trade_order tro
LEFT JOIN smart_wallet_profile p
  ON p.wallet_address COLLATE utf8mb4_unicode_ci = tro.wallet_address COLLATE utf8mb4_unicode_ci
LEFT JOIN smart_wallet_behavior_profile b
  ON b.wallet_address COLLATE utf8mb4_unicode_ci = tro.wallet_address COLLATE utf8mb4_unicode_ci
GROUP BY
  tro.wallet_address,
  p.win_rate,
  p.recent_win_rate_7d,
  p.recent_trade_count_7d,
  p.recent_win_rate_30d,
  p.recent_trade_count_30d,
  p.wallet_score,
  b.archetype,
  b.follow_lane,
  b.replicability_score,
  b.recommended_exit_lane
ORDER BY pnl ASC;

-- 3. 退出/结算原因与失误归因
SELECT
  COALESCE(eo.exit_reason,
    CASE
      WHEN tro.status = 'CLAIMED' THEN 'SETTLEMENT'
      WHEN tro.status = 'OPEN' THEN 'OPEN'
      ELSE 'UNKNOWN'
    END) AS reason,
  COUNT(*) AS trades,
  SUM(tro.status <> 'OPEN') AS closed_trades,
  SUM(tro.total_pnl_usdc > 0) AS wins,
  SUM(tro.total_pnl_usdc < 0) AS losses,
  SUM(tro.total_pnl_usdc = 0) AS breakeven,
  ROUND(SUM(tro.total_pnl_usdc), 4) AS pnl,
  ROUND(SUM(tro.entry_cost_usdc), 4) AS cost,
  ROUND(AVG(tro.roi_rate), 4) AS avg_roi
FROM smart_wallet_trade_order tro
LEFT JOIN smart_wallet_order eo
  ON eo.position_id = tro.position_id
 AND eo.side = 'SELL'
GROUP BY reason
ORDER BY pnl ASC;

SELECT
  CASE
    WHEN tro.status = 'CLAIMED' AND tro.settlement_proceeds_usdc = 0 THEN '结算归零风险'
    WHEN eo.exit_reason = 'STOP_LOSS_AUTO_EXIT' THEN '止损退出'
    WHEN tro.status = 'OPEN' AND tro.total_pnl_usdc < 0 THEN '开放浮亏'
    WHEN eo.exit_reason = 'TAKE_PROFIT_AUTO_EXIT' THEN '止盈退出'
    WHEN eo.exit_reason = 'CAPITAL_RECYCLE_AUTO_EXIT' THEN '资金回收/保本退出'
    WHEN tro.total_pnl_usdc = 0 THEN '零收益/未实现'
    ELSE '其他'
  END AS attribution,
  COUNT(*) AS trades,
  SUM(tro.status <> 'OPEN') AS closed_trades,
  SUM(tro.total_pnl_usdc > 0) AS wins,
  SUM(tro.total_pnl_usdc < 0) AS losses,
  ROUND(SUM(tro.total_pnl_usdc), 4) AS pnl,
  ROUND(SUM(tro.entry_cost_usdc), 4) AS cost
FROM smart_wallet_trade_order tro
LEFT JOIN smart_wallet_order eo
  ON eo.position_id = tro.position_id
 AND eo.side = 'SELL'
GROUP BY attribution
ORDER BY pnl ASC;

-- 4. Top 亏损 / Top 盈利订单
SELECT
  tro.wallet_address,
  tro.position_id,
  tro.status,
  LEFT(mi.question, 90) AS question,
  mi.outcome,
  mi.normalized_category AS category,
  tro.entry_avg_price,
  tro.entry_cost_usdc,
  tro.total_pnl_usdc,
  tro.roi_rate,
  tro.opened_at,
  COALESCE(tro.last_exit_at, tro.settled_at) AS closed_or_marked_at,
  COALESCE(eo.exit_reason,
    CASE
      WHEN tro.status = 'CLAIMED' THEN 'SETTLEMENT'
      WHEN tro.status = 'OPEN' THEN 'OPEN'
      ELSE 'UNKNOWN'
    END) AS reason
FROM smart_wallet_trade_order tro
LEFT JOIN smart_wallet_market_info mi ON mi.id = tro.market_info_id
LEFT JOIN smart_wallet_order eo
  ON eo.position_id = tro.position_id
 AND eo.side = 'SELL'
ORDER BY tro.total_pnl_usdc ASC
LIMIT 15;

SELECT
  tro.wallet_address,
  tro.position_id,
  tro.status,
  LEFT(mi.question, 90) AS question,
  mi.outcome,
  mi.normalized_category AS category,
  tro.entry_avg_price,
  tro.entry_cost_usdc,
  tro.total_pnl_usdc,
  tro.roi_rate,
  tro.opened_at,
  COALESCE(tro.last_exit_at, tro.settled_at) AS closed_or_marked_at,
  COALESCE(eo.exit_reason,
    CASE
      WHEN tro.status = 'CLAIMED' THEN 'SETTLEMENT'
      WHEN tro.status = 'OPEN' THEN 'OPEN'
      ELSE 'UNKNOWN'
    END) AS reason
FROM smart_wallet_trade_order tro
LEFT JOIN smart_wallet_market_info mi ON mi.id = tro.market_info_id
LEFT JOIN smart_wallet_order eo
  ON eo.position_id = tro.position_id
 AND eo.side = 'SELL'
ORDER BY tro.total_pnl_usdc DESC
LIMIT 10;

-- 5. 市场分类与入场价格桶
SELECT
  COALESCE(mi.normalized_category, 'UNKNOWN') AS category,
  COUNT(*) AS trades,
  SUM(tro.status <> 'OPEN') AS closed_trades,
  SUM(tro.status <> 'OPEN' AND tro.total_pnl_usdc > 0) AS wins,
  SUM(tro.status <> 'OPEN' AND tro.total_pnl_usdc < 0) AS losses,
  ROUND(SUM(tro.status <> 'OPEN' AND tro.total_pnl_usdc > 0) / NULLIF(SUM(tro.status <> 'OPEN'), 0), 4) AS win_rate,
  ROUND(SUM(tro.total_pnl_usdc), 4) AS pnl,
  ROUND(SUM(tro.entry_cost_usdc), 4) AS cost
FROM smart_wallet_trade_order tro
LEFT JOIN smart_wallet_market_info mi ON mi.id = tro.market_info_id
GROUP BY category
ORDER BY pnl ASC;

SELECT
  CASE
    WHEN entry_avg_price < 0.08 THEN '<0.08'
    WHEN entry_avg_price < 0.18 THEN '0.08-0.18'
    WHEN entry_avg_price < 0.40 THEN '0.18-0.40'
    WHEN entry_avg_price < 0.60 THEN '0.40-0.60'
    WHEN entry_avg_price < 0.78 THEN '0.60-0.78'
    ELSE '>=0.78'
  END AS price_bucket,
  COUNT(*) AS trades,
  SUM(status <> 'OPEN') AS closed_trades,
  SUM(status <> 'OPEN' AND total_pnl_usdc > 0) AS wins,
  SUM(status <> 'OPEN' AND total_pnl_usdc < 0) AS losses,
  ROUND(SUM(status <> 'OPEN' AND total_pnl_usdc > 0) / NULLIF(SUM(status <> 'OPEN'), 0), 4) AS win_rate,
  ROUND(SUM(total_pnl_usdc), 4) AS pnl,
  ROUND(SUM(entry_cost_usdc), 4) AS cost
FROM smart_wallet_trade_order
GROUP BY price_bucket
ORDER BY MIN(entry_avg_price);

-- 6. follow_task 候选信号与 SKIPPED 拦截原因
SELECT
  status,
  execution_mode,
  COUNT(*) AS cnt,
  ROUND(SUM(recommended_stake_usdc), 2) AS stake
FROM smart_wallet_follow_task
GROUP BY status, execution_mode
ORDER BY cnt DESC;

SELECT
  CASE
    WHEN error_message LIKE '%ARCHETYPE_BLOCK%' OR error_message LIKE '%OBSERVE_ONLY%' THEN '画像/行为拦截'
    WHEN error_message LIKE '%Signal is stale%' THEN '信号过期'
    WHEN error_message LIKE '%MARKET_RESOLVED%' THEN '市场已结算'
    WHEN error_message LIKE '%MARKET_CLOSED%' THEN '市场已关闭'
    WHEN error_message LIKE '%MARKET_NOT_ACCEPTING_ORDERS%' THEN '市场不接单'
    WHEN error_message LIKE '%Open position count%' THEN '持仓容量限制'
    WHEN error_message LIKE '%Trigger price is out%' THEN '价格越界'
    WHEN error_message LIKE '%Wallet is not qualified%' THEN '地址未达准入'
    ELSE '其他'
  END AS reason_category,
  COUNT(*) AS cnt,
  ROUND(SUM(recommended_stake_usdc), 2) AS stake
FROM smart_wallet_follow_task
WHERE status = 'SKIPPED'
GROUP BY reason_category
ORDER BY cnt DESC;

-- 7. 每个地址 Top 3 SKIPPED 原因
SELECT wallet_address, reason_category, cnt
FROM (
  SELECT
    wallet_address,
    CASE
      WHEN error_message LIKE '%ARCHETYPE_BLOCK%' OR error_message LIKE '%OBSERVE_ONLY%' THEN '画像/行为拦截'
      WHEN error_message LIKE '%Signal is stale%' THEN '信号过期'
      WHEN error_message LIKE '%MARKET_RESOLVED%' THEN '市场已结算'
      WHEN error_message LIKE '%MARKET_CLOSED%' THEN '市场已关闭'
      WHEN error_message LIKE '%MARKET_NOT_ACCEPTING_ORDERS%' THEN '市场不接单'
      WHEN error_message LIKE '%Open position count%' THEN '持仓容量限制'
      WHEN error_message LIKE '%Trigger price is out%' THEN '价格越界'
      WHEN error_message LIKE '%Wallet is not qualified%' THEN '地址未达准入'
      ELSE '其他'
    END AS reason_category,
    COUNT(*) AS cnt,
    ROW_NUMBER() OVER (PARTITION BY wallet_address ORDER BY COUNT(*) DESC) AS rn
  FROM smart_wallet_follow_task
  WHERE status = 'SKIPPED'
  GROUP BY wallet_address, reason_category
) x
WHERE rn <= 3
ORDER BY wallet_address, cnt DESC;

-- 8. 数据质量检查
SELECT 'position_closed_before_opened' AS check_name, COUNT(*) AS cnt
FROM smart_wallet_position
WHERE closed_at IS NOT NULL
  AND opened_at IS NOT NULL
  AND closed_at < opened_at
UNION ALL
SELECT 'position_cost_zero_trade_order_cost_nonzero', COUNT(*)
FROM smart_wallet_position p
JOIN smart_wallet_trade_order tro ON tro.position_id = p.id
WHERE p.cost_basis_usdc = 0
  AND tro.entry_cost_usdc <> 0
UNION ALL
SELECT 'trade_order_open_has_exit_reason', COUNT(*)
FROM smart_wallet_trade_order tro
JOIN smart_wallet_order o
  ON o.position_id = tro.position_id
 AND o.side = 'SELL'
WHERE tro.status = 'OPEN'
  AND o.exit_reason IS NOT NULL
UNION ALL
SELECT 'trade_order_claimed_no_settlement_time', COUNT(*)
FROM smart_wallet_trade_order
WHERE status = 'CLAIMED'
  AND settled_at IS NULL
UNION ALL
SELECT 'trade_order_negative_entry_cost', COUNT(*)
FROM smart_wallet_trade_order
WHERE entry_cost_usdc < 0;
