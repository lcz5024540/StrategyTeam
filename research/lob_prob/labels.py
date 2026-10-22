"""H 秒内按 tick 网格的首达标签。起点是信号之后的下一整秒。"""
from decimal import Decimal, ROUND_FLOOR


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


def next_whole_second(signal_ts):
    """返回严格晚于信号的下一整秒。信号落在整秒上时也要再进一秒。"""
    whole = _dec(signal_ts).to_integral_value(rounding=ROUND_FLOOR)
    return whole + 1


def first_touch_label(mid0, path, *, signal_ts, k_ticks, tick_size, horizon_s):
    """窗内第一次碰到上障返回 1，下障返回 -1，否则返回 0。

    上障是起点中间价加 k 个 tick，下障是起点中间价减 k 个 tick。
    只使用起点到起点加 H（含两端）的价格。路径按时间排序，起点之前和窗口之后的打印都忽略。
    """
    if isinstance(k_ticks, bool) or not isinstance(k_ticks, int) or k_ticks <= 0:
        raise ValueError("k_ticks 必须是正整数")
    tick = _dec(tick_size)
    horizon = _dec(horizon_s)
    if tick <= 0 or horizon <= 0:
        raise ValueError("tick_size 和 horizon_s 必须为正")
    start = next_whole_second(signal_ts)
    end = start + horizon
    origin = _dec(mid0)
    up = origin + (Decimal(k_ticks) * tick)
    down = origin - (Decimal(k_ticks) * tick)
    for ts, mid in sorted(path, key=lambda row: _dec(row[0])):
        stamp = _dec(ts)
        if stamp < start or stamp > end:
            continue
        price = _dec(mid)
        if price >= up:
            return 1
        if price <= down:
            return -1
    return 0
