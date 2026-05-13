-- Polymarket lifecycle trade order backfill.
--
-- Purpose:
--   Merge historical BUY/SELL execution orders into one lifecycle master row per
--   smart_wallet_position.id in smart_wallet_trade_order.
--
-- Safety:
--   - Idempotent: uses UNIQUE KEY uk_trade_order_position(position_id).
--   - Fill-first: smart_wallet_fill is treated as the execution fact source.
--   - Order fallback: if fills are missing for a side, FILLED smart_wallet_order rows
--     are used as fallback so older data is not migrated as zero quantity.
--   - Settlement is not represented as a virtual SELL order.
--
-- Run:
--   SOURCE sql/polymarket/smart_wallet_trade_order_migration.sql;
--   SOURCE sql/polymarket/smart_wallet_trade_order_backfill.sql;

START TRANSACTION;

INSERT INTO `smart_wallet_trade_order` (
  `position_id`,
  `market_info_id`,
  `execution_address`,
  `wallet_address`,
  `source_follow_task_id`,
  `entry_quantity`,
  `entry_avg_price`,
  `entry_cost_usdc`,
  `entry_fee_usdc`,
  `opened_at`,
  `exit_quantity`,
  `exit_avg_price`,
  `exit_proceeds_usdc`,
  `exit_realized_pnl_usdc`,
  `exit_fee_usdc`,
  `last_exit_at`,
  `remaining_quantity`,
  `remaining_cost_basis_usdc`,
  `last_mark_price`,
  `unrealized_pnl_usdc`,
  `settlement_price`,
  `settlement_proceeds_usdc`,
  `settlement_pnl_usdc`,
  `settled_at`,
  `total_realized_pnl_usdc`,
  `total_pnl_usdc`,
  `roi_rate`,
  `status`,
  `created_at`,
  `updated_at`
)
SELECT
  p.id AS position_id,
  p.market_info_id,
  p.execution_address,
  (
    SELECT o.wallet_address
    FROM smart_wallet_order o
    WHERE o.position_id = p.id
    ORDER BY COALESCE(o.placed_at, o.filled_at, o.created_at), o.id
    LIMIT 1
  ) AS wallet_address,
  (
    SELECT o.follow_task_id
    FROM smart_wallet_order o
    WHERE o.position_id = p.id
    ORDER BY COALESCE(o.placed_at, o.filled_at, o.created_at), o.id
    LIMIT 1
  ) AS source_follow_task_id,

  CASE WHEN COALESCE(f.entry_fill_count, 0) > 0 THEN COALESCE(f.entry_quantity, 0) ELSE COALESCE(o.entry_quantity, 0) END AS entry_quantity,
  CASE
    WHEN (CASE WHEN COALESCE(f.entry_fill_count, 0) > 0 THEN COALESCE(f.entry_quantity, 0) ELSE COALESCE(o.entry_quantity, 0) END) > 0
    THEN ROUND(
      (CASE WHEN COALESCE(f.entry_fill_count, 0) > 0 THEN COALESCE(f.entry_cost_usdc, 0) ELSE COALESCE(o.entry_cost_usdc, 0) END)
      / (CASE WHEN COALESCE(f.entry_fill_count, 0) > 0 THEN COALESCE(f.entry_quantity, 0) ELSE COALESCE(o.entry_quantity, 0) END),
      8
    )
    ELSE 0
  END AS entry_avg_price,
  CASE WHEN COALESCE(f.entry_fill_count, 0) > 0 THEN COALESCE(f.entry_cost_usdc, 0) ELSE COALESCE(o.entry_cost_usdc, 0) END AS entry_cost_usdc,
  CASE WHEN COALESCE(f.entry_fill_count, 0) > 0 THEN COALESCE(f.entry_fee_usdc, 0) ELSE COALESCE(o.entry_fee_usdc, 0) END AS entry_fee_usdc,
  COALESCE(p.opened_at, f.first_entry_at, o.first_entry_at, p.created_at) AS opened_at,

  CASE WHEN COALESCE(f.exit_fill_count, 0) > 0 THEN COALESCE(f.exit_quantity, 0) ELSE COALESCE(o.exit_quantity, 0) END AS exit_quantity,
  CASE
    WHEN (CASE WHEN COALESCE(f.exit_fill_count, 0) > 0 THEN COALESCE(f.exit_quantity, 0) ELSE COALESCE(o.exit_quantity, 0) END) > 0
    THEN ROUND(
      (CASE WHEN COALESCE(f.exit_fill_count, 0) > 0 THEN COALESCE(f.exit_proceeds_usdc, 0) ELSE COALESCE(o.exit_proceeds_usdc, 0) END)
      / (CASE WHEN COALESCE(f.exit_fill_count, 0) > 0 THEN COALESCE(f.exit_quantity, 0) ELSE COALESCE(o.exit_quantity, 0) END),
      8
    )
    ELSE 0
  END AS exit_avg_price,
  CASE WHEN COALESCE(f.exit_fill_count, 0) > 0 THEN COALESCE(f.exit_proceeds_usdc, 0) ELSE COALESCE(o.exit_proceeds_usdc, 0) END AS exit_proceeds_usdc,
  CASE WHEN COALESCE(f.exit_fill_count, 0) > 0 THEN COALESCE(f.exit_realized_pnl_usdc, 0) ELSE COALESCE(o.exit_realized_pnl_usdc, 0) END AS exit_realized_pnl_usdc,
  CASE WHEN COALESCE(f.exit_fill_count, 0) > 0 THEN COALESCE(f.exit_fee_usdc, 0) ELSE COALESCE(o.exit_fee_usdc, 0) END AS exit_fee_usdc,
  COALESCE(f.last_exit_at, o.last_exit_at) AS last_exit_at,

  COALESCE(p.quantity, 0) AS remaining_quantity,
  COALESCE(p.cost_basis_usdc, 0) AS remaining_cost_basis_usdc,
  COALESCE(p.last_mark_price, 0) AS last_mark_price,
  COALESCE(p.unrealized_pnl_usdc, 0) AS unrealized_pnl_usdc,
  CASE
    WHEN UPPER(COALESCE(p.status, '')) IN ('RESOLVED_UNCLAIMED', 'CLAIMED') THEN COALESCE(p.last_mark_price, 0)
    ELSE NULL
  END AS settlement_price,
  COALESCE(l.settlement_proceeds_usdc, 0) AS settlement_proceeds_usdc,
  CASE
    WHEN UPPER(COALESCE(p.status, '')) IN ('RESOLVED_UNCLAIMED', 'CLAIMED')
    THEN COALESCE(p.realized_pnl_usdc, 0)
      - (CASE WHEN COALESCE(f.exit_fill_count, 0) > 0 THEN COALESCE(f.exit_realized_pnl_usdc, 0) ELSE COALESCE(o.exit_realized_pnl_usdc, 0) END)
    ELSE 0
  END AS settlement_pnl_usdc,
  CASE
    WHEN UPPER(COALESCE(p.status, '')) IN ('RESOLVED_UNCLAIMED', 'CLAIMED') THEN p.closed_at
    ELSE NULL
  END AS settled_at,
  COALESCE(p.realized_pnl_usdc, 0) AS total_realized_pnl_usdc,
  COALESCE(p.realized_pnl_usdc, 0) + COALESCE(p.unrealized_pnl_usdc, 0) AS total_pnl_usdc,
  CASE
    WHEN COALESCE(NULLIF(
      CASE WHEN COALESCE(f.entry_fill_count, 0) > 0 THEN COALESCE(f.entry_cost_usdc, 0) ELSE COALESCE(o.entry_cost_usdc, 0) END,
      0
    ), COALESCE(NULLIF(p.original_cost_basis_usdc, 0), 0)) > 0
    THEN ROUND(
      (COALESCE(p.realized_pnl_usdc, 0) + COALESCE(p.unrealized_pnl_usdc, 0))
      / COALESCE(NULLIF(
          CASE WHEN COALESCE(f.entry_fill_count, 0) > 0 THEN COALESCE(f.entry_cost_usdc, 0) ELSE COALESCE(o.entry_cost_usdc, 0) END,
          0
        ), p.original_cost_basis_usdc),
      8
    )
    ELSE 0
  END AS roi_rate,
  CASE
    WHEN UPPER(COALESCE(p.status, '')) = 'CLOSED' THEN 'EXITED'
    WHEN UPPER(COALESCE(p.status, '')) = 'OPEN'
      AND (CASE WHEN COALESCE(f.exit_fill_count, 0) > 0 THEN COALESCE(f.exit_quantity, 0) ELSE COALESCE(o.exit_quantity, 0) END) > 0
      THEN 'PARTIALLY_EXITED'
    WHEN COALESCE(p.status, '') = '' THEN 'OPEN'
    ELSE UPPER(p.status)
  END AS status,
  COALESCE(p.created_at, NOW()) AS created_at,
  NOW() AS updated_at
