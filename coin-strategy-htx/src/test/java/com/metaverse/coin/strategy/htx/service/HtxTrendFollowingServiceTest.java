package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.model.HtxDepth;
import com.metaverse.coin.strategy.common.client.htx.model.HtxPriceLevel;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapAccount;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapContractInfo;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapFundingRate;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapFeeRate;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapKline;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapLeverageResult;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOpenOrders;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrder;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrderRequest;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrderResult;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapPosition;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxUsdtSwapService;
import com.metaverse.coin.strategy.htx.config.HtxContractProperties;
import com.metaverse.coin.strategy.htx.config.HtxFundingArbitrageProperties;
import com.metaverse.coin.strategy.htx.config.HtxMarketMakerProperties;
import com.metaverse.coin.strategy.htx.config.HtxTrendProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxTrendRecorder;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendStrategyPolicyEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendExitDecisionEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendOrderEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendPositionEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendSignalEntity;
import com.metaverse.coin.strategy.htx.persistence.model.HtxTrendBreadthStats;
import com.metaverse.coin.strategy.htx.persistence.model.HtxTrendPerformanceStats;
import com.metaverse.coin.strategy.htx.service.HtxTrendSignalCalculator.TrendSide;
import com.metaverse.coin.strategy.htx.service.HtxTrendSignalCalculator.TrendSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    private HtxTrendMirrorExecutor mirrorExecutor;
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
        properties.setDirectionConfidenceGateEnabled(false);
        properties.setConfidenceSizingEnabled(false);
        properties.setEnsembleEntryEnabled(false);
        properties.setTargetQuoteNotional(new BigDecimal("190"));
        properties.setMaxQuoteNotional(new BigDecimal("190"));
        properties.setRiskPerTradeUsdt(new BigDecimal("1.5"));
        properties.setMaxAdaptivePositionMultiplier(new BigDecimal("1.50"));
        properties.setMaxEntryFundingCostRate(new BigDecimal("0.0006"));
        mirrorExecutor = mock(HtxTrendMirrorExecutor.class);
        when(mirrorExecutor.syncEntry(any())).thenReturn(HtxTrendMirrorExecutor.SyncResult.ok("mirror disabled"));
        when(mirrorExecutor.syncClose(any())).thenReturn(HtxTrendMirrorExecutor.SyncResult.ok("mirror disabled"));
        when(mirrorExecutor.syncPartialClose(any(), anyLong())).thenReturn(HtxTrendMirrorExecutor.SyncResult.ok("mirror disabled"));
        when(mirrorExecutor.blockPrimaryCloseUntilSynced()).thenReturn(true);
        service = new HtxTrendFollowingService(swap, contractProperties, fundingArbitrageProperties,
                marketMakerProperties, properties, recorder, calculator, mirrorExecutor);
        when(recorder.findActive(any())).thenReturn(Optional.empty());
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
        assertTrue(result.message().contains("entry gate: funding cost blocked"));
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
        assertTrue(result.message().contains("mode gate: observe-only signal=LONG"));
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
    void observeOnlyBypassesLiveEntryFundingCostGate() {
        properties.setObserveOnly(true);
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.SHORT);
        givenFunding("-0.0010");
        givenDepth();
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.empty());

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.OBSERVED, result.code());
        assertTrue(result.message().contains("observe-only signal=SHORT"));
        assertTrue(result.message().contains("candidates=1"));
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
        verify(swap, never()).openOrders(any());
        verify(swap, never()).positionInfo(any());
        verify(swap, never()).accountInfo(any());
        verify(swap, never()).switchLeverage(any(), any(), anyInt());
    }

    @Test
    void observeOnlyBypassesLiveLeverageGate() {
        properties.setObserveOnly(true);
        properties.setLeverage(contractProperties.getMaxLeverage() + 1);
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.LONG);
        givenFunding("0.0001");
        givenDepth();
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.empty());

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.OBSERVED, result.code());
        assertTrue(result.message().contains("live entry gates bypassed"));
        verify(swap, never()).switchLeverage(any(), any(), anyInt());
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void liveEntryClassifiesLeverageFailureAsExecutionPreflight() {
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.LONG);
        givenFunding("-0.0001");
        givenDepth();
        givenNoOpenOrders();
        givenNoExternalPositions();
        givenContractMargin();
        when(swap.switchLeverage(eq("XAUT-USDT"), eq("long"), eq(properties.getLeverage())))
                .thenReturn(error("risk", "leverage unavailable"));
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.empty());

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.SKIPPED, result.code());
        assertTrue(result.message().contains("execution preflight: leverage switch failed"));
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
        ArgumentCaptor<HtxTrendPositionEntity> positionCaptor = ArgumentCaptor.forClass(HtxTrendPositionEntity.class);
        verify(recorder).createIntent(positionCaptor.capture());
        HtxTrendPositionEntity positionIntent = positionCaptor.getValue();
        assertEquals("primary", positionIntent.getAccountLabel());
        assertEquals(new BigDecimal("0.0004"), positionIntent.getEntryFeeRate());
        assertTrue(positionIntent.getEstimatedEntryFee().signum() > 0);
        assertTrue(positionIntent.getEstimatedCloseFee().signum() > 0);
        assertTrue(positionIntent.getExpectedNetAfterFees().signum() > 0);
        assertEquals(new BigDecimal("1.00"), positionIntent.getSizingMultiplier().setScale(2));
        assertTrue(positionIntent.getSizingReason().contains("quality=NEUTRAL"));
        assertTrue(positionIntent.getEntryGateReason().contains("expectedMoveGate=PASS"));

        ArgumentCaptor<HtxTrendOrderEntity> captor = ArgumentCaptor.forClass(HtxTrendOrderEntity.class);
        verify(recorder).createOrderIntent(captor.capture());
        HtxTrendOrderEntity intent = captor.getValue();
        assertEquals(77L, intent.getPositionId());
        assertEquals(HtxTrendRecorder.LEG_ENTRY, intent.getLeg());
        assertEquals("SHORT", intent.getTrendSide());
        assertEquals("sell", intent.getOrderSide());
        assertEquals("short", intent.getPositionSide());
        assertEquals(false, intent.getReduceOnly());
        assertEquals("primary", intent.getAccountLabel());
        assertEquals(new BigDecimal("0.0004"), intent.getFeeRateUsed());
        assertTrue(intent.getEstimatedFee().signum() > 0);
        assertTrue(intent.getReason().contains("entry signal"));
        verify(recorder).markOrderAccepted(77L, HtxTrendRecorder.LEG_ENTRY, intent.getClientOrderId(),
                88L, 99L, "entry accepted by exchange");
    }

    @Test
    void highConfidenceConfluenceUsesLargerButCappedPositionSize() {
        properties.setDirectionConfidenceGateEnabled(true);
        properties.setConfidenceSizingEnabled(true);
        properties.setTargetQuoteNotional(new BigDecimal("220"));
        properties.setMaxQuoteNotional(new BigDecimal("350"));
        properties.setRiskPerTradeUsdt(new BigDecimal("10"));
        properties.setMaxAdaptivePositionMultiplier(new BigDecimal("1.80"));
        givenContractInfo();
        givenRisingKlines("XAUT-USDT");
        givenSignal(TrendSide.LONG, new BigDecimal("12"), new BigDecimal("0.0060"), freshBarTime(),
                new BigDecimal("60"), "close broke Donchian high with higher-timeframe EMA uptrend");
        givenFunding("-0.0001");
        givenDepth();
        givenNoOpenOrders();
        givenNoExternalPositions();
        givenContractMargin();
        givenBroadBullishBreadth();
        givenStrongPerformance();
        when(swap.switchLeverage(eq("XAUT-USDT"), eq("long"), eq(properties.getLeverage())))
                .thenReturn(ok(leverage("long")));
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.empty());
        when(recorder.createIntent(any())).thenReturn(77L);
        when(swap.placeOrder(any(HtxSwapOrderRequest.class))).thenReturn(ok(accepted(88L, 99L)));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.ENTRY_SENT, result.code());
        assertTrue(result.message().contains("sizingMultiplier=1.80"));
        assertTrue(result.message().contains("confidence=STRONG_CONFLUENCE"));
        ArgumentCaptor<HtxSwapOrderRequest> captor = ArgumentCaptor.forClass(HtxSwapOrderRequest.class);
        verify(swap).placeOrder(captor.capture());
        assertEquals("81", captor.getValue().getVolume());
    }

    @Test
    void accountFeeRateCanBlockFeeDominatedEntry() {
        properties.setEstimatedTakerFeeRate(new BigDecimal("0.0001"));
        properties.setMaxEntrySignalChaseAtrMultiplier(BigDecimal.ZERO);
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.LONG, new BigDecimal("10.0"));
        givenFunding("-0.0001");
        givenDepth();
        givenNoOpenOrders();
        givenNoExternalPositions();
        when(swap.tradingFee("XAUT-USDT")).thenReturn(ok(List.of(feeRate("XAUT-USDT", "0.0030", "0.0030"))));
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.empty());

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.SKIPPED, result.code());
        assertTrue(result.message().contains("expected move too small after fees"));
        assertTrue(result.message().contains("feeRate=0.0030"));
        verify(swap, never()).switchLeverage(any(), any(), any(Integer.class));
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void broadBullishMarketAndStrongQualityIncreaseAlignedLongSize() {
        properties.setMaxQuoteNotional(new BigDecimal("285"));
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.LONG);
        givenFunding("-0.0001");
        givenDepth();
        givenNoOpenOrders();
        givenNoExternalPositions();
        givenContractMargin();
        givenBroadBullishBreadth();
        givenStrongPerformance();
        when(swap.switchLeverage(eq("XAUT-USDT"), eq("long"), eq(properties.getLeverage())))
                .thenReturn(ok(leverage("long")));
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.empty());
        when(recorder.createIntent(any())).thenReturn(77L);
        when(swap.placeOrder(any(HtxSwapOrderRequest.class))).thenReturn(ok(accepted(88L, 99L)));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.ENTRY_SENT, result.code());
        assertTrue(result.message().contains("sizingMultiplier=1.50"));
        ArgumentCaptor<HtxSwapOrderRequest> captor = ArgumentCaptor.forClass(HtxSwapOrderRequest.class);
        verify(swap).placeOrder(captor.capture());
        assertEquals("66", captor.getValue().getVolume());
    }

    @Test
    void recentLosingPositionKeepsSameContractInLossCooldown() {
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.LONG);
        givenFunding("-0.0001");
        when(recorder.findLastDone("XAUT-USDT")).thenReturn(Optional.of(donePosition("-1.20", "0.05", "0.05", 30)));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.SKIPPED, result.code());
        assertTrue(result.message().contains("loss cooldown active"));
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void recentProfitablePositionUsesShortProfitCooldownForOrdinarySignal() {
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.LONG);
        givenFunding("-0.0001");
        when(recorder.findLastDone("XAUT-USDT")).thenReturn(Optional.of(donePosition("3.00", "0.05", "0.05", 5)));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.SKIPPED, result.code());
        assertTrue(result.message().contains("profit cooldown active"));
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void strongBreakoutCanReenterAfterProfitableCooldownMinimumAge() {
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.LONG, new BigDecimal("12"), new BigDecimal("0.0030"), freshBarTime(),
                new BigDecimal("35"), "close broke Donchian high with higher-timeframe EMA uptrend");
        givenFunding("-0.0001");
        givenDepth();
        givenNoOpenOrders();
        givenNoExternalPositions();
        givenContractMargin();
        givenBroadBullishBreadth();
        when(recorder.findLastDone("XAUT-USDT")).thenReturn(Optional.of(donePosition("3.00", "0.05", "0.05", 11)));
        when(swap.switchLeverage(eq("XAUT-USDT"), eq("long"), eq(properties.getLeverage())))
                .thenReturn(ok(leverage("long")));
        when(recorder.createIntent(any())).thenReturn(77L);
        when(swap.placeOrder(any(HtxSwapOrderRequest.class))).thenReturn(ok(accepted(88L, 99L)));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.ENTRY_SENT, result.code());
        verify(swap).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void weakQualityDownsizesButDoesNotDisableLiveEntry() {
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.LONG);
        givenFunding("-0.0001");
        givenDepth();
        givenNoOpenOrders();
        givenNoExternalPositions();
        givenContractMargin();
        givenWeakPerformance();
        when(swap.switchLeverage(eq("XAUT-USDT"), eq("long"), eq(properties.getLeverage())))
                .thenReturn(ok(leverage("long")));
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.empty());
        when(recorder.createIntent(any())).thenReturn(77L);
        when(swap.placeOrder(any(HtxSwapOrderRequest.class))).thenReturn(ok(accepted(88L, 99L)));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.ENTRY_SENT, result.code());
        assertTrue(result.message().contains("sizingMultiplier=0.50"));
        ArgumentCaptor<HtxSwapOrderRequest> captor = ArgumentCaptor.forClass(HtxSwapOrderRequest.class);
        verify(swap).placeOrder(captor.capture());
        assertEquals("22", captor.getValue().getVolume());
    }

    @Test
    void broadMarketDoesNotResizeWeakQualityAboveWeakCap() {
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.LONG);
        givenFunding("-0.0001");
        givenDepth();
        givenNoOpenOrders();
        givenNoExternalPositions();
        givenContractMargin();
        givenBroadBullishBreadth();
        givenWeakPerformance();
        when(swap.switchLeverage(eq("XAUT-USDT"), eq("long"), eq(properties.getLeverage())))
                .thenReturn(ok(leverage("long")));
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.empty());
        when(recorder.createIntent(any())).thenReturn(77L);
        when(swap.placeOrder(any(HtxSwapOrderRequest.class))).thenReturn(ok(accepted(88L, 99L)));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.ENTRY_SENT, result.code());
        assertTrue(result.message().contains("sizingMultiplier=0.60"));
        assertTrue(result.message().contains("qualityCap=WEAK_MAX"));
        ArgumentCaptor<HtxSwapOrderRequest> captor = ArgumentCaptor.forClass(HtxSwapOrderRequest.class);
        verify(swap).placeOrder(captor.capture());
        assertEquals("26", captor.getValue().getVolume());
    }

    @Test
    void positiveFundingCanPromoteFlatSignalToCarryShortWhenLocallyAndBroadConfirmed() {
        givenContractInfo();
        givenKlines();
        givenFlatSignalForCarry(TrendSide.SHORT);
        givenFunding("0.0003");
        givenDepth();
        givenNoOpenOrders();
        givenNoExternalPositions();
        givenContractMargin();
        givenBroadBearishBreadth();
        when(swap.switchLeverage(eq("XAUT-USDT"), eq("short"), eq(properties.getLeverage())))
                .thenReturn(ok(leverage("short")));
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.empty());
        when(recorder.createIntent(any())).thenReturn(77L);
        when(swap.placeOrder(any(HtxSwapOrderRequest.class))).thenReturn(ok(accepted(88L, 99L)));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.ENTRY_SENT, result.code());
        ArgumentCaptor<HtxTrendOrderEntity> captor = ArgumentCaptor.forClass(HtxTrendOrderEntity.class);
        verify(recorder).createOrderIntent(captor.capture());
        assertEquals("SHORT", captor.getValue().getTrendSide());
        assertTrue(captor.getValue().getReason().contains("funding carry SHORT"));
    }

    @Test
    void negativeFundingCanPromoteFlatSignalToCarryLongWhenLocallyAndBroadConfirmed() {
        givenContractInfo();
        givenKlines();
        givenFlatSignalForCarry(TrendSide.LONG);
        givenFunding("-0.0003");
        givenDepth();
        givenNoOpenOrders();
        givenNoExternalPositions();
        givenContractMargin();
        givenBroadBullishBreadth();
        when(swap.switchLeverage(eq("XAUT-USDT"), eq("long"), eq(properties.getLeverage())))
                .thenReturn(ok(leverage("long")));
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.empty());
        when(recorder.createIntent(any())).thenReturn(77L);
        when(swap.placeOrder(any(HtxSwapOrderRequest.class))).thenReturn(ok(accepted(88L, 99L)));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.ENTRY_SENT, result.code());
        ArgumentCaptor<HtxTrendOrderEntity> captor = ArgumentCaptor.forClass(HtxTrendOrderEntity.class);
        verify(recorder).createOrderIntent(captor.capture());
        assertEquals("LONG", captor.getValue().getTrendSide());
        assertTrue(captor.getValue().getReason().contains("funding carry LONG"));
    }

    @Test
    void fundingCarryDoesNotPromoteWeakAdxFlatSignalToLiveEntry() {
        givenContractInfo();
        givenKlines();
        givenFlatSignalForCarryBelowAdx(TrendSide.LONG);
        givenFunding("-0.0003");
        givenBroadBullishBreadth();

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.NO_SIGNAL, result.code());
        assertTrue(result.message().contains("base signal is weak trend"));
        ArgumentCaptor<HtxTrendSignalEntity> captor = ArgumentCaptor.forClass(HtxTrendSignalEntity.class);
        verify(recorder).recordSignal(captor.capture());
        assertEquals("FLAT", captor.getValue().getSignal());
        assertTrue(captor.getValue().getReason().contains("funding carry rejected"));
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void fundingCarryDoesNotShortAgainstBroadBullishMarket() {
        givenContractInfo();
        givenKlines();
        givenFlatSignalForCarry(TrendSide.SHORT);
        givenFunding("0.0003");
        givenBroadBullishBreadth();
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.empty());

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.NO_SIGNAL, result.code());
        assertTrue(result.message().contains("funding carry rejected"));
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void liveRunnerManagesExistingPositionsThenOpensTopCandidatesUpToRemainingSlots() {
        properties.setContractCodes(List.of("BTC-USDT", "ETH-USDT", "SOL-USDT"));
        properties.setMaxActivePositions(3);
        properties.setCandidateScanConcurrency(1);
        when(recorder.countOpenLike()).thenReturn(1L);
        givenContractInfo("BTC-USDT", "0.001");
        givenContractInfo("ETH-USDT", "0.01");
        givenContractInfo("SOL-USDT", "0.1");
        givenKlines("BTC-USDT");
        givenKlines("ETH-USDT");
        givenKlines("SOL-USDT");
        givenFunding("BTC-USDT", "0.0001");
        givenFunding("ETH-USDT", "-0.0001");
        givenFunding("SOL-USDT", "-0.0001");
        givenDepth("ETH-USDT", "1999", "2001");
        givenDepth("SOL-USDT", "99.95", "100.05");
        TrendSignal btcFlat = TrendSignal.of(TrendSide.FLAT, freshBarTime(), new BigDecimal("60000"),
                new BigDecimal("59900"), new BigDecimal("59800"), new BigDecimal("60500"),
                new BigDecimal("59000"), new BigDecimal("120"), new BigDecimal("0.0020"),
                new BigDecimal("30"), "btc active flat");
        TrendSignal ethLong = TrendSignal.of(TrendSide.LONG, freshBarTime(), new BigDecimal("2000"),
                new BigDecimal("1990"), new BigDecimal("1980"), new BigDecimal("2010"),
                new BigDecimal("1960"), new BigDecimal("20"), new BigDecimal("0.0100"),
                new BigDecimal("30"), "eth breakout");
        TrendSignal solLong = TrendSignal.of(TrendSide.LONG, freshBarTime(), new BigDecimal("100"),
                new BigDecimal("99"), new BigDecimal("98"), new BigDecimal("101"),
                new BigDecimal("96"), new BigDecimal("3"), new BigDecimal("0.0300"),
                new BigDecimal("40"), "sol breakout");
        when(calculator.evaluate(anyList(), anyList(), eq(properties), anyLong())).thenReturn(btcFlat, ethLong, solLong);
        HtxTrendPositionEntity active = activeLongPosition("BTC-USDT", "0.001", 1L,
                "60000", "0.024", "59800", "59900", "60100", "60000");
        when(recorder.findActive("BTC-USDT")).thenReturn(Optional.of(active));
        when(swap.positionInfo("BTC-USDT")).thenReturn(ok(List.of(openLongPosition("BTC-USDT", "1", "60050"))));
        givenNoOpenOrders("ETH-USDT");
        givenNoExternalPositions("ETH-USDT");
        givenContractMargin("ETH-USDT");
        givenNoOpenOrders("SOL-USDT");
        givenNoExternalPositions("SOL-USDT");
        givenContractMargin("SOL-USDT");
        when(swap.switchLeverage(eq("ETH-USDT"), eq("long"), eq(properties.getLeverage())))
                .thenReturn(ok(leverage("ETH-USDT", "long")));
        when(swap.switchLeverage(eq("SOL-USDT"), eq("long"), eq(properties.getLeverage())))
                .thenReturn(ok(leverage("SOL-USDT", "long")));
        when(recorder.createIntent(any())).thenReturn(77L, 78L);
        when(swap.placeOrder(any(HtxSwapOrderRequest.class))).thenReturn(ok(accepted(88L, 99L)), ok(accepted(89L, 100L)));

        HtxTrendFollowingService.RunResult result = service.runOnce();
        assertEquals(HtxTrendFollowingService.RunCode.ENTRY_SENT, result.code());
        assertTrue(result.message().contains("candidates=2"));
        InOrder order = inOrder(recorder, swap);
        order.verify(recorder).updateRiskLevels(eq(active.getId()), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any());
        order.verify(swap).placeOrder(any(HtxSwapOrderRequest.class));
        ArgumentCaptor<HtxTrendOrderEntity> captor = ArgumentCaptor.forClass(HtxTrendOrderEntity.class);
        verify(recorder, times(2)).createOrderIntent(captor.capture());
        assertEquals("SOL-USDT", captor.getAllValues().get(0).getContractCode());
        assertEquals("ETH-USDT", captor.getAllValues().get(1).getContractCode());
    }

    @Test
    void outsideEntryWindowCachesCandidateThenOpensWithoutRepeatingFullScan() {
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.LONG);
        givenFunding("-0.0001");
        givenDepth();
        givenNoOpenOrders();
        givenNoExternalPositions();
        givenContractMargin();
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.empty());
        when(recorder.createIntent(any())).thenReturn(77L);
        when(swap.switchLeverage(eq("XAUT-USDT"), eq("long"), eq(properties.getLeverage())))
                .thenReturn(ok(leverage("XAUT-USDT", "long")));
        when(swap.placeOrder(any(HtxSwapOrderRequest.class))).thenReturn(ok(accepted(88L, 99L)));

        HtxTrendFollowingService.RunResult deferred = service.runOnce(false);
        assertEquals(HtxTrendFollowingService.RunCode.SKIPPED, deferred.code());
        assertTrue(deferred.message().contains("entry window closed; candidates cached"));
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));

        HtxTrendFollowingService.RunResult opened = service.runOnce(true);
        assertEquals(HtxTrendFollowingService.RunCode.ENTRY_SENT, opened.code());
        assertTrue(opened.message().contains("cached entry candidate"));
        verify(swap).placeOrder(any(HtxSwapOrderRequest.class));
        verify(swap, times(1)).historyKline(eq("XAUT-USDT"), eq(properties.getPeriod()), eq(properties.getKlineSize()));
        verify(swap, times(1)).historyKline(eq("XAUT-USDT"), eq(properties.getTrendPeriod()), eq(properties.getTrendKlineSize()));
    }

    @Test
    void duplicateConfiguredContractsAreEvaluatedOncePerCandidateScan() {
        properties.setContractCodes(List.of("XAUT-USDT", "xaut-usdt", " XAUT-USDT "));
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.FLAT);
        givenFunding("-0.0001");
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.empty());

        HtxTrendFollowingService.RunResult result = service.runOnce(false);

        assertEquals(HtxTrendFollowingService.RunCode.NO_SIGNAL, result.code());
        verify(swap, times(1)).historyKline(eq("XAUT-USDT"), eq(properties.getPeriod()), eq(properties.getKlineSize()));
        verify(swap, times(1)).historyKline(eq("XAUT-USDT"), eq(properties.getTrendPeriod()), eq(properties.getTrendKlineSize()));
    }



    @Test
    void marketAnchorBoostsMajorMultiTimeframeTrendIntoDirectionConfidencePass() {
        properties.setDirectionConfidenceGateEnabled(true);
        properties.setConfidenceSizingEnabled(false);
        properties.setTargetQuoteNotional(new BigDecimal("220"));
        properties.setMaxQuoteNotional(new BigDecimal("350"));
        properties.setRiskPerTradeUsdt(new BigDecimal("10"));
        givenContractInfo();
        givenRisingKlines("XAUT-USDT");
        givenBullishMarketAnchor();
        givenSignal(TrendSide.LONG, new BigDecimal("12"), new BigDecimal("0.0028"), freshBarTime(),
                new BigDecimal("24"), "test long momentum signal");
        givenFunding("-0.0001");
        givenDepth();
        givenNoOpenOrders();
        givenNoExternalPositions();
        givenContractMargin();
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.empty());
        when(recorder.createIntent(any())).thenReturn(77L);
        when(swap.switchLeverage(eq("XAUT-USDT"), eq("long"), eq(properties.getLeverage())))
                .thenReturn(ok(leverage("long")));
        when(swap.placeOrder(any(HtxSwapOrderRequest.class))).thenReturn(ok(accepted(88L, 99L)));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.ENTRY_SENT, result.code());
        ArgumentCaptor<HtxTrendPositionEntity> positionCaptor = ArgumentCaptor.forClass(HtxTrendPositionEntity.class);
        verify(recorder).createIntent(positionCaptor.capture());
        HtxTrendPositionEntity position = positionCaptor.getValue();
        assertTrue(position.getEntrySignal().contains("market anchor aligned BROAD_UP"));
        assertTrue(position.getEntryGateReason().contains("marketAnchor+"));
        verify(swap).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void marketAnchorDoesNotPromoteOrdinaryFlatSignalByItself() {
        properties.setMarketAnchorEnabled(true);
        properties.setEnsembleEntryEnabled(false);
        givenContractInfo();
        givenKlines();
        givenBullishMarketAnchor();
        givenSignal(TrendSide.FLAT, new BigDecimal("12"), new BigDecimal("0.0028"), freshBarTime(),
                new BigDecimal("24"), "no breakout or Fibonacci continuation aligned with higher-timeframe trend=LONG");

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.NO_SIGNAL, result.code());
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void ensembleEntryPromotesStrongFlatSignalIntoLiveEntryCandidate() {
        properties.setEnsembleEntryEnabled(true);
        properties.setDirectionConfidenceGateEnabled(true);
        properties.setConfidenceSizingEnabled(true);
        properties.setTargetQuoteNotional(new BigDecimal("220"));
        properties.setMaxQuoteNotional(new BigDecimal("350"));
        properties.setRiskPerTradeUsdt(new BigDecimal("10"));
        givenContractInfo("XAUT-USDT", "0.001", "0.01");
        givenEnsembleRisingKlines("XAUT-USDT");
        givenSignal(TrendSide.FLAT, new BigDecimal("35"), new BigDecimal("0.0070"), freshBarTime(),
                new BigDecimal("30"), "no breakout or Fibonacci continuation aligned with higher-timeframe trend=LONG");
        givenFunding("-0.0001");
        givenDepth("XAUT-USDT", "4989", "4991", "10000");
        givenNoOpenOrders();
        givenNoExternalPositions();
        givenContractMargin();
        when(swap.switchLeverage(eq("XAUT-USDT"), eq("long"), eq(properties.getLeverage())))
                .thenReturn(ok(leverage("long")));
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.empty());
        when(recorder.createIntent(any())).thenReturn(77L);
        when(swap.placeOrder(any(HtxSwapOrderRequest.class))).thenReturn(ok(accepted(88L, 99L)));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.ENTRY_SENT, result.code());
        assertTrue(result.message().contains("entry order sent"));
        ArgumentCaptor<HtxTrendPositionEntity> positionCaptor = ArgumentCaptor.forClass(HtxTrendPositionEntity.class);
        verify(recorder).createIntent(positionCaptor.capture());
        HtxTrendPositionEntity position = positionCaptor.getValue();
        assertEquals("LONG", position.getSide());
        assertTrue(position.getEntrySignal().contains("ensemble entry LONG"));
        assertTrue(position.getEntryGateReason().contains("directionConfidence=PASS"));
        assertTrue(position.getEntryGateReason().contains("expectedMoveGate=PASS"));
        verify(swap).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void liveEntrySkipsStaleSignalBar() {
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.LONG, new BigDecimal("12"), new BigDecimal("0.0028"), staleBarTime());
        givenFunding("-0.0001");
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.empty());

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.SKIPPED, result.code());
        assertTrue(result.message().contains("entry signal too stale"));
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void liveEntrySkipsWhenExpectedMoveCannotCoverRoundTripCosts() {
        properties.setMaxEntrySignalChaseAtrMultiplier(BigDecimal.ZERO);
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
    void liveEntrySkipsWhenEntryPriceChasesSignalTooFar() {
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.LONG, new BigDecimal("10.0"));
        givenFunding("-0.0001");
        givenDepth("XAUT-USDT", "4302", "4303");
        givenNoOpenOrders();
        givenNoExternalPositions();
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.empty());

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.SKIPPED, result.code());
        assertTrue(result.message().contains("entry price chased signal too far"));
        verify(swap, never()).switchLeverage(any(), any(), any(Integer.class));
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void liveEntrySkipsWeakFibonacciWhenDirectionConfidenceIsTooLow() {
        properties.setDirectionConfidenceGateEnabled(true);
        properties.setMinDirectionConfidenceScore(new BigDecimal("200"));
        properties.setMinMixedDirectionConfidenceScore(new BigDecimal("200"));
        properties.setFibonacciMinDirectionConfidenceScore(new BigDecimal("200"));
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.LONG, new BigDecimal("12"), new BigDecimal("0.0028"),
                freshBarTime(), new BigDecimal("24"),
                "Fibonacci 38.2%-61.8% pullback continuation with higher-timeframe EMA uptrend");
        givenFunding("-0.0001");
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.empty());

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.SKIPPED, result.code());
        assertTrue(result.message().contains("direction confidence too low"));
        verify(swap, never()).switchLeverage(any(), any(), anyInt());
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void combinationEntryBypassesLegacyDirectionConfidenceInMixedMarket() {
        properties.setCombinationEntryEnabled(true);
        properties.setDirectionConfidenceGateEnabled(true);
        properties.setMinDirectionConfidenceScore(new BigDecimal("200"));
        properties.setMinMixedDirectionConfidenceScore(new BigDecimal("200"));
        HtxTrendCombinationService combinationService = mock(HtxTrendCombinationService.class);
        HtxTrendStrategyPolicyEntity policy = new HtxTrendStrategyPolicyEntity();
        policy.setPolicyCode("FIB_BOX_DONCHIAN");
        policy.setPolicyVersion("combo-v2");
        policy.setBaseNotionalUsdt(new BigDecimal("190"));
        policy.setMaxNotionalUsdt(new BigDecimal("285"));
        HtxTrendCombinationCalculator.CombinationCandidate candidate =
                new HtxTrendCombinationCalculator.CombinationCandidate("FIB_PULLBACK_CONTINUATION",
                        "BOX_BREAKOUT,DONCHIAN_BREAKOUT", HtxTrendEnsembleSignalCalculator.Direction.LONG,
                        new BigDecimal("90"), "BROAD_UP", new BigDecimal("4310"), new BigDecimal("4260"),
                        new BigDecimal("4310"), new BigDecimal("4260"), new BigDecimal("4270"),
                        new BigDecimal("4290"), new BigDecimal("4260"), new BigDecimal("4350"),
                        new BigDecimal("4350"), new BigDecimal("4350"), new BigDecimal("4380"),
                        new BigDecimal("4400"), new BigDecimal("2.0"), new BigDecimal("70"),
                        "test combination", HtxTrendCombinationCalculator.FibonacciEvidence.notApplicable(32, 8));
        HtxTrendCombinationService.Selection selection = new HtxTrendCombinationService.Selection(candidate, policy,
                1L, BigDecimal.ONE, new BigDecimal("190"), "run-quality-observation", null);
        when(combinationService.select(eq("XAUT-USDT"), eq(properties.getPeriod()), anyList(), any()))
                .thenReturn(selection);
        service.setCombinationService(combinationService);
        givenContractInfo();
        givenEnsembleRisingKlines("XAUT-USDT");
        givenFunding("-0.0001");
        givenDepth();
        givenNoOpenOrders();
        givenNoExternalPositions();
        givenContractMargin();
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.empty());
        when(recorder.createIntent(any())).thenReturn(77L);
        when(swap.switchLeverage(eq("XAUT-USDT"), eq("long"), eq(properties.getLeverage())))
                .thenReturn(ok(leverage("long")));
        when(swap.placeOrder(any(HtxSwapOrderRequest.class))).thenReturn(ok(accepted(88L, 99L)));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.ENTRY_SENT, result.code(), result.message());
        verify(recorder).trendPerformanceForBucket(any(LocalDateTime.class), eq(properties.getQualityLookbackTrades()),
                eq("LONG"), eq("FIB_PULLBACK_CONTINUATION"), eq("BROAD_UP"), eq("primary"));
        verify(combinationService).recordDirectionalQualityEvidence(eq("XAUT-USDT"), eq(properties.getPeriod()),
                anyLong(), eq("combo-v2"), eq("BROAD_UP"), any());
        verify(swap).placeOrder(any(HtxSwapOrderRequest.class));
    }


    @Test
    void broadMarketFibonacciEntryPassesModerateDirectionConfidence() {
        properties.setDirectionConfidenceGateEnabled(true);
        properties.setConfidenceSizingEnabled(false);
        properties.setMinMixedDirectionConfidenceScore(new BigDecimal("82"));
        properties.setFibonacciMinDirectionConfidenceScore(new BigDecimal("80"));
        givenContractInfo();
        givenRisingKlines("XAUT-USDT");
        givenSignal(TrendSide.LONG, new BigDecimal("12"), new BigDecimal("0.0028"),
                freshBarTime(), new BigDecimal("24"),
                "Fibonacci 38.2%-61.8% pullback continuation with higher-timeframe EMA uptrend");
        givenFunding("-0.0001");
        givenDepth();
        givenNoOpenOrders();
        givenNoExternalPositions();
        givenContractMargin();
        givenBroadBullishBreadth();
        givenWeakPerformance();
        when(swap.switchLeverage(eq("XAUT-USDT"), eq("long"), eq(properties.getLeverage())))
                .thenReturn(ok(leverage("long")));
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.empty());
        when(recorder.createIntent(any())).thenReturn(77L);
        when(swap.placeOrder(any(HtxSwapOrderRequest.class))).thenReturn(ok(accepted(88L, 99L)));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.ENTRY_SENT, result.code());
        ArgumentCaptor<HtxTrendPositionEntity> positionCaptor = ArgumentCaptor.forClass(HtxTrendPositionEntity.class);
        verify(recorder).createIntent(positionCaptor.capture());
        assertTrue(positionCaptor.getValue().getEntryGateReason().contains("directionConfidence=PASS"));
        assertTrue(positionCaptor.getValue().getEntryGateReason().contains("required=80"));
    }

    @Test
    void liveEntrySkipsWhenRecentTurnoverIsTooLow() {
        properties.setMinEntryAvgTurnoverUsdt(new BigDecimal("50000"));
        properties.setLiquidityLookbackBars(2);
        givenContractInfo();
        givenKlinesWithTurnover("XAUT-USDT", "12000", "18000");
        givenSignal(TrendSide.LONG);
        givenFunding("-0.0001");
        givenDepth();
        givenNoOpenOrders();
        givenNoExternalPositions();
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.empty());

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.SKIPPED, result.code());
        assertTrue(result.message().contains("liquidity turnover too low"));
        verify(swap, never()).switchLeverage(any(), any(), any(Integer.class));
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void liveEntrySkipsWhenEntrySideDepthCannotAbsorbOrder() {
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.SHORT);
        givenFunding("0.0001");
        givenDepth("XAUT-USDT", "4299", "4301", "10");
        givenNoOpenOrders();
        givenNoExternalPositions();
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.empty());

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.SKIPPED, result.code());
        assertTrue(result.message().contains("liquidity depth too low"));
        verify(swap, never()).switchLeverage(any(), any(), any(Integer.class));
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void liveEntrySkipsWhenAtrPctLooksLikeThinBookSpike() {
        properties.setMaxEntryAtrPct(new BigDecimal("0.0100"));
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.LONG, new BigDecimal("80"), new BigDecimal("0.0180"));
        givenFunding("-0.0001");
        givenDepth();
        givenNoOpenOrders();
        givenNoExternalPositions();
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.empty());

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.SKIPPED, result.code());
        assertTrue(result.message().contains("ATR too high for liquidity-adjusted entry"));
        verify(swap, never()).switchLeverage(any(), any(), any(Integer.class));
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void entryDisabledSkipsNewPositionsButStillRecordsSignal() {
        properties.setEntryEnabled(false);
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.LONG);
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.empty());

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.SKIPPED, result.code());
        assertTrue(result.message().contains("new trend entries disabled"));
        verify(recorder).recordSignal(any());
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void maxActivePositionsZeroSkipsNewEntriesWithoutDisablingPositionManagement() {
        properties.setMaxActivePositions(0);
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.LONG);
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.empty());

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.SKIPPED, result.code());
        assertTrue(result.message().contains("max active trend positions reached"));
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void activeLongWaitsForConfirmedFlatSignalAfterFeeProtection() {
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.FLAT, new BigDecimal("7.0"));
        givenFunding("-0.0001");
        HtxTrendPositionEntity active = activeLongPosition();
        active.setCreatedAt(LocalDateTime.now().minusMinutes(30));
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.of(active));
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(List.of(openLongPosition("4324.34"))));
        when(recorder.countRecentSignalsBySide(eq("XAUT-USDT"), eq("FLAT"), any(LocalDateTime.class), eq(3)))
                .thenReturn(1L);

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.ACTIVE, result.code());
        assertTrue(result.message().contains("trailingStop=4323.84"));
        assertTrue(result.message().contains("structureStopCandidate=4290.00"));
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(recorder).updateRiskLevels(eq(77L), eq(new BigDecimal("4295.00")),
                eq(new BigDecimal("4323.84")), eq(null), eq(new BigDecimal("4332.21")),
                eq(new BigDecimal("4315.84")), messageCaptor.capture(), eq("EXHAUSTION_ZONE"),
                any(), any(), any(), any(), any(), any());
        assertTrue(messageCaptor.getValue().contains("stopSource=LEGACY_FEE_PROTECTED_CLAMP"));
        assertTrue(messageCaptor.getValue().contains("structureStopCandidate=4290.00"));
        assertTrue(messageCaptor.getValue().contains("exitSnapshot state="));
        assertTrue(messageCaptor.getValue().contains("candidates=["));
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void activeLongExitsOnlyAfterConfirmedFlatSignalAfterFeeProtection() {
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.FLAT, new BigDecimal("7.0"));
        givenFunding("-0.0001");
        HtxTrendPositionEntity active = activeLongPosition();
        active.setCreatedAt(LocalDateTime.now().minusMinutes(30));
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.of(active));
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(List.of(openLongPosition("4324.34"))));
        when(recorder.countRecentSignalsBySide(eq("XAUT-USDT"), eq("FLAT"), any(LocalDateTime.class), eq(3)))
                .thenReturn(3L);
        when(swap.placeOrder(any(HtxSwapOrderRequest.class))).thenReturn(ok(accepted(888L, 999L)));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.CLOSE_SENT, result.code());
        assertTrue(result.message().contains("exit engine: FEE_PROTECTION"));
        assertTrue(result.message().contains("confirmed flat trend after fee protection"));
        ArgumentCaptor<HtxTrendOrderEntity> captor = ArgumentCaptor.forClass(HtxTrendOrderEntity.class);
        verify(recorder).createOrderIntent(captor.capture());
        assertEquals(HtxTrendRecorder.LEG_CLOSE, captor.getValue().getLeg());
        assertEquals(true, captor.getValue().getReduceOnly());
        assertTrue(captor.getValue().getReason().contains("exit engine: FEE_PROTECTION"));
        assertTrue(captor.getValue().getReason().contains("flatBars=3/3"));
        assertTrue(captor.getValue().getReason().contains("exitSnapshot state="));
        assertTrue(captor.getValue().getReason().contains("giveback="));
    }

    @Test
    void activeLongRecordsSimulatedPartialTakeProfitWithoutSendingCloseOrder() {
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.LONG, new BigDecimal("12"));
        givenFunding("-0.0001");
        HtxTrendPositionEntity active = activeLongPosition();
        active.setCreatedAt(LocalDateTime.now().minusMinutes(45));
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.of(active));
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(List.of(openLongPosition("4342.00"))));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.ACTIVE, result.code());
        ArgumentCaptor<String> candidatesCaptor = ArgumentCaptor.forClass(String.class);
        verify(recorder).updateRiskLevels(eq(77L), eq(new BigDecimal("4295.00")),
                any(), eq(null), eq(new BigDecimal("4342.00")), eq(new BigDecimal("4315.84")),
                any(), any(), any(), any(), any(), any(), any(), candidatesCaptor.capture());
        assertTrue(candidatesCaptor.getValue().contains("PARTIAL_TAKE_PROFIT:simulate_trigger"));
        assertTrue(candidatesCaptor.getValue().contains("suggestClose=33%"));
        assertTrue(candidatesCaptor.getValue().contains("PROFIT_PROTECTION:"));
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void fibExitEngineObservationPersistsDecisionWithoutSendingCloseOrder() {
        properties.setExitEngineEnabled(true);
        properties.setExitEngineLiveEnabled(false);
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.LONG, new BigDecimal("12"));
        givenFunding("-0.0001");
        HtxTrendPositionEntity active = activeLongPosition();
        active.setPrimaryModel(HtxTrendExitDecisionEngine.FIB_PULLBACK_CONTINUATION);
        active.setInvalidationPrice(new BigDecimal("4280.00"));
        active.setTargetZoneLow(new BigDecimal("4400.00"));
        active.setRunId("run-exit-observe");
        active.setAccountLabel("primary");
        active.setCreatedAt(LocalDateTime.now().minusMinutes(45));
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.of(active));
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(List.of(openLongPosition("4342.00"))));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.ACTIVE, result.code());
        verify(recorder).updateExitEngineState(eq(77L), any(), any(), any(), any(), any(),
                eq(HtxTrendExitDecisionEngine.VERSION));
        ArgumentCaptor<HtxTrendExitDecisionEntity> decisionCaptor = ArgumentCaptor.forClass(HtxTrendExitDecisionEntity.class);
        verify(recorder).recordExitDecision(decisionCaptor.capture());
        assertEquals("OBSERVE", decisionCaptor.getValue().getExecutedAction());
        assertEquals(HtxTrendExitDecisionEngine.FIB_PULLBACK_CONTINUATION,
                decisionCaptor.getValue().getPrimaryModel());
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void observedFibExitDoesNotFallThroughToLegacyFlatLossClose() {
        properties.setExitEngineEnabled(true);
        properties.setExitEngineLiveEnabled(false);
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.FLAT, new BigDecimal("12"));
        givenFunding("-0.0001");
        HtxTrendPositionEntity active = activeLongPosition();
        active.setPrimaryModel(HtxTrendExitDecisionEngine.FIB_PULLBACK_CONTINUATION);
        active.setInvalidationPrice(new BigDecimal("4200.00"));
        active.setAccountLabel("primary");
        active.setCreatedAt(LocalDateTime.now().minusMinutes(45));
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.of(active));
        when(recorder.countConsecutiveSignalsBySide(eq("XAUT-USDT"), eq(properties.getPeriod()), eq("FLAT"),
                any(LocalDateTime.class), eq(3))).thenReturn(3L);
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(List.of(openLongPosition("4290.00"))));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.ACTIVE, result.code());
        assertTrue(result.message().contains("exit-engine observing"));
        ArgumentCaptor<HtxTrendExitDecisionEntity> decisionCaptor = ArgumentCaptor.forClass(HtxTrendExitDecisionEntity.class);
        verify(recorder).recordExitDecision(decisionCaptor.capture());
        assertEquals("FULL_CLOSE", decisionCaptor.getValue().getSuggestedAction());
        assertEquals("OBSERVE", decisionCaptor.getValue().getExecutedAction());
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void liveFibExitEngineRecordsOnlySubmittedModelInvalidationCloseAsExecuted() {
        properties.setExitEngineEnabled(true);
        properties.setExitEngineLiveEnabled(true);
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.LONG, new BigDecimal("12"));
        givenFunding("-0.0001");
        HtxTrendPositionEntity active = activeLongPosition();
        active.setPrimaryModel(HtxTrendExitDecisionEngine.FIB_PULLBACK_CONTINUATION);
        active.setInvalidationPrice(new BigDecimal("4330.00"));
        active.setAccountLabel("primary");
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.of(active));
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(List.of(openLongPosition("4320.00"))));
        when(swap.placeOrder(any(HtxSwapOrderRequest.class))).thenReturn(ok(accepted(888L, 999L)));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.CLOSE_SENT, result.code());
        assertTrue(result.message().contains("MODEL_INVALIDATION"));
        ArgumentCaptor<HtxTrendExitDecisionEntity> decisionCaptor = ArgumentCaptor.forClass(HtxTrendExitDecisionEntity.class);
        verify(recorder).recordExitDecision(decisionCaptor.capture());
        assertEquals("FULL_CLOSE", decisionCaptor.getValue().getSuggestedAction());
        assertEquals("FULL_CLOSE", decisionCaptor.getValue().getExecutedAction());
        assertEquals(null, decisionCaptor.getValue().getExecutionBlockReason());
    }

    @Test
    void liveFibProfitLockCrossSubmitsModelRoutedCloseWithoutLegacyFallthrough() {
        properties.setExitEngineEnabled(true);
        properties.setExitEngineLiveEnabled(true);
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.FLAT, new BigDecimal("12"));
        givenFunding("-0.0001");
        HtxTrendPositionEntity active = activeLongPosition();
        active.setPrimaryModel(HtxTrendExitDecisionEngine.FIB_PULLBACK_CONTINUATION);
        active.setInvalidationPrice(new BigDecimal("4290.00"));
        active.setTrailingArmedAt(LocalDateTime.of(2026, 9, 25, 1, 20));
        active.setTrailingArmReason("NET_COST_AND_ATR_MILESTONE");
        active.setMaxFavorableR(new BigDecimal("0.40"));
        active.setProfitLockPrice(new BigDecimal("4324.00"));
        active.setAccountLabel("primary");
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.of(active));
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(List.of(openLongPosition("4320.00"))));
        when(swap.placeOrder(any(HtxSwapOrderRequest.class))).thenReturn(ok(accepted(888L, 999L)));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.CLOSE_SENT, result.code());
        assertTrue(result.message().contains("armed-protection-crossed"));
        ArgumentCaptor<HtxTrendExitDecisionEntity> decisionCaptor = ArgumentCaptor.forClass(HtxTrendExitDecisionEntity.class);
        verify(recorder).recordExitDecision(decisionCaptor.capture());
        assertEquals("FULL_CLOSE", decisionCaptor.getValue().getSuggestedAction());
        assertEquals("FULL_CLOSE", decisionCaptor.getValue().getExecutedAction());
        assertTrue(decisionCaptor.getValue().getExecutionBlockReason() == null);
    }

    @Test
    void activeLongRecordsTimeframeConfirmationHoldWithoutSendingCloseOrder() {
        givenContractInfo();
        givenKlines();
        givenExitConfirmationKlines("XAUT-USDT", true);
        givenSignal(TrendSide.LONG, new BigDecimal("12"));
        givenFunding("-0.0001");
        HtxTrendPositionEntity active = activeLongPosition();
        active.setCreatedAt(LocalDateTime.now().minusMinutes(45));
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.of(active));
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(List.of(openLongPosition("4342.00"))));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.ACTIVE, result.code());
        ArgumentCaptor<String> candidatesCaptor = ArgumentCaptor.forClass(String.class);
        verify(recorder).updateRiskLevels(eq(77L), eq(new BigDecimal("4295.00")),
                any(), eq(null), eq(new BigDecimal("4342.00")), eq(new BigDecimal("4315.84")),
                any(), any(), any(), any(), any(), any(), any(), candidatesCaptor.capture());
        assertTrue(candidatesCaptor.getValue()
                .contains("TIMEFRAME_CONFIRMATION:hold:30m=LONG,60m=LONG,4h=LONG"));
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void activeLongRecordsTimeframeConfirmationMajorWarningWithoutSendingCloseOrder() {
        givenContractInfo();
        givenKlines();
        givenExitConfirmationKlines("XAUT-USDT", false);
        givenSignal(TrendSide.LONG, new BigDecimal("12"));
        givenFunding("-0.0001");
        HtxTrendPositionEntity active = activeLongPosition();
        active.setCreatedAt(LocalDateTime.now().minusMinutes(45));
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.of(active));
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(List.of(openLongPosition("4326.00"))));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.ACTIVE, result.code());
        ArgumentCaptor<String> candidatesCaptor = ArgumentCaptor.forClass(String.class);
        verify(recorder).updateRiskLevels(eq(77L), eq(new BigDecimal("4295.00")),
                any(), eq(null), eq(new BigDecimal("4332.21")), eq(new BigDecimal("4315.84")),
                any(), any(), any(), any(), any(), any(), any(), candidatesCaptor.capture());
        assertTrue(candidatesCaptor.getValue()
                .contains("TIMEFRAME_CONFIRMATION:warn_major:30m=SHORT,60m=SHORT,4h=SHORT"));
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void activeLongRecordsPreOneRTrailingBreachWithoutSendingCloseOrder() {
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.FLAT, new BigDecimal("7.0"));
        givenFunding("-0.0001");
        HtxTrendPositionEntity active = activeLongPosition();
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.of(active));
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(List.of(openLongPosition("4319.50"))));
        when(swap.placeOrder(any(HtxSwapOrderRequest.class))).thenReturn(ok(accepted(888L, 999L)));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.ACTIVE, result.code());
        assertTrue(result.message().contains("trailingStop=4323.84"));
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void activeLongUsesContractPriceTickForFeeProtectedStop() {
        properties.setContractCodes(List.of("NEAR-USDT"));
        properties.setFlatExitConfirmationBars(1);
        givenContractInfo("NEAR-USDT", "1", "0.001");
        givenKlines("NEAR-USDT");
        TrendSignal signal = TrendSignal.of(TrendSide.FLAT, 2L, new BigDecimal("2.482"),
                new BigDecimal("2.470"), new BigDecimal("2.460"), new BigDecimal("2.482"),
                new BigDecimal("2.440"), new BigDecimal("0.019"), new BigDecimal("0.0077"),
                new BigDecimal("30"), "near flat");
        when(calculator.evaluate(anyList(), anyList(), eq(properties), anyLong())).thenReturn(signal);
        givenFunding("NEAR-USDT", "-0.0001");
        HtxTrendPositionEntity active = activeLongPosition("NEAR-USDT", "1", 31L,
                "2.472", "0.0306528", "2.440", "2.440", "2.482", "2.452");
        active.setCreatedAt(LocalDateTime.now().minusMinutes(30));
        when(recorder.findActive("NEAR-USDT")).thenReturn(Optional.of(active));
        when(swap.positionInfo("NEAR-USDT")).thenReturn(ok(List.of(openLongPosition("NEAR-USDT", "31", "2.482"))));
        when(recorder.countRecentSignalsBySide(eq("NEAR-USDT"), eq("FLAT"), any(LocalDateTime.class), eq(1)))
                .thenReturn(1L);
        when(swap.placeOrder(any(HtxSwapOrderRequest.class))).thenReturn(ok(accepted(888L, 999L)));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.CLOSE_SENT, result.code());
        verify(recorder).updateRiskLevels(eq(active.getId()), eq(new BigDecimal("2.440")),
                eq(new BigDecimal("2.481")), eq(null), eq(new BigDecimal("2.482")),
                eq(new BigDecimal("2.452")), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void activeShortExitsConfirmedFlatWhenEstimatedLossExceedsBudget() {
        properties.setContractCodes(List.of("FIL-USDT"));
        givenContractInfo("FIL-USDT", "0.1", "0.0001");
        givenKlines("FIL-USDT");
        TrendSignal signal = TrendSignal.of(TrendSide.FLAT, 2L, new BigDecimal("0.7873"),
                new BigDecimal("0.7860"), new BigDecimal("0.7850"), new BigDecimal("0.7920"),
                new BigDecimal("0.7810"), new BigDecimal("0.0030"), new BigDecimal("0.0038"),
                new BigDecimal("30"), "fil flat");
        when(calculator.evaluate(anyList(), anyList(), eq(properties), anyLong())).thenReturn(signal);
        givenFunding("FIL-USDT", "0.0001");
        HtxTrendPositionEntity active = activeShortPosition("FIL-USDT", "0.1", 788L,
                "0.7843", "0.024721136", "0.8000", "0.7979", "0.7919", "0.7819");
        active.setCreatedAt(LocalDateTime.now().minusMinutes(30));
        when(recorder.findActive("FIL-USDT")).thenReturn(Optional.of(active));
        when(swap.positionInfo("FIL-USDT")).thenReturn(ok(List.of(openShortPosition("FIL-USDT", "788", "0.7873"))));
        when(recorder.countRecentSignalsBySide(eq("FIL-USDT"), eq("FLAT"), any(LocalDateTime.class), eq(3)))
                .thenReturn(3L);
        when(swap.placeOrder(any(HtxSwapOrderRequest.class))).thenReturn(ok(accepted(888L, 999L)));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.CLOSE_SENT, result.code());
        assertTrue(result.message().contains("exit engine: TREND_EXHAUSTION"));
        assertTrue(result.message().contains("loss control"));
        ArgumentCaptor<HtxTrendOrderEntity> captor = ArgumentCaptor.forClass(HtxTrendOrderEntity.class);
        verify(recorder).createOrderIntent(captor.capture());
        assertEquals(HtxTrendRecorder.LEG_CLOSE, captor.getValue().getLeg());
        assertEquals(true, captor.getValue().getReduceOnly());
        assertTrue(captor.getValue().getReason().contains("exit engine: TREND_EXHAUSTION"));
        assertTrue(captor.getValue().getReason().contains("flatBars=3/3"));
    }

    @Test
    void activeLongExitsWhenCombinationModelInvalidates() {
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.LONG, new BigDecimal("12"));
        givenFunding("-0.0001");
        HtxTrendPositionEntity active = activeLongPosition("XAUT-USDT", "0.001", 44L,
                "4315.84", "0.075958784", "4295.00", "4290.00", "4316.10", "4304.00");
        active.setPrimaryModel("DONCHIAN_BREAKOUT");
        active.setInvalidationPrice(new BigDecimal("4310.00"));
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.of(active));
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(List.of(openLongPosition("4304.00"))));
        when(swap.placeOrder(any(HtxSwapOrderRequest.class))).thenReturn(ok(accepted(888L, 999L)));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.CLOSE_SENT, result.code());
        assertTrue(result.message().contains("exit engine: MODEL_INVALIDATION"));
        assertTrue(result.message().contains("combination invalidation DONCHIAN_BREAKOUT"));
        ArgumentCaptor<HtxTrendOrderEntity> captor = ArgumentCaptor.forClass(HtxTrendOrderEntity.class);
        verify(recorder).createOrderIntent(captor.capture());
        assertTrue(captor.getValue().getReason().contains("exit engine: MODEL_INVALIDATION"));
        assertTrue(captor.getValue().getReason().contains("exitSnapshot state=LOSS_ZONE"));
    }

    @Test
    void activeLongExitsWhenHoldFundingCostIsTooHigh() {
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.LONG, new BigDecimal("12"));
        givenFunding("0.0020");
        HtxTrendPositionEntity active = activeLongPosition("XAUT-USDT", "0.001", 44L,
                "4315.84", "0.075958784", "4295.00", "4290.00", "4316.10", "4304.00");
        active.setCreatedAt(LocalDateTime.now().minusMinutes(30));
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.of(active));
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(List.of(openLongPosition("4315.00"))));
        when(swap.placeOrder(any(HtxSwapOrderRequest.class))).thenReturn(ok(accepted(888L, 999L)));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.CLOSE_SENT, result.code());
        assertTrue(result.message().contains("exit engine: FUNDING_COST"));
        assertTrue(result.message().contains("funding cost blocked"));
        verify(recorder).createOrderIntent(any(HtxTrendOrderEntity.class));
    }

    @Test
    void activePositionMissingOnExchangeIsHardRiskManualReview() {
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.LONG);
        HtxTrendPositionEntity active = activeLongPosition();
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.of(active));
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(Collections.emptyList()));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.MANUAL_REVIEW, result.code());
        assertTrue(result.message().contains("exit engine: HARD_RISK"));
        assertTrue(result.message().contains("managed position missing on exchange"));
        verify(recorder).markStatus(eq(77L), eq(HtxTrendRecorder.UNKNOWN),
                eq("exit engine: HARD_RISK: managed position missing on exchange; manual review"));
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void activeShortWaitsWhenConfirmedFlatLossIsWithinBudget() {
        properties.setContractCodes(List.of("FIL-USDT"));
        givenContractInfo("FIL-USDT", "0.1", "0.0001");
        givenKlines("FIL-USDT");
        TrendSignal signal = TrendSignal.of(TrendSide.FLAT, 2L, new BigDecimal("0.7860"),
                new BigDecimal("0.7850"), new BigDecimal("0.7840"), new BigDecimal("0.7920"),
                new BigDecimal("0.7810"), new BigDecimal("0.0030"), new BigDecimal("0.0038"),
                new BigDecimal("30"), "fil flat");
        when(calculator.evaluate(anyList(), anyList(), eq(properties), anyLong())).thenReturn(signal);
        givenFunding("FIL-USDT", "0.0001");
        HtxTrendPositionEntity active = activeShortPosition("FIL-USDT", "0.1", 788L,
                "0.7843", "0.024721136", "0.8000", "0.7979", "0.7919", "0.7819");
        active.setCreatedAt(LocalDateTime.now().minusMinutes(30));
        when(recorder.findActive("FIL-USDT")).thenReturn(Optional.of(active));
        when(swap.positionInfo("FIL-USDT")).thenReturn(ok(List.of(openShortPosition("FIL-USDT", "788", "0.7860"))));
        when(recorder.countRecentSignalsBySide(eq("FIL-USDT"), eq("FLAT"), any(LocalDateTime.class), eq(3)))
                .thenReturn(3L);

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.ACTIVE, result.code());
        assertTrue(result.message().contains("holding"));
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void activeLongHoldsThroughLocalPullbackWithoutEarlyExitRules() {
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.LONG, new BigDecimal("12"));
        givenFunding("-0.0001");
        HtxTrendPositionEntity active = activeLongPosition("XAUT-USDT", "0.001", 44L,
                "4315.84", "0.075958784", "4295.00", "4290.00", "4316.10", "4304.00");
        active.setCreatedAt(LocalDateTime.now().minusMinutes(40));
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.of(active));
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(List.of(openLongPosition("4304.00"))));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.ACTIVE, result.code());
        assertTrue(result.message().contains("holding"));
        assertTrue(result.message().contains("stopSource=ATR_TRAILING"));
        assertTrue(result.message().contains("structureStopCandidate=4290.00"));
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void activeLongUsesStructureStopWhenExplicitlyEnabled() {
        properties.setStructureStopEnabled(true);
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.LONG, new BigDecimal("12"));
        givenFunding("-0.0001");
        HtxTrendPositionEntity active = activeLongPosition("XAUT-USDT", "0.001", 44L,
                "4315.84", "0.075958784", "4285.00", "4290.00", "4316.10", "4304.00");
        active.setCreatedAt(LocalDateTime.now().minusMinutes(40));
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.of(active));
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(List.of(openLongPosition("4291.00"))));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.ACTIVE, result.code());
        assertTrue(result.message().contains("stopSource=STRUCTURE_STOP"));
        assertTrue(result.message().contains("trailingStop=4290.00"));
        assertTrue(result.message().contains("atrStop=4292.10"));
        assertTrue(result.message().contains("structureStopCandidate=4290.00"));
        verify(recorder).updateRiskLevels(eq(77L), eq(new BigDecimal("4285.00")),
                eq(new BigDecimal("4290.00")), eq(null), eq(new BigDecimal("4316.10")),
                eq(new BigDecimal("4291.00")), any(), any(), any(), any(), any(), any(), any(), any());
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void primaryEntryFillTriggersMirrorEntryAfterPrimaryFill() {
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.FLAT);
        HtxTrendPositionEntity position = entrySentLongPosition();
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.of(position));
        when(swap.queryOrder("XAUT-USDT", 88L)).thenReturn(ok(List.of(filledOrder(88L, 99L, "44", "4316.00", "0.0759"))));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.ACTIVE, result.code());
        assertTrue(result.message().contains("entry filled"));
        InOrder order = inOrder(recorder, mirrorExecutor);
        order.verify(recorder).markEntryFilled(eq(77L), eq(new BigDecimal("44")),
                eq(new BigDecimal("4316.00")), eq(new BigDecimal("0.0759")),
                eq(new BigDecimal("4316.00")), eq(new BigDecimal("4316.00")), any());
        ArgumentCaptor<HtxTrendPositionEntity> captor = ArgumentCaptor.forClass(HtxTrendPositionEntity.class);
        order.verify(mirrorExecutor).syncEntry(captor.capture());
        assertEquals(new BigDecimal("4316.00"), captor.getValue().getEntryAvgPrice());
        assertEquals(new BigDecimal("44"), captor.getValue().getEntryFilledContracts());
    }

    @Test
    void primaryCloseDoneWaitsWhenMirrorClosePending() {
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.FLAT);
        HtxTrendPositionEntity position = closeSentLongPosition();
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.of(position));
        when(swap.queryOrder("XAUT-USDT", 188L)).thenReturn(ok(List.of(filledOrder(188L, 199L, "44", "4320.00", "0.0760"))));
        when(mirrorExecutor.syncClose(any())).thenReturn(HtxTrendMirrorExecutor.SyncResult.pending("mirror close sent"));
        when(mirrorExecutor.blockPrimaryCloseUntilSynced()).thenReturn(true);

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.WAITING, result.code());
        assertTrue(result.message().contains("mirror pending"));
        verify(mirrorExecutor).syncClose(position);
        verify(recorder, never()).markCloseFilled(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void primaryCloseMarksDoneWhenMirrorAlreadyFlat() {
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.FLAT);
        HtxTrendPositionEntity position = closeSentLongPosition();
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.of(position));
        when(swap.queryOrder("XAUT-USDT", 188L)).thenReturn(ok(List.of(filledOrder(188L, 199L, "44", "4320.00", "0.0760"))));
        when(mirrorExecutor.syncClose(any())).thenReturn(HtxTrendMirrorExecutor.SyncResult.ok("mirror already flat"));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.DONE, result.code());
        verify(mirrorExecutor).syncClose(position);
        verify(recorder).markCloseFilled(eq(77L), eq(new BigDecimal("44")), eq(new BigDecimal("4320.00")),
                eq(new BigDecimal("0.0760")), eq(new BigDecimal("0.25")), any());
    }

    @Test
    void unknownPositionReconcilesPreviouslyAcceptedCloseOrder() {
        HtxTrendPositionEntity position = activeLongPosition();
        position.setStatus(HtxTrendRecorder.UNKNOWN);
        HtxTrendOrderEntity closeOrder = new HtxTrendOrderEntity();
        closeOrder.setPositionId(position.getId());
        closeOrder.setLeg(HtxTrendRecorder.LEG_CLOSE);
        closeOrder.setStatus(HtxTrendRecorder.ORDER_ACCEPTED);
        closeOrder.setClientOrderId("199");
        closeOrder.setExchangeOrderId(188L);
        closeOrder.setReason("exit signal: exit engine: TRAILING_STOP");
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.of(position));
        when(recorder.findAcceptedCloseOrder(77L)).thenReturn(Optional.of(closeOrder));
        when(swap.queryOrder("XAUT-USDT", 188L))
                .thenReturn(ok(List.of(filledOrder(188L, 199L, "44", "4320.00", "0.0760"))));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.DONE, result.code());
        verify(recorder).markCloseAccepted(eq(77L), eq("exit signal: exit engine: TRAILING_STOP"),
                eq("199"), eq(188L), contains("recovered accepted close order"));
        verify(recorder).markCloseFilled(eq(77L), eq(new BigDecimal("44")), eq(new BigDecimal("4320.00")),
                eq(new BigDecimal("0.0760")), eq(new BigDecimal("0.25")), any());
        verify(swap, never()).positionInfo("XAUT-USDT");
    }

    @Test
    void activeLongSendsReduceOnlyFirstPartialCloseAtOneRWhenLiveEnabled() {
        properties.setPartialTakeProfitLiveEnabled(true);
        properties.setFeeProtectionEnabled(false);
        properties.setExitFlatAfterFeeProtection(false);
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.LONG, new BigDecimal("12"));
        givenFunding("-0.0001");
        HtxTrendPositionEntity active = activeLongPosition();
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.of(active));
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(List.of(openLongPosition("4340.00"))));
        when(swap.placeOrder(any(HtxSwapOrderRequest.class))).thenReturn(ok(accepted(888L, 999L)));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.PARTIAL_CLOSE_SENT, result.code());
        ArgumentCaptor<HtxTrendOrderEntity> orderCaptor = ArgumentCaptor.forClass(HtxTrendOrderEntity.class);
        verify(recorder).createOrderIntent(orderCaptor.capture());
        assertEquals(HtxTrendRecorder.LEG_PARTIAL_CLOSE, orderCaptor.getValue().getLeg());
        assertEquals(true, orderCaptor.getValue().getReduceOnly());
        assertEquals(14L, orderCaptor.getValue().getRequestVolume());
        verify(recorder).markPartialCloseAccepted(eq(77L), any(), eq(888L), any());
    }

    @Test
    void partialCloseFillReturnsPositionToActiveAndKeepsRemainingContracts() {
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.LONG, new BigDecimal("12"));
        givenFunding("-0.0001");
        HtxTrendPositionEntity active = activeLongPosition();
        active.setStatus(HtxTrendRecorder.PARTIAL_CLOSE_SENT);
        active.setRemainingContractVolume(44L);
        active.setPartialCloseClientOrderId("199");
        active.setPartialCloseOrderId(188L);
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.of(active));
        when(swap.queryOrder("XAUT-USDT", 188L))
                .thenReturn(ok(List.of(filledOrder(188L, 199L, "14", "4340.00", "0.0200"))));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.ACTIVE, result.code());
        verify(recorder).markPartialCloseFilled(eq(77L), eq(1), eq("199"), eq(new BigDecimal("14")),
                eq(new BigDecimal("4340.00")), eq(new BigDecimal("0.0200")), eq(new BigDecimal("0.25")), any());
        verify(mirrorExecutor).syncPartialClose(active, 14L);
    }

    @Test
    void partiallyCanceledPartialCloseStillRecordsTheExecutedContracts() {
        givenContractInfo();
        givenKlines();
        givenSignal(TrendSide.LONG, new BigDecimal("12"));
        givenFunding("-0.0001");
        HtxTrendPositionEntity active = activeLongPosition();
        active.setStatus(HtxTrendRecorder.PARTIAL_CLOSE_SENT);
        active.setRemainingContractVolume(44L);
        active.setPartialCloseClientOrderId("199");
        active.setPartialCloseOrderId(188L);
        HtxSwapOrder partial = filledOrder(188L, 199L, "7", "4340.00", "0.0100");
        partial.setStatus(5);
        partial.setState("partially_canceled");
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.of(active));
        when(swap.queryOrder("XAUT-USDT", 188L)).thenReturn(ok(List.of(partial)));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.ACTIVE, result.code());
        verify(recorder).markOrderReconciled(eq(77L), eq(HtxTrendRecorder.LEG_PARTIAL_CLOSE), eq("199"),
                eq(HtxTrendRecorder.ORDER_PARTIALLY_FILLED), anyLong(), anyLong(), eq("partially_canceled"),
                eq(new BigDecimal("7")), eq(new BigDecimal("4340.00")), eq(new BigDecimal("0.0100")), any(), any(), any());
        verify(recorder).markPartialCloseFilled(eq(77L), eq(1), eq("199"), eq(new BigDecimal("7")),
                eq(new BigDecimal("4340.00")), eq(new BigDecimal("0.0100")), eq(new BigDecimal("0.25")), any());
    }

    @Test
    void runnerTrailingHoldsWhenAllHigherTimeframesStillAlign() {
        properties.setFeeProtectionEnabled(false);
        properties.setExitFlatAfterFeeProtection(false);
        givenContractInfo();
        givenKlines();
        givenExitConfirmationKlines("XAUT-USDT", true);
        givenSignal(TrendSide.LONG, new BigDecimal("12"));
        givenFunding("-0.0001");
        HtxTrendPositionEntity active = activeLongPosition();
        active.setHighestPrice(new BigDecimal("4400.00"));
        active.setTrailingStopPrice(new BigDecimal("4350.00"));
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.of(active));
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(List.of(openLongPosition("4340.00"))));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.ACTIVE, result.code());
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void runnerTrailingClosesWhenTwoHigherTimeframesOppose() {
        properties.setFeeProtectionEnabled(false);
        properties.setExitFlatAfterFeeProtection(false);
        givenContractInfo();
        givenKlines();
        givenExitConfirmationKlines("XAUT-USDT", false);
        givenSignal(TrendSide.LONG, new BigDecimal("12"));
        givenFunding("-0.0001");
        HtxTrendPositionEntity active = activeLongPosition();
        active.setHighestPrice(new BigDecimal("4400.00"));
        active.setTrailingStopPrice(new BigDecimal("4350.00"));
        when(recorder.findActive("XAUT-USDT")).thenReturn(Optional.of(active));
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(List.of(openLongPosition("4340.00"))));
        when(swap.placeOrder(any(HtxSwapOrderRequest.class))).thenReturn(ok(accepted(888L, 999L)));

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.CLOSE_SENT, result.code());
        assertTrue(result.message().contains("exit engine: TRAILING_STOP"));
    }

    @Test
    void marketMakerSwitchBlocksExclusiveTrendMode() {
        marketMakerProperties.setEnabled(true);

        HtxTrendFollowingService.RunResult result = service.runOnce();

        assertEquals(HtxTrendFollowingService.RunCode.SKIPPED, result.code());
        assertTrue(result.message().contains("market-maker enabled"));
        verify(swap, never()).contractInfo(any());
    }


    private HtxTrendPositionEntity entrySentLongPosition() {
        HtxTrendPositionEntity position = activeLongPosition();
        position.setStatus(HtxTrendRecorder.ENTRY_SENT);
        position.setEntryOrderId(88L);
        position.setEntryClientOrderId("99");
        position.setEntryAvgPrice(null);
        position.setEntryFilledContracts(BigDecimal.ZERO);
        position.setEntryFee(BigDecimal.ZERO);
        return position;
    }

    private HtxTrendPositionEntity closeSentLongPosition() {
        HtxTrendPositionEntity position = activeLongPosition();
        position.setStatus(HtxTrendRecorder.CLOSE_SENT);
        position.setCloseOrderId(188L);
        position.setCloseClientOrderId("199");
        return position;
    }

    private HtxSwapOrder filledOrder(long orderId, long clientOrderId, String tradeVolume, String avgPrice, String fee) {
        HtxSwapOrder order = new HtxSwapOrder();
        order.setOrderId(orderId);
        order.setClientOrderId(clientOrderId);
        order.setStatus(6);
        order.setState("filled");
        order.setTradeVolume(new BigDecimal(tradeVolume));
        order.setTradeAveragePrice(new BigDecimal(avgPrice));
        order.setFee(new BigDecimal(fee));
        order.setFeeCurrency("USDT");
        order.setRealProfit(new BigDecimal("0.25"));
        return order;
    }

    private void givenContractInfo() {
        givenContractInfo("XAUT-USDT", "0.001");
    }

    private void givenContractInfo(String contractCode, String contractSize) {
        givenContractInfo(contractCode, contractSize, "0.01");
    }

    private void givenContractInfo(String contractCode, String contractSize, String priceTick) {
        HtxSwapContractInfo info = new HtxSwapContractInfo();
        info.setContractCode(contractCode);
        info.setContractSize(new BigDecimal(contractSize));
        info.setPriceTick(new BigDecimal(priceTick));
        when(swap.contractInfo(contractCode)).thenReturn(ok(List.of(info)));
    }

    private void givenKlines() {
        givenKlines("XAUT-USDT");
    }

    private void givenKlines(String contractCode) {
        List<HtxSwapKline> klines = List.of(candle(1L), candle(2L));
        when(swap.historyKline(eq(contractCode), eq(properties.getPeriod()), eq(properties.getKlineSize())))
                .thenReturn(ok(klines));
        when(swap.historyKline(eq(contractCode), eq(properties.getTrendPeriod()), eq(properties.getTrendKlineSize())))
                .thenReturn(ok(klines));
    }

    private void givenKlinesWithTurnover(String contractCode, String firstTurnover, String secondTurnover) {
        List<HtxSwapKline> klines = List.of(candle(1L, firstTurnover), candle(2L, secondTurnover));
        when(swap.historyKline(eq(contractCode), eq(properties.getPeriod()), eq(properties.getKlineSize())))
                .thenReturn(ok(klines));
        when(swap.historyKline(eq(contractCode), eq(properties.getTrendPeriod()), eq(properties.getTrendKlineSize())))
                .thenReturn(ok(klines));
    }


    private void givenEnsembleRisingKlines(String contractCode) {
        long periodSeconds = 15 * 60L;
        long lastBar = freshBarTime();
        long firstBar = lastBar - 139L * periodSeconds;
        List<HtxSwapKline> klines = java.util.stream.IntStream.range(0, 140)
                .mapToObj(index -> trendingCandle(firstBar + index * periodSeconds,
                        new BigDecimal("3600").add(new BigDecimal(index).multiply(new BigDecimal("10"))),
                        index >= 120 ? "220000" : "100000"))
                .toList();
        when(swap.historyKline(eq(contractCode), eq(properties.getPeriod()), eq(properties.getKlineSize())))
                .thenReturn(ok(klines));
        when(swap.historyKline(eq(contractCode), eq(properties.getTrendPeriod()), eq(properties.getTrendKlineSize())))
                .thenReturn(ok(klines));
    }

    private void givenRisingKlines(String contractCode) {
        List<HtxSwapKline> klines = List.of(candle(1L, "100000", "4290"), candle(2L, "100000", "4305"));
        when(swap.historyKline(eq(contractCode), eq(properties.getPeriod()), eq(properties.getKlineSize())))
                .thenReturn(ok(klines));
        when(swap.historyKline(eq(contractCode), eq(properties.getTrendPeriod()), eq(properties.getTrendKlineSize())))
                .thenReturn(ok(klines));
    }


    private void givenBullishMarketAnchor() {
        for (String contractCode : properties.getMarketAnchorContracts()) {
            for (String period : properties.getMarketAnchorPeriods()) {
                givenMarketAnchorKlines(contractCode, period, true);
            }
        }
    }

    private void givenExitConfirmationKlines(String contractCode, boolean rising) {
        for (String period : List.of("30min", "60min", "4hour")) {
            givenMarketAnchorKlines(contractCode, period, rising);
        }
    }

    private void givenMarketAnchorKlines(String contractCode, String period, boolean rising) {
        long periodSeconds = testPeriodSeconds(period);
        long lastBar = Instant.now().getEpochSecond() - periodSeconds - 30L;
        long firstBar = lastBar - 39L * periodSeconds;
        BigDecimal base = contractCode.startsWith("BTC") ? new BigDecimal("60000") : new BigDecimal("3000");
        List<HtxSwapKline> klines = java.util.stream.IntStream.range(0, 40)
                .mapToObj(index -> {
                    BigDecimal offset = new BigDecimal(index).multiply(contractCode.startsWith("BTC")
                            ? new BigDecimal("120") : new BigDecimal("8"));
                    BigDecimal close = rising ? base.add(offset) : base.subtract(offset);
                    return trendingCandle(firstBar + index * periodSeconds, close, "500000");
                })
                .toList();
        when(swap.historyKline(eq(contractCode), eq(period), eq(properties.getMarketAnchorKlineSize())))
                .thenReturn(ok(klines));
    }

    private long testPeriodSeconds(String period) {
        String value = period == null ? "" : period.trim().toLowerCase();
        if (value.endsWith("min")) {
            return Long.parseLong(value.substring(0, value.length() - 3)) * 60L;
        }
        if (value.endsWith("hour")) {
            return Long.parseLong(value.substring(0, value.length() - 4)) * 3_600L;
        }
        throw new IllegalArgumentException("unsupported test period: " + period);
    }

    private void givenSignal(TrendSide side) {
        givenSignal(side, new BigDecimal("12"));
    }

    private void givenSignal(TrendSide side, BigDecimal atr) {
        givenSignal(side, atr, new BigDecimal("0.0028"));
    }

    private void givenSignal(TrendSide side, BigDecimal atr, BigDecimal atrPct) {
        givenSignal(side, atr, atrPct, freshBarTime());
    }

    private void givenSignal(TrendSide side, BigDecimal atr, BigDecimal atrPct, long barTime) {
        givenSignal(side, atr, atrPct, barTime, new BigDecimal("24"), "test signal");
    }

    private void givenSignal(TrendSide side, BigDecimal atr, BigDecimal atrPct, long barTime,
                             BigDecimal adx, String reason) {
        TrendSignal signal = TrendSignal.of(side, barTime, new BigDecimal("4300"), new BigDecimal("4290"),
                new BigDecimal("4280"), new BigDecimal("4310"), new BigDecimal("4260"),
                atr, atrPct, adx, reason);
        when(calculator.evaluate(anyList(), anyList(), eq(properties), anyLong())).thenReturn(signal);
    }

    private void givenFlatSignalForCarry(TrendSide confirmationSide) {
        BigDecimal emaFast = confirmationSide == TrendSide.LONG ? new BigDecimal("4290") : new BigDecimal("4270");
        BigDecimal emaSlow = confirmationSide == TrendSide.LONG ? new BigDecimal("4280") : new BigDecimal("4280");
        TrendSignal signal = TrendSignal.of(TrendSide.FLAT, freshBarTime(), new BigDecimal("4300"), emaFast,
                emaSlow, new BigDecimal("4310"), new BigDecimal("4260"), new BigDecimal("12"),
                new BigDecimal("0.0028"), new BigDecimal("24"), "flat base signal");
        when(calculator.evaluate(anyList(), anyList(), eq(properties), anyLong())).thenReturn(signal);
    }


    private void givenFlatSignalForCarryBelowAdx(TrendSide confirmationSide) {
        BigDecimal emaFast = confirmationSide == TrendSide.LONG ? new BigDecimal("4290") : new BigDecimal("4270");
        BigDecimal emaSlow = new BigDecimal("4280");
        TrendSignal signal = TrendSignal.of(TrendSide.FLAT, freshBarTime(), new BigDecimal("4300"), emaFast,
                emaSlow, new BigDecimal("4310"), new BigDecimal("4260"), new BigDecimal("12"),
                new BigDecimal("0.0028"), new BigDecimal("17"), "ADX below threshold");
        when(calculator.evaluate(anyList(), anyList(), eq(properties), anyLong())).thenReturn(signal);
    }

    private long freshBarTime() {
        return Instant.now().getEpochSecond() - 15 * 60L - 30L;
    }

    private long staleBarTime() {
        return Instant.now().getEpochSecond() - 15 * 60L - properties.getMaxEntrySignalAgeSeconds() - 60L;
    }

    private void givenFunding(String value) {
        givenFunding("XAUT-USDT", value);
    }

    private void givenFunding(String contractCode, String value) {
        HtxSwapFundingRate funding = new HtxSwapFundingRate();
        funding.setContractCode(contractCode);
        funding.setFundingRate(new BigDecimal(value));
        when(swap.fundingRate(contractCode)).thenReturn(ok(funding));
    }

    private void givenDepth() {
        givenDepth("XAUT-USDT", "4299", "4301");
    }

    private void givenDepth(String contractCode, String bid, String ask) {
        givenDepth(contractCode, bid, ask, "1000");
    }

    private void givenDepth(String contractCode, String bid, String ask, String amount) {
        HtxDepth depth = new HtxDepth();
        depth.setBids(List.of(new HtxPriceLevel(new BigDecimal(bid), new BigDecimal(amount))));
        depth.setAsks(List.of(new HtxPriceLevel(new BigDecimal(ask), new BigDecimal(amount))));
        when(swap.depth(contractCode, contractProperties.getDepthStep())).thenReturn(ok(depth));
    }

    private void givenNoOpenOrders() {
        givenNoOpenOrders("XAUT-USDT");
    }

    private void givenNoOpenOrders(String contractCode) {
        HtxSwapOpenOrders openOrders = new HtxSwapOpenOrders();
        openOrders.setOrders(Collections.emptyList());
        when(swap.openOrders(contractCode)).thenReturn(ok(openOrders));
    }

    private void givenNoExternalPositions() {
        givenNoExternalPositions("XAUT-USDT");
    }

    private void givenNoExternalPositions(String contractCode) {
        when(swap.positionInfo(contractCode)).thenReturn(ok(Collections.emptyList()));
    }

    private void givenContractMargin() {
        givenContractMargin("XAUT-USDT");
    }

    private void givenContractMargin(String contractCode) {
        HtxSwapAccount account = new HtxSwapAccount();
        account.setContractCode(contractCode);
        account.setMarginAvailable(new BigDecimal("1000"));
        when(swap.accountInfo(contractCode)).thenReturn(ok(List.of(account)));
    }

    private void givenBroadBullishBreadth() {
        HtxTrendBreadthStats stats = new HtxTrendBreadthStats();
        stats.setTotalCount(50L);
        stats.setLongContextCount(38L);
        stats.setShortContextCount(2L);
        stats.setLongSignalCount(6L);
        stats.setShortSignalCount(0L);
        when(recorder.latestBreadthStats(any(LocalDateTime.class))).thenReturn(stats);
    }


    private void givenBroadBearishBreadth() {
        HtxTrendBreadthStats stats = new HtxTrendBreadthStats();
        stats.setTotalCount(50L);
        stats.setLongContextCount(2L);
        stats.setShortContextCount(38L);
        stats.setLongSignalCount(0L);
        stats.setShortSignalCount(6L);
        when(recorder.latestBreadthStats(any(LocalDateTime.class))).thenReturn(stats);
    }

    private void givenStrongPerformance() {
        HtxTrendPerformanceStats stats = new HtxTrendPerformanceStats();
        stats.setClosedCount(12L);
        stats.setWinCount(8L);
        stats.setLossCount(4L);
        stats.setGrossProfit(new BigDecimal("3.00"));
        stats.setGrossLoss(new BigDecimal("1.00"));
        stats.setNetProfit(new BigDecimal("2.00"));
        when(recorder.trendPerformanceSince(any(LocalDateTime.class), eq(properties.getQualityLookbackTrades())))
                .thenReturn(stats);
    }

    private void givenWeakPerformance() {
        HtxTrendPerformanceStats stats = new HtxTrendPerformanceStats();
        stats.setClosedCount(12L);
        stats.setWinCount(6L);
        stats.setLossCount(6L);
        stats.setGrossProfit(new BigDecimal("1.00"));
        stats.setGrossLoss(new BigDecimal("2.00"));
        stats.setNetProfit(new BigDecimal("-1.00"));
        when(recorder.trendPerformanceSince(any(LocalDateTime.class), eq(properties.getQualityLookbackTrades())))
                .thenReturn(stats);
    }

    private HtxSwapKline candle(long id) {
        return candle(id, "100000");
    }

    private HtxSwapKline candle(long id, String tradeTurnover) {
        return candle(id, tradeTurnover, "4305");
    }

    private HtxSwapKline candle(long id, String tradeTurnover, String close) {
        HtxSwapKline candle = new HtxSwapKline();
        candle.setId(id);
        candle.setOpen(new BigDecimal("4300"));
        candle.setHigh(new BigDecimal("4310"));
        candle.setLow(new BigDecimal("4290"));
        candle.setClose(new BigDecimal(close));
        candle.setTradeTurnover(new BigDecimal(tradeTurnover));
        return candle;
    }


    private HtxSwapKline trendingCandle(long id, BigDecimal close, String tradeTurnover) {
        HtxSwapKline candle = new HtxSwapKline();
        candle.setId(id);
        candle.setOpen(close.subtract(new BigDecimal("3")));
        candle.setHigh(close.add(new BigDecimal("3")));
        candle.setLow(close.subtract(new BigDecimal("40")));
        candle.setClose(close);
        candle.setTradeTurnover(new BigDecimal(tradeTurnover));
        return candle;
    }

    private HtxSwapFeeRate feeRate(String contractCode, String openTaker, String closeTaker) {
        HtxSwapFeeRate feeRate = new HtxSwapFeeRate();
        feeRate.setContractCode(contractCode);
        feeRate.setOpenMakerFee(new BigDecimal("0.0001"));
        feeRate.setOpenTakerFee(new BigDecimal(openTaker));
        feeRate.setCloseMakerFee(new BigDecimal("0.0001"));
        feeRate.setCloseTakerFee(new BigDecimal(closeTaker));
        feeRate.setFeeAsset("USDT");
        return feeRate;
    }

    private HtxTrendPositionEntity activeLongPosition() {
        return activeLongPosition("XAUT-USDT", "0.001", 44L, "4315.84", "0.075958784",
                "4295.00", "4316.64", "4332.21", "4315.84");
    }

    private HtxTrendPositionEntity activeLongPosition(String contractCode, String contractSize, long volume,
                                                     String entryPrice, String entryFee, String stopPrice,
                                                     String trailingStopPrice, String highestPrice, String lowestPrice) {
        HtxTrendPositionEntity position = new HtxTrendPositionEntity();
        position.setId(77L);
        position.setPositionKey("CSTF-" + contractCode.replace("-", "") + "-test");
        position.setContractCode(contractCode);
        position.setStatus(HtxTrendRecorder.ACTIVE);
        position.setSide("LONG");
        position.setContractSize(new BigDecimal(contractSize));
        position.setContractVolume(volume);
        position.setEntryAvgPrice(new BigDecimal(entryPrice));
        position.setEntryFee(new BigDecimal(entryFee));
        position.setEntryFilledContracts(new BigDecimal(Long.toString(volume)));
        position.setStopPrice(new BigDecimal(stopPrice));
        position.setTrailingStopPrice(new BigDecimal(trailingStopPrice));
        position.setTakeProfitPrice(null);
        position.setHighestPrice(new BigDecimal(highestPrice));
        position.setLowestPrice(new BigDecimal(lowestPrice));
        return position;
    }

    private HtxTrendPositionEntity donePosition(String realizedProfit, String entryFee, String closeFee, long minutesAgo) {
        HtxTrendPositionEntity position = new HtxTrendPositionEntity();
        position.setId(66L);
        position.setContractCode("XAUT-USDT");
        position.setStatus(HtxTrendRecorder.DONE);
        position.setSide("LONG");
        position.setRealizedProfit(new BigDecimal(realizedProfit));
        position.setEntryFee(new BigDecimal(entryFee));
        position.setCloseFee(new BigDecimal(closeFee));
        position.setUpdatedAt(LocalDateTime.now().minusMinutes(minutesAgo));
        return position;
    }

    private HtxSwapPosition openLongPosition(String markPrice) {
        return openLongPosition("XAUT-USDT", "44", markPrice);
    }

    private HtxSwapPosition openLongPosition(String contractCode, String volume, String markPrice) {
        HtxSwapPosition position = new HtxSwapPosition();
        position.setContractCode(contractCode);
        position.setPositionSide("long");
        position.setVolume(new BigDecimal(volume));
        position.setMarkPrice(new BigDecimal(markPrice));
        return position;
    }

    private HtxTrendPositionEntity activeShortPosition(String contractCode, String contractSize, long volume,
                                                       String entryPrice, String entryFee, String stopPrice,
                                                       String trailingStopPrice, String highestPrice, String lowestPrice) {
        HtxTrendPositionEntity position = new HtxTrendPositionEntity();
        position.setId(77L);
        position.setPositionKey("CSTF-" + contractCode.replace("-", "") + "-test");
        position.setContractCode(contractCode);
        position.setStatus(HtxTrendRecorder.ACTIVE);
        position.setSide("SHORT");
        position.setContractSize(new BigDecimal(contractSize));
        position.setContractVolume(volume);
        position.setEntryAvgPrice(new BigDecimal(entryPrice));
        position.setEntryFee(new BigDecimal(entryFee));
        position.setEntryFilledContracts(new BigDecimal(Long.toString(volume)));
        position.setStopPrice(new BigDecimal(stopPrice));
        position.setTrailingStopPrice(new BigDecimal(trailingStopPrice));
        position.setTakeProfitPrice(null);
        position.setHighestPrice(new BigDecimal(highestPrice));
        position.setLowestPrice(new BigDecimal(lowestPrice));
        return position;
    }

    private HtxSwapPosition openShortPosition(String contractCode, String volume, String markPrice) {
        HtxSwapPosition position = new HtxSwapPosition();
        position.setContractCode(contractCode);
        position.setPositionSide("short");
        position.setVolume(new BigDecimal(volume));
        position.setMarkPrice(new BigDecimal(markPrice));
        return position;
    }

    private <T> HtxRestResponse<T> ok(T data) {
        return HtxRestResponse.<T>builder().status("ok").data(data).build();
    }

    private <T> HtxRestResponse<T> error(String code, String message) {
        return HtxRestResponse.<T>builder().status("error").errCode(code).errMsg(message).build();
    }

    private HtxSwapOrderResult accepted(long orderId, long clientOrderId) {
        HtxSwapOrderResult result = new HtxSwapOrderResult();
        result.setOrderId(orderId);
        result.setClientOrderId(clientOrderId);
        return result;
    }

    private HtxSwapLeverageResult leverage(String positionSide) {
        return leverage("XAUT-USDT", positionSide);
    }

    private HtxSwapLeverageResult leverage(String contractCode, String positionSide) {
        HtxSwapLeverageResult result = new HtxSwapLeverageResult();
        result.setContractCode(contractCode);
        result.setPositionSide(positionSide);
        result.setLeverage(properties.getLeverage());
        return result;
    }
}
