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

    /**
     * 2026-05-30 修复回归用例（见 docs/problem/订单未结算根因排查-2026-05-30.md）：
     * 体育/赛事类 market 的"四指标全终结"状态（closed=true && !acceptingOrders &&
     * resolved=true && winningTokenId 非空），即便 Polymarket endAt 尚未到达（marketEnded=false），
     * 也应允许结算。修复前会被 d4302c90 引入的 !marketEnded 硬闸误判为"提前结算"，
     * 导致 18 笔 OPEN 仓位永久卡死、paper-book 余额透支。
     */
    @Test
    void oracleTerminalAllSignalsSet_allowed_evenIfMarketNotEnded() {
        PolymarketApiClient.MarketMetadata md = mock(PolymarketApiClient.MarketMetadata.class);
        lenient().when(md.isClosed()).thenReturn(true);
        lenient().when(md.isAcceptingOrders()).thenReturn(false);
        lenient().when(md.isResolved()).thenReturn(true);
        lenient().when(md.getWinningTokenId()).thenReturn("token-yes");
        lenient().when(md.getSlug()).thenReturn("test-market");
        lenient().when(md.getUmaResolutionStatus()).thenReturn(null);

        SettlementTerminalCheck check = service.settlementTerminalCheck(md, /*marketEnded*/ false);

        assertTrue(check.canSettle(),
                "四指标全终结（closed/!acceptingOrders/resolved/winningTokenId 非空）应放行，"
                        + "即便 Polymarket endAt 未到——修复体育/赛事类被永久卡住的回归。");
        assertEquals("oracle_terminal_all_signals_set", check.reason());
    }

    /**
     * 2026-05-30 修复回归用例：四指标缺一不可——resolved=false 时（d4302c90 当年防的
     * "单一抖动"场景），即便 closed/acceptingOrders/winningTokenId 都到位，新短路也不放行，
     * 仍由 !marketEnded 硬闸拦下。
     */
    @Test
    void oracleTerminal_resolvedFalse_stillBlocked() {
        PolymarketApiClient.MarketMetadata md = mock(PolymarketApiClient.MarketMetadata.class);
        lenient().when(md.isClosed()).thenReturn(true);
        lenient().when(md.isAcceptingOrders()).thenReturn(false);
        lenient().when(md.isResolved()).thenReturn(false);
        lenient().when(md.getWinningTokenId()).thenReturn("token-yes");
        lenient().when(md.getSlug()).thenReturn("test-market");

        SettlementTerminalCheck check = service.settlementTerminalCheck(md, /*marketEnded*/ false);

        assertFalse(check.canSettle(),
                "resolved=false 不构成\"四指标全终结\"，新短路不放行，仍被 !marketEnded 硬闸拦下");
        assertEquals("early_settlement_blocked_market_not_ended", check.reason());
    }

    /**
     * 2026-05-30 修复回归用例：四指标缺一不可——winningTokenId 为空时，
     * 新短路不放行，仍由 !marketEnded 硬闸拦下。
     */
    @Test
    void oracleTerminal_winningTokenEmpty_stillBlocked() {
        PolymarketApiClient.MarketMetadata md = mock(PolymarketApiClient.MarketMetadata.class);
        lenient().when(md.isClosed()).thenReturn(true);
        lenient().when(md.isAcceptingOrders()).thenReturn(false);
        lenient().when(md.isResolved()).thenReturn(true);
        lenient().when(md.getWinningTokenId()).thenReturn("");
        lenient().when(md.getSlug()).thenReturn("test-market");
        lenient().when(md.getUmaResolutionStatus()).thenReturn(null);

        SettlementTerminalCheck check = service.settlementTerminalCheck(md, /*marketEnded*/ false);

        assertFalse(check.canSettle(),
                "winningTokenId 空不构成\"四指标全终结\"，新短路不放行，仍被 !marketEnded 硬闸拦下");
        assertEquals("early_settlement_blocked_market_not_ended", check.reason());
    }

    // ───────────── A1 争议期禁止结算守卫（2026-06-05 补充场景库：结算反转防线）─────────────

    /**
     * UMA 争议中的市场，即便 ended+closed+resolved+winningTokenId 全到位（本会按 ended_and_closed
     * 放行），也必须被争议守卫拦下，避免按可能翻盘的 outcome 误结算 + 虚增可用资金。
     */
    @Test
    void disputedMarket_blockedEvenIfEndedAndClosed() {
        PolymarketApiClient.MarketMetadata md = mock(PolymarketApiClient.MarketMetadata.class);
        lenient().when(md.getSlug()).thenReturn("test-market");
        lenient().when(md.isClosed()).thenReturn(true);
        lenient().when(md.isAcceptingOrders()).thenReturn(false);
        lenient().when(md.isResolved()).thenReturn(true);
        lenient().when(md.getWinningTokenId()).thenReturn("token-yes");
        lenient().when(md.isUmaDisputed()).thenReturn(true);

        SettlementTerminalCheck check = service.settlementTerminalCheck(md, /*marketEnded*/ true);

        assertFalse(check.canSettle(), "争议期市场即便 ended+closed 也不放行结算");
        assertEquals("blocked_uma_disputed", check.reason());
    }

    /** 开关关闭（blockSettlementWhileDisputed=false）时争议守卫不生效，回到既有 ended_and_closed。 */
    @Test
    void disputedMarket_flagOff_fallsThroughToEndedAndClosed() {
        SmartWalletProperties props = new SmartWalletProperties();
        props.setBlockSettlementWhileDisputed(false);
        SmartWalletAccountingService svc = newServiceWithProps(props);

        PolymarketApiClient.MarketMetadata md = mock(PolymarketApiClient.MarketMetadata.class);
        lenient().when(md.getSlug()).thenReturn("test-market");
        lenient().when(md.isClosed()).thenReturn(true);
        lenient().when(md.isAcceptingOrders()).thenReturn(false);
        lenient().when(md.isUmaDisputed()).thenReturn(true);

        SettlementTerminalCheck check = svc.settlementTerminalCheck(md, /*marketEnded*/ true);

        assertTrue(check.canSettle(), "开关关闭时争议守卫旁路，按既有 ended_and_closed 放行");
        assertEquals("ended_and_closed", check.reason());
    }

    /** 非争议的 ended+closed 市场不受守卫影响，正常结算。 */
    @Test
    void notDisputed_endedAndClosed_settlesNormally() {
        PolymarketApiClient.MarketMetadata md = mock(PolymarketApiClient.MarketMetadata.class);
        lenient().when(md.getSlug()).thenReturn("test-market");
        lenient().when(md.isClosed()).thenReturn(true);
        lenient().when(md.isAcceptingOrders()).thenReturn(false);
        lenient().when(md.isUmaDisputed()).thenReturn(false);

        SettlementTerminalCheck check = service.settlementTerminalCheck(md, /*marketEnded*/ true);

        assertTrue(check.canSettle(), "非争议 ended+closed 正常结算");
        assertEquals("ended_and_closed", check.reason());
    }

    // ───────────── C4 坍塌归零结算坏 tick 防护（2026-06-05 补充场景库）─────────────

    @Test
    void c4_recentTradeContradictsCollapse_blocked() {
        // 近窗口成交最高价 0.30 > 阈值 0.15 → 与坍塌矛盾，应阻断归零结算
        assertTrue(SmartWalletAccountingService.isWorthlessSettleContradicted(
                new java.math.BigDecimal("0.30"), new java.math.BigDecimal("0.15")));
    }

    @Test
    void c4_recentTradeAlsoLow_notBlocked() {
        // 近窗口成交也低（0.04 ≤ 0.15）→ 印证坍塌，不阻断
        assertFalse(SmartWalletAccountingService.isWorthlessSettleContradicted(
                new java.math.BigDecimal("0.04"), new java.math.BigDecimal("0.15")));
    }

    @Test
    void c4_noRecentTradeOrBadCeil_notBlocked() {
        // 无近成交 → 无矛盾证据，不阻断（沿用既有「无 bid=坍塌」语义）
        assertFalse(SmartWalletAccountingService.isWorthlessSettleContradicted(
                null, new java.math.BigDecimal("0.15")));
        // 阈值非法 → 不阻断
        assertFalse(SmartWalletAccountingService.isWorthlessSettleContradicted(
                new java.math.BigDecimal("0.30"), java.math.BigDecimal.ZERO));
    }

    private SmartWalletAccountingService newServiceWithProps(SmartWalletProperties props) {
        return new SmartWalletAccountingService(
                orderMapper, fillMapper, positionMapper, cashLedgerMapper, dailyReportMapper,
                tradeEventMapper, followTaskMapper, marketMetadataService,
                new SmartWalletTimeFormatter(), props,
                followTaskServiceProvider, marketInfoService, marketStateService,
                marketLinkService, tradeOrderServiceProvider, feeScheduleService,
                marketDataCacheService);
    }
}