FROM smart_wallet_position p
LEFT JOIN (
  SELECT
    position_id,
    SUM(CASE WHEN UPPER(COALESCE(side, '')) <> 'SELL' THEN 1 ELSE 0 END) AS entry_fill_count,
    SUM(CASE WHEN UPPER(COALESCE(side, '')) <> 'SELL' THEN COALESCE(size, 0) ELSE 0 END) AS entry_quantity,
    SUM(CASE WHEN UPPER(COALESCE(side, '')) <> 'SELL' THEN COALESCE(notional_usdc, 0) ELSE 0 END) AS entry_cost_usdc,
    SUM(CASE WHEN UPPER(COALESCE(side, '')) <> 'SELL' THEN COALESCE(fee_usdc, 0) ELSE 0 END) AS entry_fee_usdc,
    MIN(CASE WHEN UPPER(COALESCE(side, '')) <> 'SELL' THEN filled_at ELSE NULL END) AS first_entry_at,
    SUM(CASE WHEN UPPER(COALESCE(side, '')) = 'SELL' THEN 1 ELSE 0 END) AS exit_fill_count,
    SUM(CASE WHEN UPPER(COALESCE(side, '')) = 'SELL' THEN COALESCE(size, 0) ELSE 0 END) AS exit_quantity,
    SUM(CASE WHEN UPPER(COALESCE(side, '')) = 'SELL' THEN COALESCE(notional_usdc, 0) ELSE 0 END) AS exit_proceeds_usdc,
    SUM(CASE WHEN UPPER(COALESCE(side, '')) = 'SELL' THEN COALESCE(realized_pnl_usdc, 0) ELSE 0 END) AS exit_realized_pnl_usdc,
    SUM(CASE WHEN UPPER(COALESCE(side, '')) = 'SELL' THEN COALESCE(fee_usdc, 0) ELSE 0 END) AS exit_fee_usdc,
    MAX(CASE WHEN UPPER(COALESCE(side, '')) = 'SELL' THEN filled_at ELSE NULL END) AS last_exit_at
  FROM smart_wallet_fill
  WHERE position_id IS NOT NULL
  GROUP BY position_id
) f ON f.position_id = p.id
LEFT JOIN (
  SELECT
    position_id,
    SUM(CASE WHEN NOT (UPPER(COALESCE(side, '')) = 'SELL' OR UPPER(COALESCE(order_type, '')) = 'EXIT') THEN 1 ELSE 0 END) AS entry_order_count,
    SUM(CASE WHEN NOT (UPPER(COALESCE(side, '')) = 'SELL' OR UPPER(COALESCE(order_type, '')) = 'EXIT') THEN COALESCE(size, 0) ELSE 0 END) AS entry_quantity,
    SUM(CASE WHEN NOT (UPPER(COALESCE(side, '')) = 'SELL' OR UPPER(COALESCE(order_type, '')) = 'EXIT') THEN COALESCE(notional_usdc, 0) ELSE 0 END) AS entry_cost_usdc,
    SUM(CASE WHEN NOT (UPPER(COALESCE(side, '')) = 'SELL' OR UPPER(COALESCE(order_type, '')) = 'EXIT') THEN COALESCE(fee_usdc, 0) ELSE 0 END) AS entry_fee_usdc,
    MIN(CASE WHEN NOT (UPPER(COALESCE(side, '')) = 'SELL' OR UPPER(COALESCE(order_type, '')) = 'EXIT') THEN COALESCE(filled_at, placed_at, created_at) ELSE NULL END) AS first_entry_at,
    SUM(CASE WHEN UPPER(COALESCE(side, '')) = 'SELL' OR UPPER(COALESCE(order_type, '')) = 'EXIT' THEN 1 ELSE 0 END) AS exit_order_count,
    SUM(CASE WHEN UPPER(COALESCE(side, '')) = 'SELL' OR UPPER(COALESCE(order_type, '')) = 'EXIT' THEN COALESCE(size, 0) ELSE 0 END) AS exit_quantity,
    SUM(CASE WHEN UPPER(COALESCE(side, '')) = 'SELL' OR UPPER(COALESCE(order_type, '')) = 'EXIT' THEN COALESCE(notional_usdc, 0) ELSE 0 END) AS exit_proceeds_usdc,
    SUM(CASE WHEN UPPER(COALESCE(side, '')) = 'SELL' OR UPPER(COALESCE(order_type, '')) = 'EXIT' THEN COALESCE(realized_pnl_usdc, 0) ELSE 0 END) AS exit_realized_pnl_usdc,
    SUM(CASE WHEN UPPER(COALESCE(side, '')) = 'SELL' OR UPPER(COALESCE(order_type, '')) = 'EXIT' THEN COALESCE(fee_usdc, 0) ELSE 0 END) AS exit_fee_usdc,
    MAX(CASE WHEN UPPER(COALESCE(side, '')) = 'SELL' OR UPPER(COALESCE(order_type, '')) = 'EXIT' THEN COALESCE(filled_at, placed_at, created_at) ELSE NULL END) AS last_exit_at
  FROM smart_wallet_order
  WHERE position_id IS NOT NULL
    AND UPPER(COALESCE(status, '')) = 'FILLED'
  GROUP BY position_id
) o ON o.position_id = p.id
LEFT JOIN (
  SELECT
    reference_id AS position_id,
    SUM(CASE WHEN UPPER(COALESCE(entry_type, '')) = 'SETTLEMENT_PROCEEDS' THEN COALESCE(amount, 0) ELSE 0 END) AS settlement_proceeds_usdc
  FROM smart_wallet_cash_ledger
  WHERE reference_type = 'POSITION'
    AND reference_id IS NOT NULL
  GROUP BY reference_id
) l ON l.position_id = p.id
ON DUPLICATE KEY UPDATE
  `market_info_id` = VALUES(`market_info_id`),
  `execution_address` = VALUES(`execution_address`),
  `wallet_address` = VALUES(`wallet_address`),
  `source_follow_task_id` = VALUES(`source_follow_task_id`),
  `entry_quantity` = VALUES(`entry_quantity`),
  `entry_avg_price` = VALUES(`entry_avg_price`),
  `entry_cost_usdc` = VALUES(`entry_cost_usdc`),
  `entry_fee_usdc` = VALUES(`entry_fee_usdc`),
  `opened_at` = VALUES(`opened_at`),
  `exit_quantity` = VALUES(`exit_quantity`),
  `exit_avg_price` = VALUES(`exit_avg_price`),
  `exit_proceeds_usdc` = VALUES(`exit_proceeds_usdc`),
  `exit_realized_pnl_usdc` = VALUES(`exit_realized_pnl_usdc`),
  `exit_fee_usdc` = VALUES(`exit_fee_usdc`),
  `last_exit_at` = VALUES(`last_exit_at`),
  `remaining_quantity` = VALUES(`remaining_quantity`),
  `remaining_cost_basis_usdc` = VALUES(`remaining_cost_basis_usdc`),
  `last_mark_price` = VALUES(`last_mark_price`),
  `unrealized_pnl_usdc` = VALUES(`unrealized_pnl_usdc`),
  `settlement_price` = VALUES(`settlement_price`),
  `settlement_proceeds_usdc` = VALUES(`settlement_proceeds_usdc`),
  `settlement_pnl_usdc` = VALUES(`settlement_pnl_usdc`),
  `settled_at` = VALUES(`settled_at`),
  `total_realized_pnl_usdc` = VALUES(`total_realized_pnl_usdc`),
  `total_pnl_usdc` = VALUES(`total_pnl_usdc`),
  `roi_rate` = VALUES(`roi_rate`),
  `status` = VALUES(`status`),
  `updated_at` = NOW();

COMMIT;

-- Validation summary.
SELECT
  'positions' AS metric,
  COUNT(*) AS value
FROM smart_wallet_position
UNION ALL
SELECT
  'trade_orders' AS metric,
  COUNT(*) AS value
FROM smart_wallet_trade_order
UNION ALL
SELECT
  'filled_orders_without_position' AS metric,
  COUNT(*) AS value
FROM smart_wallet_order
WHERE UPPER(COALESCE(status, '')) = 'FILLED'
  AND position_id IS NULL;

-- Spot-check rows with entry/exit merged into one lifecycle master row.
SELECT
  t.id,
  t.position_id,
  t.entry_quantity,
  t.entry_cost_usdc,
  t.exit_quantity,
  t.exit_proceeds_usdc,
  t.remaining_quantity,
  t.settlement_proceeds_usdc,
  t.total_pnl_usdc,
  t.status
FROM smart_wallet_trade_order t
ORDER BY t.id DESC
LIMIT 20;
