"""Shadow zone evaluation for frozen P3 predicates R1 and B2.

Missing books do not censor R1/B2: those predicates may use ``mid_source=trade``.
Stream gaps and dedupe cooldown are censored and stay out of the M2 denominator.
Band and RR failures are non-confirmations, not false triggers.
False-trigger labels are descriptive only in Phase-0.
"""
from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal, ROUND_HALF_EVEN

from .schema import FEE_RATE, money, sha256_text


@dataclass(frozen=True, slots=True)
class TradePrint:
    ts_ms: int
    price: Decimal
    qty: Decimal
    side: str
    trade_id: str


@dataclass(frozen=True, slots=True)
class BookSnap:
    bids: tuple[tuple[Decimal, Decimal], ...]
    asks: tuple[tuple[Decimal, Decimal], ...]


@dataclass(frozen=True, slots=True)
class MarketObservation:
    ts_ms: int
    trades: tuple[TradePrint, ...]
    book: BookSnap | None
    stream_ok: bool = True
    data_origin: str = "fixture_replay"


def _book_hash(book: BookSnap | None) -> str | None:
    if book is None:
        return None
    lines = [f"b,{money(price)},{money(size)}" for price, size in book.bids]
    lines.extend(f"a,{money(price)},{money(size)}" for price, size in book.asks)
    return sha256_text("\n".join(lines))


def _trade_hash(trades: tuple[TradePrint, ...]) -> str | None:
    if not trades:
        return None
    lines = [
        f"{item.ts_ms},{money(item.price)},{money(item.qty)},{item.side},{item.trade_id}"
        for item in trades
    ]
    return sha256_text("\n".join(lines))


def _net_rr(bias: str, executable: Decimal, target: Decimal, invalidation: Decimal) -> dict[str, object]:
    reward = executable - target if bias == "SHORT" else target - executable
    risk = invalidation - executable if bias == "SHORT" else executable - invalidation
    payload: dict[str, object] = {
        "executable_price": money(executable),
        "net_rr": None,
        "qualified": False,
        "fee_rate_assumption": money(FEE_RATE),
    }
    if reward <= 0 or risk <= 0:
        return payload
    reward_after = reward * (Decimal("1") - FEE_RATE) - executable * FEE_RATE
    risk_incl = risk * (Decimal("1") + FEE_RATE) + executable * FEE_RATE
    if reward_after <= 0 or risk_incl <= 0:
        return payload
    ratio = (reward_after / risk_incl).quantize(Decimal("0.00000001"), rounding=ROUND_HALF_EVEN)
    payload["net_rr"] = money(ratio)
    payload["qualified"] = True
    return payload


