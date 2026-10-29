"""PAPER-only stable candles/windows and resumable, bounded historical repair."""
from __future__ import annotations
from datetime import UTC, datetime
from threading import Event, Thread
import logging
from ..market_data import Candle, KlineWindow, period_seconds
from .quality import audit
from .store import canonical, digest


class CompactEvidence:
    def __init__(self, store):
        self.store = store
        with store._lock, store.db:
            store.db.execute('''CREATE TABLE IF NOT EXISTS compact_bars(
                symbol TEXT,period TEXT,bar_time INTEGER,hash TEXT,
                PRIMARY KEY(symbol,period,bar_time,hash))''')

    @staticmethod
    def stream(symbol, period):
        return f'compact:{symbol}:{period}'

    def failure(self, symbol, period, reason, raw=None):
        """One raw example per consecutive failure reason, plus bounded counters."""
        name = 'failure:' + self.stream(symbol, period)
        with self.store._lock:
            old = self.store.checkpoint(name) or {}
            timestamp = datetime.now(UTC).isoformat()
            if old.get('reason') != reason:
                ref = self.store.put('compact_data_anomaly', {
                    'symbol': symbol, 'period': period, 'reason': reason,
                    'observed_at': timestamp, 'response': raw})
                old = {'reason': reason, 'first_seen': timestamp, 'count': 0, 'raw_ref': ref}
            old.update(last_seen=timestamp, count=old['count'] + 1)
            self.store.checkpoint(name, old)

    def capture(self, window, envelope, *, observed_at, decision_as_of, history=False):
        symbol, period = window.contract_code, window.period
        if not window.is_complete:
            self.failure(symbol, period, 'INCOMPLETE_WINDOW', envelope)
            raise ValueError('incomplete compact window')
        name = self.stream(symbol, period)
        with self.store._lock:
            old = self.store.checkpoint(name) or {}
            content_id = digest([symbol, period, window.expected_size, window.window_hash])
            if not history and old.get('window_content') == content_id:
                # Full closure is verified again when this window is used by a decision.
                self.store.get(old['window_ref'])
                ref = old['window_ref']
            else:
                refs, changed, rows = [], False, []
                for bar in window.candles:
                    key = self.store.put('compact_candle', {
                        'exchange': 'HTX', 'contract_code': symbol, 'period': period, 'candle': bar})
                    prior = self.store.db.execute('SELECT hash FROM compact_bars WHERE symbol=? AND period=? AND bar_time=?',
                                                 (symbol, period, bar.open_time)).fetchall()
                    changed |= bool(prior and key not in {r[0] for r in prior})
                    refs.append(key)
                    rows.append((symbol, period, bar.open_time, key))
                ref = self.store.put('compact_kline_window', {
                    'contract_code': symbol, 'period': period, 'expected_size': window.expected_size,
                    'start': window.candles[0].open_time, 'end_exclusive': window.candles[-1].open_time + period_seconds(period),
                    'complete': True, 'candle_refs': refs}, refs=tuple(refs))
                if changed:
                    self.store.put('compact_kline_revision', {
                        'symbol': symbol, 'period': period, 'observed_at': observed_at,
                        'decision_as_of': decision_as_of, 'response': envelope}, refs=(ref,))
                with self.store.db:
                    self.store.db.executemany('INSERT OR IGNORE INTO compact_bars VALUES(?,?,?,?)', rows)
            start = window.candles[0].open_time
            end = window.candles[-1].open_time + period_seconds(period)
            # A later live window must not leap across a missing historical interval.
            coverage_end = old.get('coverage_end', end)
            if start <= coverage_end:
                coverage_end = max(coverage_end, end)
            update = {**old, 'coverage_start': old.get('coverage_start', start),
                'coverage_end': coverage_end, 'target_end': max(old.get('target_end', end), end),
                'first_seen': old.get('first_seen', observed_at), 'last_seen': observed_at,
                'observations': old.get('observations', 0) + 1}
            if not history:
                update.update(window_content=content_id, window_ref=ref)
            self.store.checkpoint(name, update)
            self.store.checkpoint('failure:' + name, {'reason': None, 'count': 0})
            return ref

    def retain_input(self, window):
        """Pin actual decision-time raw separately from the reusable stable window."""
        if not window.evidence_refs or window.evidence_status == 'FAILED':
            raise ValueError('decision input evidence unavailable')
        for key in window.evidence_refs:
            self.store.verify(key)
        if window.capture is None:
            return window.evidence_refs
        raw = self.store.put('compact_decision_raw', window.capture, refs=window.evidence_refs)
        return (*window.evidence_refs, raw)

    def repair_once(self, client, symbol, period, *, page_bars=500):
        """One page per stream/pass. Network outside lock; no invented missing bars."""
        if not 1 <= page_bars <= 2000:
            raise ValueError('invalid page bound')
        step = period_seconds(period)
        with self.store._lock:
            state = self.store.checkpoint(self.stream(symbol, period))
        if not state or state['coverage_end'] >= state['target_end']:
            return False
        start = state['coverage_end']
        end = min(state['target_end'], start + step * page_bars)
        envelope = client.history_kline_envelope(symbol, period, start=start, end=end-step)
        quality = audit(envelope.get('data', []), period, start=start, end=end)
        if envelope.get('status') != 'ok' or quality['integrity'] != 'COMPLETE':
            self.failure(symbol, period, 'HISTORY_GAP', envelope)
            return False
        candles = tuple(sorted((Candle.from_htx(row) for row in envelope['data']), key=lambda c: c.open_time))
        self.capture(KlineWindow(symbol, period, candles, len(candles)), envelope,
                     observed_at=int(datetime.now(UTC).timestamp()), decision_as_of=end, history=True)
        return True


class CompactBackfill:
    """Separate public client; serial repair, bounded per pass, no private credentials."""
    def __init__(self, compact, client, symbols, periods):
        self.compact, self.client = compact, client
        self.streams = tuple((s, p) for s in symbols for p in periods)
        self.stopped = Event()
        self.thread = Thread(target=self._run, name='htx-compact-backfill', daemon=True)

    def start(self):
        self.thread.start()

    def _run(self):
        try:
            while not self.stopped.is_set():
                for symbol, period in self.streams:
                    if self.stopped.is_set():
                        return
                    try:
                        self.compact.repair_once(self.client, symbol, period)
                    except Exception as error:
                        try:
                            self.compact.failure(symbol, period, 'REPAIR:' + type(error).__name__)
                        except Exception:
                            # No per-poll traceback flood on a full filesystem.
                            pass
                self.stopped.wait(60)
        finally:
            self.client.close()

    def close(self):
        self.stopped.set()
        # HTTP client has a bounded timeout; complete shutdown before closing the store.
        self.thread.join()


def load_window(store, key):
    """Reconstruct the exact ordered version used by a recorded decision."""
    from decimal import Decimal
    store.verify(key)
    obj = store.get(key)
    p = obj['payload']
    if obj['kind'] == 'compact_kline_window':
        rows = [store.get(ref)['payload']['candle'] for ref in p['candle_refs']]
    elif obj['kind'] == 'runtime_kline_window':
        rows = p['candles']
    else:
        raise ValueError('not a runtime window')
    def candle(row):
        return Candle(**{k: (Decimal(str(v)) if v is not None and k not in ('open_time', 'trade_count') else v)
                         for k, v in row.items()})
    return KlineWindow(p['contract_code'], p['period'], tuple(candle(r) for r in rows), p['expected_size'], (key,), 'COMPLETE')
