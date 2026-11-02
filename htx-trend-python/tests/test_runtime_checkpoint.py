from dataclasses import replace
from decimal import Decimal as D
from pathlib import Path
from threading import Event
import gzip
import json

import pytest

from htx_trend.runtime_checkpoint import RuntimeCheckpoint, PersistentWorker, encode, decode

IDENTITY=dict(instance_key='one',account_id='7',mode='LIVE',strategy_family='TREND')


def real_worker(monkeypatch, **kwargs):
    from test_entry_diagnostics import make_worker
    from test_java_entry_contract import _uptrend
    from htx_trend.market_data import KlineWindow, OrderBook, ContractSpec
    w,repo,market,ex,_=make_worker(monkeypatch,observe_only=False,**kwargs)
    market.kline_window.side_effect=lambda contract,period,size,**kw:KlineWindow(contract,period,_uptrend(),len(_uptrend()))
    market.order_book.return_value=OrderBook(((D(100),D(1000)),),((D('100.01'),D(1000)),))
    market.contract_spec.return_value=ContractSpec('BTC-USDT',D('.01'),D('.01'),1)
    return w,repo,market,ex


def test_checkpoint_requires_explicit_bootstrap_valid_identity_and_one_writer(tmp_path):
    with pytest.raises(ValueError,match='initialization'):
        RuntimeCheckpoint(tmp_path,IDENTITY)
    state=RuntimeCheckpoint(tmp_path,IDENTITY,initialize=True)
    with pytest.raises(BlockingIOError):
        RuntimeCheckpoint(tmp_path,IDENTITY,initialize=True)
    state.save('DRAINING');state.close()
    with pytest.raises(ValueError,match='identity|mismatch'):
        RuntimeCheckpoint(tmp_path,{**IDENTITY,'account_id':'2'})
    resumed=RuntimeCheckpoint(tmp_path,IDENTITY)
    assert resumed.previous_status=='DRAINING'
    resumed.close()


def test_same_bar_candidate_roundtrip_does_not_rescan_or_recreate_order(tmp_path,monkeypatch):
    w,repo,market,ex=real_worker(monkeypatch,active=1)
    state=RuntimeCheckpoint(tmp_path,IDENTITY,initialize=True)
    wrapped=PersistentWorker(w,state,Event())
    assert wrapped.tick().status=='HEALTHY'
    assert w._pending
    saved=w.export_runtime()
    assert decode(encode(saved))==saved
    wrapped.stop();state.close()
    w2,repo2,market2,ex2=real_worker(monkeypatch,active=0)
    state2=RuntimeCheckpoint(tmp_path,IDENTITY)
    wrapped2=PersistentWorker(w2,state2,Event())
    ex2.submit.side_effect=lambda **kw: ex.submit.return_value
    assert wrapped2.tick().status=='HEALTHY'
    assert ex2.submit.call_count==1 and market2.kline_window.call_count==0
    wrapped2.stop();state2.close()
    w3,repo3,market3,ex3=real_worker(monkeypatch,active=0)
    state3=RuntimeCheckpoint(tmp_path,IDENTITY)
    wrapped3=PersistentWorker(w3,state3,Event())
    wrapped3.tick()
    ex3.submit.assert_not_called();market3.kline_window.assert_not_called()
    wrapped3.stop();state3.close()


def test_snapshot_write_failure_blocks_entry_after_management(tmp_path,monkeypatch):
    w,repo,market,ex=real_worker(monkeypatch,active=0)
    state=RuntimeCheckpoint(tmp_path,IDENTITY,initialize=True)
    wrapped=PersistentWorker(w,state,Event())
    monkeypatch.setattr(state,'save',lambda *a:(_ for _ in ()).throw(OSError('full')))
    with pytest.raises(OSError):wrapped.tick()
    ex.submit.assert_not_called();w._position_manager.manage.assert_called_once()
    state.close()


def test_reconcile_failure_blocks_new_risk_but_still_manages(tmp_path,monkeypatch):
    w,repo,market,ex=real_worker(monkeypatch)
    ex.reconcile_pending.return_value={'old':'ERROR:TimeoutError'}
    state=RuntimeCheckpoint(tmp_path,IDENTITY,initialize=True)
    wrapped=PersistentWorker(w,state,Event())
    assert wrapped.tick().status=='DEGRADED'
    w._position_manager.manage.assert_called_once();ex.submit.assert_not_called()
    wrapped.stop();state.close()


def test_drain_during_management_stops_new_entries(tmp_path,monkeypatch):
    w,repo,market,ex=real_worker(monkeypatch)
    event=Event()
    w._position_manager.manage.side_effect=lambda *a:(event.set() or [])
    state=RuntimeCheckpoint(tmp_path,IDENTITY,initialize=True)
    wrapped=PersistentWorker(w,state,event)
    wrapped.tick();ex.submit.assert_not_called()
    wrapped.stop()
    assert RuntimeCheckpoint.read(state.path)['status']=='STOPPED'
    state.close()


