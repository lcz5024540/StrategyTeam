import copy, json
from pathlib import Path
from types import SimpleNamespace
from decimal import Decimal as D
import pytest
from htx_trend.realtime.fills import collect, validate, FillRecovery
from htx_trend.research.store import FileEvidenceStore


def row(ident="1", at=1000, qty="1", price="100"):
    return dict(
        id=ident,
        trade_id=ident,
        contract_code="ETH-USDT",
        order_id="77",
        side="buy",
        position_side="long",
        margin_mode="isolated",
        trade_price=price,
        trade_volume=qty,
        updated_time=str(at),
        created_time=str(at),
        trade_fee=".04",
        fee_currency="USDT",
    )


def order(qty="1", status="FILLED"):
    return dict(
        position_id=1,
        contract_code="ETH-USDT",
        exchange_order_id=77,
        order_side="BUY",
        position_side="LONG",
        filled_contracts=qty,
        average_price="100",
        status=status,
    )


def test_verified_real_v5_response_uses_matching_time_not_local_query():
    sample = json.loads(
        (
            Path(__file__).parent / "fixtures/realtime/v5-real-readonly-fill.json"
        ).read_text()
    )
    r = sample["response"]["data"][0]
    result = validate(
        {"trades": [r]},
        dict(
            order(),
            contract_code=r["contract_code"],
            exchange_order_id=r["order_id"],
            order_side=r["side"],
            position_side=r["position_side"],
            average_price=r["trade_price"],
        ),
        now_ms=sample["response"]["ts"],
    )
    assert result["first_fill_ms"] == int(r["updated_time"]) < sample["response"]["ts"]
    assert result["detail_fee_usdt"] == r["trade_fee"]


def test_pages_duplicates_order_and_quantity_validation():
    pages = [
        [row("1", 1000), row("2", 1100)],
        [row("2", 1100), row("3", 1050)],
        [row("3", 1050)],
    ]
    calls = []

    def query(*args, **kwargs):
        calls.append(kwargs["from_id"])
        return pages.pop(0)

    result = collect(
        SimpleNamespace(order_details=query), "ETH-USDT", "77", page_size=2
    )
    facts = validate(result, order("3"), now_ms=1200)
    assert (
        facts["first_fill_ms"] == 1000
        and facts["last_fill_ms"] == 1100
        and facts["detail_fee_usdt"] == "0.12"
    )
    assert calls == ["0", "2", "3"]
    with pytest.raises(ValueError, match="quantity mismatch"):
        validate(result, order("4"), now_ms=1200)
    wrong = copy.deepcopy(result)
    wrong["trades"][0]["order_id"] = "other"
    with pytest.raises(ValueError, match="ownership"):
        validate(wrong, order("3"), now_ms=1200)


def test_pagination_must_advance_and_unknown_fees_not_zero():
    executor = SimpleNamespace(order_details=lambda *a, **k: [row()])
    with pytest.raises(ValueError, match="no progress"):
        collect(executor, "ETH-USDT", "77", page_size=1)
    r = row()
    r["trade_fee"] = None
    assert validate({"trades": [r]}, order(), now_ms=2000)["detail_fee_usdt"] is None


class Repo:
    def __init__(self):
        self.state = {
            "entry_execution": {"submitted_ms": 500},
            "highest": "103",
            "last_bar": 900,
        }
        self.order = order()

    def _one(self, *_):
        return copy.deepcopy(self.order)

    def exit_state(self, _):
        return copy.deepcopy(self.state)

    def save_management_state(self, _, state):
        self.state = copy.deepcopy(state)


def recovery(tmp_path, repo, executor, clock):
    engine = SimpleNamespace(
        rows={"p": {"phase": "FILLED", "position_id": 1, "client_order_id": "fixed"}}
    )
    store = FileEvidenceStore(tmp_path)
    return FillRecovery(executor, repo, engine, 7, store, clock=clock), store


def test_terminal_order_failed_details_retries_after_restart(tmp_path):
    repo = Repo()

    def fail(*a, **k):
        raise OSError("read unavailable")

    clock = [2]
    one, store = recovery(
        tmp_path, repo, SimpleNamespace(order_details=fail), lambda: clock[0]
    )
    one.poll()
    with pytest.raises(OSError):
        one.future.result(timeout=2)
    one.poll()
    assert repo.state["fill_receipt_status"] == "PENDING"
    one.close()
    two, _ = recovery(
        tmp_path,
        repo,
        SimpleNamespace(order_details=lambda *a, **k: [row()]),
        lambda: clock[0],
    )
    two.poll()
    two.future.result(timeout=2)
    two.poll()
    assert (
        repo.state["first_fill_ms"] == 1000
        and repo.state["last_bar"] == 900
        and repo.state["highest"] == "103"
    )
    assert two.updated == {1} and repo.state["fill_receipt_status"] == "COMPLETE"
    two.poll()
    assert two.future is None
    two.close()
    store.close()


def test_receipt_evidence_failure_does_not_mark_complete(tmp_path, monkeypatch):
    repo = Repo()
    recover, store = recovery(
        tmp_path,
        repo,
        SimpleNamespace(order_details=lambda *a, **k: [row()]),
        lambda: 2,
    )
    monkeypatch.setattr(
        store, "put", lambda *a, **k: (_ for _ in ()).throw(OSError("full"))
    )
    recover.poll()
    recover.future.result(timeout=2)
    recover.poll()
    assert (
        repo.state["fill_receipt_status"] == "PENDING"
        and "first_fill_ms" not in repo.state
    )
    recover.close()
    store.close()


def test_partial_receipt_is_checked_against_current_cumulative_projection(tmp_path):
    repo = Repo()
    repo.order["status"] = "PARTIALLY_FILLED"
    recover, store = recovery(
        tmp_path,
        repo,
        SimpleNamespace(order_details=lambda *a, **k: [row()]),
        lambda: 2,
    )
    recover.poll()
    recover.future.result(timeout=2)
    repo.order["filled_contracts"] = "2"
    recover.poll()
    assert repo.state["fill_receipt_status"] == "PENDING"
    assert "first_fill_ms" not in repo.state
    recover.close()
    store.close()


def test_receipt_preserves_rebate_sign_and_checks_cumulative_fee():
    r = row()
    r["trade_fee"] = "-.01"
    o = dict(order(), fee="-.01", fee_currency="USDT")
    assert validate({"trades": [r]}, o, now_ms=2000)["detail_fee_usdt"] == "-0.01"
    o["fee"] = ".01"
    with pytest.raises(ValueError, match="fee mismatch"):
        validate({"trades": [r]}, o, now_ms=2000)
