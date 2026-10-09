# HTX Python live safety validation — 2026-10-09

Scope: local implementation verification only. No production SQL, deployment, configuration change, or exchange mutation was performed.

| Check | Result |
|---|---|
| Python full suite | `99 passed in 0.13s` |
| Java reactor tests | `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q -pl coin-strategy-htx -am test` exited `0` |
| Python compile | `python -m compileall -q src tests` exited `0` |
| Draw.io parse | `HTX_TREND_PYTHON_LIVE_SAFETY_V1.drawio` parsed successfully |
| Public HTX read | `HtxV5Client().history_kline("BTC-USDT", "15min", 2)` returned 2 rows from `https://api.hbdm.com` |
| Diff hygiene | `git diff --check` exited `0` |

The local host has no usable MySQL server or Docker runtime. Real MySQL transaction semantics and two-process `GET_LOCK` competition remain a cutover acceptance item. Production frozen exit evidence is also unavailable, so Java/Python per-trade exit parity remains unapproved.

Evidence image: [htx-python-live-safety-validation-20261009.png](htx-python-live-safety-validation-20261009.png)
