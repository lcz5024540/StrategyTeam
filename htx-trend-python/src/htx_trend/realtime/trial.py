"""Restart-safe bounded trial controls. Formal financial admission is never implied.

Only the single account writer mutates this state. MySQL compare-and-swap also
rejects stale writers; reservations are durable before any exchange mutation.
"""

from __future__ import annotations

import json
import sqlite3
from copy import deepcopy
from dataclasses import dataclass, replace
from decimal import Decimal as D

from ..research.store import canonical

SCHEMA = "htx-realtime-trial-state-v1"


def number(value):
    result = D(str(value))
    if not result.is_finite():
        raise ValueError("nonfinite trial money")
    return result


@dataclass(frozen=True)
class Limits:
    per_trade: D = D(2)
    loss: D = D(20)
    slots: int = 10
    trades: int = 10
    # None is an explicitly authorized no-deadline mode, not a legacy reset.
    duration_ms: int | None = 86400000

    def __post_init__(self):
        if not (D(0) < number(self.per_trade) <= 2 and D(0) < number(self.loss) <= 20):
            raise ValueError("trial monetary limits exceed authorization")
        if any(
            type(v) is not int or v <= 0
            for v in (self.slots, self.trades)
        ):
            raise ValueError("trial count/duration must be positive integers")
        if self.duration_ms is not None and (
            type(self.duration_ms) is not int or not 0 < self.duration_ms <= 86400000
        ):
            raise ValueError("trial duration must be null or a positive integer <=24h")
        if self.slots > 10 or self.trades > 10:
            raise ValueError("trial count/duration exceeds authorization")

    def payload(self):
        return dict(
            per_trade=str(self.per_trade),
            loss=str(self.loss),
            slots=self.slots,
            trades=self.trades,
            duration_ms=self.duration_ms,
        )


class SqliteTrialStore:
    """Offline tests only; never selected by LIVE factory."""

    def __init__(self, path):
        self.db = sqlite3.connect(path)
        self.db.execute("PRAGMA synchronous=FULL")
        self.db.execute(
            "CREATE TABLE IF NOT EXISTS trial (id TEXT PRIMARY KEY, revision INTEGER, state TEXT)"
        )
        self.db.commit()

    def load(self, trial_id):
        row = self.db.execute(
            "SELECT state FROM trial WHERE id=?", (trial_id,)
        ).fetchone()
        return json.loads(row[0]) if row else None

    def save(self, state, previous):
        with self.db:
            if previous is None:
                self.db.execute(
                    "INSERT INTO trial VALUES(?,?,?)",
                    (state["trial_id"], state["revision"], canonical(state).decode()),
                )
            elif (
                self.db.execute(
                    "UPDATE trial SET revision=?,state=? WHERE id=? AND revision=?",
                    (
                        state["revision"],
                        canonical(state).decode(),
                        state["trial_id"],
                        previous,
                    ),
                ).rowcount
                != 1
            ):
                raise ValueError("stale trial writer")


class MySqlTrialStore:
    def __init__(self, connection):
        self.db = connection
        with self.db.cursor() as c:
            c.execute(
                "SELECT trial_id,revision,state_json FROM htx_trend_live_trial LIMIT 0"
            )

    def load(self, trial_id):
        with self.db.cursor() as c:
            c.execute(
                "SELECT state_json FROM htx_trend_live_trial WHERE trial_id=%s",
                (trial_id,),
            )
            row = c.fetchone()
        if row is None:
            return None
        return (
            json.loads(row["state_json"])
            if isinstance(row["state_json"], str)
            else row["state_json"]
        )

    def save(self, state, previous):
        try:
            with self.db.cursor() as c:
                if previous is None:
                    c.execute(
                        "INSERT INTO htx_trend_live_trial(trial_id,account_id,instance_key,revision,state_json) "
                        "VALUES(%s,%s,%s,%s,%s)",
                        (
                            state["trial_id"],
                            state["binding"]["account_id"],
                            state["binding"]["instance_key"],
                            state["revision"],
                            canonical(state).decode(),
                        ),
                    )
                else:
                    c.execute(
                        "UPDATE htx_trend_live_trial SET revision=%s,state_json=%s "
                        "WHERE trial_id=%s AND revision=%s",
                        (
                            state["revision"],
                            canonical(state).decode(),
                            state["trial_id"],
                            previous,
                        ),
                    )
                    if c.rowcount != 1:
                        raise ValueError("stale trial writer")
            self.db.commit()
        except BaseException:
            self.db.rollback()
            raise


