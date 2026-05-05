package com.metaverse.coin.strategy.job.strategy.application;

import com.metaverse.coin.strategy.common.dao.mapper.SingleExchangeArbitrageFundingSettlementMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SingleExchangeArbitrageOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SingleExchangeArbitragePositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SingleExchangeArbitrageFundingSettlement;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SingleExchangeArbitrageOrder;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SingleExchangeArbitragePosition;
import com.metaverse.coin.strategy.core.arbitrage.config.SingleExchangeSpotFuturesProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletCapacitySummaryView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletOrderView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPerformanceView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPnlSummaryView;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletAccountingService;
import com.metaverse.coin.strategy.job.strategy.model.StrategyDashboardOverviewView;
import com.metaverse.coin.strategy.job.strategy.model.StrategyFactorView;
import com.metaverse.coin.strategy.job.strategy.model.StrategySummaryDetailView;
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

@ExtendWith(MockitoExtension.class)
class StrategyProductQueryServiceTest {

    @Mock
    private SmartWalletAccountingService accountingService;
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
                accountingService,
                positionMapper,
                orderMapper,
                settlementMapper,
                new SingleExchangeSpotFuturesProperties()
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
        when(accountingService.listOrders(eq(100), eq("PAPER"))).thenReturn(List.of(
                SmartWalletOrderView.builder()
                        .calibratedFollowProbability(new BigDecimal("0.64"))
                        .estimatedSlippageUsdc(new BigDecimal("1.20"))
                        .build()
        ));

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
        lenient().when(accountingService.performance(any())).thenReturn(SmartWalletPerformanceView.builder()
                .totalOrders(10)
                .filledOrders(8)
                .openPositions(2)
                .closedPositions(6)
                .winningPositions(4)
                .losingPositions(2)
                .winRate(new BigDecimal("0.62"))
                .realizedPnlUsdc(new BigDecimal("12.30"))
                .unrealizedPnlUsdc(new BigDecimal("1.20"))
                .pendingSettlementPnlUsdc(BigDecimal.ZERO)
                .compositePnlUsdc(new BigDecimal("13.50"))
                .netPnlUsdc(new BigDecimal("13.50"))
                .profitFactor(new BigDecimal("1.40"))
                .maxDrawdownUsdc(new BigDecimal("2.00"))
                .conflictingMarkets(0)
                .openConflictingMarkets(0)
                .conflictNetPnlUsdc(BigDecimal.ZERO)
                .earlyExitOrderCount(0)
                .earlyExitRealizedPnlUsdc(BigDecimal.ZERO)
                .takeProfitOrderCount(0)
                .takeProfitRealizedPnlUsdc(BigDecimal.ZERO)
                .stopLossOrderCount(0)
                .stopLossRealizedPnlUsdc(BigDecimal.ZERO)
                .build());
        lenient().when(accountingService.summary(any())).thenReturn(SmartWalletPnlSummaryView.builder()
                .tradedVolumeUsdc(new BigDecimal("100"))
                .feeUsdc(new BigDecimal("1"))
                .build());
        lenient().when(accountingService.capacitySummary(any())).thenReturn(SmartWalletCapacitySummaryView.builder()
                .remainingPositionSlots(4)
                .remainingCapitalBudgetUsdc(new BigDecimal("900"))
                .availableCapitalUsdc(new BigDecimal("850"))
                .lockedClaimProceedsUsdc(BigDecimal.ZERO)
                .build());
        lenient().when(accountingService.listNextSettlements(eq(1), any())).thenReturn(List.of());
        lenient().when(accountingService.conflictingMarkets(anyInt(), anyBoolean(), any())).thenReturn(List.of());
    }
}
