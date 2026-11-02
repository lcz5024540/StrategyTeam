import gzip
import json
from decimal import Decimal as D
import pytest
from htx_trend.realtime.feed import MarketEvent, decode, EventOrder
from htx_trend.realtime.storage import PlanStore, SegmentRecorder


def test_decode_heartbeat_and_ack_are_not_prices():
    assert decode(gzip.compress(b'{"ping":1}'), epoch='a', received_ms=1) == ([], {'pong': 1})
    assert decode('{"status":"ok","subbed":"market.ETH-USDT.bbo"}', epoch='a', received_ms=1) == ([], None)
    with pytest.raises(ValueError):
        decode('{"status":"error","err-code":"bad-request"}', epoch='a', received_ms=1)


def test_batch_trades_distinct_and_exchange_clock_retained():
    raw = {'ch':'market.ETH-USDT.trade.detail','tick':{'data':[
        {'id':2,'ts':1001,'price':20,'amount':1}, {'id':1,'ts':1000,'price':21,'amount':2}]}}
    events, _ = decode(json.dumps(raw), epoch='epoch', received_ms=1200)
    assert [e.event_id for e in events] == ['1','2']
    assert events[0].exchange_ms == 1000 and events[0].received_ms == 1200
    assert events[0].fresh(2000) and not events[0].fresh(3100)
    assert not events[0].fresh(999)
    order = EventOrder()
    assert all(order.accept(e) for e in events)
    assert not order.accept(events[0]) and not order.accept(events[1])
    assert MarketEvent.load(events[0].dump()) == events[0]


def test_bbo_crossed_or_nonfinite_rejected():
    raw={'ch':'market.ETH-USDT.bbo','tick':{'bid':[21,1],'ask':[20,1],'ts':1000,'version':8}}
    with pytest.raises(ValueError): decode(json.dumps(raw),epoch='a',received_ms=1000)
    raw['tick']['bid'][0]='NaN'
    with pytest.raises(ValueError): decode(json.dumps(raw),epoch='a',received_ms=1000)


def test_plan_restart_and_instance_isolation(tmp_path):
    store=PlanStore(tmp_path/'state.db')
    store.save('a','p',{'phase':'SUBMITTED','client_id':'fixed'})
    store.close()
    store=PlanStore(tmp_path/'state.db')
    assert store.all('a')['p']['client_id']=='fixed'
    assert store.all('b')=={}
    store.close()


def test_segment_hash_restart_quota_and_no_deletion(tmp_path):
    e=MarketEvent('TRADE','ETH-USDT',1000,1001,'1','a',price=D('10'))
    recorder=SegmentRecorder(tmp_path,max_bytes=5000)
    assert recorder.append(e)
    ref=recorder.flush()
    assert ref and recorder.verify()==[e.dump()]
    again=SegmentRecorder(tmp_path,max_bytes=recorder.used)
    assert again.append(MarketEvent('TRADE','ETH-USDT',2000,2001,'2','a',price=D('11')))
    assert again.flush() is None and again.failure=='CAPTURE_QUOTA_EXCEEDED'
    assert (tmp_path/(ref+'.json.gz')).exists()
    assert again.verify()==[e.dump()]
    (tmp_path/(ref+'.json.gz')).write_bytes(gzip.compress(b'{}'))
    with pytest.raises(ValueError):again.verify()


def test_same_trade_id_with_changed_timestamp_is_still_duplicate():
    order=EventOrder()
    assert order.accept(MarketEvent('TRADE','ETH-USDT',100,100,'7','a',price=D(10)))
    assert not order.accept(MarketEvent('TRADE','ETH-USDT',101,101,'7','a',price=D(10)))
