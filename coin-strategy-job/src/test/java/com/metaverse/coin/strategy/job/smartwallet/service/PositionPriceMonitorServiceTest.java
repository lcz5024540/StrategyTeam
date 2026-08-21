package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletMarketInfoService;
import com.metaverse.coin.strategy.job.engine.monitor.AlertType;
import com.metaverse.coin.strategy.job.smartwallet.audit.SmartWalletAuditAlertSink;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单元测试覆盖:
 * <ul>
 *   <li>三档 entry 价位 → tier 间隔</li>
 *   <li>tier 间隔未到不刷新</li>
 *   <li>bids 空触发 WARN + auditAlertSink.publish</li>
 *   <li>bids 非空只刷新不告警</li>
 *   <li>同仓位告警冷却生效</li>
 *   <li>enabled=false 整体跳过</li>
 *   <li>fetchOrderBook 失败不更新 lastRefreshedAt (允许下轮重试)</li>
 * </ul>
 */
class PositionPriceMonitorServiceTest {

    private SmartWalletProperties properties;
    private SmartWalletProperties.PositionPriceMonitor cfg;
    private SmartWalletPositionMapper positionMapper;
    private SmartWalletMarketInfoService marketInfoService;
    private PolymarketMarketDataCacheService marketDataCacheService;
    private SmartWalletAuditAlertSink auditAlertSink;
    private PolymarketClobExecutionService clobExecutionService;
    private SmartWalletOrderMapper orderMapper;

    private PositionPriceMonitorService service;

    @BeforeEach
    void setUp() {
        properties = new SmartWalletProperties();
        cfg = properties.getPositionPriceMonitor();
        cfg.setEnabled(true);
        cfg.setTierLowPrice(new BigDecimal("0.10"));
        cfg.setTierMidPrice(new BigDecimal("0.30"));
        cfg.setLowTierIntervalSeconds(120);
        cfg.setMidTierIntervalSeconds(600);
        cfg.setHighTierIntervalSeconds(1800);
        cfg.setEmptyBidAlertEnabled(true);
        cfg.setAlertCooldownSeconds(1800);
        cfg.setRecentAlertsBufferSize(50);
        // D2: 默认 2, 测试里改成 1 让大多数现有测试单次即触发
        cfg.setConsecutiveEmptyBidThreshold(1);
        cfg.setAutoExitMaxReferencePrice(new BigDecimal("0.05"));
        cfg.setAutoExitFailureRetrySeconds(120);
        cfg.setAutoExitFailureMaxRetries(3);
        cfg.setAutoExitReplaceExistingOrder(true);

        positionMapper = mock(SmartWalletPositionMapper.class);
        marketInfoService = mock(SmartWalletMarketInfoService.class);
        marketDataCacheService = mock(PolymarketMarketDataCacheService.class);
        auditAlertSink = mock(SmartWalletAuditAlertSink.class);
        clobExecutionService = mock(PolymarketClobExecutionService.class);
        orderMapper = mock(SmartWalletOrderMapper.class);

        service = new PositionPriceMonitorService(
                properties, positionMapper, marketInfoService, marketDataCacheService);
        // sink / clob / orderMapper 都是 @Autowired(required=false) 字段, 测试里通过反射注入
        injectField(service, "auditAlertSink", auditAlertSink);
        injectField(service, "clobExecutionService", clobExecutionService);
        injectField(service, "orderMapper", orderMapper);
        // 默认: orderMapper 返回空, 没有活跃 SELL
        when(orderMapper.selectList(any())).thenReturn(new ArrayList<>());
    }

    // ─── tier 间隔解析 ─────────────────────────────────────────────────────

    @Test
    void resolveTierIntervalSeconds_lowPriceTier() {
        assertEquals(120, service.resolveTierIntervalSeconds(positionWithMark("0.50", "0.05"), cfg));
        assertEquals(120, service.resolveTierIntervalSeconds(positionWithEntry("0.09"), cfg));
    }

    @Test
    void resolveTierIntervalSeconds_midPriceTier() {
        // mark == tierLowPrice (0.10) 已经进入 mid
        assertEquals(600, service.resolveTierIntervalSeconds(positionWithEntry("0.10"), cfg));
        assertEquals(600, service.resolveTierIntervalSeconds(positionWithEntry("0.29"), cfg));
    }

    @Test
    void resolveTierIntervalSeconds_highPriceTier() {
        assertEquals(1800, service.resolveTierIntervalSeconds(positionWithEntry("0.30"), cfg));
        assertEquals(1800, service.resolveTierIntervalSeconds(positionWithEntry("0.95"), cfg));
    }

    @Test
    void resolveTierIntervalSeconds_nullPositionFallsToHighTier() {
        assertEquals(1800, service.resolveTierIntervalSeconds(null, cfg));
    }

