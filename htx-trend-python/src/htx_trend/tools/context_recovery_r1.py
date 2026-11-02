"""R1-α read-only context recovery commands.

Does not hot-update, install, trade, or write the contract universe.
``required_bars_1d`` stays 121 inside the library; this command has no override.
"""
from __future__ import annotations

import argparse
from datetime import UTC, datetime
import json
import os
from pathlib import Path

from htx_trend.context_recovery import (
    assemble_preflight,
    classify_degraded,
    closed_bars_from_public_client,
    contracts_from_env,
    evaluate_position_guard,
    load_closed_bars,
    load_expected_thin,
    load_open_symbols,
    render_preflight_markdown,
)


def build_public_client():
    """Public market client. Trading stays disabled; no credential is required."""
    from htx_trend.htx_v5 import HtxV5Client
    return HtxV5Client(live_trading_enabled=False)


def _read_symbols(path: Path) -> tuple[str, ...]:
    text = path.read_text(encoding="utf-8")
    symbols: list[str] = []
    for line in text.splitlines():
        item = line.split("#", 1)[0].strip()
        if item:
            symbols.extend(part.strip() for part in item.split(",") if part.strip())
    return tuple(symbols)


def _registry(path: Path | None, inline: str) -> frozenset[str]:
    found: set[str] = set()
    if path is not None:
        found.update(load_expected_thin(path.read_text(encoding="utf-8")))
    if inline.strip():
        found.update(load_expected_thin(inline.replace(",", "\n")))
    return frozenset(found)


def _emit(payload: dict, output: Path | None = None, markdown_path: Path | None = None, markdown: str | None = None) -> None:
    text = json.dumps(payload, ensure_ascii=False, indent=2) + "\n"
    print(text, end="")
    if output is not None:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(text, encoding="utf-8")
    if markdown_path is not None and markdown is not None:
        markdown_path.parent.mkdir(parents=True, exist_ok=True)
        markdown_path.write_text(markdown, encoding="utf-8")


def _preflight(args: argparse.Namespace) -> int:
    if args.from_env:
        symbols = contracts_from_env(os.environ)
    elif args.universe_file is not None:
        symbols = _read_symbols(args.universe_file)
    else:
        symbols = tuple(part.strip() for part in args.universe.split(",") if part.strip())
    if args.public_kline:
        now_epoch = int(datetime.now(UTC).timestamp()) if args.now_epoch is None else args.now_epoch
        client = build_public_client()
        try:
            depths = {
                symbol: closed_bars_from_public_client(client, symbol, now_epoch=now_epoch)
                for symbol in symbols
            }
        finally:
            close = getattr(client, "close", None)
            if close is not None:
                close()
    else:
        depths = load_closed_bars(json.loads(args.depth_file.read_text(encoding="utf-8")))
    registry = _registry(args.expected_thin_file, args.expected_thin)
    open_symbols = None
    if args.position_report is not None:
        open_symbols = load_open_symbols(json.loads(args.position_report.read_text(encoding="utf-8")))
    report = assemble_preflight(symbols, depths, registry, open_symbols)
    _emit(report, args.output, args.markdown, render_preflight_markdown(report))
    if report["preflight_verdict"] == "FAIL_UNEXPECTED":
        return 2
    return 0


def _classify(args: argparse.Namespace) -> int:
    failures = list(args.failure)
    if args.failures_file is not None:
        loaded = json.loads(args.failures_file.read_text(encoding="utf-8"))
        if not isinstance(loaded, list):
            raise ValueError("failures file must be a JSON list")
        failures.extend(str(item) for item in loaded)
    result = classify_degraded(
        failures,
        _registry(args.expected_thin_file, args.expected_thin),
        process_alive=not args.process_down,
    )
    _emit(result)
    if result["degraded_class"] in {"DEGRADED_UNEXPECTED", "UNHEALTHY"}:
        return 2
    return 0


def _guard(args: argparse.Namespace) -> int:
    decision = evaluate_position_guard(
        report_path=None if args.position_report is None else str(args.position_report),
        allow_skip=args.allow_skip_positions,
        product_waiver=args.product_waiver,
    )
    _emit(decision)
    return 0 if decision["allowed"] else 2


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)

    preflight = commands.add_parser("preflight", help="read-only universe 1day depth preflight")
    source = preflight.add_mutually_exclusive_group(required=True)
    source.add_argument("--universe", help="comma-separated contracts; read-only")
    source.add_argument("--universe-file", type=Path, help="contract list file; read-only")
    source.add_argument("--from-env", action="store_true", help="read HTX_TREND_CONTRACTS without writing it")
    depth_source = preflight.add_mutually_exclusive_group(required=True)
    depth_source.add_argument("--depth-file", type=Path, help="symbol to closed 1day bar count; no bar synthesis")
    depth_source.add_argument("--public-kline", action="store_true", help="read public 1day klines; no trading")
    preflight.add_argument("--now-epoch", type=int, help="close cutoff for --public-kline")
    preflight.add_argument("--expected-thin-file", type=Path)
    preflight.add_argument("--expected-thin", default="", help="comma-separated classification registry; default empty")
    preflight.add_argument("--position-report", type=Path)
    preflight.add_argument("--output", type=Path)
    preflight.add_argument("--markdown", type=Path)

    health = commands.add_parser("classify-health", help="compare failures with the expected-thin registry")
    health.add_argument("--failures-file", type=Path)
    health.add_argument("--failure", action="append", default=[])
    health.add_argument("--expected-thin-file", type=Path)
    health.add_argument("--expected-thin", default="")
    health.add_argument("--process-down", action="store_true")

    guard = commands.add_parser("position-guard", help="require a readonly position report or an explicit product waiver")
    guard.add_argument("--position-report", type=Path)
    guard.add_argument("--allow-skip-positions", action="store_true")
    guard.add_argument("--product-waiver", default="")

    args = parser.parse_args(argv)
    if args.command == "preflight":
        return _preflight(args)
    if args.command == "classify-health":
        return _classify(args)
    if args.command == "position-guard":
        return _guard(args)
    raise AssertionError(args.command)


if __name__ == "__main__":
    raise SystemExit(main())
