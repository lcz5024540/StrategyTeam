"""费后 EV 记账。taker 锁定 12 bps；maker 的费和漏成率必须是实测数。"""
from decimal import Decimal

TAKER_BPS = Decimal("12")
UNFILLED_PNL_BPS = Decimal("0")


class UnmeasuredParameter(ValueError):
    """maker 费率或漏成率仍是占位符，不能拿去算期望。"""


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


def _measured(name, value):
    if value is None:
        raise UnmeasuredParameter(f"{name} 未实测，拒绝记账")
    if isinstance(value, str) and value.strip() == "measured":
        raise UnmeasuredParameter(f"{name} 未实测，拒绝记账")
    return _dec(value)


def account_ev(gross_bps, *, role, maker_bps=None, miss_rate=None, taker_bps=TAKER_BPS):
    """把一笔已签名的毛收益记成费后期望，单位 bps。

    taker：成交率按 1，费用固定 12 bps，ev = gross - 12。
    maker：ev = (1 - 漏成率) * (gross - 实测 maker 费) + 漏成率 * 0。
    未成交不改记成 taker。maker 费和漏成率缺实测值时直接拒绝。
    """
    gross = _dec(gross_bps)
    if role == "taker":
        fee = _dec(taker_bps)
        if fee != TAKER_BPS:
            raise ValueError("taker 费率锁定为 12 bps")
        if miss_rate is not None and _dec(miss_rate) != 0:
            raise ValueError("taker 按成交记账，不接受漏成率")
        return {
            "role": "taker",
            "gross_bps": gross,
            "fee_bps": TAKER_BPS,
            "miss_rate": Decimal(0),
            "fill_rate": Decimal(1),
            "ev_bps": gross - TAKER_BPS,
        }
    if role != "maker":
        raise ValueError("role 只能是 taker 或 maker")
    fee = _measured("maker_bps", maker_bps)
    miss = _measured("maker_miss_rate", miss_rate)
    if miss < 0 or miss > 1:
        raise ValueError("漏成率必须在 [0, 1]")
    fill = Decimal(1) - miss
    ev = fill * (gross - fee) + miss * UNFILLED_PNL_BPS
    return {
        "role": "maker",
        "gross_bps": gross,
        "fee_bps": fee,
        "miss_rate": miss,
        "fill_rate": fill,
        "ev_bps": ev,
    }