    @Test
    void resolveTierIntervalSeconds_markPriceOverridesEntry() {
        // C: entry=0.50 但 mark=0.05 → 应当落到低价档 (2min), 而不是高价档
        assertEquals(120, service.resolveTierIntervalSeconds(positionWithMark("0.50", "0.05"), cfg));
        // 反过来: entry=0.05 但 mark=0.40 → mark 主导, 走高价档
        assertEquals(1800, service.resolveTierIntervalSeconds(positionWithMark("0.05", "0.40"), cfg));
        // mark 为 null 时退回 entry
        assertEquals(120, service.resolveTierIntervalSeconds(positionWithEntry("0.05"), cfg));
        // mark 为 0 (无效) 时也退回 entry
        assertEquals(120, service.resolveTierIntervalSeconds(positionWithMark("0.05", "0"), cfg));
    }

    // ─── processOne 主要路径 ───────────────────────────────────────────────

    @Test
    void processOne_triggersAlertWhenBidsEmpty() {
        SmartWalletPositionEntity p = position(1L, "0.05", "tok-1");
        Instant now = Instant.parse("2026-05-28T16:00:00Z");
        stubEmptyBidBook("tok-1");

        PositionPriceMonitorService.ScanOutcome outcome = service.processOne(p, cfg, now);

        assertEquals(PositionPriceMonitorService.ScanOutcome.ALERT_TRIGGERED, outcome);
        verify(auditAlertSink, times(1)).publish(
                eq(AlertType.POSITION_EMPTY_BID_BOOK), anyString(), anyString(), any(), any());
        // 内存 ring buffer 应留下一条
        assertEquals(1, service.recentAlerts().size());
        assertEquals(1L, service.recentAlerts().get(0).getPositionId());
    }

    @Test
    void processOne_doesNotAlertWhenBidsPresent() {
        SmartWalletPositionEntity p = position(2L, "0.05", "tok-2");
        Instant now = Instant.parse("2026-05-28T16:00:00Z");
        stubBookWithBids("tok-2", new BigDecimal("0.04"), new BigDecimal("0.06"));

        PositionPriceMonitorService.ScanOutcome outcome = service.processOne(p, cfg, now);

        assertEquals(PositionPriceMonitorService.ScanOutcome.REFRESHED, outcome);
        verify(auditAlertSink, never()).publish(any(AlertType.class), anyString(), anyString(), any(), any());
        assertTrue(service.recentAlerts().isEmpty());
    }

    @Test
    void processOne_skipsWhenWithinTierCooldown() {
        SmartWalletPositionEntity p = position(3L, "0.05", "tok-3");
        // 低价档 = 120s; t0 触发, t0+60s 再进入应当被跳过
        Instant t0 = Instant.parse("2026-05-28T16:00:00Z");
        stubBookWithBids("tok-3", new BigDecimal("0.04"), new BigDecimal("0.06"));
        service.processOne(p, cfg, t0);

        PositionPriceMonitorService.ScanOutcome outcome = service.processOne(p, cfg, t0.plusSeconds(60));

        assertEquals(PositionPriceMonitorService.ScanOutcome.SKIPPED_COOLDOWN, outcome);
        // marketDataCacheService.getOrderBook 应只被调用 1 次 (第一次), 第二次被冷却跳过
        verify(marketDataCacheService, times(1)).getOrderBook("tok-3");
    }

    @Test
    void processOne_alertCooldownPreventsDoublePublish() {
        SmartWalletPositionEntity p = position(4L, "0.05", "tok-4");
        Instant t0 = Instant.parse("2026-05-28T16:00:00Z");
        stubEmptyBidBook("tok-4");

        // t0: 触发告警
        service.processOne(p, cfg, t0);
        // 把 lastRefreshedAt 推进过 tier 间隔, 但 alertCooldown 仍未过
        Instant t1 = t0.plusSeconds(cfg.getLowTierIntervalSeconds() + 1);
        PositionPriceMonitorService.ScanOutcome outcome = service.processOne(p, cfg, t1);

        // 第二次扫描刷新成功 (orderbook 重新拉了一次), 但不再发告警
        assertEquals(PositionPriceMonitorService.ScanOutcome.REFRESHED, outcome);
        verify(auditAlertSink, times(1)).publish(
                eq(AlertType.POSITION_EMPTY_BID_BOOK), anyString(), anyString(), any(), any());
    }

    @Test
    void processOne_skipsWhenNoTokenId() {
        SmartWalletPositionEntity p = position(5L, "0.05", null);

        PositionPriceMonitorService.ScanOutcome outcome = service.processOne(p, cfg, Instant.now());

        assertEquals(PositionPriceMonitorService.ScanOutcome.SKIPPED_NO_TOKEN, outcome);
    }

    @Test
    void processOne_fetchFailureDoesNotUpdateLastRefreshedAt() {
        SmartWalletPositionEntity p = position(6L, "0.05", "tok-6");
        Instant t0 = Instant.parse("2026-05-28T16:00:00Z");
        when(marketDataCacheService.getOrderBook("tok-6")).thenThrow(new RuntimeException("api 500"));

        PositionPriceMonitorService.ScanOutcome outcome = service.processOne(p, cfg, t0);
        assertEquals(PositionPriceMonitorService.ScanOutcome.FETCH_FAILED, outcome);

        // 下一轮立刻重试: 即便距 t0 只有 1s, 也应该尝试 (因为 lastRefreshedAt 未被更新)
        stubEmptyBidBook("tok-6");
        PositionPriceMonitorService.ScanOutcome retry = service.processOne(p, cfg, t0.plusSeconds(1));
        assertEquals(PositionPriceMonitorService.ScanOutcome.ALERT_TRIGGERED, retry);
    }

