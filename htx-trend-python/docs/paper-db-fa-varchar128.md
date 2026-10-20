# PAPER-DB F-A v1 — VARCHAR(128)

Encoding-phase candidate only. approval=SYNTHETIC. 候选包 ≠ 可以热更 ≠ 可装现网。

- FA1: widen only `htx_trend_order.client_order_id` and
  `coin_strategy_ledger_event.client_order_id` to VARCHAR(128), retaining order
  UNIQUE `uk_trend_order_client`. Canonical schema and checked-in migration agree.
  Migration: `sql/2026-10-19__paper_db_fa_client_order_id_varchar128.sql`.
  禁现网执行直至热更口令 / DO NOT RUN ON PROD until separate hot-update order
  「可以再热更 PAPER」. No migration executed against MySQL.
- FA2: preflight every client ID before any replay writes: non-empty string,
  at most 128 characters; raise ValueError otherwise. Never truncate.
- FA3: SQLite replay tests cover short IDs and explicit 65/93/128-character IDs,
  six fills / three closed positions, ledger/order identity preservation,
  authoritative JSON unchanged and idempotency. 129 characters, empty strings,
  null and non-string values fail before any write.
- FA4: build `0.1.6-paper-db-<HEAD8>` only after commit, with SHA256 sidecar;
  verifier retains 0.1.2/0.1.3/0.1.4/0.1.5 support. Approval template is SYNTHETIC.
- FA5: compared with baseline `273bf19b9896c18cc73c2713331ba41563bfa5cb`
  (0.1.5), runtime changes are only the DB guard and package version/allowlist;
  other changes are the two schema widths, migration, tests and this note.
  `git diff 273bf19b -- htx-trend-python/src/htx_trend/paper.py
  htx-trend-python/src/htx_trend/paper_worker.py` is empty: submit/fill sequence
  and generated IDs retain baseline behavior. All DB replay tests forbid LIVE
  executor construction via an autouse monkeypatch fixture.
- FA6: final commit, package digest, verification and push result are recorded in
  `/Users/jenniferni/jenifer/build/codex-logs/paper-db-fa-20261019/handoff.md`.

Verification (Python 3.11 test environment):

```sh
python -m pytest tests/test_paper_db_persist.py tests/test_g3_release.py tests/test_paper_worker.py -q --tb=short
python -m release.package build --paper --out-root /Users/jenniferni/jenifer/build/paper-frozen
```

No production ALTER/DML, hot update, candidate installation, state ID rewrite,
`paper_clock` change, LIVE R0 change, patrol restore, real admission seal,
Dashboard nginx/listener change or merge. Other feature branches are excluded.
