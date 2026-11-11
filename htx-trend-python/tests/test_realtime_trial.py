from decimal import Decimal as D
from types import SimpleNamespace as NS
import json
import pytest

from htx_trend.realtime.trial import Trial, SqliteTrialStore, Limits
from htx_trend.realtime.trial_facts import project_position, funding_history


def trial(tmp_path):
    return Trial(
        SqliteTrialStore(tmp_path / "trial.sqlite"),
        "one",
        {"account_id": 7, "instance_key": "15m"},
    )


def snapshot(t, at, realized="0", unrealized="0", statuses=None, coverage=True):
    states = {k: {"status": v["status"]} for k, v in t.state["intents"].items()}
    states.update({k: {"status": v} for k, v in (statuses or {}).items()})
    t.observe(
        at,
        dict(
            coverage=coverage,
            realized_net=realized,
            unrealized_net=unrealized,
            intents=states,
        ),
    )


def reserve(t, n, at=1001, risk="1"):
    intent = NS(
        client_order_id=str(n),
        plan_id=f"plan{n}",
        contract_code=f"C{n}-USDT",
        size=NS(risk_at_stop=D(risk)),
    )
    t.reserve(
        intent,
        at,
        equity=D(300),
        remaining_account_risk=D(100),
        per_trade_rate=D(".005"),
    )


def budget(t, at=1001):
    return t.budget(at, equity=300, remaining_account_risk=100, per_trade_rate=".005")


def test_ten_pending_slots_and_first_intent_does_not_pause(tmp_path):
    t = trial(tmp_path)
    t.activate(1000)
    snapshot(t, 1000)
    for n in range(10):
        reserve(t, n)
    assert t.status(1001)["occupied_slots"] == 10
    with pytest.raises(ValueError, match="slots"):
        reserve(t, 10)
    assert t.state["stop_reason"] is None


def test_restart_preserves_loss_and_float_profit_cannot_offset(tmp_path):
    t = trial(tmp_path)
    t.activate(1000)
    snapshot(t, 1000, realized="-19", unrealized="5")
    assert budget(t) == 1
    reserve(t, 1, risk="1")
    resumed = trial(tmp_path)
    assert resumed.state == t.state
    with pytest.raises(ValueError, match="exhausted"):
        budget(resumed)
    snapshot(resumed, 1002, realized="-20", unrealized="100")
    assert resumed.state["stop_reason"] == "LOSS_LIMIT"
    snapshot(resumed, 1003, realized="10", unrealized="100")
    assert resumed.state["stop_reason"] == "LOSS_LIMIT"
    with pytest.raises(ValueError, match="activated"):
        resumed.activate(99999)


def test_floating_loss_adds_to_realized_loss(tmp_path):
    t = trial(tmp_path)
    t.activate(1000)
    snapshot(t, 1000, "-9", "-11")
    assert t.state["stop_reason"] == "LOSS_LIMIT"


def test_equity_cap_is_stricter_than_two(tmp_path):
    t = trial(tmp_path)
    t.activate(1000)
    snapshot(t, 1000)
    assert budget(t) == D("1.5")
    with pytest.raises(ValueError, match="exceeds"):
        reserve(t, 1, risk="2")


def test_expiry_does_not_reset_on_restart(tmp_path):
    t = trial(tmp_path)
    t.activate(1000)
    snapshot(t, 1000)
    with pytest.raises(ValueError, match="expired"):
        budget(t, 86401000)
    assert trial(tmp_path).state["stop_reason"] == "TIME_LIMIT"


def test_explicit_no_deadline_survives_restart_but_keeps_loss_gate(tmp_path):
    store = SqliteTrialStore(tmp_path / "unlimited.sqlite")
    binding = {"account_id": 7, "instance_key": "15m"}
    t = Trial(store, "unlimited", binding, Limits(duration_ms=None))
    t.activate(1000)
    later = 365 * 86400000
    snapshot(t, later)
    assert budget(t, later) == D("1.5")
    assert t.state["expires_ms"] is None
    resumed = Trial(store, "unlimited", binding, Limits(duration_ms=None))
    assert resumed.state == t.state
    assert resumed.status(later)["remaining_ms"] is None
    snapshot(resumed, later + 1, realized="-20")
    assert resumed.state["stop_reason"] == "LOSS_LIMIT"
    with pytest.raises(ValueError, match="stopped"):
        budget(resumed, later + 1)


def test_no_deadline_does_not_migrate_or_clear_a_stopped_timed_trial(tmp_path):
    t = trial(tmp_path)
    t.activate(1000)
    snapshot(t, 86401000)
    assert t.state["stop_reason"] == "TIME_LIMIT"
    with pytest.raises(ValueError, match="identity/limits"):
        Trial(t.store, "one", t.state["binding"], Limits(duration_ms=None))
    assert trial(tmp_path).state["stop_reason"] == "TIME_LIMIT"


