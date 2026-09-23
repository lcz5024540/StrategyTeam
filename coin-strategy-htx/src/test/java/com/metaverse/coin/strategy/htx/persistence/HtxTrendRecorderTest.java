package com.metaverse.coin.strategy.htx.persistence;

import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendOrderEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendPositionEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendSignalEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendOrderMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendPositionMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendSignalMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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

    @Test
    void createIntentProjectsPositionIntentToUnifiedLedger() {
        HtxTrendPositionMapper positionMapper = mock(HtxTrendPositionMapper.class);
        HtxTrendOrderMapper orderMapper = mock(HtxTrendOrderMapper.class);
        HtxTrendSignalMapper signalMapper = mock(HtxTrendSignalMapper.class);
        CoinStrategyLedgerRecorder ledgerRecorder = mock(CoinStrategyLedgerRecorder.class);
        HtxTrendRecorder recorder = new HtxTrendRecorder(positionMapper, orderMapper, signalMapper, ledgerRecorder);
        HtxTrendPositionEntity position = trendPosition();

        recorder.createIntent(position);

        verify(positionMapper).insert(position);
        verify(ledgerRecorder).recordTrendPositionIntent(position);
    }

    @Test
    void createOrderIntentProjectsOrderIntentToUnifiedLedger() {
        HtxTrendPositionMapper positionMapper = mock(HtxTrendPositionMapper.class);
        HtxTrendOrderMapper orderMapper = mock(HtxTrendOrderMapper.class);
        HtxTrendSignalMapper signalMapper = mock(HtxTrendSignalMapper.class);
        CoinStrategyLedgerRecorder ledgerRecorder = mock(CoinStrategyLedgerRecorder.class);
        HtxTrendRecorder recorder = new HtxTrendRecorder(positionMapper, orderMapper, signalMapper, ledgerRecorder);
        HtxTrendOrderEntity order = trendOrder();

        recorder.createOrderIntent(order);

        verify(orderMapper).insert(order);
        verify(ledgerRecorder).recordTrendOrderIntent(order);
    }

    @Test
    void markEntryFilledProjectsFilledPositionToUnifiedLedger() {
        HtxTrendPositionMapper positionMapper = mock(HtxTrendPositionMapper.class);
        HtxTrendOrderMapper orderMapper = mock(HtxTrendOrderMapper.class);
        HtxTrendSignalMapper signalMapper = mock(HtxTrendSignalMapper.class);
        CoinStrategyLedgerRecorder ledgerRecorder = mock(CoinStrategyLedgerRecorder.class);
        HtxTrendRecorder recorder = new HtxTrendRecorder(positionMapper, orderMapper, signalMapper, ledgerRecorder);
        HtxTrendPositionEntity position = trendPosition();
        when(positionMapper.selectById(42L)).thenReturn(position);

        recorder.markEntryFilled(42L, new BigDecimal("2"), new BigDecimal("100"), new BigDecimal("0.01"),
                new BigDecimal("100"), new BigDecimal("100"), "filled");

        verify(positionMapper).markEntryFilled(42L, new BigDecimal("2"), new BigDecimal("100"),
                new BigDecimal("0.01"), new BigDecimal("100"), new BigDecimal("100"), "filled");
        verify(ledgerRecorder).recordTrendEntryFill(position);
    }

    @Test
    void markCloseFilledProjectsClosedPositionToUnifiedLedger() {
        HtxTrendPositionMapper positionMapper = mock(HtxTrendPositionMapper.class);
        HtxTrendOrderMapper orderMapper = mock(HtxTrendOrderMapper.class);
        HtxTrendSignalMapper signalMapper = mock(HtxTrendSignalMapper.class);
        CoinStrategyLedgerRecorder ledgerRecorder = mock(CoinStrategyLedgerRecorder.class);
        HtxTrendRecorder recorder = new HtxTrendRecorder(positionMapper, orderMapper, signalMapper, ledgerRecorder);
        HtxTrendPositionEntity position = trendPosition();
        when(positionMapper.selectById(42L)).thenReturn(position);

        recorder.markCloseFilled(42L, new BigDecimal("2"), new BigDecimal("110"), new BigDecimal("0.02"),
                new BigDecimal("0.50"), "closed");

        verify(positionMapper).markCloseFilled(42L, new BigDecimal("2"), new BigDecimal("110"),
                new BigDecimal("0.02"), new BigDecimal("0.50"), "closed");
        verify(ledgerRecorder).recordTrendCloseFill(position);
    }

    @Test
    void closeAcknowledgementFitsPositionColumnWhenExitEvidenceIsLong() {
        HtxTrendPositionMapper positionMapper = mock(HtxTrendPositionMapper.class);
        HtxTrendRecorder recorder = new HtxTrendRecorder(positionMapper, mock(HtxTrendOrderMapper.class),
                mock(HtxTrendSignalMapper.class));
        String longEvidence = "x".repeat(600);

        recorder.markCloseAccepted(7L, longEvidence, "client-7", 8L, "close sent reason=" + longEvidence);

        ArgumentCaptor<String> exitReason = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(positionMapper).markCloseAccepted(anyLong(), exitReason.capture(), anyString(), anyLong(), message.capture());
        assertEquals(512, exitReason.getValue().length());
        assertEquals(512, message.getValue().length());
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

    private static HtxTrendPositionEntity trendPosition() {
        HtxTrendPositionEntity position = new HtxTrendPositionEntity();
        position.setId(42L);
        position.setPositionKey("trend-BTC-USDT-42");
        position.setContractCode("BTC-USDT");
        position.setStatus(HtxTrendRecorder.ACTIVE);
        position.setSide("LONG");
        position.setContractSize(new BigDecimal("0.001"));
        position.setContractVolume(2L);
        position.setEntryClientOrderId("1001");
        position.setEntryFilledContracts(new BigDecimal("2"));
        position.setEntryAvgPrice(new BigDecimal("100000"));
        position.setEntryFee(new BigDecimal("0.01"));
        position.setCloseClientOrderId("1002");
        position.setCloseFilledContracts(new BigDecimal("2"));
        position.setCloseAvgPrice(new BigDecimal("100500"));
        position.setCloseFee(new BigDecimal("0.02"));
        position.setRealizedProfit(new BigDecimal("0.50"));
        position.setEntrySignal("Fibonacci pullback continuation");
        position.setExitReason("trailing stop");
        position.setAccountLabel("primary");
        return position;
    }

    private static HtxTrendOrderEntity trendOrder() {
        HtxTrendOrderEntity order = new HtxTrendOrderEntity();
        order.setId(7L);
        order.setPositionId(42L);
        order.setPositionKey("trend-BTC-USDT-42");
        order.setContractCode("BTC-USDT");
        order.setLeg(HtxTrendRecorder.LEG_ENTRY);
        order.setTrendSide("LONG");
        order.setOrderSide("buy");
        order.setClientOrderId("1001");
        order.setAccountLabel("primary");
        order.setEstimatedFee(new BigDecimal("0.01"));
        order.setReason("entry signal: Fibonacci pullback continuation");
        return order;
    }
}
