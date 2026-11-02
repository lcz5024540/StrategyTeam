"""Credential-free HTX stream, bounded handoff and explicit connection epochs."""
from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
import gzip
import json
import queue
import re
import threading
import time
import uuid
from typing import Any

from ..research.store import digest, primitive


@dataclass(frozen=True)
class MarketEvent:
    kind: str
    contract: str
    exchange_ms: int
    received_ms: int
    event_id: str
    epoch: str
    price: Decimal | None = None
    bid: Decimal | None = None
    ask: Decimal | None = None
    bid_size: Decimal | None = None
    ask_size: Decimal | None = None
    amount: Decimal | None = None
    raw: dict | None = None

    def fresh(self, now_ms: int, max_age_ms: int = 2000) -> bool:
        return (self.kind in {'TRADE', 'BBO'} and bool(self.epoch)
                and 0 <= now_ms-self.received_ms <= max_age_ms
                and 0 <= now_ms-self.exchange_ms <= max_age_ms)

    def dump(self) -> dict:
        return primitive(self)

    @classmethod
    def load(cls, value: dict) -> 'MarketEvent':
        value = dict(value)
        for key in ('price', 'bid', 'ask', 'bid_size', 'ask_size', 'amount'):
            if value.get(key) is not None:
                value[key] = Decimal(value[key])
        return cls(**value)


def _positive(value, *, zero=False):
    value = Decimal(str(value))
    if not value.is_finite() or (value < 0 if zero else value <= 0):
        raise ValueError('invalid market decimal')
    return value


def decode(raw: bytes | str, *, epoch: str, received_ms: int) -> tuple[list[MarketEvent], dict | None]:
    """No credentials, subscription ACKs are not market observations."""
    if isinstance(raw, bytes):
        raw = gzip.decompress(raw) if raw.startswith(b'\x1f\x8b') else raw
    value = json.loads(raw)
    if 'ping' in value:
        return [], {'pong': value['ping']}
    if value.get('status') == 'error' or value.get('err-code'):
        raise ValueError('HTX subscription error: '+str(value.get('err-code', value.get('err-msg'))))
    if 'ch' not in value:
        return [], None
    match = re.fullmatch(r'market\.([A-Z0-9]+-USDT)\.(trade\.detail|bbo)', value['ch'])
    if not match:
        return [], None
    code, channel = match.groups()
    tick = value['tick']
    if channel == 'bbo':
        bid, ask = _positive(tick['bid'][0]), _positive(tick['ask'][0])
        if bid > ask:
            raise ValueError('crossed market')
        return [MarketEvent('BBO', code, int(tick['ts']), received_ms,
                    str(tick.get('version', tick.get('mrid', tick['ts']))), epoch,
                    bid=bid, ask=ask, bid_size=_positive(tick['bid'][1], zero=True),
                    ask_size=_positive(tick['ask'][1], zero=True), raw=value)], None
    events = []
    # HTX batches can contain multiple fills. Sort only inside this already received packet.
    for trade in sorted(tick['data'], key=lambda x: (int(x['ts']), int(x['id']))):
        events.append(MarketEvent('TRADE', code, int(trade['ts']), received_ms,
                      str(trade['id']), epoch, price=_positive(trade['price']),
                      amount=_positive(trade['amount'], zero=True), raw={'ch': value['ch'], 'trade': trade}))
    return events, None


class EventOrder:
    """Monotone per-channel timestamps/IDs; versions need not be contiguous."""
    def __init__(self):
        self.seen: dict[tuple, tuple] = {}

    def accept(self, event: MarketEvent) -> bool:
        key = (event.epoch, event.contract, event.kind)
        ident = int(event.event_id) if event.event_id.isdigit() else event.event_id
        marker = (event.exchange_ms, str(ident).zfill(32))
        if marker <= self.seen.get(key, (-1, '')):
            return False
        self.seen[key] = marker
        return True

    def reset(self):
        self.seen.clear()


class PublicStream:
    """One reader thread only. Overflow poisons the connection instead of dropping quietly."""
    def __init__(self, contracts, *, url='wss://api.hbdm.com/linear-swap-ws', capacity=10000,
                 connector=None, clock=time.time):
        if not contracts or any(not re.fullmatch('[A-Z0-9]+-USDT', c) for c in contracts):
            raise ValueError('explicit HTX contracts required')
        if not url.startswith('wss://'):
            raise ValueError('TLS websocket required')
        self.contracts = tuple(dict.fromkeys(contracts))
        self.url, self.clock, self.connector = url, clock, connector
        self.events = queue.Queue(maxsize=capacity)
        self.stop_event = threading.Event()
        self.thread = None
        self.socket = None
        self.failure = None
        self.epoch = ''
        self.connected = False
        self.observed = set()
        self._status_lock = threading.Lock()

    def start(self):
        if self.thread is None:
            self.thread = threading.Thread(target=self._run, name='htx-public-realtime', daemon=True)
            self.thread.start()

    def _run(self):
        from websockets.sync.client import connect
        connector = self.connector or connect
        delay = 1
        while not self.stop_event.is_set():
            try:
                with connector(self.url, open_timeout=10, close_timeout=2, max_size=8*1024*1024) as socket:
                    self.socket = socket
                    self.epoch = uuid.uuid4().hex
                    self.observed.clear()
                    self.connected = True
                    for code in self.contracts:
                        for channel in ('trade.detail', 'bbo'):
                            socket.send(json.dumps({'sub': f'market.{code}.{channel}', 'id': f'{code}-{channel}'}))
                    order = EventOrder()
                    while not self.stop_event.is_set():
                        raw = socket.recv(timeout=15)
                        events, pong = decode(raw, epoch=self.epoch, received_ms=int(self.clock()*1000))
                        if pong:
                            socket.send(json.dumps(pong))
                        for event in events:
                            if event.contract not in self.contracts or not order.accept(event):
                                continue
                            self.events.put_nowait(event)
                            self.observed.add((event.contract, event.kind))
                        if events:
                            delay = 1
            except Exception as error:
                with self._status_lock:
                    self.failure = type(error).__name__
                    self.connected = False
                    self.epoch = ''
                # A consumer checks connection epoch as well as event freshness.
                while True:
                    try:
                        self.events.get_nowait()
                    except queue.Empty:
                        break
                self.stop_event.wait(delay)
                delay = min(30, delay*2)
            finally:
                self.connected = False
                self.socket = None

    def drain(self, limit=1000):
        result = []
        for _ in range(limit):
            try:
                result.append(self.events.get_nowait())
            except queue.Empty:
                break
        return result

    def close(self):
        self.stop_event.set()
        if self.socket is not None:
            self.socket.close()
        if self.thread is not None:
            self.thread.join(timeout=20)
