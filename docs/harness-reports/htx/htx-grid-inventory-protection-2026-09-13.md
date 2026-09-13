# HTX Moving Grid Inventory Protection — 2026-09-13

## Scope

This change addresses the loss pattern where a moving grid window repriced legacy filled inventory
below its fee-inclusive break-even price. It is a local code change only: it does not alter any
server configuration, exchange order, position, or deployment.

## Runtime rules

1. A window-derived sell price may raise a sell price, but the effective sell price is always the
   maximum of that price and the fee-inclusive price calculated from the actual buy fill.
2. `HEDGE_DEGRADED` or `UNKNOWN` rows in either durable hedge or recycle recovery queue fail closed
   for new grid buys. Existing cells still run their sell and reconciliation paths.
3. `htx.market-maker.grid-max-new-cells-per-tick` defaults to `2`, limiting a single polling cycle's
   new buy submissions while preserving near-market maker quotes across subsequent cycles.

## Configuration and rollout

The new `htx.market-maker.require-healthy-hedge-for-new-buys` default is `true`. Before any future
deployment, inspect the durable hedge/recycle recovery queues and resolve genuine degraded or unknown
records; otherwise the service will intentionally pause new spot inventory. Do not use this gate to
justify cancelling existing sells or flattening inventory.

## Verification

- `MarketMakerServiceGridTest` covers legacy inventory protection, per-tick opening limit, and the
  degraded-hedge new-risk pause.
- `mvn -q -pl coin-strategy-htx -am test` passes from the CoinStrategy worktree.
