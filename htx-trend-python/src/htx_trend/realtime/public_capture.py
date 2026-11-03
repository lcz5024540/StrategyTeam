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
from ..research.store import canonical, digest, primitive
from .feed import decode
from .storage import SegmentRecorder

GIB = 1024**3
CONTROL_RESERVE = 1024**2
CHANNELS = ("trade.detail", "bbo", "depth.step0")
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


def atomic_json(path, value):
    raw = canonical(value)
    if len(raw) > CONTROL_RESERVE // 4:
        raise ValueError("control document exceeded reserve")
    temporary = path.with_suffix(".tmp")
    with temporary.open("wb") as f:
        f.write(raw)
        f.flush()
        os.fsync(f.fileno())
    os.replace(temporary, path)


def read_reference(root, ref):
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
    if index >= len(value["events"]):
        raise ValueError("capture index out of range")
    event = value["events"][index]
    if digest(event) != ref.get("event_hash"):
        raise ValueError("capture observation hash mismatch")
    return event


def iter_records(root):
    """Streaming audit/export; never loads a 2GiB capture into memory."""
    root = Path(root) / "segments"
    with (root / "segments.jsonl").open() as manifest:
        for line in manifest:
            ref = json.loads(line)
            value = json.loads(
                gzip.decompress((root / (ref["hash"] + ".json.gz")).read_bytes())
            )
            if digest(value) != ref["hash"] or len(value["events"]) != ref["count"]:
                raise ValueError("capture segment integrity failure")
            for index, event in enumerate(value["events"]):
                yield (
                    event,
                    dict(segment=ref["hash"], index=index, event_hash=digest(event)),
                )


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
    ):
        if not 1 <= seconds <= 86400 or not CONTROL_RESERVE < max_bytes <= 2 * GIB:
            raise ValueError("capture duration/cap exceeds approved bounds")
        if (
            not contracts
            or len(contracts) > 100
            or any(not re.fullmatch("[A-Z0-9]+-USDT", c) for c in contracts)
        ):
            raise ValueError("invalid explicit contract pool")
        self.root, self.contracts = Path(output), tuple(dict.fromkeys(contracts))
        self.root.mkdir(parents=True, exist_ok=False)
        self.lock = (self.root / "capture.lock").open("w")
        fcntl.flock(self.lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        self.max_bytes, self.min_free_bytes, self.seconds, self.clock = (
            max_bytes,
            min_free_bytes,
            seconds,
            clock,
        )
        self.stop = threading.Event()
        self.events = queue.Queue(maxsize=4096)
        self.failure = None
        self.socket = None
        self.counts, self.channels = Counter(), Counter()
        self.last_seen, self.errors = {}, Counter()
        self.recorder = SegmentRecorder(
            self.root / "segments",
            max_bytes=max_bytes - CONTROL_RESERVE,
            gzip_level=1,
            json_ready=True,
        )
        self.client = HtxV5Client()  # no credentials, no environment loading
        self.rate_lock, self.next_request = threading.Lock(), 0.0
        self.started_ms = self.now()
        self.specs = {}
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
            self.failure = "QUEUE_OVERFLOW"
            self.stop.set()  # stop rather than silently lose coverage

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

    def status(self, final=False):
        now = self.now()
        stale = [
            c + ":" + k
            for c in self.contracts
            for k in ("BBO", "DEPTH")
            if now - self.last_seen.get(c + ":" + k, 0) > 2000
        ]
        return dict(
            pid=os.getpid(),
            started_ms=self.started_ms,
            observed_ms=now,
            final=final,
            stop_reason=self.failure,
            counts=dict(self.counts),
            channels=dict(self.channels),
            stale_price_channels=stale,
            errors=dict(self.errors),
            bytes=self.recorder.used,
            min_free_bytes=self.min_free_bytes,
            disk_free_bytes=shutil.disk_usage(self.root).free,
            queue_depth=self.events.qsize(),
            admission="NOT_ELIGIBLE",
            trading=False,
        )

    def run(self):
        threads = []
        try:
            if shutil.disk_usage(self.root).free < self.min_free_bytes + self.max_bytes:
                self.failure = "INSUFFICIENT_START_HEADROOM"
                return
            self.load_specifications()
            threads = [
                threading.Thread(target=self.stream_loop, daemon=True),
                threading.Thread(target=self.rest_loop, daemon=True),
            ]
            for t in threads:
                t.start()
            deadline, flush_at, checked_at = time.monotonic() + self.seconds, 0.0, 0.0
            while not self.stop.is_set() and time.monotonic() < deadline:
                if time.monotonic() - checked_at >= 1:
                    checked_at = time.monotonic()
                    if shutil.disk_usage(self.root).free < self.min_free_bytes:
                        self.failure = "LOW_DISK_HEADROOM"
                        break
                try:
                    self.write(self.events.get(timeout=0.1))
                except queue.Empty:
                    pass
                if time.monotonic() - flush_at >= 10:
                    self.recorder.flush()
                    if self.recorder.failure:
                        self.failure = self.recorder.failure
                        break
                    atomic_json(self.root / "status.json", self.status())
                    flush_at = time.monotonic()
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
                self.write(self.events.get_nowait())
            self.recorder.flush()
            atomic_json(self.root / "status.json", self.status(final=True))
            self.client.close()
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
    )
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, lambda *_: capture.stop.set())
    capture.run()
    print(json.dumps(capture.status(final=True)))


if __name__ == "__main__":
    main()