class ShadowSession:
    """Replay one plan against a real or fixture tape. Never submits an order."""

    def __init__(self, plan: dict) -> None:
        self.plan = plan
        self.plan_id = str(plan["plan_id"])
        self.symbol = str(plan["symbol"])
        self.bias = str(plan["bias"])
        behavior = plan["entry_behavior"]
        self.kind = str(behavior["kind"])
        self.primary = str(behavior["primary_predicate"])
        self.window_ms = int(plan["dedupe_cooldown"]["window_seconds"]) * 1000
        support = plan["zone_support"][0]
        resistance = plan["zone_resistance"][0]
        self.support = (Decimal(support["low"]), Decimal(support["high"]))
        self.resistance = (Decimal(resistance["low"]), Decimal(resistance["high"]))
        band = plan["max_acceptable_fill_band"]
        self.band = (Decimal(band["low"]), Decimal(band["high"]))
        self.invalidation = Decimal(plan["invalidation_price"])
        self.target = Decimal(plan["target_price"])
        self.forbidden = list(plan["trade_forbidden_if"])
        if self.kind == "REJECTION":
            zone = self.resistance if self.bias == "SHORT" else self.support
        else:
            zone = self.support if self.bias == "SHORT" else self.resistance
        self.z_lo, self.z_hi = zone
        self.s_level = self.support[0] if self.bias == "SHORT" else self.resistance[1]
        self.counts = {
            "censored_dedupe": 0,
            "censored_stream_gap": 0,
            "non_confirmation_band": 0,
            "non_confirmation_rr": 0,
            "confirmed_description_only": 0,
        }
        self.in_zone = False
        self.entered = False
        self.intra_high: Decimal | None = None
        self.intra_low: Decimal | None = None
        self.extreme_ts = 0
        self.pierced_far = False
        self.saw_far = False
        self.r2_result = False
        self.b1_result = False
        self.r4_result = False
        self.bid_enter: Decimal | None = None
        self.ask_enter_size: Decimal | None = None
        self.b_phase = "idle"
        self.post_extreme: Decimal | None = None
        self.retest_extreme: Decimal | None = None
        self.outcome_open = False
        self.cooldown_until: int | None = None
        self.departed = False
        self.cycle_suppressed = False
        self.dedupe_status = "inactive"
        self.reactivate_reason: str | None = None

    def apply(self, obs: MarketObservation) -> list[dict]:
        if not obs.stream_ok or (obs.book is None and not obs.trades):
            self.counts["censored_stream_gap"] += 1
            return [self._event(
                obs, stage="stream_gap", decision="censored", reasons=["stream_gap"],
                primary_result=False, mid=None, mid_source=None, inside=False,
                rr={"executable_price": None, "net_rr": None, "qualified": False, "fee_rate_assumption": money(FEE_RATE)},
                shadow=None, would_trigger=False, m2="excluded_censored",
            )]
        trade = obs.trades[-1]
        mid, mid_source = self._mid(obs, trade)
        executable = self._executable(obs, trade)
        inside = self.band[0] <= executable <= self.band[1]
        rr = _net_rr(self.bias, executable, self.target, self.invalidation)
        if self.outcome_open:
            hit = self._outcome_hit(trade.price)
            if hit is not None:
                self.outcome_open = False
                self._note_departed(trade.price)
                self.in_zone = self.z_lo <= trade.price <= self.z_hi
                return [self._outcome_event(obs, hit)]
        if self.forbidden:
            return [self._event(
                obs, stage="primary_decision", decision="non_confirmation", reasons=["trade_forbidden"],
                primary_result=False, mid=mid, mid_source=mid_source, inside=inside, rr=rr,
                shadow=executable, would_trigger=False, m2="excluded_non_confirmation",
            )]
        if self.kind == "BREAKOUT":
            return self._step_breakout(obs, trade, mid, mid_source, executable, inside, rr)
        return self._step_rejection(obs, trade, mid, mid_source, executable, inside, rr)

    def _step_rejection(self, obs: MarketObservation, trade: TradePrint, mid: Decimal, mid_source: str,
                        executable: Decimal, inside: bool, rr: dict[str, object]) -> list[dict]:
        in_zone = self.z_lo <= mid <= self.z_hi
        if in_zone and not self.in_zone:
            self._begin_cycle(obs.ts_ms)
            self.entered = True
            self.pierced_far = False
            self.saw_far = False
            self.r2_result = False
            self.extreme_ts = trade.ts_ms
            if self.bias == "SHORT":
                self.intra_high = trade.price
            else:
                self.intra_low = trade.price
            if obs.book is not None:
                self.bid_enter = obs.book.bids[0][0]
                self.ask_enter_size = obs.book.asks[0][1]
            self.in_zone = True
            return [self._event(
                obs, stage="zone_enter", decision="non_confirmation", reasons=["awaiting_primary_predicate"],
                primary_result=False, mid=mid, mid_source=mid_source, inside=inside, rr=rr,
                shadow=executable, would_trigger=False, m2="excluded_non_confirmation",
            )]
        self.in_zone = in_zone
        self._note_departed(mid)
        terminal = self._rejection_terminal(obs, trade, mid, mid_source, executable, inside, rr)
        if terminal is not None:
            return [terminal]
        self._update_rejection_extreme(trade, mid)
        return []

    def _rejection_terminal(self, obs: MarketObservation, trade: TradePrint, mid: Decimal, mid_source: str,
                            executable: Decimal, inside: bool, rr: dict[str, object]) -> dict | None:
        if not self.entered:
            return None
        broke = trade.price < self.z_lo if self.bias == "SHORT" else trade.price > self.z_hi
        if not broke or trade.ts_ms <= self.extreme_ts:
            return None
        self.r2_result = self.saw_far and broke
        extreme_inside = (
            self.intra_high is not None and self.z_lo <= self.intra_high <= self.z_hi
            if self.bias == "SHORT"
            else self.intra_low is not None and self.z_lo <= self.intra_low <= self.z_hi
        )
        geometry = extreme_inside and not self.pierced_far
        self.entered = False
        return self._gate(
            obs, geometry=geometry, mid=mid, mid_source=mid_source, executable=executable,
            inside=inside, rr=rr, fail_reason="primary_predicate_false",
        )

    def _update_rejection_extreme(self, trade: TradePrint, mid: Decimal) -> None:
        if not self.entered:
            return
        far = mid > self.z_hi or trade.price > self.z_hi if self.bias == "SHORT" else mid < self.z_lo or trade.price < self.z_lo
        if far:
            self.pierced_far = True
            self.saw_far = True
            return
        if self.bias == "SHORT" and self.z_lo <= trade.price <= self.z_hi and (
            self.intra_high is None or trade.price >= self.intra_high
        ):
            self.intra_high = trade.price
            self.extreme_ts = trade.ts_ms
        if self.bias == "LONG" and self.z_lo <= trade.price <= self.z_hi and (
            self.intra_low is None or trade.price <= self.intra_low
        ):
            self.intra_low = trade.price
            self.extreme_ts = trade.ts_ms

    def _step_breakout(self, obs: MarketObservation, trade: TradePrint, mid: Decimal, mid_source: str,
                       executable: Decimal, inside: bool, rr: dict[str, object]) -> list[dict]:
        short = self.bias == "SHORT"
        broke = mid < self.s_level if short else mid > self.s_level
        crossed_back = mid >= self.s_level if short else mid <= self.s_level
        self._note_departed(mid)
        if self.b_phase == "idle":
            if not broke:
                return []
            self._begin_cycle(obs.ts_ms)
            self.b_phase = "broke"
            self.post_extreme = mid
            self.retest_extreme = None
            self.b1_result = inside
            return [self._event(
                obs, stage="zone_enter", decision="non_confirmation", reasons=["awaiting_retest_continuation"],
                primary_result=False, mid=mid, mid_source=mid_source, inside=inside, rr=rr,
                shadow=executable, would_trigger=False, m2="excluded_non_confirmation",
            )]
        if crossed_back:
            self.b_phase = "idle"
            return [self._event(
                obs, stage="primary_decision", decision="non_confirmation", reasons=["retest_reentered_S"],
                primary_result=False, mid=mid, mid_source=mid_source, inside=inside, rr=rr,
                shadow=executable, would_trigger=False, m2="excluded_non_confirmation",
            )]
        if self.b_phase == "broke":
            moved_toward = mid > self.post_extreme if short else mid < self.post_extreme
            if moved_toward:
                self.b_phase = "retested"
                self.retest_extreme = mid
            elif self.post_extreme is not None:
                self.post_extreme = min(self.post_extreme, mid) if short else max(self.post_extreme, mid)
            return []
        if self.b_phase == "retested" and self.post_extreme is not None and self.retest_extreme is not None:
            if short and self.s_level > mid > self.retest_extreme:
                self.retest_extreme = mid
            if (not short) and self.s_level < mid < self.retest_extreme:
                self.retest_extreme = mid
            continued = mid < self.post_extreme if short else mid > self.post_extreme
            held = self.retest_extreme < self.s_level if short else self.retest_extreme > self.s_level
            if continued and held:
                self.b_phase = "idle"
                return [self._gate(
                    obs, geometry=True, mid=mid, mid_source=mid_source, executable=executable,
                    inside=inside, rr=rr, fail_reason="primary_predicate_false",
                )]
        return []

    def _gate(self, obs: MarketObservation, *, geometry: bool, mid: Decimal, mid_source: str,
              executable: Decimal, inside: bool, rr: dict[str, object], fail_reason: str) -> dict:
        primary_result = geometry and inside
        would = primary_result and bool(rr["qualified"])
        self.r4_result = would and not self.cycle_suppressed
        if self.cycle_suppressed and would:
            self.counts["censored_dedupe"] += 1
            self._reset_pattern()
            return self._event(
                obs, stage="primary_decision", decision="censored", reasons=["dedupe_cooldown"],
                primary_result=True, mid=mid, mid_source=mid_source, inside=inside, rr=rr,
                shadow=executable, would_trigger=True, m2="excluded_censored",
            )
        if not geometry:
            self._reset_pattern()
            return self._event(
                obs, stage="primary_decision", decision="non_confirmation", reasons=[fail_reason],
                primary_result=False, mid=mid, mid_source=mid_source, inside=inside, rr=rr,
                shadow=executable, would_trigger=False, m2="excluded_non_confirmation",
            )
        if not inside:
            self.counts["non_confirmation_band"] += 1
            self._reset_pattern()
            return self._event(
                obs, stage="primary_decision", decision="non_confirmation", reasons=["band"],
                primary_result=False, mid=mid, mid_source=mid_source, inside=False, rr=rr,
                shadow=executable, would_trigger=False, m2="excluded_non_confirmation",
            )
        if not rr["qualified"]:
            self.counts["non_confirmation_rr"] += 1
            self._reset_pattern()
            return self._event(
                obs, stage="primary_decision", decision="non_confirmation", reasons=["rr_recompute_unqualified"],
                primary_result=True, mid=mid, mid_source=mid_source, inside=inside, rr=rr,
                shadow=executable, would_trigger=False, m2="excluded_non_confirmation",
            )
        self.outcome_open = True
        self.counts["confirmed_description_only"] += 1
        self.cooldown_until = obs.ts_ms + self.window_ms
        self.cycle_suppressed = False
        self._reset_pattern()
        return self._event(
            obs, stage="primary_decision", decision="confirmed", reasons=["primary_confirmed"],
            primary_result=True, mid=mid, mid_source=mid_source, inside=inside, rr=rr,
            shadow=executable, would_trigger=True, m2="pending_outcome_not_in_denominator",
        )

    def _begin_cycle(self, ts_ms: int) -> None:
        if self.cooldown_until is None or ts_ms >= self.cooldown_until:
            reason = None if self.cooldown_until is None else "cooldown_elapsed"
            self.cooldown_until = None
            self.departed = False
            self.cycle_suppressed = False
            self.dedupe_status = "inactive" if reason is None else "reactivated"
            self.reactivate_reason = reason
            return
        if self.departed:
            self.departed = False
            self.cooldown_until = None
            self.cycle_suppressed = False
            self.dedupe_status = "reactivated"
            self.reactivate_reason = "left_zone_and_reentered"
            return
        self.cycle_suppressed = True
        self.dedupe_status = "cooldown_suppress"
        self.reactivate_reason = None

    def _reset_pattern(self) -> None:
        self.entered = False
        self.intra_high = None
        self.intra_low = None
        self.pierced_far = False

    def _note_departed(self, price: Decimal) -> None:
        in_zone = self.z_lo <= price <= self.z_hi
        in_band = self.band[0] <= price <= self.band[1]
        if not in_zone and not in_band:
            self.departed = True

    def _outcome_hit(self, price: Decimal) -> str | None:
        if self.bias == "SHORT":
            hit_inv, hit_tgt = price >= self.invalidation, price <= self.target
        else:
            hit_inv, hit_tgt = price <= self.invalidation, price >= self.target
        if hit_inv and hit_tgt:
            return "ambiguous_same_print"
        if hit_inv:
            return "invalidation_first"
        if hit_tgt:
            return "target_first"
        return None

    def _mid(self, obs: MarketObservation, trade: TradePrint) -> tuple[Decimal, str]:
        if obs.book is not None and obs.book.bids and obs.book.asks:
            bid, ask = obs.book.bids[0][0], obs.book.asks[0][0]
            return (bid + ask) / Decimal("2"), "book"
        return trade.price, "trade"

    def _executable(self, obs: MarketObservation, trade: TradePrint) -> Decimal:
        if obs.book is not None and obs.book.bids and obs.book.asks:
            return obs.book.bids[0][0] if self.bias == "SHORT" else obs.book.asks[0][0]
        return trade.price

    def _sensitivity(self) -> list[dict[str, object]]:
        if self.primary == "R1":
            return [{"id": "R2", "role": "sensitivity", "result": self.r2_result}]
        return [{"id": "B1", "role": "sensitivity", "result": self.b1_result}]

    def _exploration(self, obs: MarketObservation) -> list[dict[str, object]]:
        if self.primary == "R1":
            return [
                {"id": "R3", "role": "exploration", "result": self._r3(obs)},
                {"id": "R4", "role": "exploration", "result": self._r4(obs)},
            ]
        return [{"id": "B3", "role": "exploration", "result": self._b3(obs)}]

    def _r3(self, obs: MarketObservation) -> object:
        if obs.book is None or self.bid_enter is None or self.ask_enter_size is None:
            return "not_applicable_book_missing"
        bid = obs.book.bids[0][0]
        ask_size = obs.book.asks[0][1]
        return bid < self.bid_enter and ask_size >= self.ask_enter_size

    def _r4(self, obs: MarketObservation) -> bool:
        return self.r4_result and obs.stream_ok

    def _b3(self, obs: MarketObservation) -> object:
        if obs.book is None:
            return "not_applicable_book_missing"
        bid, ask = obs.book.bids[0][0], obs.book.asks[0][0]
        if self.bias == "SHORT":
            return ask < self.s_level and bid < self.s_level
        return bid > self.s_level and ask > self.s_level

    def _event(self, obs: MarketObservation, *, stage: str, decision: str, reasons: list[str],
               primary_result: bool, mid: Decimal | None, mid_source: str | None, inside: bool,
               rr: dict[str, object], shadow: Decimal | None, would_trigger: bool, m2: str) -> dict[str, object]:
        return {
            "event_type": "zone_arrival_evaluation",
            "stage": stage,
            "exp_id": "EXP-SPRT-20261102",
            "phase": "phase-0",
            "track": "探索",
            "description_only": True,
            "formal_m2_counted": False,
            "formal_window": False,
            "plan_id": self.plan_id,
            "symbol": self.symbol,
            "observed_at_ms": obs.ts_ms,
            "mid_price": None if mid is None else money(mid),
            "mid_source": mid_source,
            "orderbook_snapshot_hash": _book_hash(obs.book),
            "trade_evidence_hash": _trade_hash(obs.trades),
            "inside_max_acceptable_fill_band": inside,
            "rr_recompute": rr,
            "shadow_executable_price": None if shadow is None else money(shadow),
            "orders_placed": 0,
            "primary_predicate": self.primary,
            "primary_result": primary_result,
            "decision": decision,
            "reasons": reasons,
            "m2_denominator": m2,
            "false_trigger": None,
            "sensitivity_notes": self._sensitivity(),
            "exploration_notes": self._exploration(obs),
            "dual_primary": False,
            "data_origin": obs.data_origin,
            "dedupe": {
                "status": self.dedupe_status,
                "would_trigger_without_dedupe": would_trigger,
                "reactivate_reason": self.reactivate_reason,
                "effect": "LOG_ONLY_NO_ORDERS",
            },
            "p3_freeze": "R1_REJECTION_B2_BREAKOUT",
        }

    def _outcome_event(self, obs: MarketObservation, hit: str) -> dict[str, object]:
        false_trigger = True if hit == "invalidation_first" else False if hit == "target_first" else None
        denominator = "excluded_censored" if hit == "ambiguous_same_print" else "eligible_description_only"
        return {
            "event_type": "outcome_watch",
            "stage": "outcome",
            "exp_id": "EXP-SPRT-20261102",
            "phase": "phase-0",
            "track": "探索",
            "description_only": True,
            "formal_m2_counted": False,
            "formal_window": False,
            "plan_id": self.plan_id,
            "symbol": self.symbol,
            "observed_at_ms": obs.ts_ms,
            "primary_predicate": self.primary,
            "false_trigger": false_trigger,
            "outcome": hit,
            "m2_denominator": denominator,
            "orders_placed": 0,
            "description_only_note": "Phase-0 describes the inv-before-tgt label and does not open a window",
        }
