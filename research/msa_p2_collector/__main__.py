"""采集器入口。不带 --dry-run 时直接退出，默认不启动。"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from .collector import DEFAULT_INTERVALS, PATHS, Collector, RecordedTransport
from .storage import ResearchStore


def main(argv=None):
    parser = argparse.ArgumentParser(description='HTX 公开行情只读采集，默认不启动')
    parser.add_argument('--dry-run', action='store_true')
    parser.add_argument('--out')
    parser.add_argument('--symbols', default='')
    args = parser.parse_args(argv)
    if not args.dry_run or not args.out:
        return 2
    symbols = tuple(part for part in args.symbols.split(',') if part) or ('BTC-USDT', 'ETH-USDT')
    out = Path(args.out)
    collector = Collector(
        RecordedTransport(), ResearchStore(out), symbols=symbols,
        clock=lambda: 1_700_000_000.0, sleep=lambda _seconds: None,
        requests_per_second=1000, max_attempts=2, on_retry=lambda *_args: None,
        intervals=DEFAULT_INTERVALS, grace_seconds=5, collect_trades=True,
    )
    collector.run_once()
    manifest = {'network': False, 'streams': list(PATHS), 'symbols': list(symbols)}
    (out / 'dry_run_manifest.json').write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + '\n', encoding='utf-8')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
