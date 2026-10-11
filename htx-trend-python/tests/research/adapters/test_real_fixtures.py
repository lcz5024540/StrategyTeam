"""Parser-layer tests against committed real HTX and Binance Vision payloads."""

from __future__ import annotations

import hashlib
import io
import json
import zipfile
from datetime import UTC, datetime, timedelta
from pathlib import Path
from unittest.mock import MagicMock

import pandas as pd
import pytest

from htx_trend.research.adapters.binance import BinanceVisionClient
from htx_trend.research.adapters.htx.market_data import (
    HtxAPIError,
    HtxMarketDataClient,
    parse_htx_funding_time,
    require_research_timestamp,
)
from htx_trend.research.features.funding_asof import settlements_in_bar

REAL_DIR = Path(__file__).resolve().parents[2] / "fixtures" / "real"
HTX_DIR = REAL_DIR / "htx-fixtures-real"
BN_DIR = REAL_DIR / "binance-fixtures-real"

HTX_FILES = (
    "contract_info_btc.json",
    "contract_info_eth.json",
    "funding_btc_page1.json",
    "funding_btc_lastpage.json",
    "funding_btc_beyond.json",
    "kline_btc_1day_size3.json",
    "kline_btc_4hour_empty.json",
    "kline_btc_4hour_invalid_range.json",
    "kline_btc_4hour_size5.json",
)
BINANCE_ARCHIVES = (
    "BTCUSDT-4h-2020-10.zip",
    "BTCUSDT-4h-2026-09.zip",
    "BTCUSDT-fundingRate-2020-10.zip",
    "BTCUSDT-fundingRate-2026-09.zip",
)


def _load_htx(name: str) -> dict:
    return json.loads((HTX_DIR / name).read_text(encoding="utf-8"))


def _funding_rows(name: str) -> list[dict]:
    envelope = _load_htx(name)
    return list((envelope.get("data") or {}).get("data") or [])


def _htx_client(inner: MagicMock | None = None) -> HtxMarketDataClient:
    client = HtxMarketDataClient()
    if inner is not None:
        client.client = inner
    return client


def _client_with_rows(rows: list[dict]) -> HtxMarketDataClient:
    inner = MagicMock()
    inner.funding_history.return_value = rows
    return _htx_client(inner)


def test_real_fixture_inventory_is_complete():
    assert (HTX_DIR / "CAPTURED_AT").is_file()
    assert (BN_DIR / "CAPTURED_AT").is_file()
    for name in HTX_FILES:
        assert (HTX_DIR / name).is_file(), name
    for name in BINANCE_ARCHIVES:
        assert (BN_DIR / name).is_file(), name
        assert (BN_DIR / f"{name}.CHECKSUM").is_file(), name


@pytest.mark.parametrize("archive", BINANCE_ARCHIVES)
def test_binance_checksum_matches_committed_zip(archive: str):
    payload = (BN_DIR / archive).read_bytes()
    listed = (BN_DIR / f"{archive}.CHECKSUM").read_text(encoding="utf-8").split()[0]
    assert hashlib.sha256(payload).hexdigest() == listed


def test_htx_contract_info_btc_fixture():
    payload = _load_htx("contract_info_btc.json")
    inner = MagicMock()
    inner.contract_info.return_value = payload["data"]
    spec = _htx_client(inner).fetch_contract_info("BTC-USDT")
    assert spec is not None
    assert spec.contract_code == "BTC-USDT"
    assert spec.contract_size == 0.001
    assert spec.price_tick == 0.1
    assert spec.min_order_size == 1
    assert spec.create_date == 20201021


def test_htx_contract_info_eth_fixture():
    payload = _load_htx("contract_info_eth.json")
    inner = MagicMock()
    inner.contract_info.return_value = payload["data"]
    spec = _htx_client(inner).fetch_contract_info("ETH-USDT")
    assert spec is not None
    assert spec.contract_code == "ETH-USDT"
    assert spec.contract_size == 0.01
    assert spec.price_tick == 0.01
    assert spec.min_order_size == 1
    assert spec.create_date == 20201021


