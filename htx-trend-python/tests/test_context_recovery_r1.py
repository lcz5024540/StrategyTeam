"""R1-α：部署预检、健康分层、有仓守护。121 锁定，不改池。"""
from __future__ import annotations

import json
import os
from decimal import Decimal
from pathlib import Path

import pytest

from htx_trend.context_recovery import (
    REQUIRED_BARS_1D,
    assemble_preflight,
    classify_degraded,
    closed_bars_from_public_client,
    contracts_from_env,
    count_closed_daily_bars,
    evaluate_position_guard,
    load_closed_bars,
    load_expected_thin,
    load_open_symbols,
)
from htx_trend.market_data import Candle
from htx_trend.tools.context_recovery_r1 import main

FOUR = ("PONS-USDT", "BOME-USDT", "RAY-USDT", "ACE-USDT")


def _bars(mapping: dict[str, int]) -> dict[str, int]:
    return dict(mapping)


def test_required_bars_1d_is_locked_at_121() -> None:
    assert REQUIRED_BARS_1D == 121
    assert "required_bars" not in assemble_preflight.__code__.co_varnames


def test_p1_all_closed_at_least_121_is_pass_without_thin() -> None:
    report = assemble_preflight(
        ("BTC-USDT", "ETH-USDT", "PONS-USDT"),
        _bars({"BTC-USDT": 200, "ETH-USDT": 121, "PONS-USDT": 400}),
        expected_thin=FOUR,
    )
    assert report["preflight_verdict"] == "PASS"
    assert report["n_ok"] == 3
    assert report["n_expected_thin"] == 0
    assert report["n_block_unexpected"] == 0
    assert report["coverage_preview"] == "3/3"
    assert {row["depth_class"] for row in report["rows"]} == {"OK"}
    assert {row["required_bars_1d"] for row in report["rows"]} == {121}
    assert report["writes_env"] is False
    assert report["removed_symbols"] == []


def test_boundary_120_is_not_ok_and_121_is_ok() -> None:
    thin = assemble_preflight(("SOL-USDT",), {"SOL-USDT": 120})
    ok = assemble_preflight(("SOL-USDT",), {"SOL-USDT": 121})
    assert thin["rows"][0]["depth_class"] == "BLOCK_UNEXPECTED"
    assert thin["rows"][0]["required_bars_1d"] == 121
    assert thin["preflight_verdict"] == "FAIL_UNEXPECTED"
    assert ok["rows"][0]["depth_class"] == "OK"
    assert ok["preflight_verdict"] == "PASS"


def test_p2_only_registered_four_below_121_is_expected_thin_not_fail() -> None:
    symbols = ("BTC-USDT", *FOUR)
    depths = {"BTC-USDT": 180, "PONS-USDT": 63, "BOME-USDT": 73, "RAY-USDT": 56, "ACE-USDT": 79}
    report = assemble_preflight(symbols, depths, expected_thin=FOUR)
    assert report["preflight_verdict"] == "PASS_WITH_EXPECTED_THIN"
    assert report["n_ok"] == 1
    assert report["n_expected_thin"] == 4
    assert report["n_block_unexpected"] == 0
    assert report["coverage_preview"] == "1/5"
    classes = {row["symbol"]: row["depth_class"] for row in report["rows"]}
    assert classes["BTC-USDT"] == "OK"
    assert set(classes[symbol] for symbol in FOUR) == {"EXPECTED_THIN"}
    assert report["removed_symbols"] == []
    assert report["registry_effect"] == "classification_only"


def test_p3_unregistered_symbol_below_121_fails() -> None:
    report = assemble_preflight(
        ("BTC-USDT", "DOGE-USDT"),
        {"BTC-USDT": 200, "DOGE-USDT": 40},
        expected_thin=FOUR,
    )
    assert report["preflight_verdict"] == "FAIL_UNEXPECTED"
    assert report["n_block_unexpected"] == 1
    doge = next(row for row in report["rows"] if row["symbol"] == "DOGE-USDT")
    assert doge["depth_class"] == "BLOCK_UNEXPECTED"
    assert doge["required_bars_1d"] == 121


