# Spot-long / perp-short funding harvest (dry-run)

Locked universe on this branch: **Binance + HTX**, **BTC + ETH** only, **paper fills only**.

The strategy buys spot and shorts the same-venue USDT perpetual, then holds to collect **funding fees**. Basis is a risk gate, not a PnL source. There is no live order path; setting `dry-run=false` fails closed.

## What runs

| Path | What it does | Live orders |
| --- | --- | --- |
| `scripts/spot-perp-dry-run.sh once` | One public REST cycle through the production engine | No |
| `scripts/spot-perp-dry-run.sh backtest` | 90-day public funding replay | No |
| Job `FundingArbSpotPerpDryRunJob` under Spring `funding` profile | Polls public books every 30s, paper state in memory | No |

Do **not** start this as a live trading job. Do **not** set Binance keys, HTX keys, or `BINANCE_LIVE`. The older `arbitrage.spot-futures.order-execution-mode` switch is a different strategy and is left untouched.

## Commands (no Spring / no DB / no RSA)

From the repo root, JDK 17:

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
chmod +x scripts/spot-perp-dry-run.sh
./scripts/spot-perp-dry-run.sh once
./scripts/spot-perp-dry-run.sh backtest
```

Equivalent Maven (the CLI `exec:java` goal must run on the core module pom, not the reactor parent):

```bash
mvn -pl coin-strategy-core -am -DskipTests install
mvn -f coin-strategy-core/pom.xml exec:java -Dexec.args=once
mvn -f coin-strategy-core/pom.xml exec:java -Dexec.args=backtest
```

Unit tests (quote the test pattern in zsh):

```bash
mvn -pl coin-strategy-core,coin-strategy-job -am test \
  -Dtest='SpotPerp*,FundingArbSpotPerp*,FundingOnlyComponentExcludeFilterTest'
```

## Config (funding profile)

`coin-strategy-job/src/main/resources/application-funding.yml`:

```yaml
arbitrage:
  spot-perp-funding:
    enabled: true
    dry-run: true          # required; false is rejected
    auto-open-enabled: true
    notional-usdt: 100
    leverage: 2
```

FULL mode (`application.yml`) keeps `enabled: false` so the existing job process does not start extra public polling. The dry-run **switch** itself defaults to `true`.

If you do start the funding-only job later (not required here):

```bash
# Do not run this unless you intentionally want the 30s paper scanner.
# It still places no live orders, but it does need the job's usual DB profile.
java -jar coin-strategy-job/target/coin-strategy-job-*.jar --spring.profiles.active=test,funding
```

Paper positions are in-memory only. Restarting the JVM flattens the book.

## Designed gates

- **Open**: last funding > `0.00008`, projected funding over 30 periods covers venue round-trip taker fees + VWAP impact, executable basis inside `[-5, 20]` bps, 10-minute settlement protection, quote age ≤ 15s, each book ≥ 500 USDT inside 5 bps and 2× coverage of 100 USDT.
- **Hedge**: paper **MARKET IOC**. Spot buy (sweep asks) first, then perp sell (sweep bids) for the filled quantity. Perp failure unwinds spot.
- **Close**: funding ≤ 0 or basis too wide (emergency, ignores min hold); funding < `0.00002` after 8h; or 21-day TTL.
- **PnL**: short perpetual funding only (`qty * mark * rate`). Fees are reported separately.
- **Leverage**: 2x on the perp short (spot is unlevered). 100 USDT is the spot target notional; one-lot overshoot up to 1.25× is allowed so BTC can still size when `0.001` is the futures step.

## Backtest honesty

Public history is funding prints, not L2. Replay uses a synthetic 1,000,000 USDT book at mark, so BTC/ETH 100 USDT depth gates are assumed to pass. HTX history has no mark price (only `funding_rate` + `avg_premium_index`); `api.hbdm.com` timed out from this network, so history was fetched from `https://api.hbdm.vn`. HTX spot L2 hosts (`api.huobi.pro`, `api.htx.com`) were unreachable or presented invalid TLS, so live dry-run proxies HTX spot from the USDT-M index.

Ran 2026-09-21 against public REST, 90 days `2026-06-23T14:00Z → 2026-09-21T14:00Z`, 270 prints/series (complete 8h calendar), 1080 prints total, 100 USDT, 2x, production fee gates (Binance 28 bps / HTX 48 bps round-trip):

| Instrument | Opens | Closes | Funding events | Funding PnL | Est. fees | Net |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| BINANCE:BTCUSDT | 7 | 6 | 91 | +0.69 | 3.68 | **-2.99** |
| BINANCE:ETHUSDT | 7 | 6 | 43 | +0.32 | 3.69 | **-3.37** |
| HTX:BTCUSDT | 0 | 0 | 0 | 0 | 0 | 0 |
| HTX:ETHUSDT | 0 | 0 | 0 | 0 | 0 | 0 |

HTX never opened: 48 bps assumed taker round-trip needs ~16 bp per 8h for 30 periods, and most prints were `FUNDING_BELOW_OPEN_THRESHOLD` / `EXPECTED_NET_YIELD_TOO_LOW` (plus some `BASIS_BELOW_MIN`). Binance funding harvested was real but smaller than retail round-trip fees on 100 USDT — that is the honest result, not a bug in the state machine.

Live one-cycle dry-run on the same day: 4/4 snapshots, paper **opened BINANCE:ETHUSDT** (funding 0.0001), skipped the other three on yield/threshold gates. No live orders.

## Live dry-run one-cycle (2026-09-21)

```
dry-run=true notional=100 USDT leverage=2x
BINANCE:BTCUSDT funding=0.00008504 SKIPPED EXPECTED_NET_YIELD_TOO_LOW
BINANCE:ETHUSDT funding=0.00010000 OPENED qty=0.036
HTX:BTCUSDT     funding=0.00004308 SKIPPED FUNDING_BELOW_OPEN_THRESHOLD
HTX:ETHUSDT     funding=0.00010000 SKIPPED EXPECTED_NET_YIELD_TOO_LOW
```

