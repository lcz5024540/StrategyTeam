package com.metaverse.coin.strategy.htx.persistence;

import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendSignalEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendOrderMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendPositionMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendSignalMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HtxTrendRecorderTest {

    @Test
    void recordSignalUpdatesExistingContractPeriodBarSnapshot() {
        HtxTrendPositionMapper positionMapper = mock(HtxTrendPositionMapper.class);
        HtxTrendOrderMapper orderMapper = mock(HtxTrendOrderMapper.class);
        HtxTrendSignalMapper signalMapper = mock(HtxTrendSignalMapper.class);
        HtxTrendRecorder recorder = new HtxTrendRecorder(positionMapper, orderMapper, signalMapper);
        HtxTrendSignalEntity signal = trendSignal();
        when(signalMapper.findLatestSignalIdForBar("BTC-USDT", "15min", 1789639200L)).thenReturn(88L);

        recorder.recordSignal(signal);

        verify(signalMapper).updateSignalSnapshotById(88L, signal);
        verify(signalMapper, never()).insert(signal);
    }

    @Test
    void recordSignalInsertsWhenNoSameBarSnapshotExists() {
        HtxTrendPositionMapper positionMapper = mock(HtxTrendPositionMapper.class);
        HtxTrendOrderMapper orderMapper = mock(HtxTrendOrderMapper.class);
        HtxTrendSignalMapper signalMapper = mock(HtxTrendSignalMapper.class);
        HtxTrendRecorder recorder = new HtxTrendRecorder(positionMapper, orderMapper, signalMapper);
        HtxTrendSignalEntity signal = trendSignal();
        when(signalMapper.findLatestSignalIdForBar("BTC-USDT", "15min", 1789639200L)).thenReturn(null);

        recorder.recordSignal(signal);

        verify(signalMapper).insert(signal);
        verify(signalMapper, never()).updateSignalSnapshotById(88L, signal);
    }

    private static HtxTrendSignalEntity trendSignal() {
        HtxTrendSignalEntity signal = new HtxTrendSignalEntity();
        signal.setContractCode("BTC-USDT");
        signal.setPeriod("15min");
        signal.setBarTime(1789639200L);
        signal.setSignal("LONG");
        signal.setClosePrice(new BigDecimal("60000"));
        signal.setAtr(new BigDecimal("100"));
        signal.setAtrPct(new BigDecimal("0.0017"));
        signal.setReason("Fibonacci pullback continuation");
        return signal;
    }
}