class Trial:
    def __init__(self, store, trial_id, binding, limits=Limits()):
        self.store, self.limits = store, limits
        saved = store.load(trial_id)
        if saved is None:
            saved = dict(
                schema=SCHEMA,
                trial_id=trial_id,
                binding=binding,
                limits=limits.payload(),
                revision=0,
                started_ms=None,
                expires_ms=None,
                stop_reason=None,
                intents={},
                snapshot=None,
                last_clock_ms=0,
            )
            store.save(saved, None)
        if (
            saved["schema"] != SCHEMA
            or saved["binding"] != binding
            or saved["limits"] != limits.payload()
        ):
            raise ValueError(
                "trial identity/limits mismatch; restart cannot renew a trial"
            )
        self.state = saved

    def _save(self, new):
        previous = self.state["revision"]
        new["revision"] = previous + 1
        self.store.save(new, previous)
        self.state = new  # Never report persistence success before commit.

    def activate(self, now_ms):
        if self.state["started_ms"] is not None or self.state["stop_reason"]:
            raise ValueError("trial already activated or stopped; cannot renew")
        new = deepcopy(self.state)
        new.update(
            started_ms=now_ms,
            expires_ms=(
                None if self.limits.duration_ms is None
                else now_ms + self.limits.duration_ms
            ),
            last_clock_ms=now_ms,
        )
        self._save(new)

    def stop(self, reason):
        if not self.state["stop_reason"]:
            new = deepcopy(self.state)
            new["stop_reason"] = reason
            self._save(new)

    def observe(self, now_ms, snapshot):
        """Snapshot is derived from linked authoritative orders, not event counters.

        Each linked intent must be present. Missing/unknown facts retain reserved
        risk and block entry. Realized net allocates entry fees to closed quantity;
        open net includes unallocated entry fees and estimated exit cost.
        """
        new = deepcopy(self.state)
        if now_ms < new["last_clock_ms"]:
            self.stop("CLOCK_REGRESSION")
            raise ValueError("trial clock regressed")
        new["last_clock_ms"] = now_ms
        states = snapshot.get("intents", {})
        for ident, reservation in new["intents"].items():
            value = states.get(ident)
            if value is None:
                snapshot = {
                    **snapshot,
                    "coverage": False,
                    "reason": "INTENT_COVERAGE_MISSING",
                }
                continue
            if value["status"] not in {
                "PENDING",
                "OPEN",
                "CLOSED",
                "NO_FILL",
                "UNKNOWN",
            }:
                raise ValueError("invalid trial intent state")
            if (
                reservation["status"] in ("CLOSED", "NO_FILL")
                and value["status"] != reservation["status"]
            ):
                raise ValueError("terminal trial state regressed")
            reservation.update(
                status=value["status"], position_id=value.get("position_id")
            )
        realized = snapshot.get("realized_net")
        unrealized = snapshot.get("unrealized_net")
        covered = (
            snapshot.get("coverage") is True
            and realized is not None
            and unrealized is not None
        )
        loss = (
            max(D(0), -number(realized)) + max(D(0), -number(unrealized))
            if covered
            else None
        )
        # A known loss component can lock the trial even if another is unknown.
        known_loss = (
            max(D(0), -number(realized)) if realized is not None else D(0)
        ) + (max(D(0), -number(unrealized)) if unrealized is not None else D(0))
        new["snapshot"] = {
            **snapshot,
            "coverage": covered,
            "observed_ms": now_ms,
            "loss": str(loss) if loss is not None else None,
        }
        completed = sum(v["status"] == "CLOSED" for v in new["intents"].values())
        if not new["stop_reason"]:
            if known_loss >= self.limits.loss:
                new["stop_reason"] = "LOSS_LIMIT"
            elif new["expires_ms"] is not None and now_ms >= new["expires_ms"]:
                new["stop_reason"] = "TIME_LIMIT"
            elif completed >= self.limits.trades:
                new["stop_reason"] = "TRADE_LIMIT"
        self._save(new)

    def budget(self, now_ms, *, equity, remaining_account_risk, per_trade_rate):
        state = self.state
        if state["stop_reason"]:
            raise ValueError("trial stopped: " + state["stop_reason"])
        if state["started_ms"] is None:
            raise ValueError("trial not activated")
        if state["expires_ms"] is not None and now_ms >= state["expires_ms"]:
            self.stop("TIME_LIMIT")
            raise ValueError("trial expired")
        snap = state["snapshot"]
        if (
            not snap
            or not snap["coverage"]
            or not 0 <= now_ms - snap["observed_ms"] <= 2000
        ):
            raise ValueError("trial PnL/fee/settlement coverage unavailable or stale")
        used = [
            v
            for v in state["intents"].values()
            if v["status"] not in ("NO_FILL", "CLOSED")
        ]
        complete = sum(v["status"] == "CLOSED" for v in state["intents"].values())
        # Avoid accepting more potential full trades than the approved ten.
        if len(used) >= self.limits.slots or complete + len(used) >= self.limits.trades:
            raise ValueError("trial position/trade slots exhausted")
        reserved = sum(
            (number(v["risk"]) for v in used if v["status"] in ("PENDING", "UNKNOWN")),
            D(0),
        )
        remaining = self.limits.loss - number(snap["loss"]) - reserved
        amount = min(
            self.limits.per_trade,
            number(equity) * number(per_trade_rate),
            number(remaining_account_risk),
            remaining,
        )
        if amount <= 0:
            raise ValueError("trial risk budget exhausted")
        return amount

    def reserve(
        self, intent, now_ms, *, equity, remaining_account_risk, per_trade_rate
    ):
        ident = intent.client_order_id
        if ident in self.state["intents"]:
            raise ValueError("trial intent already reserved; reconcile same client ID")
        budget = self.budget(
            now_ms,
            equity=equity,
            remaining_account_risk=remaining_account_risk,
            per_trade_rate=per_trade_rate,
        )
        risk = number(intent.size.risk_at_stop)
        if not 0 < risk <= budget:
            raise ValueError("trial planned risk exceeds remaining budget")
        new = deepcopy(self.state)
        new["intents"][ident] = dict(
            plan_id=intent.plan_id,
            contract=intent.contract_code,
            risk=str(risk),
            status="PENDING",
            position_id=None,
            submitted_ms=now_ms,
        )
        self._save(new)

    def status(self, now_ms):
        s = self.state
        used = [
            v for v in s["intents"].values() if v["status"] not in ("NO_FILL", "CLOSED")
        ]
        complete = sum(v["status"] == "CLOSED" for v in s["intents"].values())
        return dict(
            trial_id=s["trial_id"],
            activated=s["started_ms"] is not None,
            stop_reason=s["stop_reason"],
            entry_blocked_reason=(
                s["stop_reason"]
                or (
                    "NOT_ACTIVATED"
                    if s["started_ms"] is None
                    else "COVERAGE_UNAVAILABLE"
                    if not s["snapshot"] or not s["snapshot"]["coverage"]
                    else None
                )
            ),
            remaining_ms=max(0, s["expires_ms"] - now_ms) if s["expires_ms"] else None,
            completed_trades=complete,
            remaining_trades=max(0, self.limits.trades - complete),
            occupied_slots=len(used),
            max_slots=self.limits.slots,
            reserved_risk=str(
                sum(
                    (
                        number(v["risk"])
                        for v in used
                        if v["status"] in ("PENDING", "UNKNOWN")
                    ),
                    D(0),
                )
            ),
            unknown_orders=sum(v["status"] == "UNKNOWN" for v in used),
            snapshot=s["snapshot"],
        )

    def execution_config(self, config, facts, now_ms):
        budget = self.budget(
            now_ms,
            equity=facts.account_equity_usdt,
            remaining_account_risk=facts.account_equity_usdt
            * config.max_total_open_risk_rate
            - facts.reserved_risk_usdt,
            per_trade_rate=config.max_risk_per_trade_rate,
        )
        return replace(
            config,
            max_risk_per_trade_usdt=min(config.max_risk_per_trade_usdt, budget),
            max_active_positions=min(config.max_active_positions, self.limits.slots),
        )
