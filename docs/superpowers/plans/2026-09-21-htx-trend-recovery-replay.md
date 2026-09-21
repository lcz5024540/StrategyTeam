# HTX Trend Recovery Replay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a deterministic, net-of-fee replay for BTC-USDT, ETH-USDT, and XAUT-USDT from 2026-09-18 through 2026-09-21, using the deployed recovery rules, and either nominate or reject initial live sleeves with evidence.

**Architecture:** Persist raw public HTX K-lines before calculation, then run an offline Python replay profile that matches the recovery version: no adverse-shock/no-extension exit, 190U cap, 1.5U risk budget, current direction gate, trailing/flat-loss exits, and double-sided taker fees. The output is per symbol and entry model, not a shared 52-symbol aggregate, so a sleeve can be accepted only from its own net result and drawdown.

**Tech Stack:** Python 3 standard library, HTX public linear-swap kline endpoint, CSV/JSON/Markdown artifacts.

---

### Task 1: Freeze reproducible public inputs

**Files:**
- Create: `htx-trend/backtest-data/20260918-21/{BTC-USDT,ETH-USDT,XAUT-USDT}_{15min,30min,60min,4hour}.json`
- Modify: `htx-trend/tools/htx_trend_backtest.py`

- [x] **Step 1: Freeze public HTX K-line responses with an explicit capture boundary**

```python
def fetch_kline_series(contract_code: str, period: str, size: int) -> dict:
    url = ("https://api.hbdm.com/linear-swap-ex/market/history/kline?"
           + urlencode({"contract_code": contract_code, "period": period, "size": size}))
    with urlopen(url, timeout=20) as response:
        payload = json.load(response)
    if payload.get("status") != "ok" or not payload.get("data"):
        raise RuntimeError(f"HTX kline fetch failed: {contract_code} {period}")
    return payload
```

- [x] **Step 2: Capture four timeframes for each candidate symbol and reject insufficient coverage**

```python
required_start = int(parse_day("2026-09-17").timestamp())
required_end = int(parse_day("2026-09-22").timestamp())
bars = load_bars(data_dir, symbol, period)
assert min(bar.ts for bar in bars) <= required_start
assert max(bar.ts for bar in bars) >= required_end - period_seconds(period)
```

- [x] **Step 3: Verify the frozen data is parseable and covers the available requested window**

Run: `python3 htx-trend/tools/htx_trend_backtest.py --validate-data --data-dir htx-trend/backtest-data/20260918-21 --symbols BTC-USDT,ETH-USDT,XAUT-USDT --start 2026-09-18 --end 2026-09-22`

Expected: one coverage line per symbol/timeframe and exit code 0.

### Task 2: Make the replay match the deployed recovery strategy

**Files:**
- Modify: `htx-trend/tools/htx_trend_backtest.py`
- Create: `htx-trend/tools/test_htx_trend_backtest.py`

- [x] **Step 1: Add focused tests for the recovery exit profile and HTX response parsing**

```python
def test_recovery_profile_does_not_emit_adverse_shock_or_no_extension_exit():
    profile = recovery_profile()
    reasons = replay_fixture(profile).exit_reasons()
    assert "adverse-shock" not in reasons
    assert "no-extension" not in reasons
```

- [x] **Step 2: Add an immutable `RecoveryProfile` and use it in `run_backtest`**

```python
@dataclass(frozen=True)
class ReplayProfile:
    name: str
    direction_gate_enabled: bool
    adverse_shock_exit_enabled: bool
    no_extension_exit_enabled: bool
    target_quote_notional: float
    max_quote_notional: float
    risk_per_trade_usdt: float

RECOVERY_PROFILE = ReplayProfile(
    name="recovery_20260921",
    direction_gate_enabled=True,
    adverse_shock_exit_enabled=False,
    no_extension_exit_enabled=False,
    target_quote_notional=190.0,
    max_quote_notional=190.0,
    risk_per_trade_usdt=1.5,
)
```

- [x] **Step 3: Gate old early exits on the profile booleans and preserve flat-loss/trailing exits**

```python
if profile.adverse_shock_exit_enabled:
    exit_reason = adverse_shock_exit(...)
if exit_reason is None and profile.no_extension_exit_enabled:
    exit_reason = no_extension_exit(...)
```

- [x] **Step 4: Run the focused tests**

Run: `python3 -m unittest htx-trend/tools/test_htx_trend_backtest.py`

Expected: PASS; no recovery trade exits through the two removed rules.

### Task 3: Produce sleeve-level evidence

**Files:**
- Modify: `htx-trend/tools/htx_trend_backtest.py`
- Create: `htx-trend/backtest-runs/20260918-21-recovery/summary.json`
- Create: `htx-trend/backtest-runs/20260918-21-recovery/summary.md`
- Create: `htx-trend/backtest-runs/20260918-21-recovery/trades.csv`

- [x] **Step 1: Group closed trades by `symbol + entry_model` and calculate net metrics**

```python
def sleeve_summary(trades: list[Trade]) -> dict[str, dict[str, float]]:
    grouped: dict[str, list[Trade]] = {}
    for trade in trades:
        grouped.setdefault(f"{trade.symbol}:{trade.entry_model}", []).append(trade)
    return {key: summarize(rows) for key, rows in grouped.items()}
```

- [x] **Step 2: Apply fixed sleeve acceptance criteria without using gross PnL**

```python
def classify_sleeve(metrics: dict[str, float]) -> str:
    if metrics["trades"] < 3:
        return "INSUFFICIENT_SAMPLE"
    if metrics["net_pnl"] <= 0 or metrics["profit_factor"] < 1.20:
        return "REJECT"
    if metrics["max_loss"] < -1.5:
        return "REJECT"
    return "PAPER_CANDIDATE"
```

- [x] **Step 3: Run the recovery profile across the frozen available window**

Run: `python3 htx-trend/tools/htx_trend_backtest.py --profile recovery_20260921 --data-dir htx-trend/backtest-data/20260918-21 --out-dir htx-trend/backtest-runs/20260918-21-recovery --symbols BTC-USDT,ETH-USDT,XAUT-USDT --start 2026-09-18 --end 2026-09-22`

Expected: CSV, JSON, and Markdown outputs contain only the three candidate symbols and never claim exchange-execution precision.

### Task 4: Verify and document the decision boundary

**Files:**
- Create: `htx-trend/HTX_TREND_20260918_21_RECOVERY_REPLAY.md`

- [x] **Step 1: Document source, profile, assumptions, and each accepted/rejected sleeve**

```markdown
| Sleeve | Trades | Net PnL | PF | Max loss | Decision |
|---|---:|---:|---:|---:|---|
```

- [x] **Step 2: State the limits of the replay**

```markdown
The replay includes two-sided taker fees and next-bar-open fills. It does not prove historical L2 depth,
partial fills, exchange latency, funding-carry promotion, or liquidation margin state.
```

- [x] **Step 3: Run final verification; artifacts are ready for a separate requested commit**

Run: `python3 -m unittest htx-trend/tools/test_htx_trend_backtest.py && git diff --check`

Expected: PASS, then commit only the runner, tests, frozen public inputs, and generated recovery artifacts.

## Self-review

- Coverage: Tasks 1–4 cover data provenance, recovery parity, net-of-fee sleeve metrics, and no-live-expansion decision evidence.
- No placeholders: all profile values, input paths, commands, classification conditions, and output paths are specified.
- Scope boundary: this plan does not add a live sleeve, alter Fibonacci thresholds, or add a new exit model.
