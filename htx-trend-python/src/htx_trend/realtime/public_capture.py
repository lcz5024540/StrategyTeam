"""Bounded public research capture. No worker, account, private read or order API.

The bundle supplies observed market inputs, not a fabricated baseline/latency.
Each normalized record keeps its raw response in the same hashed gzip segment.

Path-B (mdqueue-b) keeps the Path-A queue at 65536. A keepalive ping timeout
reconnects into a DEPTH.step0 snapshot plus BBO flood; that storm ran ~14
minutes and a ~2 minute buffer cannot absorb it. Relief is reconnect
throttling, a cheaper gzip/flush path, and a visible DEPTH catch-up window.
TRADE and BBO are never coalesced or downsampled. QUEUE_OVERFLOW stays
fail-closed: the rejected event is not queued and the process stops.
"""

from __future__ import annotations

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from decimal import Decimal as D, InvalidOperation
import fcntl
import gzip
import json
import logging
import os
from pathlib import Path

import queue
import re
import shutil
import signal
import threading
import time
import uuid

from ..htx_v5 import HtxV5Client
from ..market_data import Candle, KlineWindow, period_seconds
from .segment_codec import segment_events
from ..research.store import canonical, digest, primitive
from .feed import decode
from .storage import SegmentRecorder
from .ws_diagnostics import WsDiagnostics

GIB = 1024**3
CONTROL_RESERVE = 1024**2
CHANNELS = ("trade.detail", "bbo", "depth.step0")
# 4096 held ~6–7s at the observed 610–640 msg/s BBO/DEPTH rate and then
# fail-closed. 65536 is about 100s of mean-rate buffer. Path-A's batch drain
# did run on the recurrence and was not enough: keepalive → full DEPTH
# resubscribe stayed ahead of one consumer for ~14 min. Do not raise this
# queue as the fix (R1). Path-B throttles reconnects and sheds DEPTH only
# inside a marked catch-up window.
EVENT_QUEUE_MAXSIZE = 65536
EVENT_DRAIN_BATCH = 512
DRAIN_BATCH_MIN = 64
DRAIN_BATCH_MAX = 4096
FLUSH_INTERVAL_S = 10.0
FLUSH_INTERVAL_MIN_S = 1.0
FLUSH_INTERVAL_MAX_S = 60.0
# SegmentRecorder still defaults to gzip 9. Public capture pins 1: flush
# high-water on the recurrence was 802ms, and the segment hash is the
# canonical payload, so the level does not change identity. 3 remains
# available through HTX_PUBLIC_CAPTURE_GZIP_LEVEL when disk, not CPU, binds.
CAPTURE_GZIP_LEVEL = 1
GZIP_LEVEL_MIN = 1
GZIP_LEVEL_MAX = 9
SEAL_INTERVAL_S = 60.0
HEARTBEAT_INTERVAL_S = 10.0
QUEUE_HIGH_WATER_FRACTIONS = (0.5, 0.75, 0.9)
# At/above half the queue, skip flush/seal for several turns and drain a
# second batch. At/above 75%, take a wider batch. The deferral cap still
# forces a flush so a segment cannot sit in memory for the whole overload.
YIELD_WATER_FRACTION = 0.5
YIELD_HIGH_DRAIN_FRACTION = 0.75
YIELD_MAX_DEFERRALS = 8
HIGH_WATER_DRAIN_MULTIPLIER = 4
SLOW_IO_LOG_MS = 50.0
# Reconnect throttle. The old loop waited 1s then doubled to 30s and
# resubscribed every channel at 10/s (~16s for 156 subs), which is the
# keepalive → DEPTH+BBO storm. Attempts are at least MIN apart, backoff
# starts at INITIAL, and CONSECUTIVE connect failures open a circuit that
# pauses DEPTH until one session stays healthy.
RECONNECT_MIN_INTERVAL_S = 5.0
RECONNECT_BACKOFF_INITIAL_S = 5.0
RECONNECT_BACKOFF_MAX_S = 60.0
RECONNECT_FAILURE_CIRCUIT = 5
RECONNECT_CIRCUIT_OPEN_S = 120.0
RECONNECT_HEALTHY_S = 30.0
RESUBSCRIBE_INITIAL_DELAY_S = 2.0
RESUBSCRIBE_CHUNK = 12
RESUBSCRIBE_CHUNK_PAUSE_S = 1.0
RESUBSCRIBE_INTERVAL_S = 0.1
# Catch-up applies only after a reconnect. One buffered DEPTH per contract;
# a newer snapshot replaces the older one. After a snapshot is written, the
# same contract is not written again until MIN_INTERVAL; intermediates count
# as downsampled and the newest is what eventually lands. TRADE and every
# BBO stay on the main queue in full — this process has no core/non-core
# split, so every BBO is core.
DEPTH_CATCHUP_WINDOW_S = 45.0
DEPTH_CATCHUP_MAX_S = 180.0
DEPTH_CATCHUP_MIN_INTERVAL_MS = 1000
DEPTH_CATCHUP_POLICY = "DEPTH_STEP0_COALESCE_LATEST_AND_DOWNSAMPLE"
logger = logging.getLogger(__name__)
MISSING = [
    "INDEPENDENT_BASELINE_SCANS_AND_DECISIONS",
    "OBSERVED_ACCOUNT_FEES",
    "OBSERVED_ORDER_LATENCY",
    "OBSERVED_MARKET_CONTEXT",
    "VERIFIED_FUNDING_SETTLEMENTS",
    "DIAGNOSTIC_HOLDOUT_HISTORY",
]


class InvalidPublicMarketData(ValueError):
    """Rejected book payload, not a transport failure or a valid observation."""


def normalize_ws(value, epoch, received_ms):
    channel = value.get('ch', '')
    if isinstance(channel, str) and re.fullmatch(r'market\.[A-Z0-9]+-USDT\.(bbo|depth\.step0)', channel):
        try:
            return _normalize_ws(value, epoch, received_ms)
        except (KeyError, IndexError, TypeError, ValueError, InvalidOperation) as exc:
            # Only the pure public book decoder is isolated. Socket, queue and
            # storage failures retain their existing fail-closed paths.
            raise InvalidPublicMarketData(str(exc)) from exc
    return _normalize_ws(value, epoch, received_ms)


def _normalize_ws(value, epoch, received_ms):
    """depth.step0 is a full snapshot, never treated as an incremental book."""
    channel = value.get("ch", "")
    match = re.fullmatch(r"market\.([A-Z0-9]+-USDT)\.depth\.step0", channel)
    if not match:
        events, pong = decode(json.dumps(value), epoch=epoch, received_ms=received_ms)
        return [e.dump() for e in events], pong
    tick = value["tick"]
    sides = {}
    for name in ("bids", "asks"):
        sides[name] = [[str(D(str(p))), str(D(str(q)))] for p, q in tick[name]]
        if not sides[name] or any(
            not D(v).is_finite() or D(v) <= 0 for level in sides[name] for v in level
        ):
            raise ValueError("invalid depth snapshot")
    bids, asks = [D(x[0]) for x in sides["bids"]], [D(x[0]) for x in sides["asks"]]
    if bids != sorted(bids, reverse=True) or asks != sorted(asks) or bids[0] > asks[0]:
        raise ValueError("unordered/crossed depth snapshot")
    return [
        dict(
            kind="DEPTH",
            source="HTX_WS_FULL_DEPTH",
            contract=match[1],
            exchange_ms=int(tick["ts"]),
            received_ms=received_ms,
            epoch=epoch,
            event_id=str(tick["mrid"]),
            raw=value,
            **sides,
        )
    ], None


