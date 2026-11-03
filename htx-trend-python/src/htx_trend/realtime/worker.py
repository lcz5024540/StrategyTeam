"""Single-writer realtime scheduler. Public structure I/O runs off the execution loop."""

from __future__ import annotations
from ..sealing.consumers import realtime_capture_tick

from concurrent.futures import ThreadPoolExecutor, as_completed
from decimal import Decimal as D
import threading
import time

from ..execution import ExecutionConfig
from ..java_entry_contract import evaluate as signal_for, JavaEntryConfig
from ..market_data import OrderBook
from ..models import TrendSide
from ..strategy_platform import WorkerHealth
from .plans import Parameters, generate
from .risk import evaluate
from .storage import TERMINAL
from .management import paper_reentry, check_reentry


class FrameLoader:
    def __init__(self, market, contracts, frames, *, clock=time.time):
        self.market, self.contracts, self.frames, self.clock = (
            market,
            tuple(contracts),
            frames,
            clock,
        )
        self.stop_event = threading.Event()
        self.thread = None
        self.errors = {}
        self.requests = {}

    def start(self):
        self.thread = threading.Thread(
            target=self.run, name="htx-structure-refresh", daemon=True
        )
        self.thread.start()

    def fetch(self, code):
        now = int(self.clock())
        windows = {
            p: self.market.kline_window(code, p, 160, now_epoch=now)
            for p in ("15min", "60min", "4hour")
        }
        if any(not w.is_complete for w in windows.values()):
            raise ValueError("incomplete structure evidence")
        return {
            "entry": windows["15min"],
            "trend": windows["60min"],
            "windows": windows,
            "contract": self.market.contract_spec(code),
            "received_ms": int(self.clock() * 1000),
            "evidence_failed": any(
                w.evidence_status == "FAILED" for w in windows.values()
            ),
        }

    def run(self):
        with ThreadPoolExecutor(
            max_workers=4, thread_name_prefix="htx-structure"
        ) as pool:
            while not self.stop_event.is_set():
                now = int(self.clock())
                closed = (now - 10) // 900 * 900 - 900
                due = [
                    c
                    for c in self.contracts
                    if self.frames.get(c, {}).get("entry") is None
                    or self.frames[c]["entry"].candles[-1].open_time < closed
                ]
                due = [c for c in due if now - self.requests.get(c, 0) >= 5]
                jobs = {pool.submit(self.fetch, c): c for c in due}
                for c in due:
                    self.requests[c] = now
                for future in as_completed(jobs):
                    code = jobs[future]
                    try:
                        frame = future.result()
                        self.frames[code] = frame
                        if frame["evidence_failed"]:
                            self.errors[code] = "EVIDENCE_FAILED"
                        else:
                            self.errors.pop(code, None)
                    except Exception as error:
                        self.errors[code] = type(error).__name__
                self.stop_event.wait(0.5)

    def close(self):
        self.stop_event.set()
        if self.thread:
            self.thread.join(timeout=20)


class MarketView:
    def __init__(self, public, frames, books, *, clock=time.time):
        self.public, self.frames, self.books, self.clock = public, frames, books, clock
        self.fallback = {}
        self.degraded = set()

    def order_book(self, code):
        now = int(self.clock() * 1000)
        event = self.books.get(code)
        if event is not None and event.fresh(now):
            self.degraded.discard(code)
            return OrderBook(
                ((event.bid, event.bid_size or D(0)),),
                ((event.ask, event.ask_size or D(0)),),
            )
        self.degraded.add(code)
        saved = self.fallback.get(code)
        if saved and now - saved[0] < 1000:
            if saved[1] is None:
                raise ValueError("REST stop quote retry throttled")
            return saved[1]
        self.fallback[code] = (now, None)
        book = self.public.order_book(code)
        if int(self.clock() * 1000) - now > 2000:
            raise ValueError("REST stop quote exceeded freshness deadline")
        self.fallback[code] = (int(self.clock() * 1000), book)
        return book

    def kline_window(self, code, period, size, **kwargs):
        frame = self.frames.get(code)
        if frame is None or period not in frame["windows"]:
            raise ValueError("closed-bar cache unavailable")
        window = frame["windows"][period]
        if len(window.candles) < size:
            raise ValueError("closed-bar cache short")
        return window

    def __getattr__(self, name):
        return getattr(self.public, name)