def test_empty_universe_fails_and_does_not_invent_rows() -> None:
    report = assemble_preflight((), {})
    assert report["rows"] == []
    assert report["preflight_verdict"] == "FAIL_UNEXPECTED"
    assert report["coverage_preview"] == "0/0"
    assert report["n_ok"] == report["n_expected_thin"] == report["n_block_unexpected"] == 0


def test_missing_depth_is_not_synthesized() -> None:
    with pytest.raises(ValueError, match="closed_bars"):
        assemble_preflight(("BTC-USDT",), {})


def test_depth_payload_cannot_lower_required_bars() -> None:
    with pytest.raises(ValueError, match="121"):
        load_closed_bars({"BTC-USDT": 200, "required_bars_1d": 80})


def test_default_expected_thin_registry_is_empty() -> None:
    assert load_expected_thin("") == frozenset()
    assert load_expected_thin("# comment only\n\n") == frozenset()
    report = assemble_preflight(("PONS-USDT",), {"PONS-USDT": 63})
    assert report["rows"][0]["depth_class"] == "BLOCK_UNEXPECTED"
    assert report["preflight_verdict"] == "FAIL_UNEXPECTED"


def test_position_unknown_until_inventory_and_not_invented() -> None:
    unknown = assemble_preflight(("BTC-USDT",), {"BTC-USDT": 130}, open_symbols=None)
    assert unknown["rows"][0]["has_open_position"] == "unknown"
    inventoried = assemble_preflight(
        ("BTC-USDT", "ETH-USDT"),
        {"BTC-USDT": 130, "ETH-USDT": 130},
        open_symbols=(),
    )
    assert [row["has_open_position"] for row in inventoried["rows"]] == [False, False]
    marked = assemble_preflight(
        ("BTC-USDT", "ETH-USDT"),
        {"BTC-USDT": 130, "ETH-USDT": 130},
        open_symbols=("ETH-USDT",),
    )
    assert [row["has_open_position"] for row in marked["rows"]] == [False, True]


def test_open_symbol_loader_refuses_statusless_rows() -> None:
    with pytest.raises(ValueError, match="status"):
        load_open_symbols({"positions": [{"symbol": "BTC-USDT"}]})
    assert load_open_symbols({"open_symbols": ["ETH-USDT"]}) == frozenset({"ETH-USDT"})
    assert load_open_symbols({"positions": [{"symbol": "BTC-USDT", "status": "ACTIVE"}]}) == frozenset({"BTC-USDT"})
    assert load_open_symbols({"positions": [{"symbol": "BTC-USDT", "status": "CLOSED"}]}) == frozenset()