def normalize_window(code, period, raw, received_ms):
    by_time = {}
    for row in raw["data"]:
        candle = Candle.from_htx(row)
        if (candle.open_time + period_seconds(period)) * 1000 <= received_ms:
            by_time[candle.open_time] = candle
    candles = tuple(by_time[t] for t in sorted(by_time))[-160:]
    window = KlineWindow(code, period, candles, 160)
    if len(candles) != 160 or not window.is_complete:
        raise ValueError("incomplete closed candle window")
    seconds = period_seconds(period)
    expected_last_open = ((received_ms // 1000) // seconds - 1) * seconds
    if candles[-1].open_time != expected_last_open:
        raise ValueError("stale closed candle window")
    return dict(received_ms=received_ms, candles=primitive(candles))


def atomic_json(path, value, *, sync=True):
    raw = canonical(value)
    if len(raw) > CONTROL_RESERVE // 4:
        raise ValueError("control document exceeded reserve")
    temporary = path.with_suffix(".tmp")
    with temporary.open("wb") as f:
        f.write(raw)
        f.flush()
        # Heartbeats skip fsync. A process crash still sees the page cache;
        # the durable copy is the 60s full status and the final document.
        if sync:
            os.fsync(f.fileno())
    os.replace(temporary, path)


def _read_reference(root, ref):
    """Read one observation by immutable segment identity, without per-tick CAS files."""
    sha = ref.get("segment")
    index = ref.get("index")
    if (
        not isinstance(sha, str)
        or not re.fullmatch("[0-9a-f]{64}", sha)
        or type(index) is not int
        or index < 0
    ):
        raise ValueError("invalid capture reference")
    value = json.loads(
        gzip.decompress((Path(root) / "segments" / (sha + ".json.gz")).read_bytes())
    )
    if digest(value) != sha:
        raise ValueError("capture segment hash mismatch")
    events = segment_events(value)
    if index >= len(events):
        raise ValueError("capture index out of range")
    event = events[index]
    if digest(event) != ref.get("event_hash"):
        raise ValueError("capture observation hash mismatch")
    return event


def read_reference(root, ref):
    from contextlib import nullcontext
    from ..sealing.control import lease, safe_file
    segments = Path(root) / 'segments'
    with lease(segments) if (segments / '.sealing/config.json').exists() else nullcontext():
        if (segments / '.sealing/config.json').exists():
            sha = ref.get('segment')
            if not isinstance(sha, str) or not re.fullmatch('[0-9a-f]{64}', sha):
                raise ValueError('invalid capture reference')
            safe_file(segments, sha + '.json.gz')
        return _read_reference(root, ref)


def iter_records(root):
    """Stream a complete audit; cold history must be restored, never silently skipped."""
    from contextlib import nullcontext
    from ..sealing.control import Control, NeedsRestore
    root = Path(root) / 'segments'
    control = Control(root) if (root / '.sealing/config.json').exists() else None
    try:
        with control.lease() if control else nullcontext():
            if control:
                if control.db.execute('SELECT 1 FROM objects WHERE local=0').fetchone():
                    raise NeedsRestore('capture includes archived generations; restore before complete audit')
                records = (json.loads(r[0]) for r in control.db.execute('SELECT metadata FROM objects ORDER BY rowid'))
                yield from _iter_capture_records(root, records)
            else:
                with (root / 'segments.jsonl').open() as manifest:
                    yield from _iter_capture_records(root, (json.loads(line) for line in manifest))
    finally:
        if control:
            control.close()


def _iter_capture_records(root, records):
    for ref in records:
        value = json.loads(gzip.decompress((root / (ref['hash'] + '.json.gz')).read_bytes()))
        events = segment_events(value)
        if digest(value) != ref['hash'] or len(events) != ref['count']:
            raise ValueError('capture segment integrity failure')
        for index, event in enumerate(events):
            yield event, dict(segment=ref['hash'], index=index, event_hash=digest(event))


def audit(root, export=None):
    root = Path(root)
    counts, channels, frames = Counter(), Counter(), Counter()
    first = last = None
    output = gzip.open(export, "xb") if export else None
    try:
        for event, ref in iter_records(root):
            kind, code = event["kind"], event.get("contract")
            counts[kind] += 1
            if code:
                channels[code + ":" + kind] += 1
            if kind == "FRAME":
                frames[code] += 1
            at = event["received_ms"]
            first, last = min(first or at, at), max(last or at, at)
            if output:
                output.write(canonical(dict(observation=event, source_ref=ref)) + b"\n")
    finally:
        if output:
            output.close()
    config = json.loads((root / "capture.json").read_text())
    missing = {
        c: [
            k
            for k in ("FRAME", "TRADE", "BBO", "DEPTH", "FUNDING")
            if not channels[c + ":" + k]
        ]
        for c in config["contracts"]
    }
    return dict(
        status="PUBLIC_INPUT_ONLY_NOT_ELIGIBLE",
        counts=dict(counts),
        channels=dict(channels),
        missing_channels={c: v for c, v in missing.items() if v},
        first_received_ms=first,
        last_received_ms=last,
        remaining_requirements=MISSING,
        observed_runtime=config.get("observed_runtime"),
        trading=False,
    )


def _env_number(name, default, lo, hi, cast):
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        value = cast(raw.strip())
    except (TypeError, ValueError):
        logger.warning(
            "public capture ignored invalid %s=%s; using %s", name, raw, default
        )
        return default
    if value < lo or value > hi:
        clamped = hi if value > hi else lo
        logger.warning("public capture clamped %s=%s to %s", name, raw, clamped)
        return cast(clamped)
    return value


def read_gzip_level():
    return _env_number(
        "HTX_PUBLIC_CAPTURE_GZIP_LEVEL",
        CAPTURE_GZIP_LEVEL,
        GZIP_LEVEL_MIN,
        GZIP_LEVEL_MAX,
        int,
    )


def read_drain_batch():
    return _env_number(
        "HTX_PUBLIC_CAPTURE_DRAIN_BATCH",
        EVENT_DRAIN_BATCH,
        DRAIN_BATCH_MIN,
        DRAIN_BATCH_MAX,
        int,
    )


def read_flush_interval_s():
    return _env_number(
        "HTX_PUBLIC_CAPTURE_FLUSH_INTERVAL_S",
        FLUSH_INTERVAL_S,
        FLUSH_INTERVAL_MIN_S,
        FLUSH_INTERVAL_MAX_S,
        float,
    )


def drain_limit(depth, maxsize, base):
    """Widen the consumer batch once the queue is in the top quartile."""
    if maxsize and depth >= int(maxsize * YIELD_HIGH_DRAIN_FRACTION):
        return min(base * HIGH_WATER_DRAIN_MULTIPLIER, DRAIN_BATCH_MAX)
    return base


def consumer_extra_drain(depth, maxsize):
    """True when the consumer should take another batch before maintenance."""
    return bool(maxsize) and depth >= int(maxsize * YIELD_WATER_FRACTION)


def resubscribe_schedule(
    contracts,
    *,
    include_depth,
    chunk=RESUBSCRIBE_CHUNK,
    initial_delay=RESUBSCRIBE_INITIAL_DELAY_S,
    pace=RESUBSCRIBE_INTERVAL_S,
    chunk_pause=RESUBSCRIBE_CHUNK_PAUSE_S,
):
    """TRADE, then BBO, then DEPTH. DEPTH is omitted while the circuit pauses it.

    The first subscription waits ``initial_delay`` so the socket can settle.
    Every ``chunk`` subscriptions add ``chunk_pause`` on top of the 10/s pace,
    spreading the depth.step0 snapshot burst that follows a reconnect.
    """
    channels = ["trade.detail", "bbo"]
    if include_depth:
        channels.append("depth.step0")
    items = [(contract, channel) for channel in channels for contract in contracts]
    schedule = []
    cursor = initial_delay
    for index, (contract, channel) in enumerate(items):
        if index > 0 and chunk > 0 and index % chunk == 0:
            cursor += chunk_pause
        schedule.append((cursor, contract, channel))
        cursor += pace
    return schedule


class ReconnectGovernor:
    """Max reconnect rate plus a DEPTH pause after consecutive CONNECT failures.

    ``circuit`` is closed, open, or half_open. Open means the next attempt
    waits RECONNECT_CIRCUIT_OPEN_S and then comes up without DEPTH. DEPTH
    stays paused until the half-open session has observed the market for
    RECONNECT_HEALTHY_S. Failures of that half-open attempt re-open.
    """

    def __init__(self, clock=time.monotonic):
        self.clock = clock
        self.lock = threading.Lock()
        self.attempts = 0
        self.consecutive_failures = 0
        self.circuit = "closed"
        self.circuit_opens = 0
        self.depth_paused = False
        self._next_attempt_at = 0.0
        self._first_observation_at = None

    def seconds_until_attempt(self, now=None):
        now = self.clock() if now is None else now
        with self.lock:
            return max(0.0, self._next_attempt_at - now)

    def begin_attempt(self, now=None):
        now = self.clock() if now is None else now
        with self.lock:
            self.attempts += 1
            if self.circuit == "open":
                self.circuit = "half_open"
            self._first_observation_at = None
            self._next_attempt_at = max(
                self._next_attempt_at, now + RECONNECT_MIN_INTERVAL_S
            )
            return self.circuit

    def note_failure(self, now=None):
        now = self.clock() if now is None else now
        with self.lock:
            self.consecutive_failures += 1
            self._first_observation_at = None
            if (
                self.circuit == "half_open"
                or self.consecutive_failures >= RECONNECT_FAILURE_CIRCUIT
            ):
                newly_opened = self.circuit != "open"
                self.circuit = "open"
                self.depth_paused = True
                if newly_opened:
                    self.circuit_opens += 1
                self._next_attempt_at = now + RECONNECT_CIRCUIT_OPEN_S
                return "opened" if newly_opened else "open"
            delay = RECONNECT_BACKOFF_INITIAL_S * (2 ** (self.consecutive_failures - 1))
            delay = min(max(delay, RECONNECT_MIN_INTERVAL_S), RECONNECT_BACKOFF_MAX_S)
            self._next_attempt_at = now + delay
            return "backoff"

    def note_observation(self, now=None):
        now = self.clock() if now is None else now
        with self.lock:
            if self._first_observation_at is None:
                self._first_observation_at = now

    def poll_health(self, now=None):
        now = self.clock() if now is None else now
        with self.lock:
            started = self._first_observation_at
            if started is None or now - started < RECONNECT_HEALTHY_S:
                return False
            self.consecutive_failures = 0
            self.circuit = "closed"
            self.depth_paused = False
            return True

    def snapshot(self):
        with self.lock:
            return dict(
                reconnect_attempts=self.attempts,
                reconnect_consecutive_failures=self.consecutive_failures,
                reconnect_circuit=self.circuit,
                reconnect_circuit_opens=self.circuit_opens,
                depth_paused=self.depth_paused,
            )


class DepthCatchup:
    """Reconnect-window buffer for DEPTH.step0 only.

    TRADE and BBO must not be passed to ``offer`` as something to drop; the
    caller leaves them on the main queue. While the window is active there is
    at most one buffered DEPTH per contract. Replacing a snapshot that has
    not been written counts as a coalesce, unless a snapshot for that contract
    was written inside DEPTH_CATCHUP_MIN_INTERVAL_MS — those replacements
    count as downsample. The buffered event is always the newest. ``end``
    returns that newest snapshot plus a GAP marker so the loss is visible.
    """

    def __init__(self):
        self.lock = threading.Lock()
        self.active = False
        self.extended = False
        self.started_mono = 0.0
        self.epoch = None
        self.buffered = {}
        self.last_release_ms = {}
        self.coalesced = 0
        self.downsampled = 0
        self._window_coalesced = 0
        self._window_downsampled = 0

    def begin(self, now_ms, now_mono, epoch):
        with self.lock:
            self.active = True
            self.extended = False
            self.started_mono = now_mono
            self.epoch = epoch
            self._window_coalesced = 0
            self._window_downsampled = 0
            # Interval is per window. A new reconnect may write one fresh
            # snapshot immediately instead of inheriting the previous pace.
            self.last_release_ms.clear()

    def _within_interval(self, contract, now_ms):
        last = self.last_release_ms.get(contract)
        return last is not None and now_ms - last < DEPTH_CATCHUP_MIN_INTERVAL_MS

    def offer(self, event, now_ms):
        with self.lock:
            contract = event.get("contract")
            if not self.active or event.get("kind") != "DEPTH" or not contract:
                return "passthrough"
            current = self.buffered.get(contract)
            if current is not None:
                self.buffered[contract] = event
                if self._within_interval(contract, now_ms):
                    self.downsampled += 1
                    self._window_downsampled += 1
                    return "downsampled"
                self.coalesced += 1
                self._window_coalesced += 1
                return "coalesced"
            self.buffered[contract] = event
            return "held" if self._within_interval(contract, now_ms) else "buffered"

    def release(self, now_ms, limit):
        with self.lock:
            ready = []
            if limit <= 0:
                return ready
            for contract, event in list(self.buffered.items()):
                if len(ready) >= limit:
                    break
                if self._within_interval(contract, now_ms):
                    continue
                ready.append(event)
                del self.buffered[contract]
                self.last_release_ms[contract] = now_ms
            return ready

    def end(self, now_ms):
        with self.lock:
            if not self.active and not self.buffered:
                return None, None
            was_active = self.active
            self.active = False
            leftover = list(self.buffered.values())
            self.buffered.clear()
            for event in leftover:
                contract = event.get("contract")
                if contract:
                    self.last_release_ms[contract] = now_ms
            if not was_active:
                return leftover, None
            marker = dict(
                kind="GAP",
                received_ms=now_ms,
                reason="DEPTH_CATCHUP_END",
                epoch=self.epoch,
                coalesced=self._window_coalesced,
                downsampled=self._window_downsampled,
                policy=DEPTH_CATCHUP_POLICY,
            )
            self._window_coalesced = 0
            self._window_downsampled = 0
            self.extended = False
            return leftover, marker


class Capture:
    def __init__(
        self,
        output,
        contracts,
        *,
        max_bytes=2 * GIB,
        min_free_bytes=5 * GIB,
        seconds=86400,
        observed_runtime=None,
        clock=time.time,
        segmented=False,
        resume=False,
    ):
        if not 1 <= seconds <= 86400 or not CONTROL_RESERVE < max_bytes <= 2 * GIB:
            raise ValueError("capture duration/cap exceeds approved bounds")
        if (
            not contracts
            or len(contracts) > 100
            or any(not re.fullmatch("[A-Z0-9]+-USDT", c) for c in contracts)
        ):
            raise ValueError("invalid explicit contract pool")
        self.root, self.contracts = Path(output).absolute(), tuple(dict.fromkeys(contracts))
        if resume and not segmented:
            raise ValueError('resume requires explicit segmented storage')
        existed = self.root.exists()
        if self.root.resolve() != self.root:
            raise ValueError('symlink capture root')
        self.root.mkdir(parents=True, exist_ok=resume)
        lock_path = self.root / 'capture.lock'
        if lock_path.is_symlink():
            raise ValueError('symlink capture lock')
        self.lock = lock_path.open('a')
        try:
            fcntl.flock(self.lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            previous = None
            if existed:
                previous = json.loads((self.root / 'capture.json').read_bytes())
                expected = dict(schema='htx-public-market-bundle-v1',contracts=list(self.contracts),
                                max_bytes=max_bytes,min_free_bytes=min_free_bytes,channels=list(CHANNELS),
                                observed_runtime=observed_runtime,trading=False)
                if any(previous.get(k) != v for k,v in expected.items()):
                    raise ValueError('resume capture configuration changed')
                if not (self.root / 'segments/.sealing/config.json').exists():
                    raise ValueError('resume requires enrolled segmented source')
        except BaseException:
            self.lock.close()
            raise
        self.max_bytes, self.min_free_bytes, self.seconds, self.clock = (
            max_bytes,
            min_free_bytes,
            seconds,
            clock,
        )
        self.stop = threading.Event()
        self.events = queue.Queue(maxsize=EVENT_QUEUE_MAXSIZE)
        self.drain_batch_limit = read_drain_batch()
        self.flush_interval_s = read_flush_interval_s()
        self.gzip_level = read_gzip_level()
        self.reconnect = ReconnectGovernor()
        self.catchup = DepthCatchup()
        self._monotonic = time.monotonic
        self.ws_diagnostics = WsDiagnostics(self.contracts)
        self.diagnostic_errors = 0
        self.diagnostic_events_dropped = 0
        self.queue_depth_high_water = 0
        self._queue_warn_level = 0
        self._queue_hw_lock = threading.Lock()
        self.last_flush_ms = None
        self.last_seal_ms = None
        self.flush_ms_high_water = 0.0
        self.seal_ms_high_water = 0.0
        self._flush_at = 0.0
        self._seal_at = 0.0
        self._heartbeat_at = 0.0
        self._maintenance_deferred = 0
        self._seal_due = False
        self.failure = None
        self.socket = None
        self.counts, self.channels = Counter(), Counter()
        self.last_seen, self.errors = {}, Counter()
        self.invalid_market_messages = Counter()
        self._invalid_market_log_at = {}
        self.recorder = SegmentRecorder(
            self.root / "segments",
            max_bytes=max_bytes - CONTROL_RESERVE,
            gzip_level=self.gzip_level,
            json_ready=True,
            segmented=segmented,
            segment_contract="capture-public",
        )
        self.client = HtxV5Client()  # no credentials, no environment loading
        self.rate_lock, self.next_request = threading.Lock(), 0.0
        self.started_ms = self.now()
        self.specs = {}
        if not previous:
            atomic_json(
                self.root / "capture.json",
                dict(
                    schema="htx-public-market-bundle-v1",
                    contracts=self.contracts,
                    max_bytes=max_bytes,
                    min_free_bytes=min_free_bytes,
                    max_seconds=seconds,
                    started_ms=self.started_ms,
                    pid=os.getpid(),
                    channels=CHANNELS,
                    observed_runtime=observed_runtime,
                    trading=False,
                    funding_poll_seconds=60,
                    frame_refresh_seconds=900,
                    rest_max_requests_per_second=4,
                    missing_requirements=MISSING,
                ),
            )

    def now(self):
        return int(self.clock() * 1000)

    def _diagnose(self, method, *args):
        try:
            return getattr(self.ws_diagnostics, method)(*args)
        except Exception:
            # Diagnostics must not terminate capture or replace its original error.
            self.diagnostic_errors += 1
            return None

    @contextmanager
    def _ws_operation(self, phase):
        self._diagnose("start", phase, self._monotonic())
        try:
            yield
        finally:
            self._diagnose("end", phase, self._monotonic())

    def _diagnostic_log(self, level, event):
        try:
            logger.log(level, "public capture ws %s", json.dumps(event, separators=(",", ":"), allow_nan=False))
        except Exception:
            self.diagnostic_errors += 1

    def emit_diagnostic(self, event):
        # Optional markers must never displace market data or trigger fail-closed.
        # Keep one full consumer batch of headroom; original market emit is unchanged.
        if self.events.qsize() >= max(0, self.events.maxsize - EVENT_DRAIN_BATCH):
            self.diagnostic_events_dropped += 1
            return
        try:
            self.events.put_nowait(event)
        except queue.Full:
            self.diagnostic_events_dropped += 1

    def _ws_runtime_snapshot(self, at, mono):
        with self.events.mutex:
            oldest = self.events.queue[0].get("received_ms") if self.events.queue else None
            depth = len(self.events.queue)
        delay = self.reconnect.seconds_until_attempt(mono)
        return dict(
            queue_depth=depth, queue_maxsize=self.events.maxsize,
            queue_oldest_received_age_ms=max(0, at - oldest) if oldest is not None else None,
            queue_age_clock="WALL_RECEIVED_MS_NOT_ENQUEUE_TIME",
            queue_depth_high_water=self.queue_depth_high_water,
            last_flush_ms=self.last_flush_ms, last_seal_ms=self.last_seal_ms,
            flush_ms_high_water=self.flush_ms_high_water, seal_ms_high_water=self.seal_ms_high_water,
            backoff_seconds=delay, next_retry_at_ms=at + round(delay * 1000),
            failure_count_scope="CONNECT_AND_ESTABLISHED_SESSION_ERRORS",
            catchup_active=self.catchup.active, **self.reconnect.snapshot(),
        )

    def _ws_failure_diagnostic(self, error):
        try:
            at, mono = self.now(), self._monotonic()
            runtime = self._ws_runtime_snapshot(at, mono)
            diagnostic = self._diagnose("failure", error, at, mono, self.socket, runtime)
            if diagnostic is not None:
                self._diagnostic_log(logging.WARNING, diagnostic)
                return diagnostic
        except Exception:
            self.diagnostic_errors += 1
        fallback = dict(schema="public-ws-diagnostics-v1", diagnostic_unavailable=True,
                        diagnostic_errors=self.diagnostic_errors,
                        exception_type=type(error).__name__, epoch=self.ws_diagnostics.epoch,
                        attempt=self.reconnect.attempts)
        self._diagnostic_log(logging.WARNING, fallback)
        return fallback

    def emit(self, event):
        if event.get("kind") == "DEPTH":
            # Outside the reconnect window this is a passthrough and the
            # event still fail-closes the queue. Inside it, DEPTH is held
            # per contract so a snapshot storm cannot be the overflow.
            action = self.catchup.offer(event, event.get("received_ms", self.now()))
            if action != "passthrough":
                return
        try:
            self.events.put_nowait(event)
        except queue.Full:
            # Fail closed: the event is not queued and the process stops.
            # Do not block the producer and do not drop quietly.
            self._observe_queue_depth(self.events.maxsize)
            self.failure = "QUEUE_OVERFLOW"
            self.stop.set()
            logger.error(
                "public capture QUEUE_OVERFLOW fail-closed high_water=%s maxsize=%s last_flush_ms=%s last_seal_ms=%s",
                self.queue_depth_high_water,
                self.events.maxsize,
                self.last_flush_ms,
                self.last_seal_ms,
            )
            return
        self._observe_queue_depth(self.events.qsize())

    def open_catchup(self, epoch):
        """Start (or restart) the reconnect DEPTH window and mark it."""
        if self.catchup.active:
            self.close_catchup()
        self.catchup.begin(self.now(), self._monotonic(), epoch)
        self.emit(
            dict(
                kind="GAP",
                received_ms=self.now(),
                reason="DEPTH_CATCHUP_BEGIN",
                epoch=epoch,
                policy=DEPTH_CATCHUP_POLICY,
                preserve="TRADE_AND_BBO",
                min_interval_ms=DEPTH_CATCHUP_MIN_INTERVAL_MS,
            )
        )

    def close_catchup(self):
        """Write the newest unsent DEPTH snapshots and the loss marker."""
        leftover, marker = self.catchup.end(self.now())
        if leftover is None and marker is None:
            return
        for event in leftover or ():
            self.emit(event)
        if marker:
            self.emit(marker)

    def poll_catchup(self, now_mono):
        """End the window, or extend it once while the queue is still high.

        Extension is capped by DEPTH_CATCHUP_MAX_S so a stuck backlog cannot
        downsample DEPTH for the rest of the process. Both edges emit a GAP.
        """
        if not self.catchup.active:
            return
        elapsed = now_mono - self.catchup.started_mono
        if elapsed >= DEPTH_CATCHUP_MAX_S:
            self.close_catchup()
            return
        if elapsed < DEPTH_CATCHUP_WINDOW_S:
            return
        high = self.events.qsize() >= int(
            (self.events.maxsize or EVENT_QUEUE_MAXSIZE) * YIELD_HIGH_DRAIN_FRACTION
        )
        if high and not self.catchup.extended:
            self.catchup.extended = True
            self.emit(
                dict(
                    kind="GAP",
                    received_ms=self.now(),
                    reason="DEPTH_CATCHUP_EXTENDED",
                    epoch=self.catchup.epoch,
                    queue_depth=self.events.qsize(),
                    policy=DEPTH_CATCHUP_POLICY,
                )
            )
            return
        if high:
            return
        self.close_catchup()

    def _observe_queue_depth(self, depth):
        if depth <= self.queue_depth_high_water:
            return
        crossed = None
        with self._queue_hw_lock:
            if depth <= self.queue_depth_high_water:
                return
            self.queue_depth_high_water = depth
            limit = self.events.maxsize
            for fraction in QUEUE_HIGH_WATER_FRACTIONS:
                mark = int(limit * fraction)
                if mark and self._queue_warn_level < mark <= depth:
                    self._queue_warn_level = mark
                    crossed = (depth, limit)
        if crossed:
            seen, limit = crossed
            logger.warning(
                "public capture queue high-water depth=%s high_water=%s maxsize=%s last_flush_ms=%s last_seal_ms=%s",
                seen,
                self.queue_depth_high_water,
                limit,
                self.last_flush_ms,
                self.last_seal_ms,
            )

    def get(self, path, params):
        # Fixed public allowlist: arbitrary URLs/private paths never enter this tool.
        if path not in {
            "/linear-swap-api/v1/swap_contract_info",
            "/linear-swap-api/v1/swap_funding_rate",
            "/linear-swap-ex/market/history/kline",
        }:
            raise ValueError("non-public capture path")
        with self.rate_lock:
            if self.stop.wait(max(0, self.next_request - time.monotonic())):
                raise InterruptedError("capture stopped")
            self.next_request = time.monotonic() + 0.25
        before = self.now()
        value = self.client._public_get(path, params)
        return value, before, self.now()

    def load_specifications(self):
        raw, requested, received = self.get(
            "/linear-swap-api/v1/swap_contract_info", {"business_type": "swap"}
        )
        self.specs = {
            r["contract_code"]: r
            for r in raw["data"]
            if r["contract_code"] in self.contracts
        }
        self.emit(
            dict(
                kind="CONTRACT_METADATA",
                received_ms=received,
                requested_ms=requested,
                raw=raw,
            )
        )

    def rest_symbol(self, code, frame):
        try:
            if frame:
                spec = self.specs[code]
                windows, sources = {}, {}
                for period in ("15min", "60min", "4hour"):
                    raw, requested, received = self.get(
                        "/linear-swap-ex/market/history/kline",
                        {"contract_code": code, "period": period, "size": "200"},
                    )
                    windows[period] = normalize_window(code, period, raw, received)
                    sources[period] = dict(
                        requested_ms=requested, received_ms=received, response=raw
                    )
                self.emit(
                    dict(
                        kind="FRAME",
                        contract=code,
                        received_ms=self.now(),
                        windows=windows,
                        spec=dict(
                            contract_code=code,
                            contract_size=str(spec["contract_size"]),
                            price_tick=str(spec["price_tick"]),
                            contract_status=spec["contract_status"],
                        ),
                        raw_windows=sources,
                        raw_spec=spec,
                    )
                )
            raw, requested, received = self.get(
                "/linear-swap-api/v1/swap_funding_rate", {"contract_code": code}
            )
            value = raw["data"]
            self.emit(
                dict(
                    kind="FUNDING",
                    contract=code,
                    received_ms=received,
                    requested_ms=requested,
                    rate=str(value["funding_rate"]),
                    funding_time=value.get("funding_time"),
                    raw=raw,
                )
            )
            return True
        except Exception as error:
            if not self.stop.is_set():
                self.emit(
                    dict(
                        kind="SOURCE_ERROR",
                        contract=code,
                        received_ms=self.now(),
                        source="FRAME_OR_FUNDING",
                        error=type(error).__name__,
                    )
                )
            return False

    def rest_loop(self):
        jobs, frame_bucket, next_poll = {}, {}, {}
        with ThreadPoolExecutor(
            max_workers=4, thread_name_prefix="public-rest"
        ) as pool:
            while not self.stop.is_set():
                now = self.clock()
                for code, (future, bucket) in list(jobs.items()):
                    if future.done():
                        if future.result():
                            frame_bucket[code] = bucket
                        next_poll[code] = now + 60
                        del jobs[code]
                for code in self.contracts:
                    if len(jobs) >= 4:
                        break
                    if code in jobs or now < next_poll.get(code, 0):
                        continue
                    bucket = int((now - 10) // 900)
                    jobs[code] = (
                        pool.submit(
                            self.rest_symbol, code, frame_bucket.get(code) != bucket
                        ),
                        bucket,
                    )
                self.stop.wait(0.1)

    def _ws_connect(self):
        from websockets.sync.client import connect

        return connect

    def _send_subscription(self, sock, step):
        _offset, contract, channel = step
        with self._ws_operation("subscribe"):
            sock.send(
                json.dumps({"sub": f"market.{contract}.{channel}", "id": f"{contract}:{channel}"})
            )

    def stream_loop(self):
        connect = self._ws_connect()
        while not self.stop.is_set():
            delay = self.reconnect.seconds_until_attempt(self._monotonic())
            if delay > 0 and self.stop.wait(delay):
                return
            if self.stop.is_set():
                return
            epoch = uuid.uuid4().hex
            self.reconnect.begin_attempt(self._monotonic())
            gap_started_ms = self.now()
            self._diagnose("begin_attempt", epoch, self.reconnect.attempts, gap_started_ms, self._monotonic())
            self.emit(
                dict(
                    kind="GAP",
                    received_ms=gap_started_ms,
                    reason="CONNECT_OR_RECONNECT",
                    epoch=epoch,
                    circuit=self.reconnect.circuit,
                    attempt=self.reconnect.attempts,
                )
            )
            if self.reconnect.depth_paused:
                self.emit(
                    dict(
                        kind="GAP",
                        received_ms=self.now(),
                        reason="DEPTH_SUBSCRIPTION_PAUSED",
                        epoch=epoch,
                        consecutive_failures=self.reconnect.consecutive_failures,
                    )
                )
            try:
                with self._ws_operation("connect"):
                    connection = connect(
                        "wss://api.hbdm.com/linear-swap-ws",
                        open_timeout=10,
                        close_timeout=2,
                        max_size=8 * 1024**2,
                    )
                with connection as sock:
                    self._pump_socket(sock, epoch, gap_started_ms)
                    self._diagnose("start", "close", self._monotonic())
                self._diagnose("end", "close", self._monotonic())
            except Exception as error:
                if not self.stop.is_set():
                    transition = self.reconnect.note_failure(self._monotonic())
                    diagnostic = self._ws_failure_diagnostic(error)
                    self.emit(
                        dict(
                            kind="GAP",
                            received_ms=self.now(),
                            reason=type(error).__name__,
                            epoch=epoch,
                            diagnostic=diagnostic,
                        )
                    )
                    if transition == "opened":
                        logger.warning(
                            "public capture reconnect circuit open failures=%s pause_s=%s",
                            self.reconnect.consecutive_failures,
                            RECONNECT_CIRCUIT_OPEN_S,
                        )
                        self.emit(
                            dict(
                                kind="GAP",
                                received_ms=self.now(),
                                reason="RECONNECT_CIRCUIT_OPEN",
                                epoch=epoch,
                                consecutive_failures=self.reconnect.consecutive_failures,
                                depth_paused=True,
                            )
                        )
            finally:
                self.socket = None
                self.close_catchup()

    def _pump_socket(self, sock, epoch, gap_started_ms):
        """Subscribe TRADE+BBO first, DEPTH last, and pace the burst.

        A subscription send counts as liveness. Chunked resubscribe is longer
        than 15s on the full contract set; treating that pace as a dead socket
        would reconnect in the middle of the snapshot storm.
        """
        self.socket = sock
        self._diagnose("connected", self._monotonic())
        depth_was_paused = self.reconnect.depth_paused
        self.open_catchup(epoch)
        steps = resubscribe_schedule(
            self.contracts, include_depth=not depth_was_paused
        )
        step_i = 0
        follow = []
        follow_i = 0
        follow_origin = None
        origin = self._monotonic()
        last = origin
        recovered = False
        while not self.stop.is_set():
            now_m = self._monotonic()
            self.poll_catchup(now_m)
            if (
                depth_was_paused
                and follow_origin is None
                and self.reconnect.poll_health(now_m)
            ):
                follow = [
                    row
                    for row in resubscribe_schedule(
                        self.contracts, include_depth=True
                    )
                    if row[2] == "depth.step0"
                ]
                follow_origin = now_m
                self.open_catchup(epoch)
            if step_i < len(steps) and now_m >= origin + steps[step_i][0]:
                self._send_subscription(sock, steps[step_i])
                step_i += 1
                last = now_m
            if (
                follow_origin is not None
                and follow_i < len(follow)
                and now_m >= follow_origin + follow[follow_i][0]
            ):
                self._send_subscription(sock, follow[follow_i])
                follow_i += 1
                last = now_m
            pending_at = []
            if step_i < len(steps):
                pending_at.append(origin + steps[step_i][0])
            if follow_origin is not None and follow_i < len(follow):
                pending_at.append(follow_origin + follow[follow_i][0])
            timeout = max(0.001, min(pending_at) - now_m) if pending_at else 1
            try:
                with self._ws_operation("recv"):
                    raw = sock.recv(timeout=timeout)
            except TimeoutError:
                if self._monotonic() - last > 15:
                    raise
                continue
            last = self._monotonic()
            self._diagnose("raw_received", self.now(), last)
            with self._ws_operation("decode"):
                value = json.loads(
                    gzip.decompress(raw)
                    if isinstance(raw, bytes) and raw[:2] == b"\x1f\x8b"
                    else raw
                )
                received = self.now()
                if value.get("status") == "error":
                    self.emit(
                        dict(
                            kind="SOURCE_ERROR",
                            received_ms=received,
                            source="SUBSCRIPTION",
                            raw=value,
                        )
                    )
                    continue  # one unavailable symbol does not erase the other 51
                try:
                    observations, pong = normalize_ws(value, epoch, received)
                except InvalidPublicMarketData as exc:
                    self.record_invalid_book(value, exc, epoch, received)
                    continue
            if pong:
                self._diagnose("htx_ping", received, self._monotonic())
                with self._ws_operation("pong_send"):
                    sock.send(json.dumps(pong))
                self._diagnose("htx_pong", self.now(), self._monotonic())
            for event in observations:
                self.emit(event)
                if not self.stop.is_set() and not self.failure:
                    marker = self._diagnose("observe", event, self._monotonic())
                    if marker is not None:
                        self.emit_diagnostic(marker)
                        self._diagnostic_log(logging.INFO, marker)
            if observations:
                if not recovered and not self.stop.is_set() and not self.failure:
                    # First market observation in this connection attempt;
                    # this does not certify all channels or recover lost data.
                    recovered_ms = self.now()
                    self.emit(
                        dict(
                            kind="GAP_RECOVERED",
                            received_ms=recovered_ms,
                            gap_started_ms=gap_started_ms,
                            gap_ms=max(0, recovered_ms - gap_started_ms),
                            epoch=epoch,
                        )
                    )
                    recovered = True
                self.reconnect.note_observation(self._monotonic())

    def record_invalid_book(self, value, error, epoch, received):
        channel = value['ch']
        code = channel.split('.')[1]
        key = channel if code in self.contracts else 'UNSUBSCRIBED'
        self.invalid_market_messages[key] += 1
        now = self._monotonic()
        if now - self._invalid_market_log_at.get(key, float('-inf')) < 60:
            return  # Full count remains visible; at most one raw sample/min/channel.
        self._invalid_market_log_at[key] = now
        raw = json.dumps(value, ensure_ascii=False)
        try:
            json.dumps(value, allow_nan=False)
            sample = value if len(raw) <= 4096 else {'truncated': True, 'sample': raw[:4096]}
        except ValueError:
            # json.loads accepts NaN/Infinity (including an overflowing 1e999).
            # Keep a labelled text sample, never poison strict recorder JSON.
            sample = {'non_json_numeric': True, 'parsed_sample': raw[:4096]}
        self.emit(dict(kind='SOURCE_ERROR', source='INVALID_PUBLIC_BOOK',
                       channel=channel, epoch=epoch, received_ms=received,
                       error_type=type(error.__cause__).__name__, message=str(error)[:256],
                       rejected_count=self.invalid_market_messages[key],
                       raw=sample))

    def write(self, event):
        if not self.recorder.append(event):
            self.failure = self.recorder.failure
            self.stop.set()
            return
        kind, code = event["kind"], event.get("contract")
        self.counts[kind] += 1
        if code:
            key = code + ":" + kind
            self.channels[key] += 1
            self.last_seen[key] = event["received_ms"]
        if kind in ("SOURCE_ERROR", "GAP"):
            self.errors[event.get("source", event.get("reason", "unknown"))] += 1

    def drain_batch(self, limit=None):
        """Take one timed get, then up to limit-1 waiting events.

        The timed get only covers an idle queue. When a backlog is present
        that first get returns immediately and the rest of the batch is
        pulled with get_nowait before any append, so a flush or seal does
        not run between individual events.
        """
        if limit is None:
            limit = drain_limit(
                self.events.qsize(), self.events.maxsize, self.drain_batch_limit
            )
        if limit < 1:
            raise ValueError("drain batch must be positive")
        batch = []
        try:
            batch.append(self.events.get(timeout=0.1))
        except queue.Empty:
            pass
        while len(batch) < limit:
            try:
                batch.append(self.events.get_nowait())
            except queue.Empty:
                break
        # Queue events (TRADE, BBO, GAP) fill the batch first. Catch-up DEPTH
        # only uses the room that is left, and only when its interval allows.
        room = limit - len(batch)
        if room > 0:
            batch.extend(self.catchup.release(self.now(), room))
        if batch:
            self._observe_queue_depth(self.events.qsize() + len(batch))
        return batch

    def write_batch(self, events):
        for event in events:
            self.write(event)
            if self.recorder.failure:
                return False
        return True

    def _timed(self, kind, fn):
        started = time.monotonic()
        try:
            return fn()
        finally:
            elapsed = round((time.monotonic() - started) * 1000.0, 3)
            if kind == "flush":
                self.last_flush_ms = elapsed
                if elapsed > self.flush_ms_high_water:
                    self.flush_ms_high_water = elapsed
            else:
                self.last_seal_ms = elapsed
                if elapsed > self.seal_ms_high_water:
                    self.seal_ms_high_water = elapsed
            if kind == "seal" or elapsed >= SLOW_IO_LOG_MS:
                logger.info(
                    "public capture %s duration_ms=%s queue_depth=%s high_water=%s",
                    kind,
                    elapsed,
                    self.events.qsize(),
                    self.queue_depth_high_water,
                )

    def publish_status(self, *, detail="full", sync=True, final=False):
        body = self.status(final=final, detail=detail)
        atomic_json(
            self.root / "status.json",
            body,
            sync=sync,
        )
        if detail == "full" or final:
            self._diagnostic_log(logging.INFO, dict(
                kind="WS_DIAGNOSTIC_SUMMARY", ws=body["ws_diagnostics"],
                queue_depth=body["queue_depth"], last_flush_ms=self.last_flush_ms,
                last_seal_ms=self.last_seal_ms,
            ))

    def maintain(self, now=None):
        """Flush often; seal and the full channel map on a slower cadence.

        Gzip flush and segment_boundary are not run in the same turn. A short
        backlog defers maintenance one turn. At or above half the queue the
        consumer keeps draining for up to YIELD_MAX_DEFERRALS turns, then
        flushes anyway. Returns False when the recorder has failed.
        """
        now = time.monotonic() if now is None else now
        depth = self.events.qsize()
        limit = self.events.maxsize or EVENT_QUEUE_MAXSIZE
        # Below half full, a backlog still defers maintenance one turn (Path-A).
        # At/above half, keep yielding up to YIELD_MAX_DEFERRALS so gzip/seal
        # does not sit in front of a reconnect flood. The cap still flushes.
        high = depth >= int(limit * YIELD_WATER_FRACTION)
        if high or depth >= self.drain_batch_limit:
            cap = YIELD_MAX_DEFERRALS if high else 1
            if self._maintenance_deferred < cap:
                self._maintenance_deferred += 1
                return True
        self._maintenance_deferred = 0
        flushed = False
        if now - self._flush_at >= self.flush_interval_s:
            self._timed("flush", self.recorder.flush)
            self._flush_at = now
            flushed = True
            if self.recorder.failure:
                self.failure = self.recorder.failure
                return False
        seal_due = self._seal_due or now - self._seal_at >= SEAL_INTERVAL_S
        if seal_due and flushed and not self._seal_due:
            self._seal_due = True
        elif seal_due:
            self._seal_due = False
            self._timed("seal", self.recorder.segment_boundary)
            self._seal_at = now
            if self.recorder.failure:
                self.failure = self.recorder.failure
                return False
            self.publish_status(detail="full", sync=True)
            self._heartbeat_at = now
            return True
        if now - self._heartbeat_at >= HEARTBEAT_INTERVAL_S:
            self.publish_status(detail="heartbeat", sync=False)
            self._heartbeat_at = now
        return True

    def status(self, final=False, *, detail="full"):
        now = self.now()
        stale_price = [
            c + ":" + k
            for c in self.contracts
            for k in ("BBO", "DEPTH")
            if now - self.last_seen.get(c + ":" + k, 0) > 2000
        ]
        stale_kline = [
            c + ":FRAME"
            for c in self.contracts
            if now - self.last_seen.get(c + ":FRAME", 0) > 20 * 60 * 1000
        ]
        if self.failure:
            continuity = "STOPPED" if final else "FAILED"
        elif stale_kline or stale_price:
            continuity = "STARTING" if not self.channels else "DEGRADED"
        else:
            continuity = "HEALTHY"
        full = final or detail == "full"
        body = dict(
            pid=os.getpid(),
            started_ms=self.started_ms,
            observed_ms=now,
            final=final,
            stop_reason=self.failure,
            release=os.environ.get('HTX_PUBLIC_CAPTURE_RELEASE_ID','UNSPECIFIED'),
            counts_scope="CURRENT_PROCESS",
            counts=dict(self.counts),
            continuity=continuity,
            stale_price_channels=stale_price,
            stale_kline_channels=stale_kline,
            errors=dict(self.errors),
            invalid_market_messages=dict(self.invalid_market_messages),
            bytes=self.recorder.used,
            min_free_bytes=self.min_free_bytes,
            startup_required_free_bytes=self.startup_required_free_bytes,
            disk_free_bytes=shutil.disk_usage(self.root).free,
            queue_depth=self.events.qsize(),
            queue_depth_high_water=self.queue_depth_high_water,
            queue_maxsize=self.events.maxsize,
            last_flush_ms=self.last_flush_ms,
            last_seal_ms=self.last_seal_ms,
            flush_ms_high_water=self.flush_ms_high_water,
            seal_ms_high_water=self.seal_ms_high_water,
            channel_keys=len(self.channels),
            channels_omitted=not full,
            status_detail="full" if full else "heartbeat",
            admission="NOT_ELIGIBLE",
            trading=False,
            gzip_level=self.recorder.gzip_level,
            drain_batch=self.drain_batch_limit,
            flush_interval_s=self.flush_interval_s,
            catchup_active=self.catchup.active,
            depth_coalesced=self.catchup.coalesced,
            depth_downsampled=self.catchup.downsampled,
        )
        body.update(self.reconnect.snapshot())
        diagnostic = self._diagnose("snapshot", now, self._monotonic(), self.socket) or {}
        diagnostic.update(diagnostic_errors=self.diagnostic_errors,
                          diagnostic_events_dropped=self.diagnostic_events_dropped)
        body["ws_diagnostics"] = diagnostic
        if full:
            body["channels"] = dict(self.channels)
        return body

    @staticmethod
    def stream_dependency_check():
        from websockets.sync.client import connect
        return connect

    @property
    def startup_required_free_bytes(self):
        # A segmented source reclaims GET-verified payloads through COS release.
        # Do not reserve the full 2GiB again at each restart. Preserve the
        # configured low-disk floor and the recorder's bounded local quota.
        return self.min_free_bytes + (0 if self.recorder.segments else self.max_bytes)

    def run(self):
        threads = []
        try:
            if shutil.disk_usage(self.root).free < self.startup_required_free_bytes:
                self.failure = "INSUFFICIENT_START_HEADROOM"
                return
            try:
                self.stream_dependency_check()
            except ImportError:
                self.failure = 'STREAM_DEPENDENCY_UNAVAILABLE'
                return
            self.emit(dict(kind='COLLECTION_SESSION',received_ms=self.now(),
                           release=os.environ.get('HTX_PUBLIC_CAPTURE_RELEASE_ID','UNSPECIFIED'),
                           session_id=uuid.uuid4().hex,trading=False))
            self.load_specifications()
            threads = [
                threading.Thread(target=self.stream_loop, daemon=True),
                threading.Thread(target=self.rest_loop, daemon=True),
            ]
            for t in threads:
                t.start()
            deadline, checked_at = time.monotonic() + self.seconds, 0.0
            while not self.stop.is_set() and time.monotonic() < deadline:
                if time.monotonic() - checked_at >= 1:
                    checked_at = time.monotonic()
                    if any(not thread.is_alive() for thread in threads):
                        self.failure = 'CAPTURE_THREAD_EXITED'
                        break
                    if shutil.disk_usage(self.root).free < self.min_free_bytes:
                        self.failure = "LOW_DISK_HEADROOM"
                        break
                batch = self.drain_batch()
                if batch and not self.write_batch(batch):
                    break
                if consumer_extra_drain(self.events.qsize(), self.events.maxsize):
                    extra = self.drain_batch()
                    if extra and not self.write_batch(extra):
                        break
                if not self.maintain():
                    break
            self.failure = self.failure or (
                "SIGNAL_STOP" if self.stop.is_set() else "DURATION_LIMIT"
            )
        except Exception as error:
            self.failure = "CAPTURE_FAILED:" + type(error).__name__
            raise
        finally:
            self.stop.set()
            if self.socket:
                self.socket.close()
            for t in threads:
                t.join(timeout=55)
            self.close_catchup()
            while not self.events.empty() and not self.recorder.failure:
                leftover = []
                while len(leftover) < self.drain_batch_limit:
                    try:
                        leftover.append(self.events.get_nowait())
                    except queue.Empty:
                        break
                if not leftover or not self.write_batch(leftover):
                    break
            self._timed("flush", self.recorder.flush)
            self._timed("seal", lambda: self.recorder.segment_boundary(force=True))
            self.publish_status(detail="full", sync=True, final=True)
            self.client.close()
            if self.recorder.segments:
                self.recorder.segments.close()
            self.lock.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    start = sub.add_parser("start")
    start.add_argument("--contracts-file", type=Path, required=True)
    start.add_argument("--output", type=Path, required=True)
    start.add_argument("--max-bytes", type=int, default=2 * GIB)
    start.add_argument("--min-free-bytes", type=int, default=5 * GIB)
    start.add_argument("--seconds", type=int, default=86400)
    start.add_argument("--segmented", action="store_true")
    start.add_argument("--resume", action="store_true", help="Restart an identical segmented source; preserve quota and provenance")
    check = sub.add_parser("audit")
    check.add_argument("--input", type=Path, required=True)
    check.add_argument("--export", type=Path)
    args = parser.parse_args()
    if args.command == "audit":
        print(json.dumps(audit(args.input, args.export)))
        return
    os.umask(0o077)
    # CLI only: enable lifecycle summaries, never websockets payload DEBUG logs.
    logging.basicConfig(level=logging.WARNING, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    logger.setLevel(logging.INFO)
    source = json.loads(args.contracts_file.read_text())
    capture = Capture(
        args.output,
        source["contracts"],
        max_bytes=args.max_bytes,
        min_free_bytes=args.min_free_bytes,
        seconds=args.seconds,
        observed_runtime=source,
        segmented=args.segmented,
        resume=args.resume,
    )
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, lambda *_: capture.stop.set())
    capture.run()
    print(json.dumps(capture.status(final=True)))
    if args.resume and capture.failure not in {'DURATION_LIMIT','SIGNAL_STOP',None}:
        raise SystemExit(2)  # systemd must preserve a visible capacity/failure stop, not spin.


if __name__ == "__main__":
    main()
