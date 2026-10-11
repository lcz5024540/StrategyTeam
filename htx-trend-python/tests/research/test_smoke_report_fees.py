"""Smoke report.md must publish a non-empty fees section and reconciliation."""

from __future__ import annotations

from datetime import UTC, datetime
from pathlib import Path

import pandas as pd

from htx_trend.research.reports.report_generator import ReportGenerator, summarize_fee_ledger


def _fills() -> list[dict]:
    return [
        {
            "symbol": "BTC-USDT",
            "side": "LONG",
            "size": 1.0,
            "price": 100.0,
            "time": datetime(2024, 2, 5, 4, tzinfo=UTC),
            "fees": 0.06,
            "slippage": 0.01,
            "is_entry": True,
            "contract_size": 1.0,
        },
        {
            "symbol": "BTC-USDT",
            "side": "SHORT",
            "size": 1.0,
            "price": 110.0,
            "time": datetime(2024, 2, 6, 4, tzinfo=UTC),
            "fees": 0.066,
            "slippage": 0.0,
            "is_entry": False,
            "contract_size": 1.0,
            "funding": 0.13,
        },
    ]


def _events() -> list[dict]:
    return [
        {
            "symbol": "BTC-USDT",
            "side": "LONG",
            "payment": 0.13,
            "signed_payment": 0.13,
            "funding_rate": 0.001,
            "settlement_time": datetime(2024, 2, 5, 16, tzinfo=UTC),
        }
    ]


def test_smoke_report_fees_section_is_non_empty(tmp_path):
    path = ReportGenerator("SMOKE-20261011-01", tmp_path).generate_report(
        config={
            "run_id": "SMOKE-20261011-01",
            "run_type": "SMOKE",
            "initial_equity": 10_000.0,
        },
        fills=_fills(),
        decisions=pd.DataFrame(),
        smoke=True,
        funding_events=_events(),
        initial_equity=10_000.0,
        final_equity=10_009.734,
    )
    text = path.read_text(encoding="utf-8")
    fees_at = text.lower().find("## fees")
    assert fees_at != -1
    section = text[fees_at:]
    next_heading = section.find("\n## ", 3)
    body = section if next_heading == -1 else section[:next_heading]
    assert body.strip() != "## fees"
    assert "entry" in body.lower()
    assert "exit" in body.lower()
    assert "slippage" in body.lower()
    assert "funding paid" in body.lower() or "funding_paid" in body.lower()
    assert "funding received" in body.lower() or "funding_received" in body.lower()
    assert "net funding" in body.lower() or "net_funding" in body.lower()
    assert "BTC-USDT" in body
    assert "reconciliation" in body.lower() or "对账" in body
    assert "10000" in body or "10,000" in body
    assert "diff" in body.lower()


def test_summarize_fee_ledger_totals_and_reconciliation():
    summary = summarize_fee_ledger(
        _fills(),
        _events(),
        initial_equity=10_000.0,
        final_equity=10_009.734,
    )
    assert summary["total"]["entry_fees"] == 0.06
    assert summary["total"]["exit_fees"] == 0.066
    assert summary["total"]["slippage"] == 0.01
    assert summary["total"]["funding_paid"] == 0.13
    assert summary["total"]["funding_received"] == 0.0
    assert summary["total"]["net_funding"] == 0.13
    assert summary["by_symbol"]["BTC-USDT"]["entry_fees"] == 0.06
    assert summary["reconciliation"]["realized_pnl"] == 10.0
    assert summary["reconciliation"]["expected_final"] == 10_009.734
    assert summary["reconciliation"]["diff"] == 0.0


def test_funding_source_timeline_counts_and_bounds(tmp_path):
    from htx_trend.research.data.collector import funding_source_timeline

    raw = tmp_path / "raw"
    raw.mkdir()
    frame = pd.DataFrame(
        {
            "funding_time": [
                pd.Timestamp("2020-10-21T16:00:00Z"),
                pd.Timestamp("2020-11-01T00:00:00Z"),
                pd.Timestamp("2026-01-01T00:00:00Z"),
            ],
            "funding_rate": [0.0001, 0.0001, 0.0001],
            "source": ["binance_external", "binance_external", "htx"],
        }
    )
    frame.to_parquet(raw / "BTC-USDT_funding.parquet", index=False)
    payload = funding_source_timeline(raw, ["BTC-USDT"])
    assert payload["BTC-USDT"]["binance_external"]["count"] == 2
    assert payload["BTC-USDT"]["htx"]["count"] == 1
    assert payload["BTC-USDT"]["binance_external"]["earliest"].startswith("2020-10-21")
    assert payload["BTC-USDT"]["htx"]["latest"].startswith("2026-01-01")


def test_smoke_script_writes_funding_timeline_to_summary():
    script = (Path(__file__).resolve().parents[2] / "scripts" / "smoke_e2e.sh").read_text()
    assert "funding_source_timeline" in script
    assert "earliest" in script
    assert "latest" in script
