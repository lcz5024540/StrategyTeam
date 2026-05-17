package com.metaverse.coin.strategy.job.smartwallet.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-P1-7 / F-P1-8 第十轮：{@link PolymarketWebSocketClient} 基础契约测试。
 *
 * <p>真实 WS 连接需要外部环境，本套测试只覆盖：
 * <ol>
 *   <li>主开关 OFF 时 {@code @PostConstruct} 不创建任何 OkHttp / 线程 / scheduler；</li>
 *   <li>{@code subscribeMarkets / subscribeUsers / getStats} 在未初始化（未 start）时返回安全默认值；</li>
 *   <li>{@code stop()} 即便未 start 也可安全调用（idempotent shutdown）。</li>
 * </ol>
 *
 * <p>真实 WS 联调走 PolymarketIntegrationTest（手动启用，CI 默认跳过）。
 */
class PolymarketWebSocketClientTest {

    private SmartWalletProperties properties;
    private PolymarketMarketDataStreamService dataStream;
    private PolymarketSmartWalletMonitorService monitorService;
    private PolymarketWebSocketClient client;

    @BeforeEach
    void setUp() {
        properties = new SmartWalletProperties();
        // explicit: master switch OFF (默认)
        properties.getPolymarket().setEnableWsClient(false);

        dataStream = new PolymarketMarketDataStreamService(properties, new ObjectMapper());
        // monitor service 复杂依赖；用 mock 隔离避免触发 jdbcTemplate 等
        monitorService = Mockito.mock(PolymarketSmartWalletMonitorService.class);
        client = new PolymarketWebSocketClient(properties, new ObjectMapper(), dataStream, monitorService);
    }

    @Test
    void start_shouldNoOpWhenSwitchDisabled() {
        // 主开关 OFF：start() 应该立即返回，不创建 OkHttp / 线程
        client.start();
        // 之后调用 subscribeMarkets/Users 都应该返回 false（未连接 / channel 未初始化）
        assertFalse(client.subscribeMarkets(List.of("token-1", "token-2")));
        assertFalse(client.subscribeUsers(List.of("0xabc", "0xdef")));

        PolymarketWebSocketClient.ClientStats stats = client.getStats();
        assertNotNull(stats);
        assertFalse(stats.isEnabled());
        assertFalse(stats.isMarketConnected());
        assertFalse(stats.isUserConnected());
        assertEquals(0L, stats.getTotalMessagesReceived());

        // dataStream 也不应该被标记为已连接
        assertFalse(dataStream.isConnected());

        // stop() idempotent，未启动也能安全调用
        client.stop();
    }

    @Test
    void subscribeMarkets_shouldRejectNullOrEmpty() {
        client.start();
        assertFalse(client.subscribeMarkets(null));
        assertFalse(client.subscribeMarkets(Collections.emptyList()));
    }

    @Test
    void subscribeUsers_shouldRejectNullOrEmpty() {
        client.start();
        assertFalse(client.subscribeUsers(null));
        assertFalse(client.subscribeUsers(Collections.emptyList()));
    }

    @Test
    void getSubscribedTokens_returnsEmptyWhenNotStarted() {
        // 不调用 start()，直接查
        assertNotNull(client.getSubscribedMarketTokens());
        assertTrue(client.getSubscribedMarketTokens().isEmpty());
        assertNotNull(client.getSubscribedUserWallets());
        assertTrue(client.getSubscribedUserWallets().isEmpty());
    }

    @Test
    void stats_shouldRebuildEachCall() {
        // 验证 getStats() builder 不会复用旧实例（避免计数滞留）
        PolymarketWebSocketClient.ClientStats first = client.getStats();
        PolymarketWebSocketClient.ClientStats second = client.getStats();
        assertNotNull(first);
        assertNotNull(second);
        // 两次调用应返回独立对象
        assertTrue(first != second);
    }
}