    // ─── scan() 全局开关 ───────────────────────────────────────────────────

    @Test
    void scan_doesNothingWhenDisabled() {
        cfg.setEnabled(false);
        service.scan();
        verify(positionMapper, never()).selectList(any());
    }

    // ─── A: 自动减仓三条件 AND 触发 ────────────────────────────────────────

    @Test
    void autoExit_skipsWhenDisabled() {
        // 默认 cfg.autoExitEnabled=false
        SmartWalletPositionEntity p = autoExitCandidate(11L, "0.03", minutesAgo(120), "live-1");
        PositionPriceMonitorService.AutoExitOutcome outcome =
                service.maybeTriggerAutoExit(p, cfg, Instant.now());
        assertEquals(PositionPriceMonitorService.AutoExitOutcome.SKIP_DISABLED, outcome);
        verify(clobExecutionService, never()).executeLiveExitAtForcedPrice(any(), any(), anyString());
    }

    @Test
    void autoExit_skipsWhenEntryAboveThreshold() {
        cfg.setAutoExitEnabled(true);
        cfg.setAutoExitLiveEnabled(true);
        // entry = 0.05 (>= autoExitMaxReferencePrice 0.05, 不严格 <)
        SmartWalletPositionEntity p = autoExitCandidate(12L, "0.05", minutesAgo(120), "live-1");
        PositionPriceMonitorService.AutoExitOutcome outcome =
                service.maybeTriggerAutoExit(p, cfg, Instant.now());
        assertEquals(PositionPriceMonitorService.AutoExitOutcome.SKIP_HIGH_ENTRY, outcome);
        verify(clobExecutionService, never()).executeLiveExitAtForcedPrice(any(), any(), anyString());
    }

    @Test
    void autoExit_D1_markPriceOverridesEntry_lowMarkTriggers() {
        cfg.setAutoExitEnabled(true);
        cfg.setAutoExitLiveEnabled(true);
        // D1: entry=0.30 但 mark=0.02 → reference=0.02 < 0.05 → 触发候选(其他条件具备时下单)
        SmartWalletPositionEntity p = autoExitCandidate(120L, "0.30", minutesAgo(120), "live-wallet");
        p.setLastMarkPrice(new BigDecimal("0.02"));
        when(clobExecutionService.executeLiveExitAtForcedPrice(eq(p), eq(cfg.getAutoExitTickFloorPrice()),
                eq("EMPTY_BID_AUTO_EXIT")))
                .thenReturn(SmartWalletExecutionResult.builder().success(true)
                        .externalOrderId("o-d1").status("PLACED").build());

        PositionPriceMonitorService.AutoExitOutcome outcome =
                service.maybeTriggerAutoExit(p, cfg, Instant.now());

        assertEquals(PositionPriceMonitorService.AutoExitOutcome.LIVE_ORDER_PLACED, outcome);
    }

    @Test
    void autoExit_D1_highMarkBlocksEvenIfEntryWasLow() {
        cfg.setAutoExitEnabled(true);
        cfg.setAutoExitLiveEnabled(true);
        // entry=0.03 但 mark=0.40 → reference=0.40 ≥ 0.05 → SKIP_HIGH_ENTRY
        SmartWalletPositionEntity p = autoExitCandidate(121L, "0.03", minutesAgo(120), "live-wallet");
        p.setLastMarkPrice(new BigDecimal("0.40"));

        PositionPriceMonitorService.AutoExitOutcome outcome =
                service.maybeTriggerAutoExit(p, cfg, Instant.now());

        assertEquals(PositionPriceMonitorService.AutoExitOutcome.SKIP_HIGH_ENTRY, outcome);
        verify(clobExecutionService, never()).executeLiveExitAtForcedPrice(any(), any(), anyString());
    }

    @Test
    void autoExit_skipsWhenHoldingTooShort() {
        cfg.setAutoExitEnabled(true);
        cfg.setAutoExitLiveEnabled(true);
        // 默认 autoExitMinHoldingMinutes=60, 仅持有 30min 不满足
        SmartWalletPositionEntity p = autoExitCandidate(13L, "0.03", minutesAgo(30), "live-1");
        PositionPriceMonitorService.AutoExitOutcome outcome =
                service.maybeTriggerAutoExit(p, cfg, Instant.now());
        assertEquals(PositionPriceMonitorService.AutoExitOutcome.SKIP_INSUFFICIENT_HOLDING, outcome);
        verify(clobExecutionService, never()).executeLiveExitAtForcedPrice(any(), any(), anyString());
    }

