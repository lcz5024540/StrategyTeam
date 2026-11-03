"""Read-only exchange settlement coverage and trial-only PnL projection.

API reads run off the price-stop loop. Absence of a verified terminal empty
funding page or a real order fee blocks entry; ledger defaults are not evidence.
"""

import time
from concurrent.futures import ThreadPoolExecutor
from copy import deepcopy
from decimal import Decimal as D

from ..execution_service import _terminal_status
from .trial import number


def funding_history(client, margin_account, start_ms, end_ms, *, max_pages=100):
    if not 0 < end_ms - start_ms <= 48 * 3600 * 1000:
        raise ValueError("funding coverage window must be within 48 hours")
    cursor, rows, pages = 0, {}, []
    for _ in range(max_pages):
        response = client._signed(
            "POST",
            "/linear-swap-api/v3/swap_financial_record_exact",
            body={
                "mar_acct": margin_account,
                "type": "30,31",
                "start_time": start_ms,
                "end_time": end_ms,
                "direct": "next",
                "from_id": cursor,
            },
        )
        page = response.get("data")
        if not isinstance(page, list) or response.get("code") != 200:
            raise ValueError("invalid funding envelope")
        pages.append(response)
        if not page:
            return dict(
                start_ms=start_ms,
                end_ms=end_ms,
                margin_account=margin_account,
                rows=list(rows.values()),
                pages=pages,
                complete=True,
            )
        for row in page:
            ident = int(row["query_id"])
            if ident <= cursor or ident in rows:
                raise ValueError("funding cursor duplicate or out of order")
            if (
                not start_ms <= int(row["ts"]) <= end_ms
                or str(row["type"]) not in ("30", "31")
                or row.get("asset") != "USDT"
                or row.get("margin_account") != margin_account
            ):
                raise ValueError("funding scope/currency mismatch")
            amount = number(row["amount"])
            if (str(row["type"]) == "30" and amount < 0) or (
                str(row["type"]) == "31" and amount > 0
            ):
                raise ValueError("funding amount sign mismatch")
            rows[ident] = row
        cursor = max(rows)
    raise ValueError("funding page limit; incomplete coverage")


def project_position(row, orders, funding, *, quote=None, exit_fee_rate=None):
    """Use actual cumulative fills; opening fees allocated once by closed fraction."""
    entry, closed, entry_value, close_value, open_fee, close_fee = (
        D(0) for _ in range(6)
    )
    gross = D(0)
    for order in orders:
        q = number(order["trade_volume"])
        if q == 0:
            continue
        if order.get("fee") is None or order.get("fee_currency") != "USDT":
            raise ValueError("actual order fee missing")
        px, fee = number(order["trade_avg_price"]), number(order["fee"])
        if q < 0 or px <= 0:
            raise ValueError("invalid cumulative execution facts")
        if order["leg"] == "ENTRY":
            entry += q
            entry_value += q * px
            open_fee += fee
        else:
            if order.get("real_profit") is None:
                raise ValueError("actual realized profit missing")
            closed += q
            close_value += q * px
            close_fee += fee
            gross += number(order["real_profit"])
    if closed > entry or entry != number(row["entry_filled_contracts"]):
        raise ValueError("order/position quantities inconsistent")
    remaining = entry - closed
    if remaining != number(row["remaining_contract_volume"]):
        raise ValueError("position remaining differs from orders")
    if entry == 0:
        return D(0), D(0)
    realized = gross - close_fee - open_fee * closed / entry + number(funding)
    if remaining == 0:
        return realized, D(0)
    if quote is None or exit_fee_rate is None:
        raise ValueError("fresh executable quote and exit fee required")
    quote, rate = number(quote), number(exit_fee_rate)
    if quote <= 0 or rate < 0:
        raise ValueError("invalid exit quote/fee")
    sign = 1 if row["side"] == "LONG" else -1
    units = remaining * number(row["contract_size"])
    unrealized = (
        (quote - entry_value / entry) * sign * units
        - open_fee * remaining / entry
        - quote * units * rate
    )
    return realized, unrealized


