"""Bounded, observation-only diagnostics for the independent public collector.

No socket control, payload retention, credentials, file writes or retry policy.
Capture isolates failures in this helper from the market-data path.
"""

import math
import re
import threading
from collections import OrderedDict
from pathlib import Path


def safe_text(value, limit=512):
    # Bound work before regexes, but redact before the final output truncation.
    text = str(value)[:8192]
    text = re.sub(
        r"(?im)\b(?:authorization|(?:set-)?cookie)\s*:\s*[^\r\n]+",
        "[HEADER_REDACTED]",
        text,
    )
    text = re.sub(r"(?i)(?:https?|wss?)://[^\s\"'<>]+", "[URL_REDACTED]", text)
    text = re.sub(r"(?i)\b(?:bearer|basic)\s+[^\s,;\"']+", "[AUTH_REDACTED]", text)
    text = re.sub(
        r"""(?ix)(["']?(?:authorization|cookie|set-cookie|access[_-]?key|secret[_-]?key|
        api[_-]?key|secret[_-]?id|secret|password|token|signature)["']?\s*[:=]\s*)
        (?:"[^"]*"|'[^']*'|[^\s,;}&]+)""",
        r"\1[REDACTED]",
        text,
    )
    text = re.sub(r"[\x00-\x1f\x7f]", " ", text)
    return text if len(text) <= limit else text[: limit - 3] + "..."


def milliseconds(seconds):
    return round(max(0.0, seconds) * 1000, 3)


def close_frame(frame):
    return (
        None
        if frame is None
        else dict(code=int(frame.code), reason=safe_text(frame.reason, 256))
    )


