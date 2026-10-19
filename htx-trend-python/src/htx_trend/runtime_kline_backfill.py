"""R1 planning and explicit, resumable collector replay. Never opens a DB itself."""
from datetime import datetime
import os
import time

from .market_data import period_anchor_seconds, period_seconds
from .research.collector import ContinuousCollector
from .runtime_kline_business import BUSINESS_KLINE_PERIODS, BUSINESS_KLINE_SYMBOLS

DEFAULT_START = int(datetime.fromisoformat('2026-10-12T00:00:00+08:00').timestamp())


def require_backfill_confirmation() -> None:
    if os.environ.get('HTX_KLINE_BACKFILL_CONFIRM') != 'YES':
        raise ValueError('non-dry-run requires HTX_KLINE_BACKFILL_CONFIRM=YES')


def backfill_plan(*, start: int, end: int, page_bars: int = 500) -> list[dict]:
    if end <= start or not 1 <= page_bars <= 2000:
        raise ValueError('invalid backfill interval or page size')
    pages = []
    for symbol in BUSINESS_KLINE_SYMBOLS:
        for period in BUSINESS_KLINE_PERIODS:
            step, anchor = period_seconds(period), period_anchor_seconds(period)
            lower = ((start-anchor+step-1)//step)*step+anchor
            upper = ((end-anchor)//step)*step+anchor
            for lower in range(lower, upper, step*page_bars):
                stop = min(upper, lower+step*page_bars)
                pages.append(dict(contract_code=symbol, period=period, start=lower,
                                  end_exclusive=stop, planned_bars=(stop-lower)//step))
    return pages


def backfill(client, store, *, sink=None, start=DEFAULT_START, end=None,
             dry_run=False, page_bars=500, clock=time.time) -> dict:
    now = int(clock())
    end = min(now-10, now if end is None else end)
    pages = backfill_plan(start=start, end=end, page_bars=page_bars)
    if dry_run:
        return dict(status='DRY_RUN', pages=pages, planned_pages=len(pages),
                    planned_bars=sum(p['planned_bars'] for p in pages), database_writes=0)
    require_backfill_confirmation()
    if sink is None:
        raise ValueError('backfill requires business sink')
    collector = ContinuousCollector(client, store, business_sink=sink, page_bars=page_bars, clock=clock)
    manifest = collector.collect(BUSINESS_KLINE_SYMBOLS, start=start, end=end, periods=BUSINESS_KLINE_PERIODS)
    payload = store.get(manifest)['payload']
    return dict(status=payload['integrity'], manifest_hash=manifest,
                failures=payload['failures'], counts=collector.business_counts)
