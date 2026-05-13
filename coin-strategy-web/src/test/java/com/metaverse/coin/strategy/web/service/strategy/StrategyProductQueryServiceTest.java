package com.metaverse.coin.strategy.web.service.strategy;

import com.metaverse.coin.strategy.common.dao.mapper.SingleExchangeArbitrageFundingSettlementMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SingleExchangeArbitrageOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SingleExchangeArbitragePositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SingleExchangeArbitrageFundingSettlement;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SingleExchangeArbitrageOrder;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SingleExchangeArbitragePosition;
import com.metaverse.coin.strategy.web.config.SingleExchangeSpotFuturesProperties;
import com.metaverse.coin.strategy.web.model.StrategyDashboardOverviewView;
import com.metaverse.coin.strategy.web.model.StrategyFactorView;
import com.metaverse.coin.strategy.web.model.StrategySummaryDetailView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletCapacitySummaryView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletOrderView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletPerformanceView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletPnlSummaryView;
import com.metaverse.coin.strategy.web.service.smartwallet.SmartWalletReadService;
import com.metaverse.coin.strategy.web.service.smartwallet.SmartWalletReportReadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * web 端 {@link StrategyProductQueryService} 的单元测试。
 *
 * <p>仅 mock {@link SmartWalletReadService} 与 {@link SmartWalletReportReadService}
 * 两个直接依赖；mapper 走真实 BaseMapper 接口但仍由 Mockito 接管。</p>
 *
 * <p>覆盖的场景与原 job 端测试保持一致：</p>
 * <ul>
 *   <li>策略总览（overview）暴露 4 条策略 + 模式/能力</li>
 *   <li>策略概要（summaryDetail）只返回 metrics + insights，不含列表</li>
 *   <li>POLYMARKET_COPY 的 factors 能映射核心账户数据</li>
 *   <li>FUNDING_ARB 的 factors 能基于 SingleExchange 三张表拼装</li>
 *   <li>funding-arb 数据源失败时，polymarket overview 不受影响（降级）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class StrategyProductQueryServiceTest {

    @Mock
    private SmartWalletReadService readService;
    @Mock
    private SmartWalletReportReadService reportReadService;
    @Mock
    private SingleExchangeArbitragePositionMapper positionMapper;
    @Mock
    private SingleExchangeArbitrageOrderMapper orderMapper;
    @Mock
    private SingleExchangeArbitrageFundingSettlementMapper settlementMapper;

    private StrategyProductQueryService service;

    @BeforeEach
    void setUp() {
        service = new StrategyProductQueryService(
                readService,
                reportReadService,
                positionMapper,
                orderMapper,
                settlementMapper,
                new SingleExchangeSpotFuturesProperties(3, new BigDecimal("0.0005"))
        );
        lenient().when(positionMapper.selectList(any())).thenReturn(List.of());
        lenient().when(orderMapper.selectList(any())).thenReturn(List.of());
        lenient().when(settlementMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void overviewShouldExposeModesCapabilitiesAndModeBehavior() {
        mockPolymarketSummary();

        StrategyDashboardOverviewView overview = service.overview("PAPER");

        assertEquals(4, overview.strategies().size());
        assertTrue(overview.strategies().get(0).capabilities().contains("FACTORS"));
        assertEquals(List.of("PAPER", "LIVE"), overview.strategies().get(0).supportedModes());
        assertEquals("ISOLATED", overview.strategies().get(0).modeBehavior());
        assertEquals("SHARED", overview.strategies().get(1).modeBehavior());
    }

    @Test
    void summaryShouldExposeDashboardMetricsOnlyForFirstScreen() {
        mockPolymarketSummary();

        StrategySummaryDetailView summary = service.summaryDetail("POLYMARKET_COPY", "PAPER");

        assertEquals("POLYMARKET_COPY", summary.summary().code());
        assertEquals(new BigDecimal("850"), summary.metrics().tvlUsdc());
        assertEquals(new BigDecimal("13.50"), summary.metrics().netPnlUsdc());
        assertEquals(new BigDecimal("12.30"), summary.metrics().realizedPnlUsdc());
        assertEquals(10, summary.metrics().totalOrders());
        assertEquals(2, summary.metrics().openPositions());
        assertEquals(6, summary.metrics().closedPositions());
        assertEquals(new BigDecimal("2.00"), summary.metrics().maxDrawdownUsdc());
    }

    @Test
    void polymarketFactorsShouldMapCurrentAccountingData() {
        mockPolymarketSummary();
        SmartWalletOrderView order = new SmartWalletOrderView(
                /* id */ 1L,
                /* followTaskId */ null,
                /* walletAddress */ null,
                /* executionMode */ "PAPER",
                /* executionAddress */ null,
                /* externalOrderId */ null,
                /* sourceEventId */ null,
                /* externalTradeId */ null,
                /* marketId */ null,
                /* marketSlug */ null,
                /* marketUrl */ null,
                /* externalMarketUrl */ null,
                /* internalMarketUrl */ null,
                /* marketLinkStatus */ null,
                /* tokenId */ null,
                /* question */ null,
                /* outcome */ null,
                /* marketEndAt */ null,
                /* marketEndAtBjt */ null,
                /* marketLifecycle */ null,
                /* activeMarket */ null,
                /* closed */ null,
                /* acceptingOrders */ null,
                /* metadataMissing */ null,
                /* manualReviewRequired */ null,
                /* abnormalMarketReason */ null,
                /* normalizedCategory */ null,
                /* sourceCategory */ null,
                /* side */ null,
                /* orderType */ null,
                /* earlyExit */ null,
                /* exitReason */ null,
                /* status */ null,
                /* price */ null,
                /* size */ null,
                /* notionalUsdc */ null,
                /* entryQuantity */ null,
                /* entryAvgPrice */ null,
                /* entryCostUsdc */ null,
                /* roiBaseUsdc */ null,
                /* roiAnomalyReason */ null,
                /* costBasisUsdc */ null,
                /* feeUsdc */ null,
                /* tradingFeeUsdc */ null,
                /* feePol */ null,
                /* gasFeeUsdc */ null,
                /* realizedPnlUsdc */ null,
                /* estimatedFeeUsdc */ null,
                /* estimatedSlippageUsdc */ new BigDecimal("1.20"),
                /* followProbability */ null,
                /* calibratedFollowProbability */ new BigDecimal("0.64"),
                /* slippageSpikeProbability */ null,
                /* estimateSampleSize */ null,
                /* errorMessage */ null,
                /* placedAt */ null,
                /* filledAt */ null
        );
        when(readService.orders(eq(100), eq("PAPER"))).thenReturn(List.of(order));

        List<StrategyFactorView> factors = service.factors("POLYMARKET_COPY", "PAPER", "30D");

        assertEquals(5, factors.size());
        StrategyFactorView walletQuality = factors.stream()
                .filter(item -> "WALLET_QUALITY".equals(item.code()))
                .findFirst()
                .orElse(null);
        assertNotNull(walletQuality);
        assertEquals("STRONG", walletQuality.signal());
        assertEquals("PAPER", walletQuality.metadata().get("mode"));
    }

    @Test
    void fundingArbFactorsShouldMapSharedFundingData() {
        SingleExchangeArbitragePosition position = new SingleExchangeArbitragePosition()
                .setId(1L)
                .setExchangeName("BINANCE")
                .setStrategyType("SINGLE_EXCHANGE_SPOT_FUTURES_ARBITRAGE_V1")
                .setStatus("OPENED")
                .setLeverage(3)
                .setEntryBasisRate(new BigDecimal("0.0015"));
        SingleExchangeArbitrageOrder order = new SingleExchangeArbitrageOrder()
                .setStatus("FILLED")
                .setFeeAmount(new BigDecimal("0.42"));
        SingleExchangeArbitrageFundingSettlement settlement = new SingleExchangeArbitrageFundingSettlement()
                .setFundingRate(new BigDecimal("0.0008"))
                .setSettlementAmount(new BigDecimal("1.50"));

        when(positionMapper.selectList(any())).thenReturn(List.of(position));
        when(orderMapper.selectList(any())).thenReturn(List.of(order));
        when(settlementMapper.selectList(any())).thenReturn(List.of(settlement));

        List<StrategyFactorView> factors = service.factors("FUNDING_ARB", "LIVE", "30D");

        assertEquals(5, factors.size());
        StrategyFactorView fundingRate = factors.stream()
                .filter(item -> "FUNDING_RATE".equals(item.code()))
                .findFirst()
                .orElse(null);
        assertNotNull(fundingRate);
        assertEquals("EARN", fundingRate.signal());
        assertEquals("SHARED", fundingRate.metadata().get("modeBehavior"));
    }

    @Test
    void fundingArbDataSourceFailureShouldNotBreakPolymarketOverview() {
        mockPolymarketSummary();
        when(positionMapper.selectList(any())).thenThrow(new IllegalStateException("missing funding table"));

        StrategyDashboardOverviewView overview = service.overview("PAPER");
        StrategySummaryDetailView fundingSummary = service.summaryDetail("FUNDING_ARB", "PAPER");

        assertEquals(4, overview.strategies().size());
        assertEquals("POLYMARKET_COPY", overview.strategies().get(0).code());
        assertEquals(new BigDecimal("13.50"), overview.strategies().get(0).netPnlUsdc());
        assertEquals("FUNDING_ARB", overview.strategies().get(1).code());
        assertEquals(BigDecimal.ZERO, overview.strategies().get(1).netPnlUsdc());
        assertEquals(BigDecimal.ZERO, fundingSummary.metrics().netPnlUsdc());
        assertTrue(fundingSummary.insights().stream()
                .anyMatch(item -> item.title().contains("暂不可用")));
    }

    private void mockPolymarketSummary() {
        SmartWalletPerformanceView performance = new SmartWalletPerformanceView(
                /* totalOrders */ 10,
                /* filledOrders */ 8,
                /* closedPositions */ 6,
                /* openPositions */ 2,
                /* pendingPositions */ 0,
                /* winningPositions */ 4,
                /* losingPositions */ 2,
                /* winRate */ new BigDecimal("0.62"),
                /* tradedVolumeUsdc */ new BigDecimal("100"),
                /* feeUsdc */ new BigDecimal("1"),
                /* realizedPnlUsdc */ new BigDecimal("12.30"),
                /* unrealizedPnlUsdc */ new BigDecimal("1.20"),
                /* pendingSettlementPnlUsdc */ BigDecimal.ZERO,
                /* compositePnlUsdc */ new BigDecimal("13.50"),
                /* netPnlUsdc */ new BigDecimal("13.50"),
                /* averageWinUsdc */ null,
                /* averageLossUsdc */ null,
                /* profitFactor */ new BigDecimal("1.40"),
                /* maxDrawdownUsdc */ new BigDecimal("2.00"),
                /* conflictingMarkets */ 0,
                /* conflictingPositions */ 0,
                /* openConflictingMarkets */ 0,
                /* openConflictingPositions */ 0,
                /* conflictNetPnlUsdc */ BigDecimal.ZERO,
                /* earlyExitOrderCount */ 0,
                /* earlyExitRealizedPnlUsdc */ BigDecimal.ZERO,
                /* takeProfitOrderCount */ 0,
                /* takeProfitRealizedPnlUsdc */ BigDecimal.ZERO,
                /* stopLossOrderCount */ 0,
                /* stopLossRealizedPnlUsdc */ BigDecimal.ZERO
        );
        lenient().when(reportReadService.performance(any())).thenReturn(performance);

        SmartWalletPnlSummaryView summary = new SmartWalletPnlSummaryView(
                /* totalOrders */ 10,
                /* filledOrders */ 8,
                /* openPositions */ 2,
                /* tradedVolumeUsdc */ new BigDecimal("100"),
                /* feeUsdc */ new BigDecimal("1"),
                /* realizedPnlUsdc */ new BigDecimal("12.30"),
                /* unrealizedPnlUsdc */ new BigDecimal("1.20"),
                /* pendingSettlementPnlUsdc */ BigDecimal.ZERO,
                /* compositePnlUsdc */ new BigDecimal("13.50"),
                /* netPnlUsdc */ new BigDecimal("13.50"),
                /* winRate */ new BigDecimal("0.62")
        );
        lenient().when(reportReadService.pnlSummary(any())).thenReturn(summary);

        SmartWalletCapacitySummaryView capacity = new SmartWalletCapacitySummaryView(
                /* openPositionCount */ 2,
                /* openPositionLimit */ 6,
                /* remainingPositionSlots */ 4,
                /* totalOpenCostUsdc */ new BigDecimal("100"),
                /* openCostLimitUsdc */ new BigDecimal("1000"),
                /* remainingCapitalBudgetUsdc */ new BigDecimal("900"),
                /* availableCapitalUsdc */ new BigDecimal("850"),
                /* lockedClaimProceedsUsdc */ BigDecimal.ZERO,
                /* maxHoursToMarketEnd */ 24,
                /* duplicateOpenPositionBlocked */ Boolean.FALSE,
                /* duplicatePendingTaskBlocked */ Boolean.FALSE
        );
        lenient().when(reportReadService.capacitySummary(any())).thenReturn(capacity);

        lenient().when(readService.nextSettlements(eq(1), any())).thenReturn(List.of());
        lenient().when(readService.conflictingMarkets(anyInt(), anyBoolean(), any())).thenReturn(List.of());
    }
}
