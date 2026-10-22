"""Offline, single-coin next-open plans with programmable fixed holding bars."""
import numpy as np
from .features import validate_grid
from .ledger import decimal


def plan_trades(events, bars, *, coin, holding_bars=1, bar_seconds=14400):
    """Return {plans, skipped}; events has aligned ts/side (-1, 0, +1).

    Signal i confirms at its close; enter at open i+1, exit at open
    i+1+holding_bars. Signals confirmed strictly before that exit are skipped.
    A signal confirming exactly at the exit may re-enter at that same open.
    Truncated tails are reported, never assigned a fabricated exit price.
    Prices are historical plan annotations, not contemporaneous features.
    Call once per complete coin history, not independently on overlapping chunks.
    """
    validate_grid(bars, bar_seconds)
    if not isinstance(holding_bars, int) or isinstance(holding_bars, bool) or holding_bars < 1:
        raise ValueError('holding_bars must be a positive integer')
    if not isinstance(coin, str) or not coin:
        raise ValueError('coin required')
    if len(events) != len(bars) or not np.array_equal(events.ts.to_numpy(), bars.ts.to_numpy()):
        raise ValueError('events must align exactly with bars')
    if not events.side.isin([-1, 0, 1]).all():
        raise ValueError('event sides must be -1, 0 or +1')
    opens = [decimal(v) for v in bars.open]
    if any(v <= 0 for v in opens):
        raise ValueError('opens must be positive')
    ts = [int(v) for v in bars.ts]
    plans, skipped = [], []
    occupied_until = -1
    for i, side in enumerate(events.side):
        if side == 0:
            continue
        entry, exit_ = i + 1, i + 1 + holding_bars
        reason = ('OVERLAPPING_POSITION' if entry < occupied_until else
                  'INCOMPLETE_HORIZON' if exit_ >= len(bars) else None)
        if reason:
            skipped.append(dict(coin=coin, signal_bar_ts=ts[i], reason=reason))
            continue
        plans.append(dict(id=f'{coin}:{ts[i]}', coin=coin, side=int(side),
                          signal_bar_ts=ts[i], entry_ts=ts[entry], exit_ts=ts[exit_],
                          entry_open=opens[entry], exit_open=opens[exit_]))
        occupied_until = exit_
    return dict(plans=plans, skipped=skipped)