def test_config_change_does_not_reuse_cached_candidate(tmp_path,monkeypatch):
    w,_,_,_=real_worker(monkeypatch,active=1)
    state=RuntimeCheckpoint(tmp_path,IDENTITY,initialize=True);wrapped=PersistentWorker(w,state,Event())
    wrapped.tick();assert w._pending
    wrapped.stop();state.close()
    w2,_,market,ex=real_worker(monkeypatch,active=0,signal_freshness_seconds=601)
    state2=RuntimeCheckpoint(tmp_path,IDENTITY);wrapped2=PersistentWorker(w2,state2,Event())
    wrapped2.tick();ex.submit.assert_not_called();market.kline_window.assert_not_called()
    wrapped2.stop();state2.close()


def test_corrupted_hash_and_untrusted_type_never_load(tmp_path):
    state=RuntimeCheckpoint(tmp_path,IDENTITY,initialize=True);path=state.path;state.close()
    value=json.loads(gzip.decompress(path.read_bytes()));value['status']='READY'
    path.write_bytes(gzip.compress(json.dumps(value).encode()))
    with pytest.raises(ValueError,match='corrupted'):
        RuntimeCheckpoint(tmp_path,IDENTITY)
    with pytest.raises(ValueError,match='unsupported'):
        decode({'type':'os.system','fields':{'command':'anything'}})


def test_expired_candidate_is_not_replayed_after_restart(tmp_path,monkeypatch):
    from datetime import datetime, UTC
    w,_,_,_=real_worker(monkeypatch,active=1)
    state=RuntimeCheckpoint(tmp_path,IDENTITY,initialize=True);wrapped=PersistentWorker(w,state,Event())
    wrapped.tick();assert w._pending
    saved=w.export_runtime();wrapped.stop();state.close()
    w2,_,_,ex=real_worker(monkeypatch,active=0)
    w2.restore_runtime(saved)
    w2._expire_pending(999999999)
    assert not w2._pending
    ex.submit.assert_not_called()


def test_paper_missing_or_moved_ledger_cannot_be_silently_reinitialized(tmp_path):
    from test_paper_worker import Market, instance
    from htx_trend.paper_worker import PaperTrendWorker, PaperWorkerConfig
    w=PaperTrendWorker(instance(),Market(),tmp_path/'ledger',PaperWorkerConfig())
    w.tick();saved=w.export_runtime();w.stop()
    other=PaperTrendWorker(instance(),Market(),tmp_path/'other',PaperWorkerConfig())
    with pytest.raises(ValueError,match='missing or relocated'):
        other.restore_runtime(saved)
    other.stop()


def test_all_profiles_must_recover_before_any_new_risk(tmp_path):
    from types import SimpleNamespace
    from htx_trend.strategy_platform import WorkerHealth
    calls=[]
    class Child:
        def __init__(self,error):self.error=error
        def prepare_management(self):
            calls.append('manage')
            return ['unknown close'] if self.error else []
        def tick(self):
            calls.append('blocked' if self._entry_recovery_blocked else 'entry')
    children=(Child(False),Child(True))
    def tick():
        for child in children:child.tick()
        return WorkerHealth('DEGRADED','pending recovery')
    worker=SimpleNamespace(_workers=children,tick=tick,stop=lambda:None)
    state=RuntimeCheckpoint(tmp_path,IDENTITY,initialize=True)
    wrapped=PersistentWorker(worker,state,Event());wrapped.tick()
    assert calls==['manage','manage','blocked','blocked']
    wrapped.stop();state.close()


def test_omitted_previous_owner_is_rejected_before_ready(monkeypatch):
    w,_,_,_=real_worker(monkeypatch)
    w._owned_strategy_names=('HTX_TREND_V1','OLDER')
    saved=w.export_runtime()
    replacement,_,_,_=real_worker(monkeypatch)
    with pytest.raises(ValueError,match='previous managed strategy'):
        replacement.restore_runtime(saved)
    replacement._owned_strategy_names=('HTX_TREND_V1','OLDER')
    replacement.restore_runtime(saved)


def test_restored_candidate_refreshes_execution_book_before_submission(tmp_path,monkeypatch):
    from htx_trend.market_data import OrderBook
    w,_,_,_=real_worker(monkeypatch,active=1)
    state=RuntimeCheckpoint(tmp_path,IDENTITY,initialize=True);wrapped=PersistentWorker(w,state,Event())
    wrapped.tick();wrapped.stop();state.close()
    w2,_,market,ex=real_worker(monkeypatch,active=0)
    fresh=OrderBook(((D(90),D(5)),),((D('90.5'),D(5)),))
    market.order_book.return_value=fresh
    state2=RuntimeCheckpoint(tmp_path,IDENTITY);wrapped2=PersistentWorker(w2,state2,Event())
    wrapped2.tick()
    assert ex.submit.call_args.kwargs['book']==fresh
    assert market.contract_spec.call_count==1 and market.funding_rate.call_count==1
    wrapped2.stop();state2.close()


def test_successful_but_stale_anchor_response_is_not_recovery_context(monkeypatch):
    from htx_trend.market_data import KlineWindow
    w,_,market,_=real_worker(monkeypatch)
    market.kline_window.return_value=KlineWindow('BTC-USDT','15min',(),80)
    market.kline_window.side_effect=None
    with pytest.raises(ValueError,match='incomplete or stale'):
        w._market_anchor_regime(117015,strict=True)
