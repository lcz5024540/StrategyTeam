import numpy as np
import pandas as pd
import pytest
from coinresearch.features import causal_features, momentum_events


def bars():
    close = np.array([100., 102., 101., 105., 103., 100., 107., 108.])
    return pd.DataFrame(dict(ts=np.arange(8)*14400, open=close, close=close,
                             high=close+1, low=close-1, volume=np.arange(8)+1))


def test_future_replacement_preserves_features_and_events():
    b = bars()
    before = causal_features(b)
    events = momentum_events(before, threshold=.015)
    assert set(events.side) == {-1, 0, 1}
    for t in range(len(b)):
        changed = b.copy()
        changed.loc[changed.ts > b.ts.iloc[t], ['open', 'close', 'high', 'low', 'volume']] = [501, 500, 900, 1, 999999]
        after = causal_features(changed)
        pd.testing.assert_frame_equal(before.iloc[:t+1], after.iloc[:t+1], check_exact=True)
        pd.testing.assert_frame_equal(events.iloc[:t+1], momentum_events(after, threshold=.015).iloc[:t+1], check_exact=True)
        pd.testing.assert_frame_equal(before.iloc[:t+1], causal_features(b.iloc[:t+1]), check_exact=True)


def test_feature_values_and_warmup():
    f = causal_features(bars(), window=3, momentum_bars=2)
    assert f['mean'].iloc[:2].isna().all()
    assert f['mean'].iloc[2] == 101
    assert f['std'].iloc[2] == pytest.approx(np.sqrt(2/3))
    assert f.momentum.iloc[2] == pytest.approx(np.log(101/100))
    assert momentum_events(f, threshold=0).side.iloc[:2].tolist() == [0, 0]
    assert momentum_events(pd.DataFrame(dict(ts=[0], momentum=[.1])), threshold=.1).side.tolist() == [0]


@pytest.mark.parametrize('mutation', ['gap', 'duplicate', 'order', 'nan', 'zero'])
def test_invalid_feature_inputs(mutation):
    b = bars()
    if mutation == 'gap': b = b.drop(index=3)
    if mutation == 'duplicate': b.loc[1, 'ts'] = 0
    if mutation == 'order': b = b.iloc[::-1]
    if mutation == 'nan': b.loc[1, 'close'] = np.nan
    if mutation == 'zero': b.loc[1, 'close'] = 0
    with pytest.raises(ValueError): causal_features(b)
