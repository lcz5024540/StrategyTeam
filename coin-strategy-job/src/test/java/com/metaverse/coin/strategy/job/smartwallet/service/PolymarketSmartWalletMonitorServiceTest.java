package com.metaverse.coin.strategy.job.smartwallet.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F-P1-8 单元测试：trade-event WebSocket 推流入口
 *
 * <p>覆盖 {@link PolymarketSmartWalletMonitorService#ingestWsTradeEvent(String)} 的：
 * <ul>
 *   <li>开关 OFF 时跳过（计数 wsTradeEventsDisabledSkips）</li>
 *   <li>开关 ON + 正常 payload 转发到 ingestTradeEvent</li>
 *   <li>本地 LRU 去重命中跳过</li>
 *   <li>JSON 解析失败计数 parseFailures 不抛出</li>
 *   <li>必需字段缺失计数 parseFailures 不抛出</li>
 *   <li>blank payload 静默返回</li>
 *   <li>开关 ON + 兼容字段命名 (proxyWallet / tradeId / ts) 也能成功解析</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PolymarketSmartWalletMonitorServiceTest {

    @Mock
    private SmartWalletProfileStoreService profileStoreService;
    @Mock
    private SmartWalletFollowTaskService followTaskService;
    @Mock
    private SmartWalletTimeFormatter timeFormatter;
    @Mock
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private PolymarketSmartWalletMonitorService service;
    private SmartWalletProperties properties;

    @BeforeEach
    void setUp() {
        properties = new SmartWalletProperties();
        // 手动调用 @RequiredArgsConstructor 生成的构造器（按字段声明顺序）：
        // properties, profileStoreService, followTaskService, timeFormatter, jdbcTemplate
        service = new PolymarketSmartWalletMonitorService(
                properties,
                profileStoreService,
                followTaskService,
                timeFormatter,
                jdbcTemplate
        );
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        // followTaskService.recordTradeEvent 默认 echo 回原 entity（避免空指针）
        lenient().when(followTaskService.recordTradeEvent(any(SmartWalletTradeEventEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void ingestWsTradeEvent_switchOff_skipsAndCountsDisabledSkips() {
        // 默认 enableWsTradeEventPush=false
        String payload = "{\"walletAddress\":\"0xabc\",\"marketId\":\"m1\",\"externalTradeId\":\"t1\"}";

        SmartWalletTradeEventEntity result = service.ingestWsTradeEvent(payload);

        assertNull(result);
        PolymarketSmartWalletMonitorService.WsTradeEventStats stats = service.getWsTradeEventStats();
        assertEquals(1L, stats.getReceived());
        assertEquals(0L, stats.getForwarded());
        assertEquals(1L, stats.getDisabledSkips());
        assertEquals(0L, stats.getParseFailures());
        verifyNoInteractions(followTaskService);
    }

    @Test
    void ingestWsTradeEvent_switchOn_validPayload_forwardsToIngestTradeEvent() {
        properties.getPolymarket().setEnableWsTradeEventPush(true);
        String payload = "{"
                + "\"walletAddress\":\"0xabc\","
                + "\"marketId\":\"m1\","
                + "\"externalTradeId\":\"t-100\","
                + "\"outcome\":\"yes\","
                + "\"side\":\"buy\","
                + "\"price\":0.55,"
                + "\"sizeShares\":50,"
                + "\"sizeUsdc\":27.5,"
                + "\"openedAt\":1700000000000,"
                + "\"question\":\"Will X happen?\""
                + "}";

        SmartWalletTradeEventEntity result = service.ingestWsTradeEvent(payload);

        assertNotNull(result);
        ArgumentCaptor<SmartWalletTradeEventEntity> captor = ArgumentCaptor.forClass(SmartWalletTradeEventEntity.class);
        verify(followTaskService).recordTradeEvent(captor.capture());
        SmartWalletTradeEventEntity captured = captor.getValue();
        assertEquals("0xabc", captured.getWalletAddress());
        assertEquals("m1", captured.getMarketId());
        assertEquals("t-100", captured.getExternalTradeId());
        assertEquals("YES", captured.getOutcome());
        assertEquals("BUY", captured.getSide());
        assertEquals(new BigDecimal("0.55"), captured.getPrice());

        PolymarketSmartWalletMonitorService.WsTradeEventStats stats = service.getWsTradeEventStats();
        assertEquals(1L, stats.getReceived());
        assertEquals(1L, stats.getForwarded());
        assertEquals(0L, stats.getDeduped());
        assertEquals(0L, stats.getParseFailures());
        assertEquals(0L, stats.getDisabledSkips());
    }

    @Test
    void ingestWsTradeEvent_lruDedupe_secondCallSkipped() {
        properties.getPolymarket().setEnableWsTradeEventPush(true);
        properties.getPolymarket().setWsTradeEventDedupeWindowMs(60_000L);
        String payload = "{\"walletAddress\":\"0xabc\",\"marketId\":\"m1\",\"externalTradeId\":\"t-200\"}";

        SmartWalletTradeEventEntity first = service.ingestWsTradeEvent(payload);
        SmartWalletTradeEventEntity second = service.ingestWsTradeEvent(payload);

        assertNotNull(first);
        assertNull(second); // 第二次命中 LRU 去重，直接返回 null
        verify(followTaskService, times(1)).recordTradeEvent(any(SmartWalletTradeEventEntity.class));

        PolymarketSmartWalletMonitorService.WsTradeEventStats stats = service.getWsTradeEventStats();
        assertEquals(2L, stats.getReceived());
        assertEquals(1L, stats.getForwarded());
        assertEquals(1L, stats.getDeduped());
    }

    @Test
    void ingestWsTradeEvent_dedupeWindowDisabled_alwaysForwards() {
        // 窗口设为 0：禁用本地 LRU 全部下放 DB 兜底
        properties.getPolymarket().setEnableWsTradeEventPush(true);
        properties.getPolymarket().setWsTradeEventDedupeWindowMs(0L);
        String payload = "{\"walletAddress\":\"0xabc\",\"marketId\":\"m1\",\"externalTradeId\":\"t-300\"}";

        service.ingestWsTradeEvent(payload);
        service.ingestWsTradeEvent(payload);

        verify(followTaskService, times(2)).recordTradeEvent(any(SmartWalletTradeEventEntity.class));
        PolymarketSmartWalletMonitorService.WsTradeEventStats stats = service.getWsTradeEventStats();
        assertEquals(0L, stats.getDeduped());
        assertEquals(2L, stats.getForwarded());
    }

    @Test
    void ingestWsTradeEvent_parseFailure_countsParseFailureNotThrow() {
        properties.getPolymarket().setEnableWsTradeEventPush(true);
        String malformed = "{not valid json"; // 故意损坏

        SmartWalletTradeEventEntity result = service.ingestWsTradeEvent(malformed);

        assertNull(result);
        verifyNoInteractions(followTaskService);
        PolymarketSmartWalletMonitorService.WsTradeEventStats stats = service.getWsTradeEventStats();
        assertEquals(1L, stats.getReceived());
        assertEquals(1L, stats.getParseFailures());
        assertEquals(0L, stats.getForwarded());
    }

    @Test
    void ingestWsTradeEvent_requiredFieldsMissing_countsParseFailure() {
        properties.getPolymarket().setEnableWsTradeEventPush(true);
        // walletAddress / marketId / externalTradeId 任何一个缺失即被视为解析失败
        String missingMarketId = "{\"walletAddress\":\"0xabc\",\"externalTradeId\":\"t-400\"}";

        SmartWalletTradeEventEntity result = service.ingestWsTradeEvent(missingMarketId);

        assertNull(result);
        verifyNoInteractions(followTaskService);
        PolymarketSmartWalletMonitorService.WsTradeEventStats stats = service.getWsTradeEventStats();
        assertEquals(1L, stats.getParseFailures());
    }

    @Test
    void ingestWsTradeEvent_blankPayload_silentlyReturns() {
        SmartWalletTradeEventEntity result1 = service.ingestWsTradeEvent(null);
        SmartWalletTradeEventEntity result2 = service.ingestWsTradeEvent("");
        SmartWalletTradeEventEntity result3 = service.ingestWsTradeEvent("   ");

        assertNull(result1);
        assertNull(result2);
        assertNull(result3);
        verifyNoInteractions(followTaskService);
        // blank payload 直接 early-return，不计入 received
        PolymarketSmartWalletMonitorService.WsTradeEventStats stats = service.getWsTradeEventStats();
        assertEquals(0L, stats.getReceived());
    }

    @Test
    void ingestWsTradeEvent_compatibleFieldAliases_parsesCorrectly() {
        properties.getPolymarket().setEnableWsTradeEventPush(true);
        // 用 proxyWallet / tradeId / ts 等别名（Polymarket WS 实际可能用这些）
        String payload = "{"
                + "\"proxyWallet\":\"0xdef\","
                + "\"conditionId\":\"m-x\","
                + "\"tradeId\":\"t-aliased\","
                + "\"action\":\"SELL\","
                + "\"price\":0.4,"
                + "\"size\":\"100\","
                + "\"ts\":1700000000"  // 注意：秒级时间戳，需被自动识别为秒并 ×1000
                + "}";

        SmartWalletTradeEventEntity result = service.ingestWsTradeEvent(payload);

        assertNotNull(result);
        ArgumentCaptor<SmartWalletTradeEventEntity> captor = ArgumentCaptor.forClass(SmartWalletTradeEventEntity.class);
        verify(followTaskService).recordTradeEvent(captor.capture());
        SmartWalletTradeEventEntity captured = captor.getValue();
        assertEquals("0xdef", captured.getWalletAddress());
        assertEquals("m-x", captured.getMarketId());
        assertEquals("t-aliased", captured.getExternalTradeId());
        assertEquals("SELL", captured.getSide());
        // 秒级 1700000000 → 毫秒级 1700000000000
        assertNotNull(captured.getOpenedAt());
        assertEquals(1700000000000L, captured.getOpenedAt().getTime());
    }

    @Test
    void ingestWsTradeEvent_envelopedPayload_walksIntoDataField() {
        properties.getPolymarket().setEnableWsTradeEventPush(true);
        // 包裹在 { data: {...} } 中的 payload — Polymarket WS 实际格式
        String payload = "{\"channel\":\"user-trades\",\"data\":{"
                + "\"walletAddress\":\"0x999\","
                + "\"marketId\":\"m-w\","
                + "\"externalTradeId\":\"t-wrapped\""
                + "}}";

        SmartWalletTradeEventEntity result = service.ingestWsTradeEvent(payload);

        assertNotNull(result);
        ArgumentCaptor<SmartWalletTradeEventEntity> captor = ArgumentCaptor.forClass(SmartWalletTradeEventEntity.class);
        verify(followTaskService).recordTradeEvent(captor.capture());
        assertEquals("0x999", captor.getValue().getWalletAddress());
        assertEquals("m-w", captor.getValue().getMarketId());
        assertEquals("t-wrapped", captor.getValue().getExternalTradeId());
    }
}