    @Test
    void autoExit_paperModeLogsButDoesNotExecute() {
        cfg.setAutoExitEnabled(true);
        // executionAddress 含 "paper" → PAPER 模式
        SmartWalletPositionEntity p = autoExitCandidate(14L, "0.03", minutesAgo(120), "paper-book-1");
        PositionPriceMonitorService.AutoExitOutcome outcome =
                service.maybeTriggerAutoExit(p, cfg, Instant.now());
        assertEquals(PositionPriceMonitorService.AutoExitOutcome.PAPER_LOGGED, outcome);
        verify(clobExecutionService, never()).executeLiveExitAtForcedPrice(any(), any(), anyString());
    }

    @Test
    void autoExit_liveModeBlockedByLiveDisabled() {
        cfg.setAutoExitEnabled(true);
        cfg.setAutoExitLiveEnabled(false); // 二级开关关
        SmartWalletPositionEntity p = autoExitCandidate(15L, "0.03", minutesAgo(120), "live-wallet");
        PositionPriceMonitorService.AutoExitOutcome outcome =
                service.maybeTriggerAutoExit(p, cfg, Instant.now());
        assertEquals(PositionPriceMonitorService.AutoExitOutcome.SKIP_LIVE_DISABLED, outcome);
        verify(clobExecutionService, never()).executeLiveExitAtForcedPrice(any(), any(), anyString());
    }

    @Test
    void autoExit_liveExecutesWhenAllConditionsMet() {
        cfg.setAutoExitEnabled(true);
        cfg.setAutoExitLiveEnabled(true);
        SmartWalletPositionEntity p = autoExitCandidate(16L, "0.03", minutesAgo(120), "live-wallet");
        when(clobExecutionService.executeLiveExitAtForcedPrice(eq(p), eq(cfg.getAutoExitTickFloorPrice()),
                eq("EMPTY_BID_AUTO_EXIT")))
                .thenReturn(SmartWalletExecutionResult.builder()
                        .success(true).externalOrderId("ord-xyz").status("PLACED").build());

        PositionPriceMonitorService.AutoExitOutcome outcome =
                service.maybeTriggerAutoExit(p, cfg, Instant.now());

        assertEquals(PositionPriceMonitorService.AutoExitOutcome.LIVE_ORDER_PLACED, outcome);
        verify(clobExecutionService, times(1)).executeLiveExitAtForcedPrice(eq(p),
                eq(cfg.getAutoExitTickFloorPrice()), eq("EMPTY_BID_AUTO_EXIT"));
    }

    @Test
    void autoExit_B2_skipsWhenExistingActiveSellMatches() {
        cfg.setAutoExitEnabled(true);
        cfg.setAutoExitLiveEnabled(true);
        SmartWalletPositionEntity p = autoExitCandidate(160L, "0.03", minutesAgo(120), "live-wallet");
        SmartWalletOrderEntity existing = activeSell(p.getId(), "old-ord-1",
                cfg.getAutoExitTickFloorPrice(), p.getQuantity());
        when(orderMapper.selectList(any())).thenReturn(List.of(existing));

        PositionPriceMonitorService.AutoExitOutcome outcome =
                service.maybeTriggerAutoExit(p, cfg, Instant.now());

        assertEquals(PositionPriceMonitorService.AutoExitOutcome.LIVE_ORDER_SKIPPED_MATCHING_ACTIVE, outcome);
        verify(clobExecutionService, never()).executeLiveExitAtForcedPrice(any(), any(), anyString());
        verify(clobExecutionService, never()).cancelOrder(anyString());
    }

    @Test
    void autoExit_B2_cancelsAndReplacesWhenSizeMismatches() {
        cfg.setAutoExitEnabled(true);
        cfg.setAutoExitLiveEnabled(true);
        SmartWalletPositionEntity p = autoExitCandidate(161L, "0.03", minutesAgo(120), "live-wallet");
        // 旧单 size 与当前 quantity 不一致
        SmartWalletOrderEntity stale = activeSell(p.getId(), "stale-ord-1",
                cfg.getAutoExitTickFloorPrice(), new BigDecimal("50"));
        when(orderMapper.selectList(any())).thenReturn(List.of(stale));
        when(clobExecutionService.executeLiveExitAtForcedPrice(eq(p), eq(cfg.getAutoExitTickFloorPrice()),
                eq("EMPTY_BID_AUTO_EXIT")))
                .thenReturn(SmartWalletExecutionResult.builder().success(true)
                        .externalOrderId("new-ord-1").status("PLACED").build());

        PositionPriceMonitorService.AutoExitOutcome outcome =
                service.maybeTriggerAutoExit(p, cfg, Instant.now());

        assertEquals(PositionPriceMonitorService.AutoExitOutcome.LIVE_ORDER_REPLACED, outcome);
        verify(clobExecutionService, times(1)).cancelOrder("stale-ord-1");
        verify(clobExecutionService, times(1)).executeLiveExitAtForcedPrice(eq(p),
                eq(cfg.getAutoExitTickFloorPrice()), eq("EMPTY_BID_AUTO_EXIT"));
    }

