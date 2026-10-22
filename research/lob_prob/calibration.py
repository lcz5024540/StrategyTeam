"""时间切分外样本上的分箱校准。门槛 400，不够就零结论。"""
from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal, ROUND_FLOOR, localcontext

INSUFFICIENT_SAMPLE = "样本不足，零结论"
MIN_BIN_COUNT_FLOOR = 400


class ThresholdLoweringForbidden(ValueError):
    def __init__(self):
        super().__init__("禁止降低分箱门槛")


@dataclass(frozen=True)
class Bin:
    count: int
    mean_p: Decimal
    frequency: Decimal


@dataclass(frozen=True)
class CalibrationReport:
    status: str
    min_bin_count: int
    n_train: int
    n_test: int
    bins: tuple
    reliability_slope: Decimal | None
    brier: Decimal | None
    brier_prior: Decimal | None
    log_loss: Decimal | None
    log_loss_prior: Decimal | None


def _dec(value):
    if isinstance(value, bool) or value is None:
        raise TypeError("数值不能是布尔或空值")
    if isinstance(value, Decimal):
        number = value
    elif isinstance(value, int):
        number = Decimal(value)
    elif isinstance(value, float):
        number = Decimal(str(value))
    elif isinstance(value, str):
        number = Decimal(value)
    else:
        raise TypeError("无法解析数值")
    if not number.is_finite():
        raise ValueError("数值必须是有限的")
    return number


def _outcome(value):
    number = _dec(value)
    if number == 0:
        return Decimal(0)
    if number == 1:
        return Decimal(1)
    raise ValueError("校准用的结果必须是 0 或 1")


def _probability(value):
    number = _dec(value)
    if not (Decimal(0) < number < Decimal(1)):
        raise ValueError("概率必须严格位于 (0, 1)")
    return number


def _rows(y, p_model, p_prior, timestamps):
    outcomes = list(y)
    model = list(p_model)
    prior = list(p_prior)
    stamps = list(timestamps)
    width = len(outcomes)
    if not (len(model) == len(prior) == len(stamps) == width):
        raise ValueError("y、概率和时间戳长度必须相同")
    if width == 0:
        raise ValueError("没有样本")
    return [
        (_outcome(outcomes[i]), _probability(model[i]), _probability(prior[i]), _dec(stamps[i]))
        for i in range(width)
    ]


def _insufficient(min_bin_count, n_train, n_test):
    return CalibrationReport(
        status=INSUFFICIENT_SAMPLE,
        min_bin_count=min_bin_count,
        n_train=n_train,
        n_test=n_test,
        bins=(),
        reliability_slope=None,
        brier=None,
        brier_prior=None,
        log_loss=None,
        log_loss_prior=None,
    )


def _make_bins(samples, min_count):
    ordered = [item for _, item in sorted(enumerate(samples), key=lambda item: (item[1][1], item[0]))]
    count = len(ordered)
    if count < 2 * min_count:
        return []
    spans = []
    start = 0
    while count - start >= min_count:
        rest = count - (start + min_count)
        if rest == 0 or rest >= min_count:
            spans.append((start, start + min_count))
            start += min_count
        else:
            spans.append((start, count))
            start = count
    bins = []
    for lo, hi in spans:
        chunk = ordered[lo:hi]
        size = hi - lo
        if size < min_count:
            return []
        mean_p = sum((row[1] for row in chunk), Decimal(0)) / Decimal(size)
        frequency = sum((row[0] for row in chunk), Decimal(0)) / Decimal(size)
        bins.append(Bin(size, mean_p, frequency))
    return bins


def _slope(bins):
    xs = [item.mean_p for item in bins]
    ys = [item.frequency for item in bins]
    width = Decimal(len(xs))
    mean_x = sum(xs, Decimal(0)) / width
    mean_y = sum(ys, Decimal(0)) / width
    variance = sum(((x - mean_x) ** 2 for x in xs), Decimal(0))
    if variance == 0:
        return None
    covariance = sum(((x - mean_x) * (y - mean_y) for x, y in zip(xs, ys)), Decimal(0))
    return covariance / variance


def _brier(rows, column):
    total = sum(((row[column] - row[0]) ** 2 for row in rows), Decimal(0))
    return total / Decimal(len(rows))


def _log_loss(rows, column):
    with localcontext() as ctx:
        ctx.prec = 50
        total = Decimal(0)
        for row in rows:
            probability = row[column]
            chosen = probability if row[0] == 1 else Decimal(1) - probability
            total += -chosen.ln()
        return total / Decimal(len(rows))


def calibrate(y, p_model, p_prior, timestamps, *, train_fraction, min_bin_count=MIN_BIN_COUNT_FLOOR):
    """只在时间切分的后段上出结论。训练段不参与计分，也不用来补测试段。

    结果 y 由调用者定成 0/1。推荐把首达 +k 记为 1，把 -k 和超时都记为 0。
    纯先验是同一批外样本上的 p_prior。每箱至少 min_bin_count 条，尾箱并入相邻箱；
    凑不满两箱、或预测值没有差异时，返回「样本不足，零结论」，不降低门槛。
    """
    if isinstance(min_bin_count, bool) or not isinstance(min_bin_count, int):
        raise TypeError("min_bin_count 必须是整数")
    if min_bin_count < MIN_BIN_COUNT_FLOOR:
        raise ThresholdLoweringForbidden()
    fraction = _dec(train_fraction)
    if not (Decimal(0) < fraction < Decimal(1)):
        raise ValueError("train_fraction 必须在 (0, 1) 内")
    rows = _rows(y, p_model, p_prior, timestamps)
    order = sorted(range(len(rows)), key=lambda i: (rows[i][3], i))
    n_train = int((Decimal(len(rows)) * fraction).to_integral_value(rounding=ROUND_FLOOR))
    test = [rows[i] for i in order[n_train:]]
    n_test = len(test)
    if n_test < 2 * min_bin_count:
        return _insufficient(min_bin_count, n_train, n_test)
    bins = _make_bins(test, min_bin_count)
    if len(bins) < 2:
        return _insufficient(min_bin_count, n_train, n_test)
    slope = _slope(bins)
    if slope is None:
        return _insufficient(min_bin_count, n_train, n_test)
    return CalibrationReport(
        status="ok",
        min_bin_count=min_bin_count,
        n_train=n_train,
        n_test=n_test,
        bins=tuple(bins),
        reliability_slope=slope,
        brier=_brier(test, 1),
        brier_prior=_brier(test, 2),
        log_loss=_log_loss(test, 1),
        log_loss_prior=_log_loss(test, 2),
    )
