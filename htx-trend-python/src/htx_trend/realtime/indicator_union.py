"""Five explicit indicator patterns; no account allocation in signal discovery."""

from decimal import Decimal as D, ROUND_FLOOR, ROUND_CEILING
from ..models import TrendSide
from ..research.store import digest, primitive
from .plans import TriggerEngine, StructurePlan, RealtimeTrigger

CANDIDATE = "INDICATOR_UNION_V1"
MODEL = "RT_INDICATOR_UNION"
EXITS = {"ATR_1_1": (D(1), D(1)), "ATR_2_1": (D(2), D(1)), "ATR_2_2": (D(2), D(2))}


def series(values, period):
    if len(values) < period:
        raise ValueError("indicator warmup incomplete")
    result = [sum(values[:period], D(0)) / period]
    alpha = D(2) / (period + 1)
    for value in values[period:]:
        result.append(result[-1] + alpha * (value - result[-1]))
    return result


def features(entry, hour):
    for window, seconds in ((entry, 900), (hour, 3600)):
        if not window.is_complete or len(window.candles) < 60:
            raise ValueError("union needs complete indicator history")
        if window.period != ("15min" if seconds == 900 else "60min"):
            raise ValueError("union timeframe mismatch")
        for b in window.candles:
            if (
                not all(
                    x.is_finite() and x > 0 for x in (b.open, b.high, b.low, b.close)
                )
                or not b.low <= min(b.open, b.close) <= max(b.open, b.close) <= b.high
            ):
                raise ValueError("invalid union candle")
    bars = entry.candles
    hours = hour.candles
    c = [b.close for b in bars]
    hc = [b.close for b in hours]
    fast = series(hc, 12)
    slow = series(hc, 26)
    dif = [a - b for a, b in zip(fast[-len(slow) :], slow)]
    histogram = dif[-1] - series(dif, 9)[-1]
    tp = [(b.high + b.low + b.close) / 3 for b in hours[-20:]]
    mean = sum(tp, D(0)) / 20
    dev = sum((abs(x - mean) for x in tp), D(0)) / 20
    cci = (tp[-1] - mean) / (D(".015") * dev) if dev else D(0)
    prior = hours[-21:-1]
    mid = (max(b.high for b in prior) + min(b.low for b in prior)) / 2
    volumes = [b.volume for b in bars[-20:]]
    cmf = None
    if all(v is not None and v.is_finite() and v >= 0 for v in volumes):
        total = sum(volumes, D(0))
        if total:
            cmf = (
                sum(
                    (
                        (2 * b.close - b.high - b.low) / (b.high - b.low) * v
                        if b.high > b.low
                        else D(0)
                        for b, v in zip(bars[-20:], volumes)
                    ),
                    D(0),
                )
                / total
            )
    tr = [
        max(b.high - b.low, abs(b.high - a.close), abs(b.low - a.close))
        for a, b in zip(bars, bars[1:])
    ]
    volatility = sum(tr[:14], D(0)) / 14
    for x in tr[14:]:
        volatility = (13 * volatility + x) / 14
    last = bars[-1]
    return dict(
        bar=last.open_time,
        hour=hours[-1].open_time,
        close=last.close,
        open=last.open,
        lower=min(last.open, last.close) - last.low,
        upper=last.high - max(last.open, last.close),
        ema9=series(c, 9)[-1],
        atr=volatility,
        macd=histogram,
        cci=cci,
        channel=hc[-1] - mid,
        cmf=cmf,
    )


def matches(f, side, bid, ask, *, newbar, previous=None, previous_ema=None):
    sign = D(1) if side is TrendSide.LONG else D(-1)
    q = bid if side is TrendSide.LONG else ask
    spread = (ask - bid) / ((ask + bid) / 2)
    wick = (
        newbar
        and (f["close"] - f["open"]) * sign > 0
        and (f["lower"] if sign > 0 else f["upper"]) >= abs(f["close"] - f["open"])
        and (q - f["close"]) * sign > 0
    )
    macd = f["macd"] * sign > 0
    cci = f["cci"] * sign > 0
    channel = f["channel"] * sign > 0
    cross = (
        previous is not None
        and previous_ema is not None
        and (previous - previous_ema) * sign <= 0
        and (q - previous_ema) * sign > 0
    )
    return tuple(
        name
        for name, hit in (
            ("M1", wick and cci and channel),
            ("M2", wick and channel),
            ("M3", wick and (macd or cci)),
            (
                "M4",
                cross
                and f["cmf"] is not None
                and f["cmf"] * sign > 0
                and spread <= D(".0005"),
            ),
            ("M5", wick and macd and spread <= D(".001")),
        )
        if hit
    )


