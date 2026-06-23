package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderAnalysisEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.model.WalletOrderStatsView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletOrderStatsServiceTest {

    @Mock
    private WalletOrderAggregationService walletOrderAggregationService;

    private WalletOrderStatsService service;

    @BeforeEach
    void setUp() {
        service = new WalletOrderStatsService(walletOrderAggregationService);
    }

    @Test
    void shouldSummarizeClosedStatsFromPersistedRows() {
        when(walletOrderAggregationService.activePositions("0xabc")).thenReturn(List.of());

        WalletOrderStatsView stats = service.summarize("0xabc", List.of(
                row("market-win", daysAgo(1), new BigDecimal("12"), new BigDecimal("0.12"), true),
                row("market-loss", daysAgo(2), new BigDecimal("-4"), new BigDecimal("-0.04"), false),
                row("market-old", daysAgo(8), new BigDecimal("20"), new BigDecimal("0.20"), true)
        ), "7d");

        assertEquals("0xabc", stats.walletAddress());
        assertEquals("7D", stats.window());
        assertEquals(2, stats.closedOrderCount());
        assertEquals(1, stats.winCount());
        assertEquals(1, stats.lossCount());
        assertEquals(new BigDecimal("0.5000"), stats.closedWinRate());
        assertEquals(new BigDecimal("8.0000"), stats.closedRealizedPnlUsdc());
        assertEquals(new BigDecimal("0.0400"), stats.averageRoiRate());
    }

    @Test
    void shouldKeepPersistedRowsWithMissingClosedAtOnlyInAllWindow() {
        when(walletOrderAggregationService.activePositions("0xabc")).thenReturn(List.of());
        SmartWalletOrderAnalysisEntity missingClosedAt = row("market-null", null,
                new BigDecimal("3"), new BigDecimal("0.03"), true);

        WalletOrderStatsView sevenDay = service.summarize("0xabc", List.of(missingClosedAt), "7d");
        WalletOrderStatsView all = service.summarize("0xabc", List.of(missingClosedAt), "all");

        assertEquals(0, sevenDay.closedOrderCount());
        assertEquals(1, all.closedOrderCount());
        assertEquals(new BigDecimal("3.0000"), all.closedRealizedPnlUsdc());
    }

    private SmartWalletOrderAnalysisEntity row(String marketId,
                                               Date closedAt,
                                               BigDecimal realizedPnl,
                                               BigDecimal roiRate,
                                               boolean isWin) {
        return new SmartWalletOrderAnalysisEntity()
                .setWalletAddress("0xabc")
                .setSource("POLYMARKET")
                .setMarketId(marketId)
                .setOutcome("YES")
                .setClosedAt(closedAt)
                .setMarketEndAt(closedAt == null ? null : closedAt.getTime() - 1_000L)
                .setInvestedUsdc(new BigDecimal("100"))
                .setRealizedPnlUsdc(realizedPnl)
                .setRoiRate(roiRate)
                .setExitStyle(isWin ? "SETTLED_WIN" : "SETTLED_LOSS")
                .setIsWin(isWin)
                .setUpdatedAt(new Date());
    }

    private Date daysAgo(int days) {
        return new Date(System.currentTimeMillis() - days * 24L * 3600_000L);
    }
}
