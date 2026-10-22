"""首达标签：信号后下一整秒起算，H 内先碰到 +k 或 -k，否则超时。"""
from decimal import Decimal

from labels import first_touch_label, next_whole_second


def test_next_whole_second_is_strictly_after_signal():
    assert next_whole_second("10.2") == Decimal("11")
    assert next_whole_second("10") == Decimal("11")
    assert next_whole_second("10.0") == Decimal("11")


def test_first_touch_up_ignores_prints_before_start():
    label = first_touch_label(
        mid0="100",
        path=[(10.5, "200"), (11, "100"), (12, "101"), (13, "102"), (14, "90")],
        signal_ts="10.2",
        k_ticks=2,
        tick_size="1",
        horizon_s=5,
    )
    assert label == 1


def test_first_touch_down_wins_when_it_happens_first():
    label = first_touch_label(
        mid0="100",
        path=[(11, "100"), (12, "99"), (13, "98"), (14, "110")],
        signal_ts="10.2",
        k_ticks=2,
        tick_size="1",
        horizon_s=5,
    )
    assert label == -1


def test_timeout_when_barrier_is_only_breached_after_horizon():
    # 起点 11，H=5，窗口止于 16。16.1 的突破不算；窗内价格未到 ±2。
    label = first_touch_label(
        mid0="100",
        path=[(16.1, "110"), (11, "100"), (16, "101"), (12, "99")],
        signal_ts="10.1",
        k_ticks=2,
        tick_size="1",
        horizon_s=5,
    )
    assert label == 0


def test_touch_exactly_at_horizon_end_counts():
    label = first_touch_label(
        mid0="100",
        path=[(16, "102")],
        signal_ts="10",
        k_ticks=2,
        tick_size="1",
        horizon_s=5,
    )
    assert label == 1


def test_barrier_lies_on_tick_grid():
    label = first_touch_label(
        mid0="0.3",
        path=[(11, "0.3"), (12, "0.4")],
        signal_ts="10.2",
        k_ticks=1,
        tick_size="0.1",
        horizon_s=5,
    )
    assert label == 1
    untouched = first_touch_label(
        mid0="0.3",
        path=[(11, "0.3"), (12, "0.39")],
        signal_ts="10.2",
        k_ticks=1,
        tick_size="0.1",
        horizon_s=5,
    )
    assert untouched == 0