class IndicatorUnionEngine(TriggerEngine):
    def __init__(self, *args, **kwargs):
        self.frames = {}
        self.history = {}
        self.generation_audit = []
        super().__init__(*args, **kwargs)

    def disconnect(self):
        self.history.clear()
        super().disconnect()

    def refresh_union_frame(self, frame, *, now_ms):
        code = frame["contract"].contract_code
        self.frames.pop(code, None)
        self.frames[code] = (frame, features(frame["entry"], frame["trend"]))
        return ()

    def validate_frame(self, code, now_ms):
        frame, f = self.frames[code]
        if (
            frame.get("evidence_failed")
            or any(
                w.evidence_status == "FAILED" for w in (frame["entry"], frame["trend"])
            )
            or frame["received_ms"] > now_ms
        ):
            raise ValueError("union frame evidence unavailable")
        if frame["entry"].contract_code != code or frame["trend"].contract_code != code:
            raise ValueError("union contract mismatch")
        if (
            not 0 <= now_ms // 1000 - (f["bar"] + 900) < 900
            or not 0 <= now_ms // 1000 - (f["hour"] + 3600) < 3600
        ):
            raise ValueError("union closed frame stale")
        if f["atr"] <= 0:
            raise ValueError("union ATR unavailable")
        return frame, f

    def confirm(self, trigger, book, now_ms):
        self.validate_frame(trigger.plan.contract, now_ms)
        row = self.rows[trigger.plan.plan_id]
        if row["phase"] != "TRIGGERED":
            return False
        q = book.top.bid if trigger.side is TrendSide.LONG else book.top.ask
        sign = D(1) if trigger.side is TrendSide.LONG else D(-1)
        spread = book.top.spread_rate
        f = {
            k: D(v) if k not in ("bar", "hour") and v is not None else v
            for k, v in row["features"].items()
        }
        return any(
            ((q - f["close"]) * sign > 0 and (m != "M5" or spread <= D(".001")))
            if m != "M4"
            else ((q - D(row["cross_line"])) * sign > 0 and spread <= D(".0005"))
            for m in row["matched_modes"]
        )

    def accept(self, event, *, now_ms):
        if self.epoch != event.epoch:
            self.disconnect()
            self.epoch = event.epoch
        if (
            event.kind != "BBO"
            or not event.fresh(now_ms, self.parameters.max_age_ms)
            or not self.order.accept(event)
        ):
            return ()
        self.books[event.contract] = event
        if event.contract not in self.frames:
            return ()
        frame, f = self.validate_frame(event.contract, now_ms)
        if event.received_ms < frame["received_ms"]:
            return ()
        old = self.history.get(event.contract)
        self.history[event.contract] = (event, dict(f))
        # Establish a new connection's observation baseline, never replay old crossings.
        if (
            old is None
            or event.received_ms - old[0].received_ms > self.parameters.max_age_ms
        ):
            return ()
        candidates = []
        for side in (TrendSide.LONG, TrendSide.SHORT):
            previous = old[0].bid if side is TrendSide.LONG else old[0].ask
            modes = matches(
                f,
                side,
                event.bid,
                event.ask,
                newbar=f["bar"] > old[1]["bar"],
                previous=previous,
                previous_ema=old[1]["ema9"],
            )
            if modes:
                candidates.append((side, modes))
        output = []
        for side, modes in candidates:
            ident = digest(
                {
                    "instance": self.instance,
                    "candidate": CANDIDATE,
                    "contract": event.contract,
                    "side": side.value,
                    "bar": f["bar"],
                }
            )
            if ident in self.rows:
                continue
            sign = D(1) if side is TrendSide.LONG else D(-1)
            reference = event.ask if sign > 0 else event.bid
            tick = frame["contract"].price_tick
            loss, profit = EXITS[self.parameters.target_mode]
            stop = (reference - sign * loss * f["atr"]) / tick
            stop = (
                stop.to_integral_value(
                    rounding=ROUND_FLOOR if sign > 0 else ROUND_CEILING
                )
                * tick
            )
            target = (reference + sign * profit * f["atr"]) / tick
            target = (
                target.to_integral_value(
                    rounding=ROUND_FLOOR if sign > 0 else ROUND_CEILING
                )
                * tick
            )
            plan = StructurePlan(
                ident,
                event.contract,
                MODEL,
                side,
                f["close"],
                D(0),
                f["atr"],
                tick,
                target,
                self.parameters.target_mode,
                f["bar"],
                f["hour"],
                f["bar"],
                ("RULE:" + CANDIDATE, *("MATCH:" + m for m in modes)),
                tuple(frame["entry"].evidence_refs + frame["trend"].evidence_refs),
                self.parameters.fingerprint,
                now_ms,
            )
            self.rows[ident] = {
                "plan": plan.dump(),
                "phase": "TRIGGERED" if len(candidates) == 1 else "REJECTED",
                "reason": None if len(candidates) == 1 else "AMBIGUOUS_DIRECTIONS",
                "matched_modes": modes,
                "features": primitive(f),
                "cross_line": str(old[1]["ema9"]),
            }
            self._save(ident)
            if len(candidates) == 1:
                output.append(
                    RealtimeTrigger(
                        plan,
                        event.received_ms,
                        event.exchange_ms,
                        reference,
                        stop,
                        stop,
                        (digest(event),),
                        digest(event),
                        event.epoch,
                    )
                )
        return tuple(output)


def bracket_target(context):
    if context.get("exit_policy_ref") not in {
        "INDICATOR_BRACKET_V1/" + key for key in EXITS
    }:
        raise ValueError("union position lacks frozen bracket ownership")
    target = D(context["structure_target"])
    if not target.is_finite() or target <= 0:
        raise ValueError("union target unavailable")
    return target
