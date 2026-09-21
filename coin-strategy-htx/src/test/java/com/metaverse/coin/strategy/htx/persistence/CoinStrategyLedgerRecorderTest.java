package com.metaverse.coin.strategy.htx.persistence;

import com.metaverse.coin.strategy.htx.persistence.entity.CoinStrategyLedgerEventEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendOrderEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendPositionEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.CoinStrategyLedgerEventMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTradeAccountMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CoinStrategyLedgerRecorderTest {

    @Test
    void trendLifecycleWritesAdditiveNetPnlWithoutDoubleCountingFees() {
        CoinStrategyLedgerEventMapper ledgerMapper = mock(CoinStrategyLedgerEventMapper.class);
        HtxTradeAccountMapper accountMapper = mock(HtxTradeAccountMapper.class);
        when(accountMapper.findIdByAccountLabel("primary")).thenReturn(1L);
        when(ledgerMapper.insert(any())).thenReturn(1);
        CoinStrategyLedgerRecorder recorder = new CoinStrategyLedgerRecorder(ledgerMapper, accountMapper);
        HtxTrendPositionEntity position = trendPosition();

        recorder.recordTrendEntryFill(position);
        recorder.recordTrendCloseFill(position);

        ArgumentCaptor<CoinStrategyLedgerEventEntity> captor = ArgumentCaptor.forClass(CoinStrategyLedgerEventEntity.class);
        verify(ledgerMapper, org.mockito.Mockito.times(5)).insert(captor.capture());
        List<CoinStrategyLedgerEventEntity> events = captor.getAllValues();

        CoinStrategyLedgerEventEntity openFill = event(events, CoinStrategyLedgerRecorder.EVENT_OPEN_FILL);
        assertEquals(new BigDecimal("200.000"), openFill.getAmountUsdt());
        assertEquals(0, BigDecimal.ZERO.compareTo(openFill.getFeeUsdt()));
        assertNull(openFill.getNetPnlAfterFee());

        CoinStrategyLedgerEventEntity closeFill = event(events, CoinStrategyLedgerRecorder.EVENT_CLOSE_FILL);
        assertEquals(new BigDecimal("201.000"), closeFill.getAmountUsdt());
        assertEquals(0, BigDecimal.ZERO.compareTo(closeFill.getFeeUsdt()));
        assertNull(closeFill.getNetPnlAfterFee());

        BigDecimal additiveNet = events.stream()
                .map(CoinStrategyLedgerEventEntity::getNetPnlAfterFee)
                .filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("0.47").compareTo(additiveNet));

        BigDecimal actualFees = events.stream()
                .filter(e -> CoinStrategyLedgerRecorder.EVENT_FEE.equals(e.getEventType()))
                .map(CoinStrategyLedgerEventEntity::getFeeUsdt)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("0.03").compareTo(actualFees));
    }

    @Test
    void orderIntentDoesNotPolluteActualFeeLedgerWithEstimatedFee() {
        CoinStrategyLedgerEventMapper ledgerMapper = mock(CoinStrategyLedgerEventMapper.class);
        HtxTradeAccountMapper accountMapper = mock(HtxTradeAccountMapper.class);
        when(accountMapper.findIdByAccountLabel("primary")).thenReturn(1L);
        CoinStrategyLedgerRecorder recorder = new CoinStrategyLedgerRecorder(ledgerMapper, accountMapper);
        HtxTrendOrderEntity order = trendOrder();

        recorder.recordTrendOrderIntent(order);

        ArgumentCaptor<CoinStrategyLedgerEventEntity> captor = ArgumentCaptor.forClass(CoinStrategyLedgerEventEntity.class);
        verify(ledgerMapper).insert(captor.capture());
        CoinStrategyLedgerEventEntity event = captor.getValue();
        assertEquals(CoinStrategyLedgerRecorder.EVENT_OPEN_ORDER, event.getEventType());
        assertEquals(0, BigDecimal.ZERO.compareTo(event.getFeeUsdt()));
        assertNull(event.getNetPnlAfterFee());
    }

    private static CoinStrategyLedgerEventEntity event(List<CoinStrategyLedgerEventEntity> events, String eventType) {
        return events.stream()
                .filter(e -> eventType.equals(e.getEventType()))
                .findFirst()
                .orElseThrow();
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
