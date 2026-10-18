"""Binance backfill uses data.binance.vision USDT-M archives, never spot."""

from __future__ import annotations

import hashlib
import io
import zipfile
from datetime import UTC, datetime

import httpx
import pytest

from htx_trend.research.adapters.binance import (
    BinanceChecksumError,
    BinanceVisionClient,
    VISION_BASE,
)


def _zip_csv(name: str, rows: list[str]) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as archive:
        archive.writestr(name, "\n".join(rows) + "\n")
    return buf.getvalue()


class FakeTransport(httpx.BaseTransport):
    def __init__(self, files: dict[str, bytes]):
        self.files = files
        self.requested: list[str] = []

    def handle_request(self, request: httpx.Request) -> httpx.Response:
        url = str(request.url)
        self.requested.append(url)
        if "/spot/" in url:
            return httpx.Response(400, request=request, text="spot forbidden")
        body = self.files.get(url)
        if body is None:
            return httpx.Response(404, request=request, text="missing")
        return httpx.Response(200, request=request, content=body)


def test_vision_downloads_um_futures_and_verifies_checksum():
    ts = int(datetime(2024, 1, 1, tzinfo=UTC).timestamp() * 1000)
    csv_rows = [f"{ts},100,101,99,100.5,12,0,1200,1,0,0,0"]
    payload = _zip_csv("BTCUSDT-4h-2024-01.csv", csv_rows)
    digest = hashlib.sha256(payload).hexdigest()
    zip_url = f"{VISION_BASE}/monthly/klines/BTCUSDT/4h/BTCUSDT-4h-2024-01.zip"
    files = {
        zip_url: payload,
        f"{zip_url}.CHECKSUM": f"{digest}  BTCUSDT-4h-2024-01.zip\n",
    }
    transport = FakeTransport(files)
    client = BinanceVisionClient(client=httpx.Client(transport=transport))
    klines = client.download_klines(
        "BTCUSDT",
        "4h",
        datetime(2024, 1, 1, tzinfo=UTC),
        datetime(2024, 1, 2, tzinfo=UTC),
    )
    assert len(klines) == 1
    assert klines[0].source == "binance"
    assert all("/spot/" not in url for url in transport.requested)
    assert all("/futures/um/" in url for url in transport.requested)


def test_checksum_mismatch_raises():
    payload = _zip_csv("BTCUSDT-4h-2024-01.csv", ["1,1,1,1,1,1,1,1,1,1,1,1"])
    zip_url = f"{VISION_BASE}/monthly/klines/BTCUSDT/4h/BTCUSDT-4h-2024-01.zip"
    files = {zip_url: payload, f"{zip_url}.CHECKSUM": "deadbeef  file.zip\n"}
    client = BinanceVisionClient(client=httpx.Client(transport=FakeTransport(files)))
    with pytest.raises(BinanceChecksumError):
        client.download_klines(
            "BTCUSDT",
            "4h",
            datetime(2024, 1, 1, tzinfo=UTC),
            datetime(2024, 1, 2, tzinfo=UTC),
        )


def test_spot_url_rejected():
    with pytest.raises(ValueError, match="Spot"):
        BinanceVisionClient(base_url="https://data.binance.vision/data/spot")


def test_vision_downloads_um_funding_rate_tagged_binance_external():
    ts = int(datetime(2020, 10, 21, 16, tzinfo=UTC).timestamp() * 1000)
    csv_rows = [
        "calc_time,funding_interval_hours,last_funding_rate",
        f"{ts},8,0.0001",
    ]
    payload = _zip_csv("BTCUSDT-fundingRate-2020-10.csv", csv_rows)
    digest = hashlib.sha256(payload).hexdigest()
    zip_url = f"{VISION_BASE}/monthly/fundingRate/BTCUSDT/BTCUSDT-fundingRate-2020-10.zip"
    files = {
        zip_url: payload,
        f"{zip_url}.CHECKSUM": f"{digest}  BTCUSDT-fundingRate-2020-10.zip\n",
    }
    transport = FakeTransport(files)
    client = BinanceVisionClient(client=httpx.Client(transport=transport))
    rates = client.download_funding_history(
        "BTCUSDT",
        datetime(2020, 10, 1, tzinfo=UTC),
        datetime(2020, 11, 1, tzinfo=UTC),
    )
    assert len(rates) == 1
    assert rates[0].source == "binance_external"
    assert rates[0].funding_rate == pytest.approx(0.0001)
    assert rates[0].funding_time == datetime(2020, 10, 21, 16, tzinfo=UTC)
    assert all("/futures/um/monthly/fundingRate/" in url for url in transport.requested)
    assert all("/spot/" not in url for url in transport.requested)
