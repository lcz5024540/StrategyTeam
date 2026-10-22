"""分箱门槛 400：不够就合并或不出，样本不够则零结论，禁止降门槛。"""
from decimal import Decimal
import math

import pytest

from calibration import (
    INSUFFICIENT_SAMPLE,
    ThresholdLoweringForbidden,
    calibrate,
)


def _column(rows, index):
    return [row[index] for row in rows]


def _balanced_test_rows(start_ts):
    rows = []
    ts = start_ts
    for _ in range(80):
        rows.append((ts, 1, Decimal("0.2"), Decimal("0.5")))
        ts += 1
    for _ in range(320):
        rows.append((ts, 0, Decimal("0.2"), Decimal("0.5")))
        ts += 1
    for _ in range(320):
        rows.append((ts, 1, Decimal("0.8"), Decimal("0.5")))
        ts += 1
    for _ in range(80):
        rows.append((ts, 0, Decimal("0.8"), Decimal("0.5")))
        ts += 1
    return rows


def test_reliability_brier_and_log_loss_on_time_split_test_only():
    rows = [(i, 1, Decimal("0.9"), Decimal("0.9")) for i in range(800)]
    rows.extend(_balanced_test_rows(800))
    report = calibrate(
        _column(rows, 1),
        _column(rows, 2),
        _column(rows, 3),
        _column(rows, 0),
        train_fraction=Decimal("0.5"),
    )
    assert report.status == "ok"
    assert report.n_train == 800
    assert report.n_test == 800
    assert report.min_bin_count == 400
    assert [bin_.count for bin_ in report.bins] == [400, 400]
    assert report.bins[0].mean_p == Decimal("0.2")
    assert report.bins[0].frequency == Decimal("0.2")
    assert report.bins[1].mean_p == Decimal("0.8")
    assert report.bins[1].frequency == Decimal("0.8")
    assert report.reliability_slope == Decimal("1")
    assert report.brier == Decimal("0.16")
    assert report.brier_prior == Decimal("0.25")
    expected_log_loss = (160 * -math.log(0.2) + 640 * -math.log(0.8)) / 800
    assert float(report.log_loss) == pytest.approx(expected_log_loss, rel=1e-12)
    assert float(report.log_loss_prior) == pytest.approx(math.log(2), rel=1e-12)


def test_short_bin_is_merged_and_train_rows_stay_out():
    rows = [(i, 0, Decimal("0.01"), Decimal("0.5")) for i in range(100)]
    ts = 100
    for _ in range(400):
        rows.append((ts, 0, Decimal("0.2"), Decimal("0.5")))
        ts += 1
    for _ in range(400):
        rows.append((ts, 1, Decimal("0.5"), Decimal("0.5")))
        ts += 1
    for _ in range(50):
        rows.append((ts, 1, Decimal("0.9"), Decimal("0.5")))
        ts += 1
    report = calibrate(
        _column(rows, 1),
        _column(rows, 2),
        _column(rows, 3),
        _column(rows, 0),
        train_fraction=Decimal(100) / Decimal(950),
    )
    assert report.status == "ok"
    assert report.n_train == 100
    assert report.n_test == 850
    assert [bin_.count for bin_ in report.bins] == [400, 450]
    assert min(bin_.count for bin_ in report.bins) >= 400
    assert report.bins[1].mean_p == Decimal("245") / Decimal("450")


def test_insufficient_test_sample_returns_zero_conclusion_without_lowering_floor():
    rows = [(i, 1, Decimal("0.6"), Decimal("0.5")) for i in range(10700)]
    report = calibrate(
        _column(rows, 1),
        _column(rows, 2),
        _column(rows, 3),
        _column(rows, 0),
        train_fraction=Decimal(10000) / Decimal(10700),
    )
    assert report.status == INSUFFICIENT_SAMPLE
    assert report.status == "样本不足，零结论"
    assert report.n_train == 10000
    assert report.n_test == 700
    assert report.min_bin_count == 400
    assert report.bins == ()
    assert report.reliability_slope is None
    assert report.brier is None
    assert report.brier_prior is None
    assert report.log_loss is None
    assert report.log_loss_prior is None


def test_bin_count_below_400_is_refused():
    rows = [(i, 1, Decimal("0.6"), Decimal("0.5")) for i in range(2000)]
    with pytest.raises(ThresholdLoweringForbidden):
        calibrate(
            _column(rows, 1),
            _column(rows, 2),
            _column(rows, 3),
            _column(rows, 0),
            train_fraction=Decimal("0.5"),
            min_bin_count=399,
        )
