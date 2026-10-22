"""采集器入口。不带参数时直接退出，默认不启动。"""
from __future__ import annotations

import argparse
import json
import logging
import signal
import sys
import time
from pathlib import Path

from .collector import DEFAULT_INTERVALS, PATHS, Collector, RecordedTransport
from .config import load_universe
from .http_client import HttpTransport
from .storage import ResearchStore

LIVE_REQUESTS_PER_SECOND = 2
LIVE_MAX_ATTEMPTS = 4
LIVE_GRACE_SECONDS = 5
DEFAULT_UNIVERSE = Path(__file__).with_name('universe.yaml')
LOG = logging.getLogger(__name__)


def main(argv=None):
    parser = argparse.ArgumentParser(description='HTX 公开行情只读采集，默认不启动')
    parser.add_argument('--dry-run', action='store_true')
    parser.add_argument('--live', action='store_true')
    parser.add_argument('--once', action='store_true', help='拉一轮公开行情后退出')
    parser.add_argument('--loop', action='store_true', help='持续采集，直到 SIGINT/SIGTERM')
    parser.add_argument('--out')
    parser.add_argument('--symbols', default='')
    parser.add_argument('--universe')
    args = parser.parse_args(argv)
    if args.dry_run:
        if args.live or args.once or args.loop or not args.out:
            return 2
        return _dry_run(args)
    if not args.live or args.once == args.loop or not args.out:
        return 2
    return _live(args)


def _dry_run(args):
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


def _live(args):
    universe = args.universe or str(DEFAULT_UNIVERSE)
    try:
        symbols = load_universe(universe)
    except (OSError, ValueError) as exc:
        print(exc, file=sys.stderr)
        return 1
    collector = Collector(
        HttpTransport(), ResearchStore(Path(args.out)), symbols=symbols,
        clock=time.time, sleep=time.sleep,
        requests_per_second=LIVE_REQUESTS_PER_SECOND, max_attempts=LIVE_MAX_ATTEMPTS,
        on_retry=_on_retry, intervals=DEFAULT_INTERVALS,
        grace_seconds=LIVE_GRACE_SECONDS, collect_trades=True,
    )
    if args.once:
        collector.run_once()
        return 0
    return _run_until_signal(collector)


def _on_retry(attempt, exc):
    LOG.warning('公开请求失败，准备重试 attempt=%s error=%s', attempt, exc)


def _run_until_signal(collector):
    stop = {'flag': False}

    def should_stop():
        return stop['flag']

    def mark_stop(_signum, _frame):
        stop['flag'] = True

    signal.signal(signal.SIGINT, mark_stop)
    signal.signal(signal.SIGTERM, mark_stop)
    collector.run_loop(should_stop)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
