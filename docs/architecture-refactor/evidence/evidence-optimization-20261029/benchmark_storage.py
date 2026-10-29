"""Synthetic unchanged-window stress benchmark; no network or production writes."""
from datetime import UTC, datetime
from decimal import Decimal
import json
from pathlib import Path
import tempfile
from time import perf_counter
from unittest.mock import patch
from htx_trend.market_data import Candle, KlineWindow, HtxMarketData, period_seconds, period_anchor_seconds
from htx_trend.research.store import FileEvidenceStore
from htx_trend.research.compact import CompactEvidence

results=[]
with tempfile.TemporaryDirectory(prefix='htx-evidence-benchmark-') as directory:
    for period in ('15min','60min','4hour','1day'):
        step=period_seconds(period); anchor=period_anchor_seconds(period)
        candles=tuple(Candle(anchor+i*step,Decimal('100'),Decimal('102'),Decimal('99'),Decimal('101'),Decimal('2')) for i in range(160))
        raw={'status':'ok','data':[{'id':c.open_time,'open':str(c.open),'high':str(c.high),'low':str(c.low),'close':str(c.close),'amount':str(c.amount)} for c in candles]}
        class Client:
            def history_kline_envelope(self,*args):return raw
        for mode in ('legacy-v1','compact-v2'):
            root=Path(directory)/period/mode
            start=perf_counter()
            with FileEvidenceStore(root,compress=mode=='compact-v2') as store:
                compact=CompactEvidence(store) if mode=='compact-v2' else None
                market=HtxMarketData(Client(),evidence_store=store,compact=compact)
                for i in range(1000):
                    class Clock(datetime):
                        @classmethod
                        def now(cls,tz=None):return datetime.fromtimestamp(1800000000+i,tz or UTC)
                    with patch('htx_trend.market_data.datetime',Clock):
                        w=market.kline_window('BTC-USDT',period,160,now_epoch=anchor+161*step+i)
                    assert w.evidence_status=='COMPLETE'
                    assert w.candles==candles
            files=[p for p in root.rglob('*') if p.is_file()]
            objects=[p for p in (root/'objects').rglob('*') if p.is_file()]
            results.append({'period':period,'format':mode,'polls':1000,'objects':len(objects),
                'object_logical_bytes':sum(p.stat().st_size for p in objects),
                'total_logical_bytes':sum(p.stat().st_size for p in files),
                'total_allocated_bytes':sum(p.stat().st_blocks*512 for p in files),
                'elapsed_seconds':round(perf_counter()-start,3)})
print(json.dumps({'scope':'SYNTHETIC unchanged 160-bar windows; timestamps vary; no decisions; not live saving estimate',
                  'includes':'objects and closed SQLite index; local filesystem block allocation', 'results':results},indent=2))
