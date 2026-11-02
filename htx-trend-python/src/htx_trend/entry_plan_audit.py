"""Offline repricing of recorded decision inputs; never a profit backtest.

Input is a versioned JSON inventory with explicit prices/ATR/book/fee provenance.
No clients, credentials, orders, or historical ledger writes are used here.
"""
from __future__ import annotations
import argparse
from dataclasses import asdict
from decimal import Decimal as D
import hashlib
import json
from pathlib import Path

from .execution import ExecutionConfig, price_plan
from .maker_execution import maker_limit_price, maker_reference_price
from .market_data import TopOfBook
from .models import ModelCandidate, ModelCode, PriceStructure, TrendSide


def decode_candidate(row):
    return ModelCandidate(ModelCode(row['model']), TrendSide(row['side']), int(row['bar_time']),
        D(row['score']), D(row['entry_price']), D(row['invalidation_price']), D(row['target_low']),
        D(row['target_high']), D(row['expected_r']),
        PriceStructure(**{k:D(v) for k,v in row['structure'].items()}), tuple(row.get('evidence', ())))


def audit(sample):
    c = decode_candidate(sample['candidate'])
    book = TopOfBook(D(sample['book']['bid']), D(sample['book']['ask']))
    atr, fee, tick = D(sample['atr']), D(sample['fee_rate']), D(sample['price_tick'])
    quote = book.ask if c.side is TrendSide.LONG else book.bid
    limit = maker_limit_price(c.side, maker_reference_price(c.side, book), D(sample['offset_bps']), price_tick=tick)
    rows = {}
    for name, version, entry, passive in (
        ('legacy_taker', 'legacy', quote, False),
        ('structure_taker', 'structure-cost-v1', quote, False),
        ('structure_limit', 'structure-cost-v1', limit, True)):
        try:
            p = price_plan(c, atr=atr, execution_price=entry, fee_rate=fee, passive=passive,
                config=ExecutionConfig(entry_plan_version=version))
            rows[name] = {'status': 'NON_POSITIVE_REWARD' if p.expected_net_reward is not None and p.expected_net_reward <= 0 else 'PRICED',
                          'plan': asdict(p)}
        except ValueError as error:
            rows[name] = {'status':'INVALID_GEOMETRY', 'reason':str(error)}
    return {k: sample[k] for k in ('source', 'identity', 'contract', 'evidence_hash', 'fee_source')} | {'plans':rows}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('inventory', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    raw = args.inventory.read_bytes(); inventory = json.loads(raw)
    if inventory['schema'] != 'entry-plan-audit-v1':
        raise ValueError('unknown input schema')
    result = {'schema':'entry-plan-audit-result-v1', 'inventory_sha256':hashlib.sha256(raw).hexdigest(),
        'acceptance':'NOT_A_PROFIT_REPLAY', 'limitations': inventory['limitations'],
        'samples':[audit(s) for s in inventory['samples']]}
    args.output.write_text(json.dumps(result, default=str, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps({'samples':len(result['samples']), 'acceptance':result['acceptance']}))


if __name__ == '__main__':
    main()
