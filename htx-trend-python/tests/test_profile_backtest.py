from decimal import Decimal
from dataclasses import replace

import pytest

from htx_trend.market_data import Candle
from htx_trend.research.profile_backtest import (ProfileLifecycleResearch, ResearchVariant,
                                                 provisional_backtest_admission)
from htx_trend.research.store import FileEvidenceStore
from htx_trend.strategy_admission import DecisionSurface
from htx_trend.strategy_profiles import profile_strategy
from htx_trend.timeframes import TimeframeProfile


def _candles(step: int, count: int, *, offset: int = 0):
    rows = []
    for i in range(count):
        # Alternating trend blocks exercise long, short and flat decisions.
        block, within = divmod(i, 45)
        center = Decimal("100") + Decimal(block * 3) + Decimal(within if block % 2 == 0 else 45-within) / 10
        rows.append(Candle(offset+i*step, center, center+Decimal("1.2"), center-Decimal("1.1"),
                           center+Decimal("0.2"), Decimal("100"), Decimal("10000"),
                           Decimal("1000000"), 10))
    return tuple(rows)


def _manifest(store: FileEvidenceStore, count: int = 180):
    pages = []
    for symbol in ("BTC-USDT", "ETH-USDT"):
        for period, step in (("15min", 900), ("4hour", 14400)):
            values = _candles(step, count)
            page = store.put("kline_page", {"symbol": symbol, "period": period,
                "integrity": "COMPLETE", "candles": [{"open_time": x.open_time, "bar": x} for x in values]})
            pages.append(page)
    return store.put("dataset_manifest", {"integrity": "COMPLETE", "pages": pages}, refs=tuple(pages))


def test_provisional_admission_is_backtest_only() -> None:
    spec = profile_strategy(TimeframeProfile.INTRADAY_15M, read_env=False)
    admission = provisional_backtest_admission(spec, "a"*64)
    admission.require(DecisionSurface.BACKTEST)
    assert admission.allowed_surfaces == (DecisionSurface.BACKTEST,)


def test_profile_research_is_content_addressed_and_reproducible(tmp_path) -> None:
    with FileEvidenceStore(tmp_path) as store:
        manifest = _manifest(store)
        runner = ProfileLifecycleResearch(store, manifest, TimeframeProfile.INTRADAY_15M)
        variant = (ResearchVariant("BASELINE"),)
        first = runner.run(variants=variant, seed=7)
        second = runner.run(variants=variant, seed=7)
        assert first == second
        payload = store.get(first)["payload"]
        assert payload["result_kind"] == "STRATEGY_SIMULATION"
        assert payload["execution_assumptions"]["intrabar"] == "stop before target"
        assert {row["split"] for row in payload["results"]} == {"TRAIN", "VALIDATION", "TEST"}
        with pytest.raises(ValueError, match="already consumed"):
            runner.run(variants=(replace(variant[0], minimum_score=Decimal("71")),), seed=7)


def test_future_candle_does_not_change_prior_decision(tmp_path) -> None:
    with FileEvidenceStore(tmp_path) as store:
        manifest = _manifest(store, 180)
        runner = ProfileLifecycleResearch(store, manifest, TimeframeProfile.INTRADAY_15M)
        base = _candles(900, 180)
        before = runner._decisions("BTC-USDT", base)[-1]
        extended = base + (Candle(180*900, Decimal("999"), Decimal("1000"), Decimal("998"),
                                  Decimal("999"), volume=Decimal("10000")),)
        after = runner._decisions("BTC-USDT", extended)[179]
        assert before is not None and after is not None
        assert before.decision_hash == after.decision_hash