def test_no_deadline_keeps_slots_trade_count_coverage_and_budget(tmp_path):
    t = Trial(SqliteTrialStore(tmp_path / "unlimited.sqlite"), "unlimited", {},
              Limits(duration_ms=None))
    t.activate(1000)
    later = 365 * 86400000
    snapshot(t, later, coverage=False)
    with pytest.raises(ValueError, match="coverage"):
        budget(t, later)
    snapshot(t, later)
    with pytest.raises(ValueError, match="exceeds"):
        reserve(t, "oversized", at=later, risk="2")
    for n in range(10):
        reserve(t, n, at=later, risk="1")
    with pytest.raises(ValueError, match="slots"):
        reserve(t, 10, at=later)
    snapshot(t, later + 1, statuses={str(n): "CLOSED" for n in range(10)})
    assert t.state["stop_reason"] == "TRADE_LIMIT"


def test_no_deadline_keeps_clock_snapshot_and_stale_writer_guards(tmp_path):
    store = SqliteTrialStore(tmp_path / "unlimited.sqlite")
    t = Trial(store, "unlimited", {}, Limits(duration_ms=None))
    other = Trial(store, "unlimited", {}, Limits(duration_ms=None))
    t.activate(1000)
    with pytest.raises(ValueError, match="stale"):
        other.activate(1000)
    snapshot(t, 1000)
    with pytest.raises(ValueError, match="stale"):
        budget(t, 4000)
    with pytest.raises(ValueError, match="regressed"):
        snapshot(t, 999)
    assert t.state["stop_reason"] == "CLOCK_REGRESSION"


@pytest.mark.parametrize("duration", [True, False, 0, -1, 1.5, "none", "0"])
def test_duration_requires_explicit_null_or_valid_integer(duration):
    with pytest.raises(ValueError):
        Limits(duration_ms=duration)


def test_partial_exit_not_complete_and_terminal_counts_once(tmp_path):
    t = trial(tmp_path)
    t.activate(1000)
    snapshot(t, 1000)
    for n in range(10):
        reserve(t, n)
    snapshot(t, 1100, statuses={str(n): "OPEN" for n in range(10)})
    assert t.status(1100)["completed_trades"] == 0
    snapshot(t, 1200, statuses={str(n): "CLOSED" for n in range(10)})
    snapshot(t, 1300, statuses={str(n): "CLOSED" for n in range(10)})
    assert t.status(1300)["completed_trades"] == 10
    assert trial(tmp_path).state["stop_reason"] == "TRADE_LIMIT"


def test_zero_fill_releases_slot_unknown_keeps_reservation(tmp_path):
    t = trial(tmp_path)
    t.activate(1000)
    snapshot(t, 1000)
    reserve(t, 1)
    reserve(t, 2)
    snapshot(t, 1100, statuses={"1": "NO_FILL", "2": "UNKNOWN"}, coverage=False)
    assert t.status(1100)["occupied_slots"] == 1
    assert t.status(1100)["reserved_risk"] == "1"
    with pytest.raises(ValueError, match="coverage"):
        budget(t, 1101)


def test_missing_fees_stale_snapshot_and_clock_regression(tmp_path):
    t = trial(tmp_path)
    t.activate(1000)
    snapshot(t, 1000, realized=None, coverage=False)
    with pytest.raises(ValueError, match="coverage"):
        budget(t)
    snapshot(t, 1001)
    with pytest.raises(ValueError, match="stale"):
        budget(t, 4000)
    with pytest.raises(ValueError, match="regressed"):
        snapshot(t, 999)
    assert t.state["stop_reason"] == "CLOCK_REGRESSION"


def test_store_failure_does_not_claim_success_and_stale_writer_rejected(
    tmp_path, monkeypatch
):
    t = trial(tmp_path)
    other = trial(tmp_path)
    t.activate(1000)
    with pytest.raises(ValueError, match="stale"):
        other.activate(1000)
    before = json.dumps(t.state, sort_keys=True)

    def fail(*args):
        raise OSError("disk full")

    monkeypatch.setattr(t.store, "save", fail)
    with pytest.raises(OSError):
        snapshot(t, 1001)
    assert json.dumps(t.state, sort_keys=True) == before


def test_fee_allocation_partial_exit_and_funding_not_double_counted():
    row = dict(
        entry_filled_contracts="10",
        remaining_contract_volume="6",
        side="LONG",
        contract_size="1",
    )
    orders = [
        dict(
            leg="ENTRY",
            trade_volume="10",
            trade_avg_price="100",
            fee="1",
            fee_currency="USDT",
        ),
        dict(
            leg="PARTIAL_CLOSE",
            trade_volume="4",
            trade_avg_price="102",
            fee=".4",
            fee_currency="USDT",
            real_profit="8",
        ),
    ]
    r, u = project_position(row, orders, "-.2", quote="103", exit_fee_rate=".001")
    assert r == D(7) and u == D("16.782")
    assert r + u == D("23.782")
    orders[1]["fee"] = None
    with pytest.raises(ValueError, match="fee"):
        project_position(row, orders, "0", quote=103, exit_fee_rate=".001")


