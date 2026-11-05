"""A/B scenarios: closed-bar context, window-scoped BBO entry, fixed bracket.

Scenario recognition is deterministic. The optional frozen Bayesian assessment
is a separate entry gate; it never changes an open holding's exit ownership.
"""
from decimal import Decimal as D, ROUND_FLOOR, ROUND_CEILING

from ..models import TrendSide
from ..research.store import digest, primitive
from .indicator_union import IndicatorUnionEngine, EXITS, features, series
from .plans import StructurePlan, RealtimeTrigger

CANDIDATE = "REGIME_AB_V1"
MODEL = "RT_REGIME_AB"


def macd(values):
    slow, fast = series(values, 26), series(values, 12)
    dif = [a - b for a, b in zip(fast[-len(slow):], slow)]
    return dif[-1] - series(dif, 9)[-1]


def regime_features(entry, hour):
    result = features(entry, hour)
    hc = [b.close for b in hour.candles]
    result.update(h1_ema20=series(hc, 20)[-1], h1_ema60=series(hc, 60)[-1],
                  m15_macd=macd([b.close for b in entry.candles]))
    return result


def classify(f):
    if f["macd"] <= 0 or f["m15_macd"] >= 0:
        return None
    if f["h1_ema20"] < f["h1_ema60"]:
        return "A"
    if f["h1_ema20"] > f["h1_ema60"]:
        return "B"
    return None


def confirms(f, scenario, bid, ask):
    if scenario not in ("A", "B") or classify(f) != scenario:
        return False
    if not 0 < bid <= ask or (ask - bid) / ((ask + bid) / 2) > D(".001"):
        return False
    body = f["close"] - f["open"]
    if scenario == "A":
        return body > 0 and f["lower"] >= body and bid > f["close"]
    return body < 0 and f["upper"] >= -body and ask < f["close"]


class RegimeEngine(IndicatorUnionEngine):
    def __init__(self, *args, **kwargs):
        self.snapshot = None
        self.snapshot_error = "SNAPSHOT_MISSING"
        super().__init__(*args, **kwargs)

    def refresh_union_frame(self, frame, *, now_ms):
        code = frame["contract"].contract_code
        self.frames.pop(code, None)
        f = regime_features(frame["entry"], frame["trend"])
        self.frames[code] = (frame, f)
        self.generation_audit = [{"contract": code, "bar": f["bar"],
                                 "scenario": classify(f) or "UNCLASSIFIED",
                                 "features": primitive(f)}]
        return ()

    def assessment(self, scenario, now_ms):
        if self.parameters.bayes_threshold is None:
            return {"allowed": True, "reason": "BAYES_DISABLED"}
        if self.snapshot is None:
            return {"allowed": False, "reason": self.snapshot_error}
        return self.snapshot.assess(scenario, self.parameters, now_ms)

    def confirm(self, trigger, book, now_ms):
        _, f = self.validate_frame(trigger.plan.contract, now_ms)
        row = self.rows[trigger.plan.plan_id]
        return (row["phase"] == "TRIGGERED" and f["bar"] == trigger.plan.structure_bar
                and confirms(f, row["scenario"], book.top.bid, book.top.ask)
                and self.assessment(row["scenario"], now_ms)["allowed"])

    def accept(self, event, *, now_ms):
        if self.epoch != event.epoch:
            self.disconnect()
            self.epoch = event.epoch
        if (event.kind != "BBO" or not event.fresh(now_ms, self.parameters.max_age_ms)
                or not self.order.accept(event)):
            return ()
        self.books[event.contract] = event
        if event.contract not in self.frames:
            return ()
        frame, f = self.validate_frame(event.contract, now_ms)
        if event.received_ms < frame["received_ms"]:
            return ()
        old = self.history.get(event.contract)
        self.history[event.contract] = (event, dict(f))
        if old is None or event.received_ms - old[0].received_ms > self.parameters.max_age_ms:
            return ()
        if self.parameters.regime_timing == "FIRST_QUOTE" and f["bar"] <= old[1]["bar"]:
            return ()
        scenario = classify(f)
        if not confirms(f, scenario, event.bid, event.ask):
            return ()
        side = TrendSide.LONG if scenario == "A" else TrendSide.SHORT
        ident = digest({"instance": self.instance, "candidate": CANDIDATE,
                        "timing": self.parameters.regime_timing,
                        "contract": event.contract, "side": side.value, "bar": f["bar"]})
        if ident in self.rows:
            return ()
        sign = D(1) if side is TrendSide.LONG else D(-1)
        reference = event.ask if sign > 0 else event.bid
        tick = frame["contract"].price_tick
        loss, profit = EXITS[self.parameters.target_mode]
        rounding = ROUND_FLOOR if sign > 0 else ROUND_CEILING
        stop = ((reference - sign * loss * f["atr"]) / tick).to_integral_value(rounding=rounding) * tick
        target = ((reference + sign * profit * f["atr"]) / tick).to_integral_value(rounding=rounding) * tick
        assessment = self.assessment(scenario, now_ms)
        plan = StructurePlan(ident, event.contract, MODEL, side, f["close"], D(0),
            f["atr"], tick, target, self.parameters.target_mode, f["bar"], f["hour"],
            f["bar"], ("RULE:" + CANDIDATE, "SCENARIO:" + scenario,
                       "TIMING:" + self.parameters.regime_timing,
                       "POSTERIOR:" + self.parameters.bayes_snapshot_hash),
            tuple(frame["entry"].evidence_refs + frame["trend"].evidence_refs),
            self.parameters.fingerprint, now_ms)
        trigger = RealtimeTrigger(plan, event.received_ms, event.exchange_ms, reference,
            stop, stop, (digest(event),), digest(event), event.epoch)
        self.rows[ident] = {"plan": plan.dump(),
            "phase": "TRIGGERED" if assessment["allowed"] else "REJECTED",
            "reason": None if assessment["allowed"] else "BAYES:" + assessment["reason"],
            "scenario": scenario, "features": primitive(f),
            "bayes_assessment": primitive(assessment), "trigger": primitive(trigger)}
        self._save(ident)
        return (trigger,) if assessment["allowed"] else ()
