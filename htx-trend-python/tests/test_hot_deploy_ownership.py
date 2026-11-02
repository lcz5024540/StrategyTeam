from dataclasses import replace
from datetime import UTC, datetime
from decimal import Decimal as D
from types import SimpleNamespace as NS

import pytest

from htx_trend.mysql_store import RuntimeInstance
from htx_trend.ownership import managed_instances, owners_from_env, OwnedPositionManagers


def live_instance():
    return RuntimeInstance('stable-instance','TREND','HTX_TREND_15M_V2',7,'primary','LIVE',True,'unit',None)


def test_alias_retains_account_instance_and_reservation_identity(monkeypatch):
    from htx_trend.execution_service import _reservation_key
    current = live_instance()
    owners = managed_instances(current, ('HTX_TREND_V1', 'HTX_TREND_V1'))
    assert len(owners) == 2
    old = owners[1]
    assert old.account_id == 7 and old.instance_key == current.instance_key
    assert old.strategy_name == 'HTX_TREND_V1'
    assert _reservation_key(old,'p') == _reservation_key(current,'p')
    monkeypatch.setenv('HTX_MANAGED_PREVIOUS_STRATEGIES','["HTX_TREND_V1"]')
    assert owners_from_env(current) == owners
    with pytest.raises(ValueError):
        managed_instances(replace(current,execution_mode='PAPER'),('HTX_TREND_V1',))
    with pytest.raises(ValueError):
        managed_instances(current,('*',))


def test_pending_order_reconciliation_keeps_old_sql_and_accounting_owner():
    from htx_trend.execution_service import HtxTrendEntryExecutor
    ex=object.__new__(HtxTrendEntryExecutor)
    ex._instance=live_instance();ex._account=NS(account_id=7)
    ex._managed_instances=managed_instances(ex._instance,('HTX_TREND_V1',))
    calls=[]
    def pending(**kw):
        calls.append(('query',kw))
        if kw['strategy_name']!='HTX_TREND_V1':
            return []
        return [dict(client_order_id='123',contract_code='BTC-USDT',position_id=1,leg='CLOSE',
                     model_name='FIB_PULLBACK_CONTINUATION',contract_size='.01',position_key='p')]
    ex._repository=NS(pending_orders=pending,apply_exchange_order=lambda **kw:calls.append(('apply',kw)))
    ex._executor=NS(order=lambda *args,**kw:dict(status='filled',trade_volume=2,trade_avg_price=100,
                                               fee='.001',order_id=42))
    assert ex.reconcile_pending() == {'123':'FILLED'}
    applied=next(v for k,v in calls if k=='apply')
    assert applied['instance'].strategy_name=='HTX_TREND_V1'
    assert applied['instance'].instance_key=='stable-instance' and applied['account'].account_id==7
    assert len([v for k,v in calls if k=='apply'])==1


def test_manager_keeps_original_owner_on_close_intents():
    owners=managed_instances(live_instance(),('HTX_TREND_V1',))
    calls=[]
    class Manager:
        instance=owners[0]
        def manage(self,epoch,context):
            calls.append((self.instance,epoch,context))
            return []
    assert OwnedPositionManagers(Manager(),owners).manage(9,'context')==[]
    assert [c[0].strategy_name for c in calls]==['HTX_TREND_15M_V2','HTX_TREND_V1']


def test_paper_config_change_preserves_ledger_and_old_margin(tmp_path):
    from test_paper_worker import Market, instance
    from htx_trend.paper_worker import PaperTrendWorker, PaperWorkerConfig
    old=PaperWorkerConfig(contracts=('BTC-USDT','ETH-USDT'),leverage=D(10))
    worker=PaperTrendWorker(instance(),Market(),tmp_path,old)
    position=worker.exchange.open_position(worker.account,'ETH-USDT','LONG',2,D(100),datetime.now(UTC),'old')
    worker.ledger.save_position_context(position.position_id,dict(contract_size='.01'))
    worker.ledger.checkpoint();worker.stop()
    new=replace(old,contracts=('BTC-USDT',),leverage=D(5),max_active_positions=2)
    restored=PaperTrendWorker(instance(),Market(),tmp_path,new)
    assert restored.ledger.position(position.position_id).quantity==2
    assert restored._position_leverage(position)==10
    assert restored.ledger.data['identity']['config']['contracts']==['BTC-USDT','ETH-USDT']
    assert restored.ledger.data['entry_config']['contracts']==['BTC-USDT']
    restored.stop()
    with pytest.raises(ValueError,match='identity/config'):
        PaperTrendWorker(instance(),Market(),tmp_path,replace(new,equity=D(301)))


def test_removed_paper_contract_is_still_managed_and_never_reopened(tmp_path):
    from test_paper_worker import Market, instance
    from htx_trend.paper_worker import PaperTrendWorker, PaperWorkerConfig
    worker=PaperTrendWorker(instance(),Market(),tmp_path,PaperWorkerConfig(contracts=('BTC-USDT',)))
    position=worker.exchange.open_position(worker.account,'ETH-USDT','LONG',2,D(100),datetime.now(UTC),'old')
    worker.ledger.save_position_context(position.position_id,dict(contract_size='.01',initial_stop='95',
        highest='100',lowest='100'))
    managed=[];scans=[]
    worker.runtime.manage=lambda **kw:managed.append(kw['position_id'])
    worker.runtime.scan=lambda **kw:(scans.append(kw['entry_window'].contract_code) or NS(candidates=()))
    worker.tick()
    assert managed==[position.position_id] and scans==['BTC-USDT']
    assert 'ETH-USDT' in worker.ledger.data['bars']
    worker.stop()