def test_funding_pages_require_terminal_empty_scope_and_progress():
    flow = dict(
        query_id=1,
        ts=1500,
        type=31,
        amount="-.2",
        asset="USDT",
        margin_account="ETH-USDT",
    )

    class API:
        def __init__(self, pages):
            self.pages = iter(pages)
            self.calls = []

        def _signed(self, *args, **kw):
            self.calls.append(kw["body"])
            return next(self.pages)

    api = API([dict(code=200, data=[flow]), dict(code=200, data=[])])
    data = funding_history(api, "ETH-USDT", 1000, 2000)
    assert data["complete"] and api.calls[-1]["from_id"] == 1
    with pytest.raises(ValueError, match="cursor"):
        funding_history(API([dict(code=200, data=[flow])] * 2), "ETH-USDT", 1000, 2000)
    with pytest.raises(ValueError, match="scope"):
        funding_history(
            API([dict(code=200, data=[{**flow, "asset": "BTC"}])]),
            "ETH-USDT",
            1000,
            2000,
        )


@pytest.mark.parametrize(
    "kwargs",
    [
        {"slots": 11},
        {"duration_ms": 86400001},
        {"per_trade": D("2.01")},
        {"loss": D(21)},
        {"trades": 11},
    ],
)
def test_no_implicit_expansion(kwargs):
    with pytest.raises(ValueError):
        Limits(**kwargs)


def test_real_four_trade_receipts_and_missing_funding_are_not_zero_filled():
    from pathlib import Path
    from decimal import InvalidOperation

    data = json.loads(
        (
            Path(__file__).parents[2]
            / "docs/htx-trend-python/evidence/trial-controls-20261103/account-readonly.json"
        ).read_text()
    )
    expected = {
        "SOL-USDT": D("-.53205320"),
        "TRX-USDT": D(".32810496"),
        "ZEC-USDT": D("-1.19708000"),
        "SUI-USDT": D("-.25507476"),
    }
    for row in data["closed_positions"]:
        orders = [{**o["receipt"], "leg": o["leg"]} for o in row["orders"]]
        # Historical receipts are market orders, NOT real IOC validation.
        assert all(o["type"] == "market" for o in orders)
        r, u = project_position(
            row, orders, "0"
        )  # fee-only calculation; hypothetical funding zero explicitly.
        assert r == expected[row["contract_code"]] and u == 0
        with pytest.raises((ValueError, InvalidOperation)):
            project_position(
                row, orders, None
            )  # Unknown settlement never authorizes entry.
        corrupt = [dict(o) for o in orders]
        corrupt[0]["fee"] = None
        with pytest.raises(ValueError, match="fee"):
            project_position(row, corrupt, "0")


def test_real_isolated_funding_pagination_and_final_cost_projection():
    from pathlib import Path

    data = json.loads(
        (
            Path(__file__).parents[2]
            / "docs/htx-trend-python/evidence/trial-controls-20261103/account-readonly.json"
        ).read_text()
    )
    total = D(0)
    for position in data["closed_positions"]:
        pages = iter(position["funding_pages"])

        class API:
            def _signed(self, *args, **kw):
                return next(pages)

        flow = funding_history(
            API(), position["contract_code"], **position["funding_window"]
        )
        assert flow["complete"]
        funding = sum((D(str(x["amount"])) for x in flow["rows"]), D(0))
        if position["contract_code"] == "TRX-USDT":
            assert funding == D("-.00315287")
        orders = [{**o["receipt"], "leg": o["leg"]} for o in position["orders"]]
        r, u = project_position(position, orders, funding)
        total += r
        assert u == 0
    assert total == D("-1.65925587")


def test_empty_management_stream_and_trial_failure_do_not_stop_management(tmp_path):
    from test_realtime_replay import tape, Actor, GRID
    from htx_trend.realtime.factory import IdleManagementStream
    actor=Actor(tape(),GRID[0])
    try:
        w=actor.worker
        t=trial(tmp_path)
        w.trial=t
        w.management_only=True
        w.stream=IdleManagementStream()
        calls=[]
        w.management.tick=lambda now,**kw:calls.append(now) or []
        def fail(now):raise OSError('trial index unavailable')
        w.trial_monitor=NS(tick=fail,close=lambda:None)
        result=w.tick()
        assert calls and 'TRIAL_STATE:OSError' in result.detail
        assert 'entry=false' in result.detail
        assert not actor.ledger.data['fills']
    finally:
        actor.close()