    @Test
    void autoExit_B2_replaceDisabled_skipsWhenAnyActiveSell() {
        cfg.setAutoExitEnabled(true);
        cfg.setAutoExitLiveEnabled(true);
        cfg.setAutoExitReplaceExistingOrder(false);
        SmartWalletPositionEntity p = autoExitCandidate(162L, "0.03", minutesAgo(120), "live-wallet");
        SmartWalletOrderEntity stale = activeSell(p.getId(), "stale-ord-2",
                new BigDecimal("0.99"), new BigDecimal("50"));
        when(orderMapper.selectList(any())).thenReturn(List.of(stale));

        PositionPriceMonitorService.AutoExitOutcome outcome =
                service.maybeTriggerAutoExit(p, cfg, Instant.now());

        assertEquals(PositionPriceMonitorService.AutoExitOutcome.LIVE_ORDER_SKIPPED_MATCHING_ACTIVE, outcome);
        verify(clobExecutionService, never()).cancelOrder(anyString());
    }

    @Test
    void autoExit_E1_shortRetryCooldownAfterFailure() {
        cfg.setAutoExitEnabled(true);
        cfg.setAutoExitLiveEnabled(true);
        cfg.setAutoExitFailureRetrySeconds(120);
        SmartWalletPositionEntity p = autoExitCandidate(170L, "0.03", minutesAgo(120), "live-wallet");
        when(clobExecutionService.executeLiveExitAtForcedPrice(any(), any(), anyString()))
                .thenReturn(SmartWalletExecutionResult.builder().success(false).status("FAILED").build());

        Instant t0 = Instant.now();
        // 第一次: 失败, 标记 LIVE_ORDER_FAILED
        assertEquals(PositionPriceMonitorService.AutoExitOutcome.LIVE_ORDER_FAILED,
                service.maybeTriggerAutoExit(p, cfg, t0));
        // 60s 后: 仍在 120s retry 窗口里, 应当 SKIP_RETRY_COOLDOWN
        assertEquals(PositionPriceMonitorService.AutoExitOutcome.SKIP_RETRY_COOLDOWN,
                service.maybeTriggerAutoExit(p, cfg, t0.plusSeconds(60)));
        // 130s 后: 超过 120s, 再次尝试 (仍配置成失败)
        assertEquals(PositionPriceMonitorService.AutoExitOutcome.LIVE_ORDER_FAILED,
                service.maybeTriggerAutoExit(p, cfg, t0.plusSeconds(130)));
    }

    @Test
    void autoExit_D2_consecutiveEmptyBidGate() {
        // D2: 把阈值改回 2, 验证单次 empty bid 不触发, 第二次才触发
        cfg.setConsecutiveEmptyBidThreshold(2);
        SmartWalletPositionEntity p = position(180L, "0.03", "tok-180");
        Instant t0 = Instant.parse("2026-05-28T16:00:00Z");
        stubEmptyBidBook("tok-180");

        // 第一次: 仅累计计数, 不告警不动手
        PositionPriceMonitorService.ScanOutcome first = service.processOne(p, cfg, t0);
        assertEquals(PositionPriceMonitorService.ScanOutcome.REFRESHED, first);
        verify(auditAlertSink, never()).publish(any(AlertType.class), anyString(), anyString(), any(), any());

        // 第二次: 计数到 2, 触发
        PositionPriceMonitorService.ScanOutcome second = service.processOne(p, cfg,
                t0.plusSeconds(cfg.getLowTierIntervalSeconds() + 1));
        assertEquals(PositionPriceMonitorService.ScanOutcome.ALERT_TRIGGERED, second);
        verify(auditAlertSink, times(1)).publish(
                eq(AlertType.POSITION_EMPTY_BID_BOOK), anyString(), anyString(), any(), any());
    }

    @Test
    void autoExit_D2_nonEmptyBidResetsCounter() {
        cfg.setConsecutiveEmptyBidThreshold(2);
        SmartWalletPositionEntity p = position(181L, "0.03", "tok-181");
        Instant t0 = Instant.parse("2026-05-28T16:00:00Z");

        // 第一次空 bid
        stubEmptyBidBook("tok-181");
        assertEquals(PositionPriceMonitorService.ScanOutcome.REFRESHED, service.processOne(p, cfg, t0));

        // 第二次盘口恢复, 计数清零
        stubBookWithBids("tok-181", new BigDecimal("0.02"), new BigDecimal("0.06"));
        Instant t1 = t0.plusSeconds(cfg.getLowTierIntervalSeconds() + 1);
        assertEquals(PositionPriceMonitorService.ScanOutcome.REFRESHED, service.processOne(p, cfg, t1));

        // 第三次再次空: 仅累计 1 次, 不应触发
        stubEmptyBidBook("tok-181");
        Instant t2 = t1.plusSeconds(cfg.getLowTierIntervalSeconds() + 1);
        assertEquals(PositionPriceMonitorService.ScanOutcome.REFRESHED, service.processOne(p, cfg, t2));
        verify(auditAlertSink, never()).publish(any(AlertType.class), anyString(), anyString(), any(), any());
    }