def test_htx_funding_page1_parses_millisecond_times():
    rows = _funding_rows("funding_btc_page1.json")
    parsed = _client_with_rows(rows).fetch_funding_history("BTC-USDT")
    assert len(parsed) == 5
    assert parsed[0].symbol == "BTC-USDT"
    assert parsed[0].funding_time == datetime(2026, 10, 10, 8, 0, tzinfo=UTC)
    assert parsed[-1].funding_time == datetime(2026, 10, 11, 16, 0, tzinfo=UTC)
    assert all(2019 <= row.funding_time.year <= 2026 for row in parsed)
    assert parsed[0].realized_rate == pytest.approx(-0.000025049098653173)


def test_htx_funding_lastpage_oldest_is_2020_10_21_16utc():
    rows = _funding_rows("funding_btc_lastpage.json")
    parsed = _client_with_rows(rows).fetch_funding_history("BTC-USDT")
    assert len(parsed) == 44
    assert parsed[0].funding_time == datetime(2020, 10, 21, 16, 0, tzinfo=UTC)
    assert parsed[-1].funding_time.year == 2020


def test_htx_funding_beyond_page_is_empty():
    rows = _funding_rows("funding_btc_beyond.json")
    parsed = _client_with_rows(rows).fetch_funding_history("BTC-USDT")
    assert parsed == []


def test_htx_kline_1day_size3_fixture():
    payload = _load_htx("kline_btc_1day_size3.json")
    klines = _htx_client()._parse_klines(payload["data"])
    assert len(klines) == 3
    assert klines[0].bar_time == datetime(2026, 10, 9, 16, 0, tzinfo=UTC)
    assert klines[-1].bar_time == datetime(2026, 10, 11, 16, 0, tzinfo=UTC)
    assert klines[-1].close == pytest.approx(83800.0)
    assert all(2019 <= row.bar_time.year <= 2026 for row in klines)


def test_htx_kline_4hour_size5_fixture():
    payload = _load_htx("kline_btc_4hour_size5.json")
    klines = _htx_client()._parse_klines(payload["data"])
    assert len(klines) == 5
    assert klines[0].bar_time == datetime(2026, 10, 11, 0, 0, tzinfo=UTC)
    assert klines[-1].bar_time == datetime(2026, 10, 11, 16, 0, tzinfo=UTC)


def test_htx_kline_4hour_empty_fixture():
    payload = _load_htx("kline_btc_4hour_empty.json")
    klines = _htx_client()._parse_klines(payload["data"])
    assert klines == []


def test_htx_kline_4hour_invalid_range_raises():
    payload = _load_htx("kline_btc_4hour_invalid_range.json")
    inner = MagicMock()
    inner.history_kline_envelope.return_value = payload
    client = _htx_client(inner)
    with pytest.raises(HtxAPIError, match="bad-request|invalid from to"):
        client._request_kline_envelope(
            "BTC-USDT", "4hour", from_timestamp=1, to_timestamp=2
        )


def test_htx_timestamp_year_must_be_in_2019_2026():
    with pytest.raises(ValueError, match="year"):
        parse_htx_funding_time(1_262_304_000_000)
    with pytest.raises(ValueError, match="year"):
        parse_htx_funding_time(1_893_456_000)
    with pytest.raises(ValueError, match="year"):
        _htx_client()._parse_klines(
            [{"id": 1_262_304_000, "open": 1, "high": 1, "low": 1, "close": 1, "vol": 1}]
        )
    ok = parse_htx_funding_time("1603296000000")
    assert ok == datetime(2020, 10, 21, 16, 0, tzinfo=UTC)
    require_research_timestamp(ok)


def test_binance_kline_2020_10_has_no_header_and_keeps_first_row():
    payload = (BN_DIR / "BTCUSDT-4h-2020-10.zip").read_bytes()
    klines = BinanceVisionClient()._parse_archive(payload, datetime.now(UTC))
    assert len(klines) == 186
    assert klines[0].bar_time == datetime(2020, 10, 1, 0, 0, tzinfo=UTC)
    assert klines[0].open == pytest.approx(10773.0)
    assert klines[-1].bar_time == datetime(2020, 10, 31, 20, 0, tzinfo=UTC)
    assert all(2019 <= row.bar_time.year <= 2026 for row in klines)


