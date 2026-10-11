"""Independent, resumable HTX public collection; no account or order access."""
from __future__ import annotations

from datetime import datetime, timezone
from typing import Callable
from ..market_data import Candle, period_anchor_seconds, period_seconds
from .quality import audit
from .store import FileEvidenceStore

PERIODS = ('15min', '30min', '60min', '4hour', '1day')


class ContinuousCollector:
    def __init__(self, client, store: FileEvidenceStore, *, page_bars: int = 500,
                 clock: Callable[[], float] | None = None):
        import time
        if not 1 <= page_bars <= 2000:
            raise ValueError('page_bars outside HTX page bound')
        self.client, self.store, self.page_bars = client, store, page_bars
        self.clock = clock or time.time

    def page(self, symbol: str, period: str, start: int, end: int, *, source: str) -> str:
        step = period_seconds(period)
        anchor = period_anchor_seconds(period)
        if (start-anchor) % step or (end-anchor) % step or end <= start or end-start > step*self.page_bars:
            raise ValueError('invalid page interval')
        request = {'contract_code': symbol, 'period': period, 'from': start, 'to': end-step}
        observed = int(self.clock())
        if end > observed - 10:
            raise ValueError('page contains a bar not closed through grace boundary')
        try:
            envelope = self.client.history_kline_envelope(symbol, period, start=start, end=end-step)
        except Exception as exc:
            self.store.put('collection_failure', {'request': request, 'observed_at': observed,
                                                 'source': source, 'error': type(exc).__name__})
            raise
        raw = self.store.put('raw_kline', {'exchange': 'HTX', 'market_type': 'USDT_PERPETUAL',
            'endpoint': '/linear-swap-ex/market/history/kline', 'request': request,
            'observed_at': observed, 'source': source, 'response': envelope})
        rows = envelope.get('data', [])
        if envelope.get('status') != 'ok' or not isinstance(rows, list):
            rows = []
        quality = audit(rows, period, start=start, end=end)
        quality_ref = self.store.put('quality', quality, refs=(raw,))
        # Preserve duplicates/invalid raw input in raw object; normalized data
        # is only produced when syntax is valid, never used to mask audit failures.
        normalized = []
        for row in rows:
            try:
                bar = Candle.from_htx(row)
                normalized.append({'open_time': bar.open_time, 'canonical': bar.canonical(), 'bar': bar})
            except (ValueError, TypeError, ArithmeticError, KeyError):
                continue
        normalized.sort(key=lambda item: item['open_time'])
        window = self.store.put('kline_page', {'symbol': symbol, 'period': period,
            'start': start, 'end_exclusive': end, 'observed_at': observed, 'source': source,
            'timezone': 'UTC', 'candles': normalized, 'quality_ref': quality_ref,
            'raw_ref': raw, 'integrity': quality['integrity']}, refs=(raw, quality_ref))
        # Progress advances only after all objects and refs are durably indexed.
        if quality['integrity'] == 'COMPLETE':
            self.store.checkpoint(f'coverage:{symbol}:{period}:{start}:{end}', {'hash': window})
        return window

    def collect(self, symbols: tuple[str, ...], *, start: int, end: int,
                periods: tuple[str, ...] = PERIODS) -> str:
        refs, failures = [], []
        if not symbols or len(set(symbols)) != len(symbols) or not periods or len(set(periods)) != len(periods):
            raise ValueError('unique nonempty universe and periods required')
        for symbol in symbols:
            for period in periods:
                step = period_seconds(period)
                anchor = period_anchor_seconds(period)
                lower = ((start-anchor + step-1)//step)*step + anchor
                upper = ((end-anchor)//step)*step + anchor
                if lower >= upper:
                    failures.append({'symbol': symbol, 'period': period, 'reason': 'EMPTY_INTERVAL'})
                for page_start in range(lower, upper, step*self.page_bars):
                    page_end = min(upper, page_start+step*self.page_bars)
                    name = f'coverage:{symbol}:{period}:{page_start}:{page_end}'
                    cached = self.store.checkpoint(name)
                    try:
                        if cached:
                            key = cached['hash']
                            self.store.verify(key)
                        else:
                            key = self.page(symbol, period, page_start, page_end, source='HISTORICAL_BACKFILL')
                        refs.append(key)
                        if self.store.get(key)['payload']['integrity'] != 'COMPLETE':
                            failures.append({'symbol': symbol, 'period': period, 'start': page_start, 'reason': 'PARTIAL'})
                    except Exception as exc:
                        failures.append({'symbol': symbol, 'period': period, 'start': page_start,
                                         'reason': type(exc).__name__})
        return self.store.put('dataset_manifest', {'schema': 'htx-python-dataset-v1',
            'symbols': symbols, 'periods': periods, 'start': start, 'end_exclusive': end,
            'integrity': 'PARTIAL' if failures else 'COMPLETE', 'failures': failures,
            'pages': refs, 'version_selection': 'FIRST_OBSERVED_CLOSED',
            'created_at': datetime.now(timezone.utc)}, refs=tuple(refs))

    def tick(self, symbols: tuple[str, ...], *, periods: tuple[str, ...] = PERIODS) -> dict:
        """Overlap last two closed bars to preserve exchange revisions, resume gaps."""
        failures, refs = [], []
        now = int(self.clock())
        for symbol in symbols:
            for period in periods:
                step = period_seconds(period)
                anchor = period_anchor_seconds(period)
                end = ((now-10-anchor)//step)*step + anchor
                name = f'continuous:{symbol}:{period}'
                previous = self.store.checkpoint(name)
                start = min(previous['end'], end-2*step) if previous else end-2*step
                # Bound work per tick. Persistent cursor resumes a long outage.
                page_end = min(end, start+self.page_bars*step)
                try:
                    key = self.page(symbol, period, start, page_end, source='CONTINUOUS')
                    refs.append(key)
                    if self.store.get(key)['payload']['integrity'] == 'COMPLETE':
                        self.store.checkpoint(name, {'end': page_end, 'hash': key})
                    else:
                        failures.append(f'{symbol}:{period}:PARTIAL')
                except Exception as exc:
                    failures.append(f'{symbol}:{period}:{type(exc).__name__}')
        return {'status': 'DEGRADED' if failures else 'HEALTHY', 'pages': refs, 'failures': failures}
