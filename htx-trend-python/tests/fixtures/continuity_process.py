"""Isolated PAPER process fixture: real worker, ledger, checkpoint and OS signals."""
import argparse
from datetime import datetime, UTC
from decimal import Decimal as D
import json
from pathlib import Path
import signal
from threading import Event
from types import SimpleNamespace as NS

from htx_trend.paper_worker import PaperTrendWorker, PaperWorkerConfig
from htx_trend.market_data import Candle, KlineWindow, ContractSpec, TopOfBook, FundingRate
from htx_trend.runtime_checkpoint import RuntimeCheckpoint, PersistentWorker

class Market:
    compact=None
    def kline_window(self,contract,period,size,*,now_epoch):
        step=900 if period=='15min' else 3600
        end=(now_epoch//step-1)*step
        return KlineWindow(contract,period,tuple(Candle(end-(size-1-i)*step,D(100),D(101),D(99),D(100)) for i in range(size)),size)
    def contract_spec(self,contract):return ContractSpec(contract,D('.01'),D('.01'),1)
    def top_of_book(self,contract):return TopOfBook(D('99.99'),D('100.01'))
    def funding_rate(self,contract):return FundingRate(contract,D(0))

parser=argparse.ArgumentParser()
parser.add_argument('--root',type=Path,required=True)
parser.add_argument('--bootstrap',action='store_true')
args=parser.parse_args()
instance=NS(instance_key='drill',strategy_family='TREND',strategy_name='HTX_TREND_15M_V2',execution_mode='PAPER',enabled=True)
worker=PaperTrendWorker(instance,Market(),args.root/'ledger',PaperWorkerConfig(contracts=('BTC-USDT',)),require_holding_policy=True)
worker.ledger.mirror=lambda _:None
if args.bootstrap:
    position=worker.exchange.open_position(worker.account,'BTC-USDT','LONG',3,D(100),datetime.now(UTC),'before-deploy')
    worker.ledger.save_position_context(position.position_id,dict(model='EMA_MOMENTUM_CONTINUATION',contract_size='.01',
        initial_stop='90',invalidation='90',structure_target='130',highest='100',lowest='100',
        entry_fee='.0012',holding_policy=worker.runtime._holding_policy.to_dict()))
    worker.ledger.checkpoint()
state=RuntimeCheckpoint(args.root/'runtime',dict(instance_key='drill',account_id='paper:drill',mode='PAPER',strategy_family='TREND'),initialize=args.bootstrap)
event=Event()
for sig in (signal.SIGTERM,signal.SIGINT):signal.signal(sig,lambda *_:event.set())
wrapped=PersistentWorker(worker,state,event)
health=wrapped.tick()
print(json.dumps(dict(status=health.status,quantity=sum(p.quantity for p in worker.ledger.active_positions(worker.account)),
    positions=list(worker.ledger.data['positions']),fills=len(worker.ledger.data['fills']),
    decisions=len(worker.ledger.data['decisions']),previous=state.previous_status)),flush=True)
try:
    while not event.wait(.1):pass
finally:
    wrapped.stop();state.close()
