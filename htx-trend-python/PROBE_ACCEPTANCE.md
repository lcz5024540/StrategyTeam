# 0.1.1-probe acceptance checklist

Package: `htx-trend-python-0.1.1-probe-<commit>.tar.gz`  
Formal v0.1.0 tarball SHA256 stays `f0e7b49c646e03d3d5af4a61fbb566e3cb7c143dc9ae8a45282ef3c92d267f30` (not rebuilt).

Locked credential spec: `docs/primary-apikey-read-method.md` sha256 `220ab16670c4c75a0bea14df78ab468caa0cdb0e7ccf36acd81d89edfe022480`.

This probe is **not** the formal live strategy. R0001 stays max 5 positions, 2% per trade, 10% total risk, same-direction cap 3, signal-driven. Probe is one-shot connectivity only.

## Gates

- [ ] `LIVE_1CONTRACT_PROBE` is exactly `1` or `true` before `open`/`close`. Any other value refuses with `LIVE_PROBE_DISABLED`.
- [ ] CLI: `python -m htx_trend.probe_1contract open|close|status`
- [ ] Defaults: symbol `BTC-USDT`, buy open / sell close, volume `1` hard-capped.
- [ ] Allowed symbols: `BTC-USDT`, `ETH-USDT` only.
- [ ] Qty > 1 or position would exceed 1 → `PROBE_QTY_LIMIT`; no HTTP place.
- [ ] Close failure writes evidence and stops. No reverse/add order.
- [ ] systemd: `htx-trend-probe-1contract@.service` (oneshot). Enable instance `open` / `close` / `status`. Do **not** put `LIVE_1CONTRACT_PROBE=1` in the unit; set it in `$STATE/probe.env`.
- [ ] Formal `HtxV5Client.place_order` and `PositionSizer` (5 / 2% / 10%) unchanged.

## Credentials (sole API)

- [ ] SQL: `SELECT id, account_label, exchange, market_type, base_url, api_key_cipher, secret_key_cipher, account_role FROM htx_trade_account WHERE account_role = 'PRIMARY' AND enabled = 1 AND trend_enabled = 1` (optional `AND id=%s` if `HTX_PROBE_ACCOUNT_ID` is set). No `SELECT *`. No `LIMIT` hiding extra rows.
- [ ] 0 rows → `PRIMARY_ACCOUNT_NONE`. >1 row → `PRIMARY_ACCOUNT_NOT_UNIQUE`. `account_role ≠ PRIMARY` → `PRIMARY_ACCOUNT_ROLE` **before** decrypt.
- [ ] Cipher must be `ENC(<base64>)`. Plaintext → `PROBE_SECRET_NOT_ENC`.
- [ ] RSA-OAEP SHA-256 / MGF1-SHA-256, PKCS8 PEM at `APP_RSA_PRIVATE_KEY_PATH` (default `/opt/coinstrategy/app-private.pem`). Decrypt fail → `PROBE_DECRYPT_FAILED`.
- [ ] Unit env may contain **read-only DB connect** + `APP_RSA_PRIVATE_KEY_PATH` only.
- [ ] Forbidden: `HTX_ACCESS_KEY`, `HTX_SECRET_KEY`, `HTX_API_KEY`, `HTX_TREND_MIRROR_*`. Never read MIRROR rows. Never call `enabled_trend_accounts` from the probe.

## Evidence JSON

`order_id`, `fills`, `position_before` / `position_after`, `balance_before` / `balance_after`, `ok`, `reason_code` on failure.

## Tests

```bash
PYTHONPATH=src python -m pytest tests/test_probe_credentials.py tests/test_probe_1contract.py tests/research/test_systemd_units.py -q
```

Must not hit the exchange (httpx mock / injected client).
