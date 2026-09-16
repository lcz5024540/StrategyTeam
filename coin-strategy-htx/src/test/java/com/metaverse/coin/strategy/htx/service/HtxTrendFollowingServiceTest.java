package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.model.HtxDepth;
import com.metaverse.coin.strategy.common.client.htx.model.HtxPriceLevel;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapAccount;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapContractInfo;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapFundingRate;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapKline;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapLeverageResult;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOpenOrders;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrderRequest;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrderResult;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapPosition;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxUsdtSwapService;
import com.metaverse.coin.strategy.htx.config.HtxContractProperties;
import com.metaverse.coin.strategy.htx.config.HtxFundingArbitrageProperties;
import com.metaverse.coin.strategy.htx.config.HtxMarketMakerProperties;
import com.metaverse.coin.strategy.htx.config.HtxTrendProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxTrendRecorder;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendOrderEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendPositionEntity;
import com.metaverse.coin.strategy.htx.service.HtxTrendSignalCalculator.TrendSide;
import com.metaverse.coin.strategy.htx.service.HtxTrendSignalCalculator.TrendSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HtxTrendFollowingServiceTest {

    private HtxUsdtSwapService swap;
    private HtxTrendRecorder recorder;
    private HtxTrendSignalCalculator calculator;
    private HtxContractProperties contractProperties;
    private HtxFundingArbitrageProperties fundingArbitrageProperties;
    private HtxMarketMakerProperties marketMakerProperties;
    private HtxTrendProperties properties;
    private HtxTrendFollowingService service;

    @BeforeEach
    void setUp() {
        swap = mock(HtxUsdtSwapService.class);
        recorder = mock(HtxTrendRecorder.class);
        calculator = mock(HtxTrendSignalCalculator.class);
        contractProperties = new HtxContractProperties();
        contractProperties.setEnabled(true);
        contractProperties.setDryRun(false);
        contractProperties.setLiveEnabled(true);
        contractProperties.setMaxLeverage(5);
        fundingArbitrageProperties = new HtxFundingArbitrageProperties();
        fundingArbitrageProperties.setEnabled(false);
        marketMakerProperties = new HtxMarketMakerProperties();
        marketMakerProperties.setEnabled(false);
        properties = new HtxTrendProperties();
        properties.setEnabled(true);
        properties.setObserveOnly(false);
        properties.setMaxEntryFundingCostRate(new BigDecimal("0.0006"));
        service = new HtxTrendFollowingService(swap, contractProperties, fundingArbitrageProperties,
                marketMakerProperties, properties, recorder, calculator);
    }

    @Test
    void shortSignalSkipsWhenNegativeFundingMakesShortSidePayTooMuch() {
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.SHORT);
        givenFunding("-0.0010");
        givenDepth();
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.empty());

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.SKIPPED, result.code());
        assertTrue(result.message().contains("funding cost blocked"));
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void observeOnlyRecordsSignalButDoesNotSendOrder() {
        properties.setObserveOnly(true);
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.LONG);
        givenFunding("-0.0001");
        givenDepth();
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.empty());

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.OBSERVED, result.code());
        assertTrue(result.message().contains("observe-only signal=LONG"));
        verify(recorder).recordSignal(any());
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void observeOnlyStillRecordsSignalWhenFundingArbitrageEnabled() {
        properties.setObserveOnly(true);
        fundingArbitrageProperties.setEnabled(true);
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.LONG);
        givenFunding("-0.0001");
        givenDepth();
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.empty());

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.OBSERVED, result.code());
        verify(recorder).recordSignal(any());
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void liveEntryPersistsOrderIntentBeforeSendingExchangeOrder() {
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.SHORT);
        givenFunding("0.0001");
        givenDepth();
        givenNoOpenOrders();
        givenNoExternalPositions();
        givenContractMargin();
        when(swap.switchLeverage(eq("XAUT-USDT"), eq("short"), eq(properties.getLeverage())))
                .thenReturn(ok(leverage("short")));
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.empty());
        when(recorder.createIntent(any())).thenReturn(77L);
        when(swap.placeOrder(any(HtxSwapOrderRequest.class))).thenReturn(ok(accepted(88L, 99L)));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.ENTRY_SENT, result.code());
        InOrder order = inOrder(recorder, swap);
        order.verify(recorder).createOrderIntent(any(HtxTrendOrderEntity.class));
        order.verify(swap).placeOrder(any(HtxSwapOrderRequest.class));
        ArgumentCaptor<HtxTrendOrderEntity> captor = ArgumentCaptor.forClass(HtxTrendOrderEntity.class);
        verify(recorder).createOrderIntent(captor.capture());
        HtxTrendOrderEntity intent = captor.getValue();
        assertEquals(77L, intent.getPositionId());
        assertEquals(HtxTrendRecorder.LEG_ENTRY, intent.getLeg());
        assertEquals("SHORT", intent.getTrendSide());
        assertEquals("sell", intent.getOrderSide());
        assertEquals("short", intent.getPositionSide());
        assertEquals(false, intent.getReduceOnly());
        assertTrue(intent.getReason().contains("entry signal"));
        verify(recorder).markOrderAccepted(77L, HtxTrendRecorder.LEG_ENTRY, intent.getClientOrderId(),
                88L, 99L, "entry accepted by exchange");
    }

    @Test
    void liveEntrySkipsWhenExpectedMoveCannotCoverRoundTripCosts() {
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.LONG, new BigDecimal("1.0"));
        givenFunding("-0.0001");
        givenDepth();
        givenNoOpenOrders();
        givenNoExternalPositions();
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.empty());

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.SKIPPED, result.code());
        assertTrue(result.message().contains("expected move too small after fees"));
        verify(swap, never()).switchLeverage(any(), any(), any(Integer.class));
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void activeLongExitsFlatSignalAfterFeeProtectionInsteadOfWaitingForWideStop() {
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.FLAT, new BigDecimal("7.0"));
        givenFunding("-0.0001");
        HtxTrendPositionEntity active = activeLongPosition();
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.of(active));
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(List.of(openLongPosition("4324.34"))));
        when(swap.placeOrder(any(HtxSwapOrderRequest.class))).thenReturn(ok(accepted(888L, 999L)));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.CLOSE_SENT, result.code());
        assertTrue(result.message().contains("flat trend after fee protection"));
        verify(recorder).updateRiskLevels(eq(77L), eq(new BigDecimal("4295.00")),
                eq(new BigDecimal("4320.16")), eq(null), eq(new BigDecimal("4332.21")),
                eq(new BigDecimal("4315.84")), any());
        ArgumentCaptor<HtxTrendOrderEntity> captor = ArgumentCaptor.forClass(HtxTrendOrderEntity.class);
        verify(recorder).createOrderIntent(captor.capture());
        assertEquals(HtxTrendRecorder.LEG_CLOSE, captor.getValue().getLeg());
        assertEquals(true, captor.getValue().getReduceOnly());
        assertTrue(captor.getValue().getReason().contains("flat trend after fee protection"));
    }

    @Test
    void activeLongClampsTrailingStopToFeeProtectedBreakevenWhenPriceFallsThroughProtectedStop() {
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.FLAT, new BigDecimal("7.0"));
        givenFunding("-0.0001");
        HtxTrendPositionEntity active = activeLongPosition();
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.of(active));
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(List.of(openLongPosition("4319.50"))));
        when(swap.placeOrder(any(HtxSwapOrderRequest.class))).thenReturn(ok(accepted(888L, 999L)));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.CLOSE_SENT, result.code());
        assertTrue(result.message().contains("stop=4320.16"));
        ArgumentCaptor<HtxTrendOrderEntity> captor = ArgumentCaptor.forClass(HtxTrendOrderEntity.class);
        verify(recorder).createOrderIntent(captor.capture());
        assertTrue(captor.getValue().getReason().contains("stop=4320.16"));
    }

    @Test
    void marketMakerSwitchBlocksExclusiveTrendMode() {
        marketMakerProperties.setEnabled(true);

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.SKIPPED, result.code());
        assertTrue(result.message().contains("market-maker enabled"));
        verify(swap, never()).contractInfo(any());
    }

    private void givenContractInfo() {
        HtxSwapContractInfo info = new HtxSwapContractInfo();
        info.setContractCode("XAUT-USDT");
        info.setContractSize(new BigDecimal("0.001"));
        when(swap.contractInfo("XAUT-USDT")).thenReturn(ok(List.of(info)));
    }

    private void givenKlines() {
        List<HtxSwapKline> klines = List.of(candle(1L), candle(2L));
        when(swap.historyKline(eq("XAUT-USDT"), eq(properties.getPeriod()), eq(properties.getKlineSize())))
                .thenReturn(ok(klines));
        when(swap.historyKline(eq("XAUT-USDT"), eq(properties.getTrendPeriod()), eq(properties.getTrendKlineSize())))
                .thenReturn(ok(klines));
    }

    private void givenSignal(TrendSide side) {
        givenSignal(side, new BigDecimal("12"));
    }

    private void givenSignal(TrendSide side, BigDecimal atr) {
        TrendSignal signal = TrendSignal.of(side, 2L, new BigDecimal("4300"), new BigDecimal("4290"),
                new BigDecimal("4280"), new BigDecimal("4310"), new BigDecimal("4260"),
                atr, new BigDecimal("0.0028"), new BigDecimal("24"), "test signal");
        when(calculator.evaluate(anyList(), anyList(), eq(properties), anyLong())).thenReturn(signal);
    }

    private void givenFunding(String value) {
        HtxSwapFundingRate funding = new HtxSwapFundingRate();
        funding.setContractCode("XAUT-USDT");
        funding.setFundingRate(new BigDecimal(value));
        when(swap.fundingRate("XAUT-USDT")).thenReturn(ok(funding));
    }

    private void givenDepth() {
        HtxDepth depth = new HtxDepth();
        depth.setBids(List.of(new HtxPriceLevel(new BigDecimal("4299"), BigDecimal.ONE)));
        depth.setAsks(List.of(new HtxPriceLevel(new BigDecimal("4301"), BigDecimal.ONE)));
        when(swap.depth("XAUT-USDT", contractProperties.getDepthStep())).thenReturn(ok(depth));
    }

    private void givenNoOpenOrders() {
        HtxSwapOpenOrders openOrders = new HtxSwapOpenOrders();
        openOrders.setOrders(Collections.emptyList());
        when(swap.openOrders("XAUT-USDT")).thenReturn(ok(openOrders));
    }

    private void givenNoExternalPositions() {
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(Collections.emptyList()));
    }

    private void givenContractMargin() {
        HtxSwapAccount account = new HtxSwapAccount();
        account.setContractCode("XAUT-USDT");
        account.setMarginAvailable(new BigDecimal("1000"));
        when(swap.accountInfo("XAUT-USDT")).thenReturn(ok(List.of(account)));
    }

    private HtxSwapKline candle(long id) {
        HtxSwapKline candle = new HtxSwapKline();
        candle.setId(id);
        candle.setOpen(new BigDecimal("4300"));
        candle.setHigh(new BigDecimal("4310"));
        candle.setLow(new BigDecimal("4290"));
        candle.setClose(new BigDecimal("4305"));
        return candle;
    }

    private HtxTrendPositionEntity activeLongPosition() {
        HtxTrendPositionEntity position = new HtxTrendPositionEntity();
        position.setId(77L);
        position.setPositionKey("CSTF-XAUTUSDT-test");
        position.setContractCode("XAUT-USDT");
        position.setStatus(HtxTrendRecorder.ACTIVE);
        position.setSide("LONG");
        position.setContractSize(new BigDecimal("0.001"));
        position.setContractVolume(44L);
        position.setEntryAvgPrice(new BigDecimal("4315.84"));
        position.setEntryFee(new BigDecimal("0.075958784"));
        position.setEntryFilledContracts(new BigDecimal("44"));
        position.setStopPrice(new BigDecimal("4295.00"));
        position.setTrailingStopPrice(new BigDecimal("4316.64"));
        position.setTakeProfitPrice(null);
        position.setHighestPrice(new BigDecimal("4332.21"));
        position.setLowestPrice(new BigDecimal("4315.84"));
        return position;
    }

    private HtxSwapPosition openLongPosition(String markPrice) {
        HtxSwapPosition position = new HtxSwapPosition();
        position.setContractCode("XAUT-USDT");
        position.setPositionSide("long");
        position.setVolume(new BigDecimal("44"));
        position.setMarkPrice(new BigDecimal(markPrice));
        return position;
    }

    private <T> HtxRestResponse<T> ok(T data) {
        return HtxRestResponse.<T>builder().status("ok").data(data).build();
    }

    private HtxSwapOrderResult accepted(long orderId, long clientOrderId) {
        HtxSwapOrderResult result = new HtxSwapOrderResult();
        result.setOrderId(orderId);
        result.setClientOrderId(clientOrderId);
        return result;
    }

    private HtxSwapLeverageResult leverage(String positionSide) {
        HtxSwapLeverageResult result = new HtxSwapLeverageResult();
        result.setContractCode("XAUT-USDT");
        result.setPositionSide(positionSide);
        result.setLeverage(properties.getLeverage());
        return result;
    }
}