def test_binance_kline_2026_09_skips_header_not_first_data_row():
    payload = (BN_DIR / "BTCUSDT-4h-2026-09.zip").read_bytes()
    klines = BinanceVisionClient()._parse_archive(payload, datetime.now(UTC))
    assert len(klines) == 180
    assert klines[0].bar_time == datetime(2026, 9, 1, 0, 0, tzinfo=UTC)
    assert klines[0].open == pytest.approx(78549.60)
    assert klines[-1].bar_time == datetime(2026, 9, 30, 20, 0, tzinfo=UTC)


def test_binance_kline_year_out_of_range_raises():
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        zf.writestr(
            "BTCUSDT-4h-2010-01.csv",
            "1262304000000,1,1,1,1,1,1262318399999,1,1,1,1,0\n",
        )
    with pytest.raises(ValueError, match="year"):
        BinanceVisionClient()._parse_archive(buf.getvalue(), datetime.now(UTC))


def _funding_frame(payload: bytes) -> pd.DataFrame:
    rates = BinanceVisionClient()._parse_funding_archive(payload, "BTCUSDT")
    return pd.DataFrame(
        {
            "funding_time": [row.funding_time for row in rates],
            "funding_rate": [row.funding_rate for row in rates],
        }
    )


def test_binance_funding_2020_10_interval_hours_and_year_range():
    payload = (BN_DIR / "BTCUSDT-fundingRate-2020-10.zip").read_bytes()
    frame = _funding_frame(payload)
    assert len(frame) == 93
    first = pd.Timestamp(frame.iloc[0]["funding_time"]).tz_convert("UTC")
    assert first.to_pydatetime() == datetime(2020, 10, 1, 0, 0, tzinfo=UTC)
    deltas = frame["funding_time"].diff().dropna()
    assert (deltas == pd.Timedelta(hours=8)).all()
    years = pd.to_datetime(frame["funding_time"], utc=True).dt.year
    assert int(years.min()) >= 2019
    assert int(years.max()) <= 2026


def test_binance_funding_2026_09_floors_calc_time_to_second():
    payload = (BN_DIR / "BTCUSDT-fundingRate-2026-09.zip").read_bytes()
    frame = _funding_frame(payload)
    first = pd.Timestamp(frame.iloc[0]["funding_time"]).tz_convert("UTC")
    assert first.to_pydatetime() == datetime(2026, 9, 1, 0, 0, tzinfo=UTC)
    assert first.microsecond == 0
    deltas = frame["funding_time"].diff().dropna()
    assert (deltas == pd.Timedelta(hours=8)).all()


def test_binance_funding_calc_time_boundary_uses_left_open_bar():
    payload = (BN_DIR / "BTCUSDT-fundingRate-2026-09.zip").read_bytes()
    frame = _funding_frame(payload)
    settlement = datetime(2026, 9, 1, 0, 0, tzinfo=UTC)
    opening_bar = settlements_in_bar(
        frame,
        settlement,
        settlement + timedelta(hours=4),
    )
    closing_bar = settlements_in_bar(
        frame,
        settlement - timedelta(hours=4),
        settlement,
    )
    assert opening_bar.empty
    assert len(closing_bar) == 1
    assigned = pd.Timestamp(closing_bar.iloc[0]["funding_time"]).tz_convert("UTC")
    assert assigned.to_pydatetime() == settlement


def test_binance_funding_interval_hours_mismatch_raises():
    buf = io.BytesIO()
    csv = (
        "calc_time,symbol,last_funding_rate,funding_interval_hours\n"
        "1601510400000,BTCUSDT,0.0001,8\n"
        "1601524800000,BTCUSDT,0.0001,8\n"
    )
    with zipfile.ZipFile(buf, "w") as zf:
        zf.writestr("BTCUSDT-fundingRate-2020-10.csv", csv)
    with pytest.raises(ValueError, match="funding_interval_hours"):
        BinanceVisionClient()._parse_funding_archive(buf.getvalue(), "BTCUSDT")


def test_captured_at_metadata_is_present():
    text = (HTX_DIR / "CAPTURED_AT").read_text(encoding="utf-8").strip()
    assert text
    stamp = datetime.fromisoformat(text.replace("Z", "+00:00"))
    assert 2019 <= stamp.year <= 2026