class RealtimeWorker:
    interval_seconds = 0.05
    checkpoint_interval_seconds = 10
    heartbeat_interval_seconds = 45

    def __init__(
        self,
        instance,
        engine,
        broker,
        management,
        stream,
        loader,
        market,
        frames,
        recorder,
        evidence_store,
        *,
        parameters=Parameters(),
        execution=ExecutionConfig(),
        leverage=D(5),
        entry_enabled=False,
        guard=lambda: None,
        admission_guard=lambda: None,
        clock=time.time,
        holding_parameters=None,
        entry_config=None,
    ):
        self.instance, self.engine, self.broker, self.management = (
            instance,
            engine,
            broker,
            management,
        )
        self.stream, self.loader, self.market, self.frames = (
            stream,
            loader,
            market,
            frames,
        )
        self.recorder, self.evidence = recorder, evidence_store
        self.parameters, self.execution, self.leverage = parameters, execution, leverage
        self.entry_enabled, self.guard, self.admission_guard = (
            entry_enabled,
            guard,
            admission_guard,
        )
        self.clock = clock
        self.holding_parameters = holding_parameters or {}
        self.entry_config = entry_config or JavaEntryConfig()
        self._entry_recovery_blocked = False
        self._drain_requested = lambda: False
        self._save_runtime = lambda: None
        self.stopped = False
        self.started = False
        self.failed = None
        self.last_epoch = None
        self.refreshed = {}
        self.last_reconcile_ms = 0
        self.last_flush_ms = 0
        self.recovery_error = None
        self.trial = None
        self.trial_monitor = None
        self.trial_connection = None
        self.management_only = False

    def runtime_key(self):
        return "REALTIME_STRUCTURE_15M"

    def export_runtime(self):
        return {
            "instance": self.instance.instance_key,
            "strategy": self.instance.strategy_name,
            "parameters": self.parameters.fingerprint,
            "failures": self.failed,
            "ledger_path": str(self.broker.ledger.path.resolve())
            if hasattr(self.broker, "ledger")
            else None,
        }

    @staticmethod
    def validate_runtime_state(state):
        from pathlib import Path
        from . import VERSION

        required = {"instance", "strategy", "parameters", "failures", "ledger_path"}
        if set(state) != required or state["strategy"] != VERSION:
            raise ValueError("unsupported realtime runtime state")
        if not isinstance(state["instance"], str) or not state["instance"]:
            raise ValueError("missing realtime instance identity")
        fingerprint = state["parameters"]
        if (
            not isinstance(fingerprint, str)
            or len(fingerprint) != 64
            or any(char not in "0123456789abcdef" for char in fingerprint)
        ):
            raise ValueError("invalid realtime parameters fingerprint")
        if (
            state["ledger_path"] is not None
            and not Path(state["ledger_path"]).is_absolute()
        ):
            raise ValueError("realtime ledger path must be absolute")

    def restore_runtime(self, state):
        self.validate_runtime_state(state)
        if (
            state["instance"] != self.instance.instance_key
            or state["strategy"] != self.instance.strategy_name
        ):
            raise ValueError("realtime checkpoint ownership mismatch")
        if hasattr(self.broker, "ledger") and (
            not self.broker.ledger.loaded
            or state["ledger_path"] != str(self.broker.ledger.path.resolve())
        ):
            raise ValueError("realtime PAPER ledger missing or relocated")
        self.failed = state["failures"]
        # Plans/intent identities are loaded from their authoritative store;
        # no network opportunity survives an unobserved restart interval.
        self.engine.disconnect()

    def prepare_management(self):
        return []

    def _preflight(self, trigger):
        now = int(self.clock() * 1000)
        if not self.stream.connected or self.stream.epoch != trigger.epoch:
            raise ValueError("trigger connection interrupted")
        frame = self.frames[trigger.plan.contract]
        if not frame["entry"].is_complete:
            raise ValueError("incomplete structure")
        if not 0 <= now // 1000 - (frame["entry"].candles[-1].open_time + 900) < 900:
            raise ValueError("structure cache stale")
        if signal_for(frame["entry"].candles, self.entry_config).side not in (
            TrendSide.FLAT,
            trigger.side,
        ):
            raise ValueError("15m direction opposed")
        source_ref = self.recorder.flush()
        if source_ref is None:
            raise ValueError("trigger events not durable")
        # Public depth is fetched at submission time, not the old signal book.
        started_ms = int(self.clock() * 1000)
        book = self.market.order_book(trigger.plan.contract)
        contract = frame["contract"]
        funding = self.market.funding_rate(trigger.plan.contract)
        facts = self.broker.facts(
            contract, trigger.side, book, self.execution, self.leverage
        )
        check_ms = int(self.clock() * 1000)
        if check_ms - started_ms > 2000:
            raise ValueError("preflight depth stale")
        ref = self.evidence.put(
            "realtime_trigger",
            {
                "trigger": trigger,
                "segment_hash": source_ref,
                "book": book,
                "book_requested_ms": started_ms,
                "book_received_ms": check_ms,
                "funding": funding,
                "facts": facts,
            },
            refs=trigger.plan.evidence_refs,
        )
        effective = self.execution
        if self.trial is not None and self.entry_enabled:
            effective = self.trial.execution_config(effective, facts, check_ms)
        gate, intent = evaluate(
            trigger,
            now_ms=check_ms,
            book=book,
            contract=contract,
            funding=funding,
            facts=facts,
            instance_key=self.instance.instance_key,
            parameters=self.parameters,
            config=effective,
            evidence_ref=ref,
            recent_candles=frame["entry"].candles,
        )
        previous = None
        if hasattr(self.broker, "ledger"):
            block = paper_reentry(
                self.broker.ledger,
                self.broker.account,
                trigger,
                frame["entry"].candles[-1],
                book.top,
                self.evidence,
            )
        else:
            from ..execution_service import _complete_exit_facts

            row = self.broker.repo.latest_done_trade(
                account_id=self.broker.account.account_id,
                contract_code=contract.contract_code,
                timeframe_profile="INTRADAY_15M",
            )
            previous = _complete_exit_facts(self.broker.repo, row)
            block = check_reentry(
                trigger, frame["entry"].candles[-1], book.top, previous
            )
        if block is not None:
            gate, intent = block, None
        self.evidence.put(
            "realtime_entry_decision",
            {
                "trigger": trigger,
                "gate": gate,
                "intent": intent,
                "previous_exit": previous,
                "entry_enabled": self.entry_enabled,
                "at_ms": check_ms,
            },
            refs=(ref,),
        )
        return gate, intent, contract, facts, check_ms

    @realtime_capture_tick
    def tick(self):
        if self.stopped:
            return WorkerHealth("STOPPED", "realtime worker stopped")
        self.guard()
        now = int(self.clock() * 1000)
        failures = []
        triggers = []
        if not self.started:
            try:
                self.broker.recover()
                self.recovery_error = None
            except Exception as error:
                self.recovery_error = "RECOVERY:" + type(error).__name__
            self.stream.start()
            self.loader.start()
            self.started = True
        if now - self.last_reconcile_ms >= 1000 and hasattr(self.broker, "entry"):
            try:
                self.broker.recover()
                self.recovery_error = None
            except Exception as error:
                self.recovery_error = "RECOVERY:" + type(error).__name__
            self.last_reconcile_ms = now
        now = int(self.clock() * 1000)
        if self.recovery_error:
            failures.append(self.recovery_error)
        # Initial stop checks do not depend on structure refresh or evidence writes.
        failures.extend(self.management.tick(now))
        if self.trial_monitor is not None:
            try:
                self.trial_monitor.tick(now)
            except Exception as error:
                failures.append('TRIAL_STATE:' + type(error).__name__)
                self._entry_recovery_blocked = True
        if self.management_only:
            status = self.trial.status(now) if self.trial else {}
            # Only owned contracts are subscribed. Existing realtime positions still
            # need post-fill coverage and immediate stops during entry shutdown.
            if self.stream.epoch != self.last_epoch:
                try:
                    self.management.gap()
                    self.last_epoch = self.stream.epoch
                except Exception as error:
                    failures.append('MANAGEMENT_GAP:' + type(error).__name__)
            for event in self.stream.drain():
                try:
                    if not self.recorder.append(event):
                        failures.append(self.recorder.failure or 'CAPTURE_FAILED')
                        self.management.gap(contract=event.contract)
                    if event.kind == 'BBO' and event.fresh(now):
                        self.engine.books[event.contract] = event
                        self.management.event(event, now)
                        failures.extend(self.management.tick(now, contract=event.contract))
                except Exception as error:
                    failures.append('MANAGEMENT_EVENT:' + type(error).__name__)
            if now - self.last_flush_ms >= 10000:
                self.recorder.flush()
                self.last_flush_ms = now
            return WorkerHealth('DEGRADED' if failures else 'HEALTHY',
                                'MANAGEMENT_ONLY; trial=' + str(status['trial_id']) +
                                '; entry=false; activated=' + str(status['activated']) + ('; ' + ','.join(failures) if failures else ''))
        if self.stream.epoch != self.last_epoch:
            try:
                self.engine.disconnect()
                self.management.gap()
                self.last_epoch = self.stream.epoch
                self.recorder.append(
                    {"kind": "GAP", "received_ms": now, "epoch": self.last_epoch}
                )
            except Exception as error:
                failures.append("GAP_STATE:" + type(error).__name__)
        if self.recorder.failure:
            failures.append(self.recorder.failure)
        if self.loader.errors:
            failures.extend(c + ":" + v for c, v in sorted(self.loader.errors.items()))
        for code, frame in list(self.frames.items()):
            try:
                windows = frame["windows"]
                key = tuple((p, w.window_hash) for p, w in windows.items())
                if self.refreshed.get(code) == key:
                    continue
                signal = signal_for(frame["entry"].candles, self.entry_config)
                plans = generate(
                    frame["entry"],
                    frame["trend"],
                    frame["contract"],
                    now_ms=now,
                    parameters=self.parameters,
                    signal_side=signal.side,
                )
                self.engine.refresh(code, plans)
                refs = tuple(r for w in windows.values() for r in w.evidence_refs)
                self.evidence.put(
                    "realtime_structure_refresh",
                    {
                        "contract": code,
                        "plans": plans,
                        "signal_side": signal.side,
                        "at_ms": now,
                        "window_hashes": key,
                    },
                    refs=refs,
                )
                self.refreshed[code] = key
            except Exception as error:
                failures.append(code + ":STRUCTURE:" + type(error).__name__)
        # Preserve arrival order. A later book cannot confirm an earlier trade.
        for event in self.stream.drain():
            now = int(self.clock() * 1000)
            if not self.stream.connected or event.epoch != self.stream.epoch:
                continue
            if not self.recorder.append(event):
                failures.append(self.recorder.failure or "CAPTURE_FAILED")
                try:
                    self.management.gap()
                except Exception as error:
                    failures.append("GAP_STATE:" + type(error).__name__)
            if event.kind == "BBO" and not event.fresh(now):
                self.recorder.append(
                    {
                        "kind": "GAP",
                        "contract": event.contract,
                        "received_ms": now,
                        "epoch": event.epoch,
                        "reason": "STALE_BBO",
                    }
                )
                self.management.gap(contract=event.contract)
                failures.append("STALE_BBO:" + event.contract)
            try:
                triggers.extend(self.engine.accept(event, now_ms=now))
            except Exception as error:
                failures.append("PLAN_STATE:" + type(error).__name__)
            if event.kind == "BBO":
                if hasattr(self.broker, "on_book"):
                    self.broker.on_book(event, now_ms=now)
                try:
                    self.management.event(event, now)
                except Exception as error:
                    failures.append("POSITION_EVENT:" + type(error).__name__)
                # Also protect fills and transient stop breaches inside this batch.
                failures.extend(self.management.tick(now, contract=event.contract))
        from itertools import groupby

        for _, batch in groupby(
            sorted(triggers, key=lambda t: (t.trigger_ms, t.plan.plan_id)),
            key=lambda t: t.trigger_ms,
        ):
            ready = []
            for trigger in batch:
                try:
                    if (
                        failures
                        or self._entry_recovery_blocked
                        or not self.stream.connected
                    ):
                        raise ValueError("runtime data/management degraded")
                    gate, intent, contract, facts, check_ms = self._preflight(trigger)
                    if intent is None or not self.entry_enabled:
                        self.engine.terminal(
                            trigger.plan.plan_id,
                            "REJECTED",
                            reason=gate.reason if intent is None else "SHADOW_ONLY",
                        )
                    else:
                        ready.append((intent.price.expected_net_r, trigger))
                except Exception as error:
                    self._reject(trigger, error, failures)
            for _, trigger in sorted(
                ready, key=lambda item: (-item[0], item[1].plan.plan_id)
            ):
                try:
                    # Re-read depth and account risk after any preceding intent.
                    failures.extend(self.management.tick(int(self.clock() * 1000)))
                    if failures or self._drain_requested():
                        raise ValueError("management degraded or drain requested")
                    gate, intent, contract, facts, check_ms = self._preflight(trigger)
                    if intent is None:
                        self.engine.terminal(
                            trigger.plan.plan_id, "REJECTED", reason=gate.reason
                        )
                        continue
                    import shutil
                    if self.trial is not None and shutil.disk_usage(self.recorder.root).free < 5 * 1024**3:
                        if self.trial is not None:
                            self.trial.stop('DISK_LIMIT')
                        raise ValueError('disk free space below 5GiB')
                    self._save_runtime()
                    self.admission_guard()
                    if self._drain_requested():
                        raise ValueError("drain requested before submission")
                    kwargs = {
                        "now_ms": check_ms,
                        "fee_rate": facts.actual_taker_fee_rate,
                        "holding_parameters": self.holding_parameters,
                    }
                    if hasattr(self.broker, "entry"):
                        kwargs.update(facts=facts, config=self.execution)
                    self.broker.submit(intent, trigger, contract, **kwargs)
                except Exception as error:
                    self._reject(trigger, error, failures)
        if now - self.last_flush_ms >= 10000:
            self.recorder.flush()
            self.last_flush_ms = now
        if not self.stream.connected:
            failures.append("WS_DISCONNECTED")
        detail = "realtime " + (
            "SHADOW" if not self.entry_enabled else self.instance.execution_mode
        )
        if failures:
            detail += "; " + ",".join(sorted(set(failures)))[:600]
        if self.trial is not None:
            status = self.trial.status(now)
            detail += '; trial=' + status['trial_id'] + '; slots=' + str(status['occupied_slots']) + '/10' + '; stop=' + str(status['stop_reason'])
        self.failed = tuple(failures)
        count = (
            len(self.broker.ledger.active_positions(self.broker.account))
            if hasattr(self.broker, "ledger")
            else self.broker.repo.active_position_count(
                account_id=self.broker.account.account_id
            )
        )
        return WorkerHealth(
            "DEGRADED" if failures else "HEALTHY", detail, len(triggers), count
        )

    def _reject(self, trigger, error, failures):
        try:
            phase = self.engine.rows[trigger.plan.plan_id]["phase"]
            if phase not in TERMINAL:
                self.engine.terminal(
                    trigger.plan.plan_id,
                    "REJECTED",
                    reason=type(error).__name__ + ":" + str(error),
                )
        except Exception as storage_error:
            failures.append("TERMINAL_PERSISTENCE:" + type(storage_error).__name__)
        failures.append(type(error).__name__)

    def stop(self):
        if self.stopped:
            return
        self.stopped = True
        self.entry_enabled = False
        self.stream.close()
        self.loader.close()
        self.recorder.flush()
        if hasattr(self.broker, "close"):
            self.broker.close()
        if self.trial_monitor is not None:
            self.trial_monitor.close()
        if self.trial_connection is not None:
            self.trial_connection.close()
        if hasattr(self.management, "flush"):
            self.management.flush()
        if hasattr(self.broker, "ledger"):
            self.broker.ledger.checkpoint()
            self.broker.ledger.close()