    @Test
    void autoExit_E1_endToEndShortToLongCooldownTransition() {
        // 端到端: 连续 3 次 LIVE_ORDER_FAILED 用尽 maxRetries=3, 第 4 次失败后, 短 cooldown 切到长 cooldown,
        // 此后 alertCooldownSeconds 之内任何尝试都应当 SKIP_RETRY_COOLDOWN, 直到长 cooldown 到期才允许重试。
        cfg.setAutoExitEnabled(true);
        cfg.setAutoExitLiveEnabled(true);
        cfg.setAutoExitFailureRetrySeconds(120);     // 短 cooldown
        cfg.setAutoExitFailureMaxRetries(3);
        cfg.setAlertCooldownSeconds(1800);           // 长 cooldown
        SmartWalletPositionEntity p = autoExitCandidate(200L, "0.03", minutesAgo(120), "live-wallet");
        when(clobExecutionService.executeLiveExitAtForcedPrice(any(), any(), anyString()))
                .thenReturn(SmartWalletExecutionResult.builder().success(false).status("FAILED").build());

        Instant t0 = Instant.now();
        // 失败 1, streak=1
        assertEquals(PositionPriceMonitorService.AutoExitOutcome.LIVE_ORDER_FAILED,
                service.maybeTriggerAutoExit(p, cfg, t0));
        // +121s: 短 cooldown 已过, 再试 → 失败 2, streak=2
        assertEquals(PositionPriceMonitorService.AutoExitOutcome.LIVE_ORDER_FAILED,
                service.maybeTriggerAutoExit(p, cfg, t0.plusSeconds(121)));
        // +242s: 再过短 cooldown → 失败 3, streak=3 (= maxRetries, 下一次切长 cooldown)
        assertEquals(PositionPriceMonitorService.AutoExitOutcome.LIVE_ORDER_FAILED,
                service.maybeTriggerAutoExit(p, cfg, t0.plusSeconds(242)));
        // streak 已 ≥ maxRetries: 现在判定窗口切到 alertCooldownSeconds=1800s
        // +362s (距最近失败 120s): 长 cooldown 内, SKIP_RETRY_COOLDOWN
        assertEquals(PositionPriceMonitorService.AutoExitOutcome.SKIP_RETRY_COOLDOWN,
                service.maybeTriggerAutoExit(p, cfg, t0.plusSeconds(362)));
        // +1200s (距最近失败 958s): 仍在长 cooldown 内
        assertEquals(PositionPriceMonitorService.AutoExitOutcome.SKIP_RETRY_COOLDOWN,
                service.maybeTriggerAutoExit(p, cfg, t0.plusSeconds(1200)));
        // +2050s (距最近失败 1808s > 1800s): 长 cooldown 已过, 允许重试
        assertEquals(PositionPriceMonitorService.AutoExitOutcome.LIVE_ORDER_FAILED,
                service.maybeTriggerAutoExit(p, cfg, t0.plusSeconds(2050)));

        // 验证 activeFailureStreaks 暴露了该 positionId
        Map<Long, Integer> streaks = service.activeFailureStreaks();
        assertTrue(streaks.containsKey(200L), "Failure streak map should expose stuck positions");
        assertTrue(streaks.get(200L) >= 3, "streak should be >= maxRetries after long cooldown");
    }

    @Test
    void autoExit_E1_streakClearsOnSuccess() {
        // 失败几次 → 突然 success → streak 应当被清空 → 下一次失败重新从 short cooldown 开始
        cfg.setAutoExitEnabled(true);
        cfg.setAutoExitLiveEnabled(true);
        cfg.setAutoExitFailureRetrySeconds(120);
        cfg.setAutoExitFailureMaxRetries(3);
        SmartWalletPositionEntity p = autoExitCandidate(201L, "0.03", minutesAgo(120), "live-wallet");
        when(clobExecutionService.executeLiveExitAtForcedPrice(any(), any(), anyString()))
                .thenReturn(SmartWalletExecutionResult.builder().success(false).status("FAILED").build())
                .thenReturn(SmartWalletExecutionResult.builder().success(true).status("PLACED").externalOrderId("ok").build())
                .thenReturn(SmartWalletExecutionResult.builder().success(false).status("FAILED").build());

        Instant t0 = Instant.now();
        // 失败 1
        assertEquals(PositionPriceMonitorService.AutoExitOutcome.LIVE_ORDER_FAILED,
                service.maybeTriggerAutoExit(p, cfg, t0));
        // +121s: 成功, streak 清零
        assertEquals(PositionPriceMonitorService.AutoExitOutcome.LIVE_ORDER_PLACED,
                service.maybeTriggerAutoExit(p, cfg, t0.plusSeconds(121)));
        assertTrue(service.activeFailureStreaks().isEmpty(),
                "Success should clear failure streak");

        // 再立即失败: 因为 streak 已清零, 应当从短 cooldown 重新开始
        assertEquals(PositionPriceMonitorService.AutoExitOutcome.LIVE_ORDER_FAILED,
                service.maybeTriggerAutoExit(p, cfg, t0.plusSeconds(122)));
        // +200s 距上次失败 78s < 120s 短 cooldown
        assertEquals(PositionPriceMonitorService.AutoExitOutcome.SKIP_RETRY_COOLDOWN,
                service.maybeTriggerAutoExit(p, cfg, t0.plusSeconds(200)));
    }

