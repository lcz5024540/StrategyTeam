"""Bounded public research capture. No worker, account, private read or order API.

The bundle supplies observed market inputs, not a fabricated baseline/latency.
Each normalized record keeps its raw response in the same hashed gzip segment.
"""

from __future__ import annotations

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from decimal import Decimal as D
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

GIB = 1024**3
CONTROL_RESERVE = 1024**2
CHANNELS = ("trade.detail", "bbo", "depth.step0")
# 4096 held ~6–7s at the observed 610–640 msg/s BBO/DEPTH rate and then
# fail-closed. 65536 is about 100s of mean-rate buffer; batch drain and
# rarer seal/status syncs are what keep that buffer from filling.
EVENT_QUEUE_MAXSIZE = 65536
EVENT_DRAIN_BATCH = 512
FLUSH_INTERVAL_S = 10.0
SEAL_INTERVAL_S = 60.0
HEARTBEAT_INTERVAL_S = 10.0
QUEUE_HIGH_WATER_FRACTIONS = (0.5, 0.75, 0.9)
SLOW_IO_LOG_MS = 50.0
logger = logging.getLogger(__name__)
MISSING = [
    "INDEPENDENT_BASELINE_SCANS_AND_DECISIONS",
    "OBSERVED_ACCOUNT_FEES",
    "OBSERVED_ORDER_LATENCY",
    "OBSERVED_MARKET_CONTEXT",
    "VERIFIED_FUNDING_SETTLEMENTS",
    "DIAGNOSTIC_HOLDOUT_HISTORY",
]


def normalize_ws(value, epoch, received_ms):
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
        self._maintenance_deferred = False
        self._seal_due = False
        self.failure = None
        self.socket = None
        self.counts, self.channels = Counter(), Counter()
        self.last_seen, self.errors = {}, Counter()
        self.recorder = SegmentRecorder(
            self.root / "segments",
            max_bytes=max_bytes - CONTROL_RESERVE,
            gzip_level=1,
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

    def emit(self, event):
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

    def stream_loop(self):
        from websockets.sync.client import connect

        backoff = 1
        while not self.stop.is_set():
            epoch = uuid.uuid4().hex
            self.emit(
                dict(
                    kind="GAP",
                    received_ms=self.now(),
                    reason="CONNECT_OR_RECONNECT",
                    epoch=epoch,
                )
            )
            try:
                with connect(
                    "wss://api.hbdm.com/linear-swap-ws",
                    open_timeout=10,
                    close_timeout=2,
                    max_size=8 * 1024**2,
                ) as sock:
                    self.socket = sock
                    subscriptions = iter(
                        (c, k) for c in self.contracts for k in CHANNELS
                    )
                    pending, after, last = (
                        next(subscriptions, None),
                        0.0,
                        time.monotonic(),
                    )
                    while not self.stop.is_set():
                        if pending and time.monotonic() >= after:
                            c, k = pending
                            sock.send(
                                json.dumps({"sub": f"market.{c}.{k}", "id": f"{c}:{k}"})
                            )
                            after = time.monotonic() + 0.1  # <=10 subscriptions/sec
                            pending = next(subscriptions, None)
                        try:
                            raw = sock.recv(
                                timeout=max(0.001, after - time.monotonic())
                                if pending
                                else 1
                            )
                        except TimeoutError:
                            if time.monotonic() - last > 15:
                                raise
                            continue
                        last = time.monotonic()
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
                        observations, pong = normalize_ws(value, epoch, received)
                        if pong:
                            sock.send(json.dumps(pong))
                        for event in observations:
                            self.emit(event)
                        if observations:
                            backoff = 1
            except Exception as error:
                if not self.stop.is_set():
                    self.emit(
                        dict(
                            kind="GAP",
                            received_ms=self.now(),
                            reason=type(error).__name__,
                            epoch=epoch,
                        )
                    )
                    self.stop.wait(backoff)
                    backoff = min(backoff * 2, 30)
            finally:
                self.socket = None

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
            limit = EVENT_DRAIN_BATCH
        if limit < 1:
            raise ValueError("drain batch must be positive")
        batch = []
        try:
            batch.append(self.events.get(timeout=0.1))
        except queue.Empty:
            return batch
        while len(batch) < limit:
            try:
                batch.append(self.events.get_nowait())
            except queue.Empty:
                break
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
        atomic_json(
            self.root / "status.json",
            self.status(final=final, detail=detail),
            sync=sync,
        )

    def maintain(self, now=None):
        """Flush often; seal and the full channel map on a slower cadence.

        Gzip flush and segment_boundary are not run in the same turn, and a
        deep backlog defers maintenance for one turn so the consumer can drain.
        Deferred work is not skipped twice. Returns False when the recorder
        has failed and the run loop must stop.
        """
        now = time.monotonic() if now is None else now
        if self.events.qsize() >= EVENT_DRAIN_BATCH and not self._maintenance_deferred:
            self._maintenance_deferred = True
            return True
        self._maintenance_deferred = False
        flushed = False
        if now - self._flush_at >= FLUSH_INTERVAL_S:
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
        stale = [
            c + ":" + k
            for c in self.contracts
            for k in ("BBO", "DEPTH")
            if now - self.last_seen.get(c + ":" + k, 0) > 2000
        ]
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
            stale_price_channels=stale,
            errors=dict(self.errors),
            bytes=self.recorder.used,
            min_free_bytes=self.min_free_bytes,
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
        )
        if full:
            body["channels"] = dict(self.channels)
        return body

    @staticmethod
    def stream_dependency_check():
        from websockets.sync.client import connect
        return connect

    def run(self):
        threads = []
        try:
            if shutil.disk_usage(self.root).free < self.min_free_bytes + self.max_bytes:
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
            while not self.events.empty() and not self.recorder.failure:
                leftover = []
                while len(leftover) < EVENT_DRAIN_BATCH:
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
