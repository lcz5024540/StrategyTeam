package com.metaverse.coin.strategy.job.smartwallet.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 单测：F1（2026-06-05 补充场景库）按 leader 自身减仓比例镜像的纯比例函数
 * {@link FollowPositionAdjustmentService#computeLeaderProRataRatio}。
 */
class FollowProRataExitTest {

    @Test
    void smallFractionOfLargeLeaderPosition_mirrorsSmallRatio() {
        // leader 卖 50 份 / 卖前持有 1000 份 → 镜像 5%（而非按我方成本可能算出的 50%）
        BigDecimal r = FollowPositionAdjustmentService.computeLeaderProRataRatio(
                new BigDecimal("50"), new BigDecimal("1000"));
        assertEquals(new BigDecimal("0.0500"), r);
    }

    @Test
    void halfOfLeaderPosition_mirrorsHalf() {
        BigDecimal r = FollowPositionAdjustmentService.computeLeaderProRataRatio(
                new BigDecimal("50"), new BigDecimal("100"));
        assertEquals(new BigDecimal("0.5000"), r);
    }

    @Test
    void sellExceedsPrior_clampedToOne() {
        // 数据异常：卖出 ≥ 卖前持仓 → 钳到 1.0（全平）
        BigDecimal r = FollowPositionAdjustmentService.computeLeaderProRataRatio(
                new BigDecimal("120"), new BigDecimal("80"));
        assertEquals(new BigDecimal("1.0000").stripTrailingZeros(), r.stripTrailingZeros());
    }

    @Test
    void nullOrNonPositiveInputs_returnNullForFallback() {
        assertNull(FollowPositionAdjustmentService.computeLeaderProRataRatio(null, new BigDecimal("100")));
        assertNull(FollowPositionAdjustmentService.computeLeaderProRataRatio(new BigDecimal("50"), null));
        assertNull(FollowPositionAdjustmentService.computeLeaderProRataRatio(BigDecimal.ZERO, new BigDecimal("100")));
        assertNull(FollowPositionAdjustmentService.computeLeaderProRataRatio(new BigDecimal("50"), BigDecimal.ZERO));
        assertNull(FollowPositionAdjustmentService.computeLeaderProRataRatio(new BigDecimal("50"), new BigDecimal("-10")));
    }
}
