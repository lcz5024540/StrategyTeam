"""V5 per-trade receipts. Cumulative accounting stays the sole quantity writer."""

from concurrent.futures import ThreadPoolExecutor
from decimal import Decimal as D
import time


def collect(executor, contract, order_id, *, page_size=100, max_pages=10):
    cursor = "0"
    rows = {}
    pages = []
    for _ in range(max_pages):
        page = executor.order_details(
            contract, str(order_id), from_id=cursor, limit=page_size
        )
        pages.append(page)
        for row in page:
            if row.get("contract_code") != contract or str(row.get("order_id")) != str(
                order_id
            ):
                raise ValueError("fill receipt order/contract mismatch")
            ident = str(row["trade_id"])
            if ident in rows and rows[ident] != row:
                raise ValueError("conflicting duplicate fill")
            rows[ident] = row
        if len(page) < page_size:
            return {"pages": pages, "trades": list(rows.values())}
        last = max(int(row["id"]) for row in page)
        if last <= int(cursor):
            raise ValueError("fill pagination made no progress")
        cursor = str(last)
    raise ValueError("fill pagination capacity exceeded")


def validate(receipt, order, *, now_ms):
    rows = receipt["trades"]
    quantity = D(0)
    value = D(0)
    times = []
    fees = []
    ids = []
    for row in rows:
        if (
            row["contract_code"] != order["contract_code"]
            or str(row["order_id"]) != str(order["exchange_order_id"])
            or row["side"].upper() != order["order_side"].upper()
            or row["position_side"].upper() != order["position_side"].upper()
            or row.get("margin_mode") != "isolated"
        ):
            raise ValueError("fill receipt ownership mismatch")
        amount = D(str(row["trade_volume"]))
        price = D(str(row["trade_price"]))
        at = int(row["updated_time"])
        if (
            not amount.is_finite()
            or amount <= 0
            or not price.is_finite()
            or price <= 0
            or not 0 < at <= now_ms
        ):
            raise ValueError("invalid fill quantity/price/matching time")
        ident = str(row["trade_id"])
        if ident in ids:
            raise ValueError("duplicate trade identity")
        ids.append(ident)
        quantity += amount
        value += amount * price
        times.append(at)
        fee = row.get("trade_fee")
        fees.append(
            D(str(fee))
            if fee is not None and row.get("fee_currency") == "USDT"
            else None
        )
        if fees[-1] is not None and not fees[-1].is_finite():
            raise ValueError("invalid fill fee")
    expected = D(str(order.get("filled_contracts") or 0))
    if not rows or quantity != expected:
        raise ValueError("fill detail/cumulative quantity mismatch")
    average = D(
        str(
            order.get("trade_avg_price")
            or order.get("avg_price")
            or order.get("average_price")
            or 0
        )
    )
    # Database projections use average_price; a truncated decimal average may
    # differ in its final scale digit, never in a tick-sized amount.
    if average > 0 and abs(value / quantity - average) > D("0.00000001"):
        raise ValueError("fill detail/cumulative average mismatch")
    known_fee = all(f is not None for f in fees)
    total_fee = sum(fees, D(0)) if known_fee else None
    if (
        known_fee
        and order.get("fee") is not None
        and order.get("fee_currency") == "USDT"
    ):
        if abs(total_fee - D(str(order["fee"]))) > D("0.00000001"):
            raise ValueError("fill detail/cumulative fee mismatch")
    return {
        "first_fill_ms": min(times),
        "last_fill_ms": max(times),
        "detail_quantity": str(quantity),
        "detail_vwap": str(value / quantity),
        "detail_trade_ids": ids,
        "detail_min_fill_price": str(min(D(str(r["trade_price"])) for r in rows)),
        "detail_max_fill_price": str(max(D(str(r["trade_price"])) for r in rows)),
        "detail_fee_status": "KNOWN" if known_fee else "UNKNOWN",
        "detail_fee_usdt": str(total_fee) if known_fee else None,
        "fill_time_source": "HTX_V5_ORDER_DETAILS.updated_time",
    }