    @Test
    void autoExit_B2_bestEffortCancel_attemptsAllEvenIfFirstFails() {
        // 验证 best-effort cancel: 第一条 cancel 抛异常, 但第二条仍被尝试
        cfg.setAutoExitEnabled(true);
        cfg.setAutoExitLiveEnabled(true);
        SmartWalletPositionEntity p = autoExitCandidate(210L, "0.03", minutesAgo(120), "live-wallet");
        SmartWalletOrderEntity stale1 = activeSell(p.getId(), "stale-a", new BigDecimal("0.5"), new BigDecimal("50"));
        SmartWalletOrderEntity stale2 = activeSell(p.getId(), "stale-b", new BigDecimal("0.3"), new BigDecimal("30"));
        when(orderMapper.selectList(any())).thenReturn(List.of(stale1, stale2));
        when(clobExecutionService.cancelOrder("stale-a")).thenThrow(new RuntimeException("api 502"));
        // stale-b cancel 应该仍被调用 (best-effort)
        when(clobExecutionService.cancelOrder("stale-b")).thenReturn(java.util.Map.of("ok", true));

        PositionPriceMonitorService.AutoExitOutcome outcome =
                service.maybeTriggerAutoExit(p, cfg, Instant.now());

        // 有 1 条 cancel 失败 → return LIVE_ORDER_FAILED, 不下新单
        assertEquals(PositionPriceMonitorService.AutoExitOutcome.LIVE_ORDER_FAILED, outcome);
        // 关键: 两条 cancel 都被尝试
        verify(clobExecutionService, times(1)).cancelOrder("stale-a");
        verify(clobExecutionService, times(1)).cancelOrder("stale-b");
        // 不下新单 (有任何 cancel 失败时)
        verify(clobExecutionService, never()).executeLiveExitAtForcedPrice(any(), any(), anyString());
    }

    @Test
    void autoExit_B2_scaleDifferenceMatched_skipsAsMatching() {
        // 验证 scale 不一致仍能 match: existing.price=0.0010 (scale=4), floor=0.001 (scale=3)
        // 应当视为相等 → SKIP_MATCHING_ACTIVE, 不 cancel 不重下
        cfg.setAutoExitEnabled(true);
        cfg.setAutoExitLiveEnabled(true);
        SmartWalletPositionEntity p = autoExitCandidate(220L, "0.03", minutesAgo(120), "live-wallet");
        // existing price 是 0.0010 (4 位 scale), quantity 是 100.0 (1 位 scale)
        SmartWalletOrderEntity existing = activeSell(p.getId(), "scale-ord",
                new BigDecimal("0.0010"), new BigDecimal("100.0"));
        when(orderMapper.selectList(any())).thenReturn(List.of(existing));

        PositionPriceMonitorService.AutoExitOutcome outcome =
                service.maybeTriggerAutoExit(p, cfg, Instant.now());

        assertEquals(PositionPriceMonitorService.AutoExitOutcome.LIVE_ORDER_SKIPPED_MATCHING_ACTIVE, outcome);
        verify(clobExecutionService, never()).cancelOrder(anyString());
        verify(clobExecutionService, never()).executeLiveExitAtForcedPrice(any(), any(), anyString());
    }

    @Test
    void autoExit_E2_countersTrackOutcomes() {
        cfg.setAutoExitEnabled(true);
        cfg.setAutoExitLiveEnabled(true);
        SmartWalletPositionEntity p = autoExitCandidate(190L, "0.03", minutesAgo(120), "live-wallet");
        when(clobExecutionService.executeLiveExitAtForcedPrice(any(), any(), anyString()))
                .thenReturn(SmartWalletExecutionResult.builder().success(true)
                        .externalOrderId("e2").status("PLACED").build());

        service.maybeTriggerAutoExit(p, cfg, Instant.now());

        var snap = service.autoExitOutcomeSnapshot();
        assertEquals(1L, snap.get(PositionPriceMonitorService.AutoExitOutcome.LIVE_ORDER_PLACED));
    }

    @Test
    void processOne_emptyBidWithPaperAutoExit_recordsTagInRingBuffer() {
        cfg.setAutoExitEnabled(true);
        SmartWalletPositionEntity p = autoExitCandidate(17L, "0.03", minutesAgo(120), "paper-book-1");
        p.getMarketInfo().setTokenId("tok-17");
        stubEmptyBidBook("tok-17");

        PositionPriceMonitorService.ScanOutcome outcome = service.processOne(p, cfg, Instant.now());

        assertEquals(PositionPriceMonitorService.ScanOutcome.ALERT_TRIGGERED, outcome);
        // ring buffer 应当带上 autoExitOutcome 字符串
        assertEquals(1, service.recentAlerts().size());
        assertEquals(PositionPriceMonitorService.AutoExitOutcome.PAPER_LOGGED.name(),
                service.recentAlerts().get(0).getAutoExitOutcome());
    }

