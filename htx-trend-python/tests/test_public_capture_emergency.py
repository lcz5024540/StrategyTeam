"""Regressions for the public-only capture/archival emergency fixes."""
import pytest
import json

from htx_trend.realtime.public_capture import Capture, normalize_ws
from htx_trend.realtime.storage import SegmentRecorder
from htx_trend.sealing.control import Control, SealingError


@pytest.mark.parametrize('contract,expected', [('capture-public', 'SEALED'), ('capture-runtime', 'OPEN')])
def test_small_generation_age_is_public_only_and_survives_restart(tmp_path, monkeypatch, contract, expected):
    now = [1000.0]
    monkeypatch.setattr('htx_trend.sealing.control.time.time', lambda: now[0])
    recorder = SegmentRecorder(tmp_path / 'segments', segmented=True, segment_contract=contract)
    recorder.append({'kind': 'FRAME', 'received_ms': 1000000})
    recorder.flush()
    recorder.segment_boundary()
    control = recorder.segments
    assert control.db.execute('SELECT state FROM generations WHERE id=1').fetchone()[0] == 'OPEN'
    now[0] = 1899.0
    recorder.segment_boundary()
    assert control.db.execute('SELECT state FROM generations WHERE id=1').fetchone()[0] == 'OPEN'
    control.close()
    resumed = Control(recorder.root)
    try:
        now[0] = 1900.0
        resumed.seal()
        assert resumed.db.execute('SELECT state FROM generations WHERE id=1').fetchone()[0] == expected
        now[0] = 2801.0
        # The empty successor must not create an endless chain of empty batches.
        assert resumed.seal() is None
        assert resumed.db.execute('SELECT count(*) FROM generations').fetchone()[0] == (2 if expected == 'SEALED' else 1)
    finally:
        resumed.close()


def test_public_age_does_not_bypass_failed_consumer(tmp_path, monkeypatch):
    now = [1000.0]
    monkeypatch.setattr('htx_trend.sealing.control.time.time', lambda: now[0])
    recorder = SegmentRecorder(tmp_path / 'segments', segmented=True, segment_contract='capture-public')
    try:
        recorder.append({'kind': 'FRAME', 'received_ms': 1000000})
        recorder.flush()
        recorder.segments.ready('recorder', [], error='FAILED')
        now[0] = 2000.0
        with pytest.raises(SealingError, match='handoff'):
            recorder.segments.seal()
        assert recorder.segments.db.execute('SELECT state FROM generations').fetchall() == [('OPEN',)]
    finally:
        recorder.segments.close()


@pytest.mark.parametrize('channel,book', [
    ('bbo', {'bid': None, 'ask': None}),
    ('bbo', {'bid': [], 'ask': [101, 1]}),
    ('bbo', {'bid': [100], 'ask': [101, 1]}),
    ('bbo', {'bid': ['NaN', 1], 'ask': [101, 1]}),
    ('bbo', {'bid': [100, 1]}),
    ('depth.step0', {'bids': [], 'asks': []}),
    ('depth.step0', {'bids': None, 'asks': [[101, 1]]}),
    ('depth.step0', {'bids': [[100, -1]], 'asks': [[101, 1]]}),
])
def test_bad_public_book_has_typed_input_error(channel, book):
    raw = {'ch': 'market.BOME-USDT.' + channel, 'tick': {'ts': 1793952000484, 'mrid': 1, **book}}
    with pytest.raises(ValueError) as caught:
        normalize_ws(raw, 'epoch', 1794388008266)
    assert type(caught.value).__name__ == 'InvalidPublicMarketData'


def test_bad_book_does_not_disconnect_healthy_symbols_or_refresh_bad_channel(tmp_path):
    c = Capture(tmp_path / 'capture', ['BOME-USDT', 'ETH-USDT'], seconds=1)
    bad_bbo = {'ch': 'market.BOME-USDT.bbo', 'tick': {'ts': 1793952000484, 'mrid': 1, 'bid': None, 'ask': None}}
    bad_depth = {'ch': 'market.BOME-USDT.depth.step0', 'tick': {'ts': 1794388008266, 'mrid': 1, 'bids': [], 'asks': []}}
    good = {'ch': 'market.ETH-USDT.bbo', 'tick': {'ts': 1794388008267, 'bid': [100, 1], 'ask': [101, 1]}}
    messages = iter([bad_bbo, bad_depth, bad_bbo, {'ping': 123}, good])
    sent = []

    class Socket:
        def send(self, raw): sent.append(json.loads(raw))
        def recv(self, timeout):
            value = next(messages, None)
            if value is None:
                c.stop.set()
                raise TimeoutError()
            return json.dumps(value)

    try:
        c._pump_socket(Socket(), 'epoch', 1794388008000)
        events = []
        while not c.events.empty():
            event = c.events.get_nowait()
            events.append(event)
            c.write(event)
        errors = [e for e in events if e['kind'] == 'SOURCE_ERROR']
        assert len(errors) == 2  # repeated invalid packets are counted, not spammed
        assert {e['raw']['ch'] for e in errors} == {bad_bbo['ch'], bad_depth['ch']}
        assert c.status()['invalid_market_messages'][bad_bbo['ch']] == 2
        assert 'BOME-USDT:BBO' not in c.last_seen and 'BOME-USDT:DEPTH' not in c.last_seen
        assert c.last_seen['ETH-USDT:BBO'] > 0
        assert {'pong': 123} in sent
        assert c.reconnect.consecutive_failures == 0
    finally:
        c.client.close()
        c.lock.close()


@pytest.mark.parametrize('number', [float('nan'), float('inf'), float('-inf')])
def test_invalid_nonfinite_raw_can_be_recorded_without_stopping_capture(tmp_path, number):
    c = Capture(tmp_path / 'capture', ['BOME-USDT'], seconds=1, segmented=True)
    raw = {'ch': 'market.BOME-USDT.bbo', 'tick': {'ts': 123, 'bid': [number, 1], 'ask': [101, 1]}}
    try:
        with pytest.raises(ValueError) as caught:
            normalize_ws(raw, 'epoch', 200)
        c.record_invalid_book(raw, caught.value, 'epoch', 200)
        c.write(c.events.get_nowait())
        c.recorder.flush()
        assert c.failure is None and c.recorder.failure is None
        assert c.counts['SOURCE_ERROR'] == 1
    finally:
        c.recorder.segments.close()
        c.client.close()
        c.lock.close()
