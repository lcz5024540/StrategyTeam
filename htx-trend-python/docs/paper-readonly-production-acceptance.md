# PAPER readonly / P4 future production acceptance

Checklist only. Execute only after a separate future deployment order. This
change does not authorize installation, hot updates, worker/unit/env changes,
nginx changes, database writes, H1 PAPER-DB installation, LiveExecutor or orders.

- [ ] Confirm separately approved API/frontend release and schema compatibility.
  API base is **http://43.139.186.27:8081**; gateway path may prefix
  `/strategy/api`. Trade/PAPER/LIVE workers are on **49.51.250.174**.
  The trade host is not the strategy-platform API or Dashboard static host.
- [ ] With an already-approved mirrored sample, GET
  `/strategy-platform/arbitrage-dashboard/htx-trend?mode=PAPER` on the API base
  (or the documented gateway route). Expect the sample's 6 FILLED orders,
  6 fill events, 3 DONE positions and zero active positions; compare by IDs if
  other paper history exists. All account labels must be paper and client keys
  must preserve paper-* strings. No sample insertion is authorized here.
- [ ] Verify terminal finalGrossProfit/finalTotalFee/finalNetProfit and
  finalPnlSource=PAPER_SIMULATED against the authoritative paper JSON/mirror
  reconciliation, including entry fees. Simulated PnL is not exchange PnL.
- [ ] Compare explicit mode=LIVE and omitted mode. Neither may include paper;
  primary/TradeX counts and PnL must match the pre-mirror LIVE baseline.
- [ ] Check the React HTX selector and static Dashboard PAPER/LIVE toggle.
  Both identify 模拟/PAPER, show orders/fills/positions and terminal fields,
  and issue only GET requests to the HTX projection. Switch twice to check cache
  separation. A failed API must show unavailable, not a healthy empty ledger.
- [ ] Verify the deployed route map remains GET-only; no paper place/cancel,
  risk-reservation or executor route is part of this feature.
- [ ] Retain acceptance evidence separately. Do not infer deployment from local
  test results; do not run the H2 fixture SQL on MySQL.

Local evidence: `HtxTrendPaperReadonlyTest`, `HtxTrendLiveScopeTest`,
`test_paper_db_persist.py`, `test_mysql_store_platform.py`, and
`coin-strategy-frontend/tests/paper-readonly.test.mjs`.
