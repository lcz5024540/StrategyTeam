package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletCashLedgerMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletDailyReportMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFillMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletMarketInfoService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletAccountingService.SettlementTerminalCheck;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P2-2 (2026-05-26 用户反馈)：未到 metadata.endAt 的「提前结算」必须被 settlementTerminalCheck 拒绝；
 * 已到期则按既有路径放行。覆盖 uma_resolved / winning_token / final_price_inference 三条早结算
 * 路径与正常的 ended_and_closed 路径。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SmartWalletSettlementGateTest {

    @Mock private SmartWalletOrderMapper orderMapper;
    @Mock private SmartWalletFillMapper fillMapper;
    @Mock private SmartWalletPositionMapper positionMapper;
    @Mock private SmartWalletCashLedgerMapper cashLedgerMapper;
    @Mock private SmartWalletDailyReportMapper dailyReportMapper;
    @Mock private SmartWalletTradeEventMapper tradeEventMapper;
    @Mock private SmartWalletFollowTaskMapper followTaskMapper;
    @Mock private PolymarketMarketMetadataService marketMetadataService;
    @Mock private ObjectProvider<SmartWalletFollowTaskService> followTaskServiceProvider;
    @Mock private SmartWalletMarketInfoService marketInfoService;
    @Mock private SmartWalletMarketStateService marketStateService;
    @Mock private SmartWalletMarketLinkService marketLinkService;
    @Mock private ObjectProvider<SmartWalletTradeOrderService> tradeOrderServiceProvider;
    @Mock private PolymarketFeeScheduleService feeScheduleService;
    @Mock private PolymarketMarketDataCacheService marketDataCacheService;

    private SmartWalletAccountingService service;

    @BeforeEach
    void setUp() {
        service = new SmartWalletAccountingService(
                orderMapper, fillMapper, positionMapper, cashLedgerMapper, dailyReportMapper,
                tradeEventMapper, followTaskMapper, marketMetadataService,
                new SmartWalletTimeFormatter(), new SmartWalletProperties(),
                followTaskServiceProvider, marketInfoService, marketStateService,
                marketLinkService, tradeOrderServiceProvider, feeScheduleService,
                marketDataCacheService);
    }

    @Test
    void winningTokenButMarketNotEnded_blocked() {
        PolymarketApiClient.MarketMetadata md = mock(PolymarketApiClient.MarketMetadata.class);
        lenient().when(md.isAcceptingOrders()).thenReturn(false);
        lenient().when(md.isResolved()).thenReturn(true);
        lenient().when(md.isClosed()).thenReturn(false);
        lenient().when(md.getWinningTokenId()).thenReturn("token-yes");
        lenient().when(md.getSlug()).thenReturn("test-market");
        lenient().when(md.getUmaResolutionStatus()).thenReturn(null);

        SettlementTerminalCheck check = service.settlementTerminalCheck(md, /*marketEnded*/ false);

        assertFalse(check.canSettle(),
                "winningTokenId 已设但 marketEnded=false 必须被新闸门拦下");
        assertEquals("early_settlement_blocked_market_not_ended", check.reason());
    }

    @Test
    void umaResolvedButMarketNotEnded_blocked() {
        PolymarketApiClient.MarketMetadata md = mock(PolymarketApiClient.MarketMetadata.class);
        lenient().when(md.isAcceptingOrders()).thenReturn(false);
        lenient().when(md.isResolved()).thenReturn(true);
        lenient().when(md.isClosed()).thenReturn(false);
        lenient().when(md.getUmaResolutionStatus()).thenReturn("resolved");
        lenient().when(md.getSlug()).thenReturn("test-market");

        SettlementTerminalCheck check = service.settlementTerminalCheck(md, /*marketEnded*/ false);

        assertFalse(check.canSettle(),
                "UMA resolved 但 marketEnded=false 必须被新闸门拦下");
        assertEquals("early_settlement_blocked_market_not_ended", check.reason());
    }

    @Test
    void finalPriceInferenceButMarketNotEnded_blocked() {
        PolymarketApiClient.MarketMetadata md = mock(PolymarketApiClient.MarketMetadata.class);
        lenient().when(md.isAcceptingOrders()).thenReturn(false);
        lenient().when(md.isResolved()).thenReturn(true);
        lenient().when(md.isClosed()).thenReturn(false);
        lenient().when(md.getResolutionSource()).thenReturn("FINAL_PRICE_INFERENCE");
        lenient().when(md.getSlug()).thenReturn("test-market");

        SettlementTerminalCheck check = service.settlementTerminalCheck(md, /*marketEnded*/ false);

        assertFalse(check.canSettle(),
                "FINAL_PRICE_INFERENCE 但 marketEnded=false 必须被新闸门拦下");
        assertEquals("early_settlement_blocked_market_not_ended", check.reason());
    }

    @Test
    void winningTokenAndMarketEnded_allowed() {
        PolymarketApiClient.MarketMetadata md = mock(PolymarketApiClient.MarketMetadata.class);
        lenient().when(md.isAcceptingOrders()).thenReturn(false);
        lenient().when(md.isResolved()).thenReturn(true);
        lenient().when(md.isClosed()).thenReturn(false);
        lenient().when(md.getWinningTokenId()).thenReturn("token-yes");
        lenient().when(md.getSlug()).thenReturn("test-market");
        lenient().when(md.getUmaResolutionStatus()).thenReturn(null);

        SettlementTerminalCheck check = service.settlementTerminalCheck(md, /*marketEnded*/ true);

        assertTrue(check.canSettle(),
                "winningTokenId + acceptingOrders=false + marketEnded=true 仍然允许结算");
        assertEquals("winning_token_accepting_orders_false", check.reason());
    }

    @Test
    void endedAndClosed_allowed() {
        PolymarketApiClient.MarketMetadata md = mock(PolymarketApiClient.MarketMetadata.class);
        lenient().when(md.isAcceptingOrders()).thenReturn(false);
        lenient().when(md.isClosed()).thenReturn(true);
        lenient().when(md.getSlug()).thenReturn("test-market");

        SettlementTerminalCheck check = service.settlementTerminalCheck(md, /*marketEnded*/ true);

        assertTrue(check.canSettle());
        assertEquals("ended_and_closed", check.reason());
    }

    @Test
    void stillAcceptingOrders_blocked() {
        PolymarketApiClient.MarketMetadata md = mock(PolymarketApiClient.MarketMetadata.class);
        lenient().when(md.isAcceptingOrders()).thenReturn(true);
        lenient().when(md.getSlug()).thenReturn("test-market");

        SettlementTerminalCheck check = service.settlementTerminalCheck(md, /*marketEnded*/ true);

        assertFalse(check.canSettle());
        assertEquals("market_still_accepting_orders", check.reason());
    }
}
