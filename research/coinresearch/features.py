"""Causal features of completed bars, never of ex-post holding coverage.

`ts` is bar opening Unix seconds. Caller must supply only completed bars for
as-of use; row t becomes available at ts[t] + bar_seconds. Missing bars are
rejected rather than compressed into a misleading momentum horizon.
"""
import numpy as np
import pandas as pd


def validate_grid(bars, bar_seconds):
    if not isinstance(bar_seconds, int) or isinstance(bar_seconds, bool) or bar_seconds <= 0:
        raise ValueError('bar_seconds must be a positive integer')
    ts = bars['ts'].to_numpy(dtype=float)
    if not np.isfinite(ts).all() or not (ts == np.floor(ts)).all():
        raise ValueError('timestamps must be finite integer Unix seconds')
    if len(ts) > 1 and not (np.diff(ts) == bar_seconds).all():
        raise ValueError('bars must be ordered on a complete regular grid')


def causal_features(bars, *, window=3, momentum_bars=1, bar_seconds=14400):
    """Trailing mean/population std and log(close[t]/close[t-k]).

    Warm-up values are NaN; no centering, backfill, or future normalization.
    Input is one coin, with ts/close columns (other OHLCV columns are ignored).
    """
    validate_grid(bars, bar_seconds)
    for value in (window, momentum_bars):
        if not isinstance(value, int) or isinstance(value, bool) or value < 1:
            raise ValueError('feature windows must be positive integers')
    close = bars['close'].reset_index(drop=True).astype(float)
    if not np.isfinite(close).all() or (close <= 0).any():
        raise ValueError('close must be finite and positive')
    return pd.DataFrame(dict(ts=bars.ts.to_numpy(),
                             mean=close.rolling(window, min_periods=window).mean(),
                             std=close.rolling(window, min_periods=window).std(ddof=0),
                             momentum=np.log(close / close.shift(momentum_bars))))


def momentum_events(features, *, threshold):
    """Signed events: +1 above threshold, -1 below -threshold, otherwise 0.

    Threshold is a nonnegative log return; equality and warm-up do not trigger.
    """
    if not np.isfinite(threshold) or threshold < 0:
        raise ValueError('threshold must be finite and nonnegative')
    m = features['momentum']
    return pd.DataFrame(dict(ts=features.ts.to_numpy(),
                             side=np.where(m > threshold, 1, np.where(m < -threshold, -1, 0))))