def test_p4_preflight_cli_is_env_readonly(monkeypatch, tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    monkeypatch.setenv("HTX_TREND_CONTRACTS", "BTC-USDT,ETH-USDT")
    sentinel = tmp_path / "htx-python.env"
    sentinel.write_text("HTX_TREND_CONTRACTS=BTC-USDT,ETH-USDT\n", encoding="utf-8")
    depth = tmp_path / "depth.json"
    depth.write_text(json.dumps({"BTC-USDT": 121, "ETH-USDT": 180}), encoding="utf-8")
    output = tmp_path / "out" / "preflight.json"
    before_env = dict(os.environ)
    code = main([
        "preflight", "--from-env", "--depth-file", str(depth),
        "--output", str(output),
    ])
    captured = capsys.readouterr()
    assert code == 0
    assert dict(os.environ) == before_env
    assert sentinel.read_text(encoding="utf-8") == "HTX_TREND_CONTRACTS=BTC-USDT,ETH-USDT\n"
    report = json.loads(output.read_text(encoding="utf-8"))
    assert report["preflight_verdict"] == "PASS"
    assert report["writes_env"] is False
    assert json.loads(captured.out)["coverage_preview"] == "2/2"
    assert contracts_from_env(os.environ) == ("BTC-USDT", "ETH-USDT")


def test_cli_rejects_required_bars_override() -> None:
    with pytest.raises(SystemExit) as caught:
        main(["preflight", "--universe", "BTC-USDT", "--depth-file", "ignored.json", "--required-bars", "80"])
    assert caught.value.code != 0


def test_public_kline_cli_counts_exchange_rows(monkeypatch, capsys: pytest.CaptureFixture[str]) -> None:
    now = 1_700_000_000
    day = 86_400

    class FakeClient:
        def history_kline_envelope(self, contract_code: str, period: str, size: int) -> dict:
            assert period == "1day"
            assert size >= REQUIRED_BARS_1D + 1
            rows = [
                {"id": now - (index + 1) * day, "open": "1", "high": "1", "low": "1", "close": "1"}
                for index in range(121)
            ]
            rows.append({"id": now - 10, "open": "1", "high": "1", "low": "1", "close": "1"})
            return {"data": rows, "contract": contract_code}

    monkeypatch.setattr(
        "htx_trend.tools.context_recovery_r1.build_public_client",
        lambda: FakeClient(),
    )
    code = main([
        "preflight", "--universe", "BTC-USDT", "--public-kline", "--now-epoch", str(now),
    ])
    report = json.loads(capsys.readouterr().out)
    assert code == 0
    assert report["rows"][0]["closed_bars_1d"] == 121
    assert report["rows"][0]["depth_class"] == "OK"
    assert report["writes_env"] is False


def test_public_read_counts_closed_bars_and_does_not_pad() -> None:
    now = 1_700_000_000
    day = 86_400

    def raw(open_time: int) -> dict[str, str | int]:
        return {"id": open_time, "open": "1", "high": "1", "low": "1", "close": "1"}

    class FakeClient:
        def history_kline_envelope(self, contract_code: str, period: str, size: int) -> dict:
            assert contract_code == "PONS-USDT"
            assert period == "1day"
            assert size >= REQUIRED_BARS_1D + 1
            rows = [raw(now - (index + 1) * day) for index in range(60)]
            rows.append(raw(now - 10))
            return {"data": rows}

    assert closed_bars_from_public_client(FakeClient(), "PONS-USDT", now_epoch=now) == 60


def test_classify_health_cli_keeps_expected_thin_distinct(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    failures = tmp_path / "failures.json"
    registry = tmp_path / "thin.txt"
    failures.write_text(json.dumps([f"{symbol}:CONTEXT_UNAVAILABLE" for symbol in FOUR]), encoding="utf-8")
    registry.write_text("\n".join(FOUR) + "\n", encoding="utf-8")
    code = main([
        "classify-health", "--failures-file", str(failures), "--expected-thin-file", str(registry),
    ])
    payload = json.loads(capsys.readouterr().out)
    assert code == 0
    assert payload["degraded_class"] == "DEGRADED_EXPECTED_THIN"
    assert payload["degraded_class"] != "HEALTHY"


def test_closed_daily_count_drops_forming_bar_without_padding() -> None:
    now = 1_700_000_000
    day = 86_400
    candles = (
        Candle(now - 2 * day, Decimal("1"), Decimal("2"), Decimal("1"), Decimal("2")),
        Candle(now - 30, Decimal("1"), Decimal("2"), Decimal("1"), Decimal("2")),
    )
    assert count_closed_daily_bars(candles, now_epoch=now) == 1


def test_h1_failures_inside_expected_set_are_not_healthy() -> None:
    failures = [f"{symbol}:CONTEXT_UNAVAILABLE" for symbol in FOUR]
    result = classify_degraded(failures, FOUR)
    assert result["degraded_class"] == "DEGRADED_EXPECTED_THIN"
    assert result["degraded_class"] != "HEALTHY"
    assert "HEALTHY" not in result["degraded_class"]
    assert result["soft_accept"] is True
    assert result["unexpected_failures"] == []
    assert result["expected_thin_symbols"] == sorted(FOUR)


def test_h2_unregistered_or_non_context_failure_is_not_soft_accepted() -> None:
    mixed = classify_degraded(
        ["PONS-USDT:CONTEXT_UNAVAILABLE", "DOGE-USDT:CONTEXT_UNAVAILABLE"],
        FOUR,
    )
    assert mixed["degraded_class"] == "DEGRADED_UNEXPECTED"
    assert mixed["soft_accept"] is False
    assert mixed["unexpected_failures"] == ["DOGE-USDT:CONTEXT_UNAVAILABLE"]
    other = classify_degraded(["PONS-USDT:RuntimeError"], ("PONS-USDT",))
    assert other["degraded_class"] == "DEGRADED_UNEXPECTED"
    assert other["soft_accept"] is False
    assert other["unexpected_failures"] == ["PONS-USDT:RuntimeError"]


def test_no_failures_stays_healthy_and_dead_process_is_unhealthy() -> None:
    healthy = classify_degraded((), FOUR)
    assert healthy["degraded_class"] == "HEALTHY"
    assert healthy["expected_thin_symbols"] == []
    assert healthy["soft_accept"] is False
    dead = classify_degraded(["PONS-USDT:CONTEXT_UNAVAILABLE"], FOUR, process_alive=False)
    assert dead["degraded_class"] == "UNHEALTHY"
    assert dead["soft_accept"] is False


def test_c1_position_guard_refuses_skip_unless_explicit_waiver(tmp_path: Path) -> None:
    refused = evaluate_position_guard()
    assert refused["allowed"] is False
    assert refused["auto_close"] is False
    assert refused["auto_reduce"] is False
    missing = evaluate_position_guard(report_path=str(tmp_path / "missing.json"))
    assert missing["allowed"] is False
    bare_skip = evaluate_position_guard(allow_skip=True, product_waiver="skip please")
    assert bare_skip["allowed"] is False
    report = tmp_path / "positions.json"
    report.write_text('{"open_symbols":[]}\n', encoding="utf-8")
    present = evaluate_position_guard(report_path=str(report))
    assert present["allowed"] is True
    assert present["auto_close"] is False
    assert present["auto_reduce"] is False
    waived = evaluate_position_guard(
        allow_skip=True,
        product_waiver="PRODUCT-WAIVER: drill may skip the readonly position report",
    )
    assert waived["allowed"] is True
    assert waived["skipped_positions"] is True
    assert waived["auto_close"] is False


def test_position_guard_cli_default_refuses(capsys: pytest.CaptureFixture[str]) -> None:
    code = main(["position-guard"])
    payload = json.loads(capsys.readouterr().out)
    assert code == 2
    assert payload["allowed"] is False
    assert payload["auto_close"] is False


def test_checked_in_fixture_previews_expected_thin(capsys: pytest.CaptureFixture[str]) -> None:
    root = Path(__file__).resolve().parent / "fixtures" / "context_recovery_r1"
    code = main([
        "preflight",
        "--universe", "BTC-USDT,PONS-USDT,BOME-USDT,RAY-USDT,ACE-USDT",
        "--depth-file", str(root / "depth.json"),
        "--expected-thin-file", str(root / "expected_thin.txt"),
        "--position-report", str(root / "positions.json"),
    ])
    report = json.loads(capsys.readouterr().out)
    assert code == 0
    assert report["preflight_verdict"] == "PASS_WITH_EXPECTED_THIN"
    assert report["coverage_preview"] == "1/5"
    assert report["rows"][0]["has_open_position"] is False
    pons = next(row for row in report["rows"] if row["symbol"] == "PONS-USDT")
    assert pons["has_open_position"] is True
    assert pons["depth_class"] == "EXPECTED_THIN"


def test_sources_do_not_trade_or_write_contracts() -> None:
    root = Path(__file__).resolve().parents[1] / "src" / "htx_trend"
    texts = [
        (root / "context_recovery.py").read_text(encoding="utf-8"),
        (root / "tools" / "context_recovery_r1.py").read_text(encoding="utf-8"),
    ]
    banned = ("putenv", "close_position", "place_order", "reduce_only", "os.environ[", "environ[")
    for text in texts:
        for token in banned:
            assert token not in text
