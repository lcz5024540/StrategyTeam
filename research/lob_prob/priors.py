"""排队竞速先验。可选失衡只在 logit 上做加性倾斜，系数不在这里估计。"""
from decimal import Decimal, localcontext

ALLOWED_TILT_WINDOWS_MIN = (1, 5, 15)


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


def queue_race_p_up(qb, qa):
    """p_up = Qb / (Qb + Qa)。双边都是 0 时无定义。"""
    bid = _dec(qb)
    ask = _dec(qa)
    if bid < 0 or ask < 0:
        raise ValueError("队列量不能为负")
    total = bid + ask
    if total == 0:
        raise ValueError("双边队列都为 0，排队竞速先验无定义")
    return bid / total


def apply_logit_tilt(p_up, imbalance_i):
    """logit(p) = logit(p_up) + I。I 为 0 时原样返回。"""
    probability = _dec(p_up)
    tilt = _dec(imbalance_i)
    if tilt == 0:
        return probability
    if not (Decimal(0) < probability < Decimal(1)):
        raise ValueError("logit 倾斜要求先验严格位于 (0, 1)")
    with localcontext() as ctx:
        ctx.prec = 50
        logit = (probability / (Decimal(1) - probability)).ln()
        return Decimal(1) / (Decimal(1) + (-(logit + tilt)).exp())


def contemporaneous_return_residual(realized_return, baseline_return):
    """同期收益减去同期基准。调用者保证两边都在信号时点已经实现。"""
    return _dec(realized_return) - _dec(baseline_return)


def tilt_from_residual(residual, scale):
    """I = scale × 同期收益残差。scale 由预注册给定，本函数不拟合。"""
    return _dec(residual) * _dec(scale)


def validate_tilt_window(window_min):
    """失衡窗口只允许 1、5、15 分钟。"""
    if isinstance(window_min, bool) or not isinstance(window_min, int):
        raise ValueError("失衡窗口必须是 1、5 或 15 分钟")
    if window_min not in ALLOWED_TILT_WINDOWS_MIN:
        raise ValueError("失衡窗口必须是 1、5 或 15 分钟")
    return window_min
