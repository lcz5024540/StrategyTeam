package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fix 5: 入场 BUY 单 5min 超时撤销的 tracker 侧测试。
 *
 * <p>覆盖:
 * <ul>
 *   <li>registerWithTimeout 不检查 isEnableGtcGlobalTimeout, 独立于全局 GTC 开关</li>
 *   <li>scan 在只启用 enableEntryBuyTimeout 时仍运行</li>
 *   <li>ENTRY_BUY 的 maxAttempts 取 entryBuyCancelMaxAttempts (默认 2), 不是 GTC 的 3</li>
 *   <li>scan 在两个开关都关时不动</li>
 * </ul>
 */
class GtcOrderTimeoutTrackerEntryBuyTest {

    private SmartWalletProperties properties;
    private ExecutionWalletContextLoader walletContextLoader;
    private PolymarketClobClientRouter clobClientRouter;
    private GtcOrderTimeoutTracker tracker;

    @BeforeEach
    void setUp() {
        properties = new SmartWalletProperties();
        // 默认 enableGtcGlobalTimeout=false; enableEntryBuyTimeout=false
        properties.getExecutionWallet().setEnablePendingTrackerPersistence(false); // 测试不动 DB
        walletContextLoader = mock(ExecutionWalletContextLoader.class);
        clobClientRouter = mock(PolymarketClobClientRouter.class);

        tracker = new GtcOrderTimeoutTracker(properties, walletContextLoader, clobClientRouter);
    }

    // ─── registerWithTimeout 不查 isEnabled ──────────────────────────────────

    @Test
    void registerWithTimeout_succeedsEvenWhenAllFlagsDisabled() {
        // 两个开关都关, registerWithTimeout 仍能注册
        tracker.registerWithTimeout(null, "ext-1", "0xwallet",
                "ENTRY_BUY", "market-x", "USA", null, 300_000L);
        // 没有 public 的 size 方法可断言, 但 scan 在两个开关都关时不会处理:
        when(walletContextLoader.requireLiveContext()).thenReturn(
                ExecutionWalletContextLoader.ExecutionWalletContext.builder().address("0xwallet").build());
        tracker.scan();
        verify(clobClientRouter, never()).cancelOrder(any(), anyString());
    }

    // ─── scan 双开关任一开都跑 ─────────────────────────────────────────────

    @Test
    void scan_runsWhenOnlyEntryBuyTimeoutEnabled() throws InterruptedException {
        properties.getExecutionWallet().setEnableEntryBuyTimeout(true);
        properties.getExecutionWallet().setEntryBuyTimeoutSeconds(1); // 1s 便于测试触发
        properties.getExecutionWallet().setEntryBuyCancelMaxAttempts(2);

        when(walletContextLoader.requireLiveContext()).thenReturn(
                ExecutionWalletContextLoader.ExecutionWalletContext.builder().address("0xwallet").build());
        when(clobClientRouter.cancelOrder(any(), anyString())).thenReturn(Map.of("ok", true));

        // 直接 register 一个已过期的条目 (timeoutMs = 0 → 立刻过期)
        tracker.registerWithTimeout(null, "ext-2", "0xwallet",
                "ENTRY_BUY", "market-y", "USA", null, 0L);

        tracker.scan();
        verify(clobClientRouter, times(1)).cancelOrder(any(), anyString());
    }

    // ─── ENTRY_BUY 走独立 maxAttempts ────────────────────────────────────────

    @Test
    void scan_entryBuyUsesEntryBuyMaxAttempts() {
        properties.getExecutionWallet().setEnableEntryBuyTimeout(true);
        properties.getExecutionWallet().setEntryBuyCancelMaxAttempts(2);
        // GTC maxAttempts 给个明显不同的值, 用于反证选错
        properties.getExecutionWallet().setGtcCancelMaxAttempts(99);

        when(walletContextLoader.requireLiveContext()).thenReturn(
                ExecutionWalletContextLoader.ExecutionWalletContext.builder().address("0xwallet").build());
        // cancelOrder 永远抛错 → 计 attempt
        when(clobClientRouter.cancelOrder(any(), anyString()))
                .thenThrow(new RuntimeException("api 500"));

        tracker.registerWithTimeout(null, "ext-3", "0xwallet",
                "ENTRY_BUY", "market-z", "USA", null, 0L);

        // 跑 maxAttempts(=2) 次 scan, 每次都失败 → 第 2 次后 abandoned 移除
        for (int i = 0; i < 3; i++) {
            tracker.scan();
        }
        // ENTRY_BUY 上限是 2, GTC 是 99. 验证调用次数是 2, 不是 99
        verify(clobClientRouter, times(2)).cancelOrder(any(), anyString());
    }

    // ─── scan 两个开关都关时不动 ─────────────────────────────────────────────

    @Test
    void scan_doesNothingWhenAllTimeoutsDisabled() {
        // 默认全关
        tracker.registerWithTimeout(null, "ext-4", "0xwallet",
                "ENTRY_BUY", "market-q", "USA", null, 0L);

        tracker.scan();
        verify(clobClientRouter, never()).cancelOrder(any(), anyString());
    }

    // ─── register (老 API) 在 GTC 全局关闭时不注册 ─────────────────────────

    @Test
    void registerLegacy_skippedWhenGtcGlobalTimeoutDisabled() {
        // 即使 enableEntryBuyTimeout=true, 老 register 仍只受 enableGtcGlobalTimeout 影响 (向后兼容)
        properties.getExecutionWallet().setEnableEntryBuyTimeout(true);
        properties.getExecutionWallet().setEnableGtcGlobalTimeout(false);

        tracker.register(null, "ext-5", "0xwallet",
                "TAKER", "market-r", "USA", null);

        when(walletContextLoader.requireLiveContext()).thenReturn(
                ExecutionWalletContextLoader.ExecutionWalletContext.builder().address("0xwallet").build());
        // scan 受 isAnyTimeoutEnabled 控制, 现在 entry-buy 开了, scan 会跑, 但 register 没注册任何条目
        tracker.scan();
        verify(clobClientRouter, never()).cancelOrder(any(), anyString());
    }
}
