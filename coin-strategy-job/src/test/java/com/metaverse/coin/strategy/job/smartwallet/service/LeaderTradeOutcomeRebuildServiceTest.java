package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletLeaderTradeOutcomeMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletProfileMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeOrderMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletLeaderTradeOutcomeEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LeaderTradeOutcomeRebuildServiceTest {

    @Mock
    private SmartWalletLeaderTradeOutcomeMapper leaderOutcomeMapper;
    @Mock
    private SmartWalletProfileMapper profileMapper;
    @Mock
    private SmartWalletTradeOrderMapper tradeOrderMapper;
    @Mock
    private SmartWalletFollowTaskMapper followTaskMapper;
    @Mock
    private SmartWalletMarketInfoService marketInfoService;
    @Mock
    private PolymarketApiClient polymarketApiClient;
    @Mock
    private PolymarketMarketMetadataService marketMetadataService;

    private LeaderTradeOutcomeRebuildService service;

    @BeforeEach
    void setUp() {
        service = new LeaderTradeOutcomeRebuildService(
                leaderOutcomeMapper,
                profileMapper,
                tradeOrderMapper,
                followTaskMapper,
                marketInfoService,
                polymarketApiClient,
                marketMetadataService
        );
        when(leaderOutcomeMapper.selectOne(any())).thenReturn(null);
        when(marketInfoService.getOrCreate(any(), any(), any(), any(), any(), any()))
                .thenReturn(new SmartWalletMarketInfoEntity().setId(99L));
    }

    @Test
    void shouldAggregateMultipleBuysIntoWinningClosedOutcome() {
        when(marketMetadataService.getBestEffortByConditionId(eq("market-1"), eq(false), any(), any()))
                .thenReturn(resolvedMetadata("market-1", "YES"));

        List<SmartWalletLeaderTradeOutcomeEntity> rows = service.rebuildWallet("0xabc", List.of(
                trade("market-1", "YES", "BUY", "0.40", "10", 1_000L),
                trade("market-1", "YES", "BUY", "0.50", "20", 2_000L)
        ));

        assertEquals(1, rows.size());
        SmartWalletLeaderTradeOutcomeEntity row = rows.get(0);
        assertEquals(LeaderTradeOutcomeRebuildService.STATUS_CLOSED_WIN, row.getOutcomeStatus());
        assertEquals(new BigDecimal("30.00000000"), row.getBuyShares());
        assertEquals(new BigDecimal("0.46666667"), row.getAvgBuyPrice());
        assertEquals(new BigDecimal("16.00000000"), row.getEstimatedRealizedPnlUsdc());
        assertEquals(Boolean.TRUE, row.getIsWin());
        assertTrue(row.getSourceConfidence().compareTo(new BigDecimal("0.5500")) >= 0);
    }

    @Test
    void shouldMarkResolvedNonWinnerAsClosedLoss() {
        when(marketMetadataService.getBestEffortByConditionId(eq("market-1"), eq(false), any(), any()))
                .thenReturn(resolvedMetadata("market-1", "NO"));

        List<SmartWalletLeaderTradeOutcomeEntity> rows = service.rebuildWallet("0xabc", List.of(
                trade("market-1", "YES", "BUY", "0.25", "100", 1_000L)
        ));

        assertEquals(1, rows.size());
        assertEquals(LeaderTradeOutcomeRebuildService.STATUS_CLOSED_LOSS, rows.get(0).getOutcomeStatus());
        assertEquals(new BigDecimal("-25.00000000"), rows.get(0).getEstimatedRealizedPnlUsdc());
        assertEquals(Boolean.FALSE, rows.get(0).getIsWin());
    }

    @Test
    void shouldIncludeSellProceedsBeforeSettlement() {
        when(marketMetadataService.getBestEffortByConditionId(eq("market-1"), eq(false), any(), any()))
                .thenReturn(resolvedMetadata("market-1", "YES"));

        List<SmartWalletLeaderTradeOutcomeEntity> rows = service.rebuildWallet("0xabc", List.of(
                trade("market-1", "YES", "BUY", "0.40", "100", 1_000L),
                trade("market-1", "YES", "SELL", "0.60", "40", 2_000L)
        ));

        assertEquals(1, rows.size());
        SmartWalletLeaderTradeOutcomeEntity row = rows.get(0);
        assertEquals(new BigDecimal("60.00000000"), row.getNetShares());
        assertEquals(new BigDecimal("44.00000000"), row.getEstimatedRealizedPnlUsdc());
        assertEquals(LeaderTradeOutcomeRebuildService.STATUS_CLOSED_WIN, row.getOutcomeStatus());
    }

    @Test
    void shouldKeepUnresolvedMarketsOutOfClosedWinRate() {
        when(marketMetadataService.getBestEffortByConditionId(eq("market-2"), eq(false), any(), any()))
                .thenReturn(openMetadata("market-2"));

        List<SmartWalletLeaderTradeOutcomeEntity> rows = service.rebuildWallet("0xabc", List.of(
                trade("market-2", "YES", "BUY", "0.40", "100", 1_000L)
        ));

        assertEquals(1, rows.size());
        assertEquals(LeaderTradeOutcomeRebuildService.STATUS_OPEN, rows.get(0).getOutcomeStatus());
        assertNull(rows.get(0).getIsWin());
    }

    @Test
    void shouldMarkOutcomeLowConfidenceWhenTradeFieldsAreMissing() {
        when(marketMetadataService.getBestEffortByConditionId(eq("market-1"), eq(false), any(), any()))
                .thenReturn(null);

        PolymarketApiClient.UserTrade malformed = PolymarketApiClient.UserTrade.builder()
                .marketId("market-1")
                .outcome("YES")
                .side("BUY")
                .price(new BigDecimal("0.45"))
                .tradedAt(2_000L)
                .build();

        List<SmartWalletLeaderTradeOutcomeEntity> rows = service.rebuildWallet("0xabc", List.of(
                trade("market-1", "YES", "BUY", "0.40", "100", 1_000L),
                malformed
        ));

        assertEquals(1, rows.size());
        assertEquals(LeaderTradeOutcomeRebuildService.STATUS_LOW_CONFIDENCE, rows.get(0).getOutcomeStatus());
    }

    private PolymarketApiClient.UserTrade trade(String marketId,
                                                String outcome,
                                                String side,
                                                String price,
                                                String shares,
                                                long tradedAt) {
        return PolymarketApiClient.UserTrade.builder()
                .marketId(marketId)
                .question("Question")
                .outcome(outcome)
                .side(side)
                .price(new BigDecimal(price))
                .sizeShares(new BigDecimal(shares))
                .tradedAt(tradedAt)
                .build();
    }

    private PolymarketApiClient.MarketMetadata resolvedMetadata(String marketId, String winner) {
        Map<String, String> tokenIds = new LinkedHashMap<>();
        tokenIds.put("YES", "token-yes");
        tokenIds.put("NO", "token-no");
        return PolymarketApiClient.MarketMetadata.builder()
                .conditionId(marketId)
                .question("Question")
                .closed(true)
                .acceptingOrders(false)
                .resolvedOutcome(winner)
                .resolutionStatus(MarketResolutionResolver.STATUS_RESOLVED)
                .outcomeTokenIds(tokenIds)
                .endAt(10_000L)
                .build();
    }

    private PolymarketApiClient.MarketMetadata openMetadata(String marketId) {
        Map<String, String> tokenIds = new LinkedHashMap<>();
        tokenIds.put("YES", "token-yes");
        tokenIds.put("NO", "token-no");
        return PolymarketApiClient.MarketMetadata.builder()
                .conditionId(marketId)
                .question("Question")
                .active(true)
                .closed(false)
                .acceptingOrders(true)
                .resolutionStatus(MarketResolutionResolver.STATUS_UNRESOLVED)
                .outcomeTokenIds(tokenIds)
                .endAt(10_000L)
                .build();
    }
}