class LiveTrialReader:
    def __init__(self, repository_factory, client_factory, evidence):
        self.repository_factory, self.client_factory, self.evidence = (
            repository_factory,
            client_factory,
            evidence,
        )

    def __call__(self, state):
        if not state["intents"]:
            return dict(
                coverage=True,
                reason=None,
                intents={},
                positions=[],
                source_ms=int(time.time() * 1000),
                evidence_ref=None,
            )
        repo = self.repository_factory()
        client = None
        start_ms = int(time.time() * 1000)
        result = dict(
            coverage=True, reason=None, intents={}, positions=[], source_ms=start_ms
        )
        try:
            client = self.client_factory(repo)
            rows = []
            for ident, reserved in state["intents"].items():
                row = repo._one(
                    "SELECT p.* FROM htx_trend_position p JOIN htx_trend_order o ON o.position_id=p.id "
                    "WHERE o.client_order_id=%s AND p.account_id=%s AND p.strategy_name=%s",
                    (
                        ident,
                        state["binding"]["account_id"],
                        state["binding"]["strategy_version"],
                    ),
                )
                plan_row = repo._one(
                    "SELECT state_json FROM htx_trend_realtime_plan WHERE instance_key=%s AND plan_id=%s",
                    (state["binding"]["instance_key"], reserved["plan_id"]),
                )
                if plan_row is not None:
                    import json

                    plan = (
                        json.loads(plan_row["state_json"])
                        if isinstance(plan_row["state_json"], str)
                        else plan_row["state_json"]
                    )
                    if plan.get("phase") == "REJECTED":
                        result["intents"][ident] = dict(
                            status="NO_FILL", position_id=row["id"] if row else None
                        )
                        continue
                if row is None:
                    result["intents"][ident] = dict(status="UNKNOWN", position_id=None)
                    result.update(coverage=False, reason="RESERVED_INTENT_UNRESOLVED")
                    continue
                orders = repo._all(
                    "SELECT * FROM htx_trend_order WHERE position_id=%s ORDER BY id",
                    (row["id"],),
                )
                raw = []
                for order in orders:
                    value = client.order(
                        row["contract_code"],
                        client_order_id=str(order["client_order_id"]),
                    )
                    if (
                        str(value.get("client_order_id"))
                        != str(order["client_order_id"])
                        or value.get("contract_code") != row["contract_code"]
                        or value.get("margin_mode") != "isolated"
                    ):
                        raise ValueError("trial order receipt identity mismatch")
                    raw.append({**value, "leg": order["leg"]})
                terminal = all(
                    _terminal_status(o)
                    in ("FILLED", "CANCELLED", "PARTIALLY_CANCELLED", "REJECTED")
                    for o in raw
                )
                entered = number(row["entry_filled_contracts"])
                remaining = number(row["remaining_contract_volume"])
                status = (
                    (
                        "NO_FILL"
                        if entered == 0
                        else "CLOSED"
                        if remaining == 0
                        else "OPEN"
                    )
                    if terminal
                    else "PENDING"
                )
                result["intents"][ident] = dict(status=status, position_id=row["id"])
                if not terminal:
                    result.update(coverage=False, reason="ORDER_RECONCILIATION_PENDING")
                rows.append((ident, row, raw, repo.exit_state(row["id"])))
            for code in sorted(
                {
                    r[1]["contract_code"]
                    for r in rows
                    if number(r[1]["entry_filled_contracts"]) > 0
                }
            ):
                # Trial orders are isolated; querying USDT cross alone misses this account.
                history = funding_history(client, code, state["started_ms"], start_ms)
                self.evidence.put("trial_funding_coverage", history)
                for flow in history["rows"]:
                    if flow.get("contract_code", flow.get("contract")) != code:
                        raise ValueError("funding contract mismatch")
                related = [
                    r
                    for r in rows
                    if r[1]["contract_code"] == code
                    and number(r[1]["entry_filled_contracts"]) > 0
                ]
                # Same-contract sequential trades require exact fill intervals, never divide fees by guess.
                assigned = {r[0]: D(0) for r in related}
                for flow in history["rows"]:
                    matches = []
                    for ident, row, raw, context in related:
                        first = context.get("first_fill_ms")
                        end = (
                            max(int(o.get("updated_time", 0)) for o in raw)
                            if number(row["remaining_contract_volume"]) == 0
                            else start_ms
                        )
                        if first is not None and first <= int(flow["ts"]) <= end:
                            matches.append(ident)
                    if len(matches) != 1:
                        raise ValueError(
                            "funding cannot uniquely map to trial position"
                        )
                    assigned[matches[0]] += number(flow["amount"])
                for ident, row, raw, _ in related:
                    from ..account_facts import _taker_fee

                    rate = (
                        _taker_fee(client.swap_fee(code))
                        if number(row["remaining_contract_volume"]) > 0
                        else None
                    )
                    result["positions"].append(
                        dict(
                            row=row,
                            orders=raw,
                            funding=str(assigned[ident]),
                            exit_fee_rate=str(rate),
                        )
                    )
            result["evidence_ref"] = self.evidence.put(
                "trial_account_observation", result
            )
            return result
        finally:
            if client is not None:
                client.close()
            repo._connection.close()


class BackgroundTrialMonitor:
    def __init__(self, trial, reader, books):
        self.trial, self.reader, self.books = trial, reader, books
        self.pool = ThreadPoolExecutor(
            max_workers=1, thread_name_prefix="htx-trial-facts"
        )
        self.future = None
        self.latest = None
        self.last = 0

    def tick(self, now_ms):
        if self.future is not None and self.future.done():
            try:
                self.latest = self.future.result()
            except Exception as error:
                self.latest = dict(
                    coverage=False,
                    reason=type(error).__name__,
                    intents={},
                    positions=[],
                    source_ms=now_ms,
                )
            self.future = None
        if now_ms - self.last < 1000:
            return
        self.last = now_ms
        if self.future is None:
            self.future = self.pool.submit(self.reader, deepcopy(self.trial.state))
        snap = self.latest
        if snap is None:
            return
        snap = deepcopy(snap)
        realized, unrealized = D(0), D(0)
        try:
            if not snap["coverage"]:
                raise ValueError(snap.get("reason") or "account coverage incomplete")
            if not 0 <= now_ms - snap["source_ms"] <= 2000:
                raise ValueError("account coverage stale")
            for position in snap.pop("positions"):
                row = position["row"]
                quote = None
                if number(row["remaining_contract_volume"]) > 0:
                    event = self.books.get(row["contract_code"])
                    if event is None or not event.fresh(now_ms):
                        raise ValueError("trial mark stale")
                    quote = event.bid if row["side"] == "LONG" else event.ask
                r, u = project_position(
                    row,
                    position["orders"],
                    position["funding"],
                    quote=quote,
                    exit_fee_rate=position["exit_fee_rate"],
                )
                realized += r
                unrealized += u
            snap.update(realized_net=str(realized), unrealized_net=str(unrealized))
        except Exception as error:
            snap.update(
                coverage=False,
                reason=str(error),
                realized_net=None,
                unrealized_net=None,
            )
        self.trial.observe(now_ms, snap)

    def close(self):
        self.pool.shutdown(wait=False, cancel_futures=True)
