import pytest
from htx_trend.writer_lock import TrendWriterLock, LOCK_NAME
from htx_trend.live import HtxLiveExecutor, LiveAuthority
from htx_trend.htx_v5 import SwapOrderRequest


class Connection:
    def __init__(self, acquired=1): self.acquired=acquired; self.owned=1; self.closed=False
    def cursor(self): return self
    def __enter__(self): return self
    def __exit__(self,*_): pass
    def execute(self,query,args): self.query=query; assert args==(LOCK_NAME,)
    def fetchone(self): return {'acquired':self.acquired} if 'GET_LOCK' in self.query else {'owned':self.owned}
    def close(self): self.closed=True


def test_second_writer_is_rejected_and_lost_lock_never_reacquires():
    with pytest.raises(RuntimeError,match='another'): TrendWriterLock(Connection(0)).acquire()
    db=Connection(); guard=TrendWriterLock(db); guard.acquire(); guard.assert_owned()
    db.owned=0
    with pytest.raises(RuntimeError,match='lost'): guard.assert_owned()
    db.owned=1
    with pytest.raises(RuntimeError): guard.assert_owned()
    with pytest.raises(RuntimeError): guard.acquire()
    guard.close(); assert db.closed


def test_live_mutation_checks_ownership_immediately_before_transport():
    class Client:
        sent=0
        def place_order(self,_): self.sent+=1
    db=Connection(); guard=TrendWriterLock(db); guard.acquire(); client=Client()
    executor=HtxLiveExecutor(client,LiveAuthority(True,'7'),'7',writer_guard=guard.assert_owned)
    db.owned=0
    with pytest.raises(RuntimeError): executor.place(SwapOrderRequest('BTC-USDT','long','buy','market','1','1'))
    assert client.sent==0


def test_shutdown_acknowledges_release_before_closing_transport():
    db=Connection();guard=TrendWriterLock(db);guard.acquire();guard.close()
    assert 'RELEASE_LOCK' in db.query and db.closed and guard.lost
    with pytest.raises(RuntimeError):guard.acquire()
