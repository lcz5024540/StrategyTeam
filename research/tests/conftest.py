import json
import hashlib
from dataclasses import replace
import numpy as np
import pandas as pd
import pytest
from coinresearch.config import Config


@pytest.fixture
def prereg(tmp_path):
    path = tmp_path / 'prereg.json'
    path.write_text(json.dumps({'split': {'explore': [0, 19], 'test': [20, 99]}, 'description': 'synthetic test protocol'}))
    return dict(prereg_path=path, prereg_sha256=hashlib.sha256(path.read_bytes()).hexdigest())


@pytest.fixture
def synthetic(tmp_path):
    root = tmp_path / 'input'; root.mkdir()
    (root / 'klines_4h').mkdir(); (root / 'funding').mkdir()
    coins = [f'S{i:02d}' for i in range(24)]
    ts = np.arange(300)*14400
    rng = np.random.default_rng(20261019)
    for s in coins:
        op = 100*np.exp(np.cumsum(rng.normal(0, .01, len(ts))))
        pd.DataFrame(dict(ts=ts, open=op, high=op*1.01, low=op*.99, close=op, vol=100, turnover=1e7)).to_csv(root / f'klines_4h/{s}.csv', index=False)
        ft = np.arange(0, int(ts[-1])+1, 28800)
        pd.DataFrame(dict(ts=ft, funding_rate=rng.normal(0, .0001, len(ft)), realized_rate=0, funding_fee_type='settlement', avg_premium_index=0)).to_csv(root / f'funding/{s}.csv', index=False)
    (root / 'manifest.json').write_text(json.dumps(dict(included=coins, coins=[dict(sym=s, f_gaps=[], cadence_h=8) for s in coins])))
    (root / 'summary.json').write_text('{}')
    cfg = replace(Config(), win90=12, adv_days=2, list_days=2, warm=20, last_open=int(ts[-1]), ndraw=30)
    return root, cfg
