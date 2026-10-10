"""Exact proportional fee allocation for v5 partial fills."""
from decimal import Decimal, ROUND_DOWN


def allocate_fee(total: Decimal, quantities: tuple[Decimal, ...]) -> tuple[Decimal, ...]:
    if total < 0 or not quantities or any(item <= 0 for item in quantities):
        raise ValueError("non-negative total and positive fill quantities required")
    denominator = sum(quantities, Decimal("0"))
    allocated = Decimal("0")
    output = []
    for index, quantity in enumerate(quantities):
        value = total - allocated if index == len(quantities) - 1 else (
            total * quantity / denominator).quantize(Decimal("1e-18"), rounding=ROUND_DOWN)
        output.append(value); allocated += value
    return tuple(output)
