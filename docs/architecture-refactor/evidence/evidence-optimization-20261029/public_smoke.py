"""Real unauthenticated HTX reads; all evidence writes confined to local tempdir."""
import json
from datetime import UTC, datetime
from hashlib import sha256
from pathlib import Path
import tempfile
from htx_trend.htx_v5 import HtxV5Client
from htx_trend.market_data import HtxMarketData
from htx_trend.research.store import FileEvidenceStore, canonical
from htx_trend.research.compact import CompactEvidence, load_window

report={'observed_at':datetime.now(UTC).isoformat(),'mode':'PUBLIC_READS_ONLY_NO_ACCOUNT','samples':[]}
with HtxV5Client() as client, tempfile.TemporaryDirectory(prefix='htx-compact-public-') as temp:
    with FileEvidenceStore(Path(temp),compress=True) as store:
        compact=CompactEvidence(store)
        market=HtxMarketData(client,evidence_store=store,compact=compact)
        for period in ('15min','60min','4hour','1day'):
            sample={'symbol':'BTC-USDT','period':period}
            try:
                at=int(datetime.now(UTC).timestamp())
                w=market.kline_window('BTC-USDT',period,160,now_epoch=at)
                assert w.is_complete and w.evidence_status=='COMPLETE'
                assert load_window(store,w.evidence_refs[0]).candles==w.candles
                refs=compact.retain_input(w)
                sample.update(status='PASS',bars=len(w.candles),window_hash=w.window_hash,
                    raw_sha256=sha256(canonical(w.capture)).hexdigest(),
                    first_bar=w.candles[0].open_time,last_bar=w.candles[-1].open_time,
                    closure_objects=store.verify(refs[-1])['objects'])
            except Exception as error:sample.update(status='FAILED',error=type(error).__name__)
            report['samples'].append(sample)
print(json.dumps(report,indent=2))
