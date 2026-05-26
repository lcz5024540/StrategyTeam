package com.metaverse.coin.strategy.job.smartwallet.service;

import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SettlementTimeGuard} 写入入口 clamp 单测（P2-2）。
 *
 * <p>验证：
 * <ul>
 *   <li>candidate 在 [openedAt, now] 区间内：原样返回。</li>
 *   <li>candidate &lt; openedAt：clamp 到 openedAt（修掉旧数据再增的根因）。</li>
 *   <li>candidate &gt; now：clamp 到 now（防"未来时间"结算）。</li>
 *   <li>candidate / openedAt 为 null 的边界。</li>
 * </ul>
 */
class SettlementTimeGuardTest {

    @Test
    void candidateWithinRange_returnsAsIs() {
        long now = System.currentTimeMillis();
        Date openedAt = new Date(now - 60_000L);
        Date candidate = new Date(now - 30_000L);

        Date result = SettlementTimeGuard.clamp(candidate, openedAt, "test.within");

        assertEquals(candidate, result);
    }

    @Test
    void candidateBeforeOpenedAt_clampToOpenedAt() {
        long now = System.currentTimeMillis();
        Date openedAt = new Date(now - 30_000L);
        Date candidate = new Date(now - 120_000L); // 早于 openedAt

        Date result = SettlementTimeGuard.clamp(candidate, openedAt, "test.before");

        assertEquals(openedAt, result, "candidate < openedAt 时应 clamp 到 openedAt，防止 settled_at < opened_at 脏数据");
    }

    @Test
    void candidateAfterNow_clampToNow() {
        long now = System.currentTimeMillis();
        Date openedAt = new Date(now - 60_000L);
        Date candidate = new Date(now + 10 * 60_000L); // 未来 10 分钟

        Date result = SettlementTimeGuard.clamp(candidate, openedAt, "test.future");

        assertNotNull(result);
        assertTrue(result.getTime() <= System.currentTimeMillis(),
                "candidate > now 时应 clamp 到 now，防止未来时间结算");
        assertTrue(result.before(candidate));
    }

    @Test
    void candidateEqualsOpenedAt_returnsCandidate() {
        Date openedAt = new Date(System.currentTimeMillis() - 60_000L);

        Date result = SettlementTimeGuard.clamp(openedAt, openedAt, "test.equal");

        assertEquals(openedAt, result);
    }

    @Test
    void nullCandidate_returnsNull() {
        Date openedAt = new Date(System.currentTimeMillis() - 60_000L);

        Date result = SettlementTimeGuard.clamp(null, openedAt, "test.null-candidate");

        assertNull(result, "null candidate（仓位未关）应原样返回 null");
    }

    @Test
    void nullOpenedAt_skipLowerBoundCheck() {
        long now = System.currentTimeMillis();
        Date candidate = new Date(now - 60_000L);

        Date result = SettlementTimeGuard.clamp(candidate, null, "test.null-opened");

        assertEquals(candidate, result, "openedAt=null 时跳过下界检查，candidate 仍在 now 之前则原样返回");
    }

    @Test
    void nullOpenedAtAndFutureCandidate_clampToNow() {
        long now = System.currentTimeMillis();
        Date candidate = new Date(now + 10 * 60_000L);

        Date result = SettlementTimeGuard.clamp(candidate, null, "test.null-opened-future");

        assertNotNull(result);
        assertTrue(result.getTime() <= System.currentTimeMillis());
        assertTrue(result.before(candidate));
    }
}
