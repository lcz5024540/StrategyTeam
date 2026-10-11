"""HTX USDT-M contract value: 1 contract = contract_size coins."""

from __future__ import annotations

from typing import Any

PERIODS_PER_YEAR: dict[str, int] = {
    "4hour": 6 * 365,
    "4h": 6 * 365,
    "1day": 365,
    "1d": 365,
}

HTX_LINEAR_SPECS: dict[str, dict[str, Any]] = {
    "BTC-USDT": {
        "contract_code": "BTC-USDT",
        "symbol": "BTC",
        "contract_size": 0.001,
        "price_tick": 0.1,
        "min_order_size": 1,
        "create_date": 0,
    },
    "ETH-USDT": {
        "contract_code": "ETH-USDT",
        "symbol": "ETH",
        "contract_size": 0.01,
        "price_tick": 0.01,
        "min_order_size": 1,
        "create_date": 0,
    },
}


def require_contract_size(contract_size: float | None, symbol: str = "") -> float:
    if contract_size is None:
        raise ValueError(f"contract_size is required{f' for {symbol}' if symbol else ''}")
    value = float(contract_size)
    if value <= 0:
        raise ValueError(f"contract_size must be > 0{f' for {symbol}' if symbol else ''}")
    return value


def coin_notional(price: float, contracts: float, contract_size: float, symbol: str = "") -> float:
    """USDT notional = contracts * contract_size * price."""
    return float(price) * float(contracts) * require_contract_size(contract_size, symbol)


def periods_per_year(timeframe: str, override: int | None = None) -> int:
    if override is not None:
        value = int(override)
        if value <= 0:
            raise ValueError("periods_per_year must be > 0")
        return value
    key = str(timeframe)
    if key not in PERIODS_PER_YEAR:
        raise ValueError(
            f"No periods-per-year for timeframe {key!r}. "
            f"Known: {sorted(PERIODS_PER_YEAR)}"
        )
    return PERIODS_PER_YEAR[key]