    @Test
    void scan_doesNothingWhenAlertGateDisabled_butStillRefreshes() {
        cfg.setEmptyBidAlertEnabled(false);
        SmartWalletPositionEntity p = position(7L, "0.05", "tok-7");
        stubOpenPositionList(p);
        stubEmptyBidBook("tok-7");

        service.scan();

        // orderbook 仍然刷新了, 只是告警没触发
        verify(marketDataCacheService, times(1)).invalidateOrderBook("tok-7");
        verify(marketDataCacheService, times(1)).getOrderBook("tok-7");
        verify(auditAlertSink, never()).publish(any(AlertType.class), anyString(), anyString(), any(), any());
    }

    // ─── 辅助 ──────────────────────────────────────────────────────────────

    private SmartWalletPositionEntity position(Long id, String entry, String tokenId) {
        SmartWalletPositionEntity p = new SmartWalletPositionEntity();
        p.setId(id);
        p.setStatus("OPEN");
        p.setAverageEntryPrice(new BigDecimal(entry));
        p.setQuantity(new BigDecimal("100"));
        SmartWalletMarketInfoEntity mi = new SmartWalletMarketInfoEntity();
        mi.setMarketId("market-" + id);
        mi.setOutcome("USA");
        mi.setTokenId(tokenId);
        p.setMarketInfo(mi);
        return p;
    }

    /** 给 resolveTierIntervalSeconds 测试用: 只设 entry, mark=null。 */
    private SmartWalletPositionEntity positionWithEntry(String entry) {
        SmartWalletPositionEntity p = new SmartWalletPositionEntity();
        p.setId(1L);
        p.setAverageEntryPrice(new BigDecimal(entry));
        return p;
    }

    /** 给 C 路径测试用: 同时设 entry 和 mark, 验证 mark 优先。 */
    private SmartWalletPositionEntity positionWithMark(String entry, String mark) {
        SmartWalletPositionEntity p = positionWithEntry(entry);
        p.setLastMarkPrice(new BigDecimal(mark));
        return p;
    }

    /** 给 A 路径测试用: 带 openedAt + executionAddress + marketInfo, 用于触发 maybeTriggerAutoExit。 */
    private SmartWalletPositionEntity autoExitCandidate(Long id, String entry, Date openedAt, String execAddr) {
        SmartWalletPositionEntity p = position(id, entry, "tok-" + id);
        p.setOpenedAt(openedAt);
        p.setExecutionAddress(execAddr);
        return p;
    }

    private static Date minutesAgo(int min) {
        return Date.from(Instant.now().minusSeconds(60L * min));
    }

    private void stubEmptyBidBook(String tokenId) {
        PolymarketApiClient.OrderBook book = PolymarketApiClient.OrderBook.builder()
                .tokenId(tokenId)
                .bids(new ArrayList<>())
                .asks(List.of(PolymarketApiClient.OrderBookLevel.builder()
                        .price(new BigDecimal("0.99")).size(new BigDecimal("5")).build()))
                .build();
        doReturn(book).when(marketDataCacheService).getOrderBook(tokenId);
    }

    private void stubBookWithBids(String tokenId, BigDecimal topBid, BigDecimal topAsk) {
        PolymarketApiClient.OrderBook book = PolymarketApiClient.OrderBook.builder()
                .tokenId(tokenId)
                .bids(List.of(PolymarketApiClient.OrderBookLevel.builder()
                        .price(topBid).size(new BigDecimal("10")).build()))
                .asks(List.of(PolymarketApiClient.OrderBookLevel.builder()
                        .price(topAsk).size(new BigDecimal("10")).build()))
                .build();
        when(marketDataCacheService.getOrderBook(tokenId)).thenReturn(book);
    }

    private void stubOpenPositionList(SmartWalletPositionEntity... positions) {
        when(positionMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(positions)));
    }

    /** 反射注入 @Autowired(required=false) 字段 (避免暴露 setter 给非测试代码)。 */
    private void injectField(PositionPriceMonitorService svc, String fieldName, Object value) {
        try {
            java.lang.reflect.Field f = PositionPriceMonitorService.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(svc, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to inject " + fieldName + " for test", e);
        }
    }

    /** 构造一条"活跃 SELL"订单, 用于 B2 测试。 */
    private SmartWalletOrderEntity activeSell(Long positionId, String orderId, BigDecimal price, BigDecimal size) {
        SmartWalletOrderEntity o = new SmartWalletOrderEntity();
        o.setPositionId(positionId);
        o.setExternalOrderId(orderId);
        o.setSide("SELL");
        o.setStatus("LIVE");
        o.setPrice(price);
        o.setSize(size);
        return o;
    }
}
