package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapAccount;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapFeeRate;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapLeverageResult;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOpenOrders;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrderRequest;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrderResult;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapPosition;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxUsdtSwapService;
import com.metaverse.coin.strategy.htx.config.HtxContractProperties;
import com.metaverse.coin.strategy.htx.config.HtxTrendProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxTrendRecorder;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendOrderEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendPositionEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HtxTrendMirrorExecutorTest {

    @Test
    void mirrorEntryPersistsAccountSpecificFeeSnapshotBeforeOrder() {
        HtxTrendProperties properties = new HtxTrendProperties();
        properties.getMirror().setEnabled(true);
        properties.getMirror().setAccountLabel("TradeX");
        properties.getMirror().setSyncLeverage(false);
        HtxContractProperties contractProperties = new HtxContractProperties();
        HtxUsdtSwapService swap = mock(HtxUsdtSwapService.class);
        HtxTrendRecorder recorder = mock(HtxTrendRecorder.class);
        HtxTrendMirrorExecutor executor = new HtxTrendMirrorExecutor(properties, contractProperties, swap, recorder);
        HtxTrendPositionEntity position = position();
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(List.of()));
        HtxSwapOpenOrders openOrders = new HtxSwapOpenOrders();
        openOrders.setOrders(List.of());
        when(swap.openOrders("XAUT-USDT")).thenReturn(ok(openOrders));
        HtxSwapAccount account = new HtxSwapAccount();
        account.setMarginAvailable(new BigDecimal("100"));
        when(swap.accountInfo("XAUT-USDT")).thenReturn(ok(List.of(account)));
        when(swap.tradingFee("XAUT-USDT")).thenReturn(ok(List.of(feeRate("0.0002"))));
        when(swap.placeOrder(any(HtxSwapOrderRequest.class))).thenReturn(ok(accepted(123L, 456L)));

        HtxTrendMirrorExecutor.SyncResult result = executor.syncEntry(position);

        assertTrue(result.ok());
        ArgumentCaptor<HtxTrendOrderEntity> captor = ArgumentCaptor.forClass(HtxTrendOrderEntity.class);
        verify(recorder).createOrderIntent(captor.capture());
        HtxTrendOrderEntity order = captor.getValue();
        assertEquals("TradeX", order.getAccountLabel());
        assertEquals(new BigDecimal("0.0002"), order.getFeeRateUsed());
        assertTrue(order.getEstimatedFee().signum() > 0);
        assertEquals(position.getSizingMultiplier(), order.getSizingMultiplier());
        assertEquals(position.getEntryGateReason(), order.getEntryGateReason());
        verify(recorder).markOrderAccepted(eq(77L), eq(HtxTrendRecorder.LEG_ENTRY), eq(order.getClientOrderId()),
                eq(123L), eq(456L), eq("mirror entry accepted by exchange"));
    }

    @Test
    void mirrorEntryAttachesItsOrderAndLifecycleToTheFollowerPosition() {
        HtxTrendProperties properties = new HtxTrendProperties();
        properties.getMirror().setEnabled(true);
        properties.getMirror().setAccountLabel("TradeX");
        properties.getMirror().setSyncLeverage(false);
        HtxUsdtSwapService swap = mock(HtxUsdtSwapService.class);
        HtxTrendRecorder recorder = mock(HtxTrendRecorder.class);
        HtxTrendMirrorExecutor executor = new HtxTrendMirrorExecutor(properties, new HtxContractProperties(), swap, recorder);
        HtxTrendPositionEntity primary = position();
        HtxTrendPositionEntity follower = position();
        follower.setId(177L);
        follower.setPositionKey("mirror:TradeX:trend-XAUT-USDT-77");
        when(recorder.ensureMirrorPosition(eq(primary), isNull(), eq("TradeX"))).thenReturn(follower);
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(List.of()));
        HtxSwapOpenOrders openOrders = new HtxSwapOpenOrders();
        openOrders.setOrders(List.of());
        when(swap.openOrders("XAUT-USDT")).thenReturn(ok(openOrders));
        HtxSwapAccount account = new HtxSwapAccount();
        account.setMarginAvailable(new BigDecimal("100"));
        when(swap.accountInfo("XAUT-USDT")).thenReturn(ok(List.of(account)));
        when(swap.tradingFee("XAUT-USDT")).thenReturn(ok(List.of(feeRate("0.0002"))));
        when(swap.placeOrder(any(HtxSwapOrderRequest.class))).thenReturn(ok(accepted(123L, 456L)));

        executor.syncEntry(primary);

        ArgumentCaptor<HtxTrendOrderEntity> orderCaptor = ArgumentCaptor.forClass(HtxTrendOrderEntity.class);
        verify(recorder).createOrderIntent(orderCaptor.capture());
        assertEquals(177L, orderCaptor.getValue().getPositionId());
        assertEquals("mirror:TradeX:trend-XAUT-USDT-77", orderCaptor.getValue().getPositionKey());
        verify(recorder).markEntryAccepted(177L, 123L, "mirror entry accepted by exchange");
    }


    @Test
    void mirrorEntryContinuesWhenLeverageSyncFailsByDefault() {
        HtxTrendProperties properties = new HtxTrendProperties();
        properties.getMirror().setEnabled(true);
        properties.getMirror().setAccountLabel("TradeX");
        properties.getMirror().setSyncLeverage(true);
        HtxContractProperties contractProperties = new HtxContractProperties();
        HtxUsdtSwapService swap = mock(HtxUsdtSwapService.class);
        HtxTrendRecorder recorder = mock(HtxTrendRecorder.class);
        HtxTrendMirrorExecutor executor = new HtxTrendMirrorExecutor(properties, contractProperties, swap, recorder);
        HtxTrendPositionEntity position = position();
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(List.of()));
        HtxSwapOpenOrders openOrders = new HtxSwapOpenOrders();
        openOrders.setOrders(List.of());
        when(swap.openOrders("XAUT-USDT")).thenReturn(ok(openOrders));
        when(swap.switchLeverage("XAUT-USDT", "long", properties.getLeverage()))
                .thenReturn(HtxRestResponse.<HtxSwapLeverageResult>builder().status("error")
                        .errMsg("Position mode parameter passing error!").build());
        HtxSwapAccount account = new HtxSwapAccount();
        account.setMarginAvailable(new BigDecimal("100"));
        when(swap.accountInfo("XAUT-USDT")).thenReturn(ok(List.of(account)));
        when(swap.tradingFee("XAUT-USDT")).thenReturn(ok(List.of(feeRate("0.0002"))));
        when(swap.placeOrder(any(HtxSwapOrderRequest.class))).thenReturn(ok(accepted(123L, 456L)));

        HtxTrendMirrorExecutor.SyncResult result = executor.syncEntry(position);

        assertTrue(result.ok());
        ArgumentCaptor<HtxTrendOrderEntity> captor = ArgumentCaptor.forClass(HtxTrendOrderEntity.class);
        verify(recorder).createOrderIntent(captor.capture());
        assertTrue(captor.getValue().getReason().contains("mirror leverage switch failed account=TradeX"));
        verify(swap).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void mirrorEntryCanRequireSuccessfulLeverageSync() {
        HtxTrendProperties properties = new HtxTrendProperties();
        properties.getMirror().setEnabled(true);
        properties.getMirror().setAccountLabel("TradeX");
        properties.getMirror().setSyncLeverage(true);
        properties.getMirror().setBlockEntryOnLeverageSyncFailure(true);
        HtxContractProperties contractProperties = new HtxContractProperties();
        HtxUsdtSwapService swap = mock(HtxUsdtSwapService.class);
        HtxTrendRecorder recorder = mock(HtxTrendRecorder.class);
        HtxTrendMirrorExecutor executor = new HtxTrendMirrorExecutor(properties, contractProperties, swap, recorder);
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(List.of()));
        HtxSwapOpenOrders openOrders = new HtxSwapOpenOrders();
        openOrders.setOrders(List.of());
        when(swap.openOrders("XAUT-USDT")).thenReturn(ok(openOrders));
        when(swap.switchLeverage("XAUT-USDT", "long", properties.getLeverage()))
                .thenReturn(HtxRestResponse.<HtxSwapLeverageResult>builder().status("error")
                        .errMsg("Position mode parameter passing error!").build());

        HtxTrendMirrorExecutor.SyncResult result = executor.syncEntry(position());

        assertTrue(!result.ok());
        assertTrue(result.message().contains("mirror leverage switch failed account=TradeX"));
        verify(recorder, never()).createOrderIntent(any());
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void mirrorPartialCloseUsesOnlyPrimaryPartialVolume() {
        HtxTrendProperties properties = new HtxTrendProperties();
        properties.getMirror().setEnabled(true);
        properties.getMirror().setAccountLabel("TradeX");
        properties.getMirror().setSyncLeverage(false);
        HtxUsdtSwapService swap = mock(HtxUsdtSwapService.class);
        HtxTrendRecorder recorder = mock(HtxTrendRecorder.class);
        HtxTrendMirrorExecutor executor = new HtxTrendMirrorExecutor(properties, new HtxContractProperties(), swap, recorder);
        HtxTrendPositionEntity position = position();
        position.setRemainingContractVolume(50L);
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(List.of(openLongPosition("50"))));
        HtxSwapOpenOrders openOrders = new HtxSwapOpenOrders();
        openOrders.setOrders(List.of());
        when(swap.openOrders("XAUT-USDT")).thenReturn(ok(openOrders));
        when(swap.tradingFee("XAUT-USDT")).thenReturn(ok(List.of(feeRate("0.0002"))));
        when(swap.placeOrder(any(HtxSwapOrderRequest.class))).thenReturn(ok(accepted(123L, 456L)));

        HtxTrendMirrorExecutor.SyncResult result = executor.syncPartialClose(position, 14L);

        assertTrue(result.pending());
        ArgumentCaptor<HtxTrendOrderEntity> captor = ArgumentCaptor.forClass(HtxTrendOrderEntity.class);
        verify(recorder).createOrderIntent(captor.capture());
        assertEquals(HtxTrendRecorder.LEG_PARTIAL_CLOSE, captor.getValue().getLeg());
        assertEquals(14L, captor.getValue().getRequestVolume());
    }

    private static HtxTrendPositionEntity position() {
        HtxTrendPositionEntity entity = new HtxTrendPositionEntity();
        entity.setId(77L);
        entity.setPositionKey("trend-XAUT-USDT-77");
        entity.setContractCode("XAUT-USDT");
        entity.setSide("LONG");
        entity.setContractSize(new BigDecimal("0.001"));
        entity.setContractVolume(50L);
        entity.setEntryAvgPrice(new BigDecimal("4300"));
        entity.setEntrySignal("test signal");
        entity.setSizingMultiplier(new BigDecimal("1.20"));
        entity.setSizingReason("quality=STRONG confidence=HIGH");
        entity.setEntryGateReason("directionConfidence=PASS score=110 required=90");
        return entity;
    }

    private static HtxSwapPosition openLongPosition(String volume) {
        HtxSwapPosition position = new HtxSwapPosition();
        position.setContractCode("XAUT-USDT");
        position.setPositionSide("long");
        position.setVolume(new BigDecimal(volume));
        return position;
    }

    private static HtxSwapFeeRate feeRate(String taker) {
        HtxSwapFeeRate rate = new HtxSwapFeeRate();
        rate.setContractCode("XAUT-USDT");
        rate.setOpenTakerFee(new BigDecimal(taker));
        rate.setCloseTakerFee(new BigDecimal(taker));
        return rate;
    }

    private static HtxSwapOrderResult accepted(long orderId, long clientOrderId) {
        HtxSwapOrderResult result = new HtxSwapOrderResult();
        result.setOrderId(orderId);
        result.setClientOrderId(clientOrderId);
        return result;
    }

    private static <T> HtxRestResponse<T> ok(T data) {
        return HtxRestResponse.<T>builder().status("ok").data(data).build();
    }
}