class FillRecovery:
    """One asynchronous private reader, state writes only on the existing writer.

    Scan durable plan/order identities, including terminal entries, independently
    of pending_orders. A late receipt is retried after errors or process restart.
    """

    def __init__(
        self, executor, repo, engine, account_id, evidence, *, clock=time.time
    ):
        self.executor, self.repo, self.engine, self.account_id, self.evidence = (
            executor,
            repo,
            engine,
            account_id,
            evidence,
        )
        self.clock = clock
        self.pool = ThreadPoolExecutor(
            max_workers=1, thread_name_prefix="htx-fill-read"
        )
        self.future = None
        self.inflight = None
        self.next_at = {}
        self.complete = set()
        self.closed = False
        self.updated = set()

    def poll(self):
        now_ms = int(self.clock() * 1000)
        self.updated = set()
        if self.future is not None and self.future.done():
            ident, order = self.inflight
            try:
                receipt = self.future.result()
                current = self.repo._one(
                    "SELECT * FROM htx_trend_order WHERE exchange_order_id=%s AND account_id=%s",
                    (order["exchange_order_id"], self.account_id),
                )
                if not current or current["position_id"] != order["position_id"]:
                    raise ValueError("fill order ownership changed")
                order = current
                facts = validate(receipt, order, now_ms=now_ms)
                ref = self.evidence.put(
                    "realtime_fill_details",
                    {
                        "account_id": self.account_id,
                        "order_id": str(order["exchange_order_id"]),
                        "observed_ms": now_ms,
                        **receipt,
                    },
                )
                state = self.repo.exit_state(order["position_id"])
                prior = state.get("first_fill_ms")
                if prior is not None and facts["first_fill_ms"] != prior:
                    raise ValueError("first fill identity changed")
                state.update(
                    facts,
                    fill_receipt_ref=ref,
                    fill_time_status="RECORDED",
                    fill_receipt_status="COMPLETE",
                    fill_receipt_error=None,
                )
                self.repo.save_management_state(order["position_id"], state)
                self.updated.add(order["position_id"])
                # Only freeze a terminal cumulative quantity. Partial live orders
                # can acquire additional receipts using the same intent identity.
                if order["status"] in ("FILLED", "PARTIALLY_CANCELLED", "CANCELLED"):
                    self.complete.add(ident)
            except Exception as error:
                state = self.repo.exit_state(order["position_id"])
                state.update(
                    fill_receipt_status="PENDING",
                    fill_receipt_error=type(error).__name__,
                )
                self.repo.save_management_state(order["position_id"], state)
                self.updated.add(order["position_id"])
            finally:
                self.next_at[ident] = now_ms + 5000
                self.future = None
                self.inflight = None
        if self.future is not None or self.closed:
            return self.updated
        for ident, row in self.engine.rows.items():
            if (
                ident in self.complete
                or self.next_at.get(ident, 0) > now_ms
                or not row.get("position_id")
            ):
                continue
            self.next_at[ident] = now_ms + 5000
            order = self.repo._one(
                "SELECT * FROM htx_trend_order WHERE client_order_id=%s AND account_id=%s",
                (row.get("client_order_id"), self.account_id),
            )
            if (
                not order
                or not order.get("exchange_order_id")
                or D(str(order.get("filled_contracts") or 0)) <= 0
            ):
                continue
            state = self.repo.exit_state(order["position_id"])
            if (
                state.get("fill_receipt_status") == "COMPLETE"
                and D(state["detail_quantity"]) == D(str(order["filled_contracts"]))
                and order["status"] in ("FILLED", "PARTIALLY_CANCELLED", "CANCELLED")
            ):
                self.complete.add(ident)
                continue
            if state.get("fill_receipt_status") == "COMPLETE" and D(
                state["detail_quantity"]
            ) != D(str(order["filled_contracts"])):
                state.update(fill_receipt_status="PENDING", extrema_coverage="GAP")
                self.repo.save_management_state(order["position_id"], state)
                self.updated.add(order["position_id"])
            submitted = state.get("entry_execution", {}).get("submitted_ms", now_ms)
            if now_ms - submitted > 3 * 86400000:
                state["fill_receipt_status"] = "EXPIRED_UNRECOVERABLE"
                self.repo.save_management_state(order["position_id"], state)
                self.complete.add(ident)
                self.updated.add(order["position_id"])
                continue
            self.inflight = (ident, dict(order))
            self.future = self.pool.submit(
                collect,
                self.executor,
                order["contract_code"],
                order["exchange_order_id"],
            )
            break
        return self.updated

    def close(self):
        self.closed = True
        self.pool.shutdown(wait=True, cancel_futures=True)
