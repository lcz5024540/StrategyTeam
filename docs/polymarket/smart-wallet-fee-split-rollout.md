# Smart Wallet Fee Split Rollout

## Goal

Roll out the new smart-wallet fee model with these rules:

- `fee_usdc` remains the display total
- `trading_fee_usdc` is the trade-coupled fee
- `gas_fee_usdc` is the USD display value of on-chain gas
- `fee_pol` records per-fill/per-order POL usage
- `fee_pol_total` records cumulative position POL usage

## Recommended Order

1. Deploy the schema changes first.
2. Run the historical fee split migration.
3. Restart the application.
4. Verify exports and UI fields in both `PAPER` and `LIVE`.

## SQL Execution

Run these scripts in this order:

1. [polymarket_smart_wallet_alter_upgrade.sql](/Users/lichangzheng.ray/Ray/CoinStrategy/sql/polymarket_smart_wallet_alter_upgrade.sql)
This upgrades legacy smart-wallet tables to the newer position-based accounting layout.

2. [smart_wallet_fee_split_migration.sql](/Users/lichangzheng.ray/Ray/CoinStrategy/sql/smart_wallet_fee_split_migration.sql)
This adds `trading_fee_usdc`, `gas_fee_usdc`, and backfills historical values conservatively.

## Backfill Rules

Historical records are migrated with these assumptions:

- Old `fee_usdc` is copied into `trading_fee_usdc`
- Old `gas_fee_usdc` is set to `0`
- `fee_pol_total` is rebuilt from `smart_wallet_fill.fee_pol`

This is intentionally conservative. It avoids inventing historical gas values that were never stored.

## Post-Deploy Checks

Check a few recent orders:

- `fee_usdc = trading_fee_usdc + gas_fee_usdc`
- `BUY` orders do not increase `cost_basis_usdc` by any fee
- `SELL` realized PnL only reflects `trading_fee_usdc`

Check a few positions:

- `cost_basis_usdc` matches principal only
- `fee_pol_total` is non-zero where fill history already had `fee_pol`
- `net_pnl_usdc` remains lower than gross PnL when `NETWORK_FEE` exists

Check exports:

- `/strategy/api/smart-wallet/orders/export`
- `/strategy/api/smart-wallet/fills/export`
- `/strategy/api/smart-wallet/positions/live/export`
- `/strategy/api/smart-wallet/positions/next-settlements/export`
- `/strategy/api/smart-wallet/positions/history/export`

## Rollback Notes

If the app must be rolled back before code is fixed:

- Keep the new columns in place
- Roll back only application code
- Do not drop the new fee columns during incident response

The added columns are backward-safe and can remain even if an older app build is temporarily restored.
