"""费后 EV：taker 固定 12 bps；maker 只用实测费和漏成率。"""
from decimal import Decimal

import pytest

from ev import UnmeasuredParameter, account_ev


def test_taker_ev_subtracts_12_bps():
    row = account_ev(Decimal("20"), role="taker")
    assert row["fee_bps"] == Decimal("12")
    assert row["miss_rate"] == Decimal("0")
    assert row["fill_rate"] == Decimal("1")
    assert row["ev_bps"] == Decimal("8")

    loss = account_ev(Decimal("-5"), role="taker")
    assert loss["ev_bps"] == Decimal("-17")


def test_maker_ev_uses_measured_fee_and_miss_rate():
    row = account_ev(
        Decimal("20"),
        role="maker",
        maker_bps=Decimal("2"),
        miss_rate=Decimal("0.25"),
    )
    assert row["fee_bps"] == Decimal("2")
    assert row["miss_rate"] == Decimal("0.25")
    assert row["fill_rate"] == Decimal("0.75")
    assert row["ev_bps"] == Decimal("13.5")
    assert row["ev_bps"] == row["fill_rate"] * (row["gross_bps"] - row["fee_bps"])


def test_full_miss_books_zero_and_rebate_is_kept():
    missed = account_ev(
        Decimal("20"),
        role="maker",
        maker_bps=Decimal("2"),
        miss_rate=Decimal("1"),
    )
    assert missed["ev_bps"] == Decimal("0")
    rebate = account_ev(
        Decimal("20"),
        role="maker",
        maker_bps=Decimal("-1"),
        miss_rate=Decimal("0"),
    )
    assert rebate["ev_bps"] == Decimal("21")


def test_maker_refuses_unmeasured_fee_or_miss_rate():
    with pytest.raises(UnmeasuredParameter):
        account_ev(Decimal("20"), role="maker", maker_bps="measured", miss_rate=Decimal("0.1"))
    with pytest.raises(UnmeasuredParameter):
        account_ev(Decimal("20"), role="maker", maker_bps=None, miss_rate=Decimal("0.1"))
    with pytest.raises(UnmeasuredParameter):
        account_ev(Decimal("20"), role="maker", maker_bps=Decimal("1"), miss_rate=None)
    with pytest.raises(UnmeasuredParameter):
        account_ev(Decimal("20"), role="maker", maker_bps=Decimal("1"), miss_rate="measured")


def test_taker_fee_cannot_be_rewritten():
    with pytest.raises(ValueError):
        account_ev(Decimal("20"), role="taker", taker_bps=Decimal("5"))
    with pytest.raises(ValueError):
        account_ev(Decimal("20"), role="taker", miss_rate=Decimal("0.2"))