class WsDiagnostics:
    def __init__(self, contracts):
        self.expected = tuple(f"{c}:{k}" for c in contracts for k in ("BBO", "DEPTH"))
        self.expected_set = frozenset(self.expected)
        self.lock = threading.RLock()
        self.last_good = {}
        self.last_raw = self.last_market = None
        self.epoch = None
        self.attempt = 0
        self.started_mono = None
        self.connected_mono = None
        self.session_had_market = False
        self.phase = "idle"
        self.operation_started = None
        self.durations = {}
        self.high_water = {}
        self.seen = set()
        self.ping = self.pong = None
        self.repeats = OrderedDict()

    def begin_attempt(self, epoch, attempt, at, mono):
        with self.lock:
            self.epoch, self.attempt = epoch, attempt
            self.started_mono = mono
            self.connected_mono = None
            self.session_had_market = False
            self.phase = "connect"
            self.seen.clear()
            self.durations.clear()
            self.ping = self.pong = None

    def connected(self, mono):
        with self.lock:
            self.connected_mono = mono

    def start(self, phase, mono):
        with self.lock:
            self.phase, self.operation_started = phase, mono

    def end(self, phase, mono):
        with self.lock:
            if self.operation_started is not None:
                elapsed = milliseconds(mono - self.operation_started)
                self.durations[phase] = elapsed
                self.high_water[phase] = max(self.high_water.get(phase, 0), elapsed)
            self.operation_started = None

    def raw_received(self, at, mono):
        with self.lock:
            self.last_raw = (at, mono)

    def htx_ping(self, at, mono):
        with self.lock:
            self.ping, self.pong = (at, mono), None

    def htx_pong(self, at, mono):
        with self.lock:
            self.pong = (at, mono)

    def observe(self, event, mono):
        kind = event.get("kind")
        if kind not in ("TRADE", "BBO", "DEPTH"):
            return None
        with self.lock:
            at = event["received_ms"]
            self.last_market = (at, mono)
            self.session_had_market = True
            key = f"{event.get('contract')}:{kind}"
            if key not in self.expected_set:
                return None
            previous = self.last_good.get(key)
            self.last_good[key] = (at, mono)
            if key in self.seen:
                return None
            self.seen.add(key)
            return dict(
                kind="WS_CHANNEL_RECOVERED",
                received_ms=at,
                epoch=self.epoch,
                attempt=self.attempt,
                channel=key,
                observation_stage="NORMALIZED_WS_RECEIVE_PRE_CATCHUP",
                recovery_scope="INTER_OBSERVATION"
                if previous
                else "FIRST_OBSERVATION_IN_PROCESS",
                last_good_ms=previous[0] if previous else None,
                first_recovered_ms=at,
                total_outage_ms=milliseconds(mono - previous[1]) if previous else None,
                wall_interval_ms=at - previous[0] if previous else None,
                attempt_elapsed_ms=milliseconds(mono - self.started_mono)
                if self.started_mono is not None
                else None,
                recovered_channels=len(self.seen),
                expected_channels=len(self.expected),
            )

    def snapshot(self, at, mono, sock):
        with self.lock:
            latency = getattr(sock, "latency", None)
            rtt = (
                milliseconds(latency)
                if isinstance(latency, (int, float))
                and math.isfinite(latency)
                and latency > 0
                else None
            )
            body = dict(
                schema="public-ws-diagnostics-v1",
                epoch=self.epoch,
                attempt=self.attempt,
                observed_ms=at,
                phase=self.phase,
                attempt_elapsed_ms=milliseconds(mono - self.started_mono)
                if self.started_mono is not None
                else None,
                durations_ms=dict(self.durations),
                duration_high_water_ms=dict(self.high_water),
                session_elapsed_ms=milliseconds(mono - self.connected_mono)
                if self.connected_mono is not None
                else None,
                session_had_market=self.session_had_market,
                operation_in_progress_ms=milliseconds(mono - self.operation_started)
                if self.operation_started is not None
                else None,
                last_raw_received_ms=self.last_raw[0] if self.last_raw else None,
                last_raw_age_ms=milliseconds(mono - self.last_raw[1])
                if self.last_raw
                else None,
                last_market_received_ms=self.last_market[0]
                if self.last_market
                else None,
                last_market_age_ms=milliseconds(mono - self.last_market[1])
                if self.last_market
                else None,
                recovered_channels=len(self.seen),
                expected_channels=len(self.expected),
                pending_channels=[k for k in self.expected if k not in self.seen],
                channels={
                    k: dict(
                        last_good_ms=self.last_good[k][0]
                        if k in self.last_good
                        else None,
                        age_ms=milliseconds(mono - self.last_good[k][1])
                        if k in self.last_good
                        else None,
                        observed_in_attempt=k in self.seen,
                    )
                    for k in self.expected
                },
                protocol_heartbeat=dict(
                    ping_interval_s=getattr(sock, "ping_interval", None),
                    ping_timeout_s=getattr(sock, "ping_timeout", None),
                    close_timeout_s=getattr(sock, "close_timeout", None),
                    last_rtt_ms=rtt,
                    last_ping_ms=None,
                    last_pong_ms=None,
                    timing_scope="LIBRARY_LAST_RTT_TIMESTAMP_UNAVAILABLE",
                ),
                htx_heartbeat=dict(
                    last_ping_ms=self.ping[0] if self.ping else None,
                    ping_age_ms=milliseconds(mono - self.ping[1])
                    if self.ping
                    else None,
                    last_pong_sent_ms=self.pong[0] if self.pong else None,
                    pong_age_ms=milliseconds(mono - self.pong[1])
                    if self.pong
                    else None,
                    response_ms=milliseconds(self.pong[1] - self.ping[1])
                    if self.pong and self.ping
                    else None,
                ),
            )
            return body

    def failure(self, error, at, mono, sock, runtime):
        with self.lock:
            body = self.snapshot(at, mono, sock)
            chain, visited = [], set()
            current = error
            while current is not None and id(current) not in visited and len(chain) < 4:
                visited.add(id(current))
                frames = []
                tb = current.__traceback__
                # No source lines or locals: either can contain payloads/secrets.
                while tb is not None:
                    frames.append(
                        dict(
                            file=Path(tb.tb_frame.f_code.co_filename).name,
                            function=tb.tb_frame.f_code.co_name,
                            line=tb.tb_lineno,
                        )
                    )
                    frames = frames[-8:]
                    tb = tb.tb_next
                chain.append(
                    dict(
                        type=type(current).__name__,
                        message=safe_text(current),
                        frames=frames,
                        close_sent=close_frame(getattr(current, "sent", None)),
                        close_received=close_frame(getattr(current, "rcvd", None)),
                    )
                )
                current = current.__cause__ or (
                    None if current.__suppress_context__ else current.__context__
                )
            # Fingerprint excludes unbounded messages / epoch IDs; cap distinct sites.
            key = (
                self.phase,
                tuple(
                    (e["type"], tuple((f["file"], f["line"]) for f in e["frames"]))
                    for e in chain
                ),
            )
            record = self.repeats.get(key)
            if record is None:
                if len(self.repeats) >= 64:
                    self.repeats.popitem(last=False)
                record = dict(count=0, first_ms=at, last_ms=at, stack_mono=None)
                self.repeats[key] = record
            record["count"] += 1
            record["last_ms"] = at
            include_stack = (
                record["stack_mono"] is None or mono - record["stack_mono"] >= 60
            )
            if include_stack:
                record["stack_mono"] = mono
            else:
                for entry in chain:
                    entry.pop("frames")
            body.update(
                exception_type=type(error).__name__,
                exception_chain=chain,
                close_sent=close_frame(getattr(error, "sent", None)),
                close_received=close_frame(getattr(error, "rcvd", None)),
                close_received_then_sent=getattr(error, "rcvd_then_sent", None),
                stack_included=include_stack,
                repeat={k: record[k] for k in ("count", "first_ms", "last_ms")},
                runtime=runtime,
            )
            return body
