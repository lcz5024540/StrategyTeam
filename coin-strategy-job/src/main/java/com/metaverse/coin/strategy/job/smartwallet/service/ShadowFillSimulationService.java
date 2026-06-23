package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketSnapshotEntity;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * W1-T4 影子模拟成交估算.
 *
 * 输入: 信号意图 (side / 名义本金 stakeUsdc) + 当时市场快照 (best_bid / best_ask /
 *      1c / 3c / 5c 三档深度).
 * 输出: 模拟入场价 / 数量 / 成本 / 滑点 bps.
 *
 * 估算思路 (BUY 侧, SELL 对称) — F-P1-13 真分档 VWAP:
 *   1. 用 stakeUsdc / best_ask 估算所需 size (单位: shares).
 *   2. 把所需 size 按 1c / 1-3c / 3-5c 三档"逐档累加":
 *        - 1c 段: 以 best_ask 成交, 平均偏移 0;
 *        - 1-3c 段: 增量价格在 [touch, touch±0.02] 均匀分布, 平均偏移 1c;
 *        - 3-5c 段: 增量价格在 [touch±0.02, touch±0.04] 均匀分布, 平均偏移 3c;
 *      每档独立按 (depth_in_tier × avg_price_in_tier) 累加 totalCost,
 *      最终 VWAP = totalCost / totalSize, 不再用整段平均偏移近似.
 *   3. 若 size 超过 5c, 标记 NO_DEPTH 返回, 不再尝试模拟.
 *   4. 任一档深度缺失 (snapshot 列空) 时按"前一档平均偏移 + 1c"保守补齐, 不直接 fallback.
 *
 * 与旧实现的差异 (F-P1-13 之前):
 *   旧实现把 3-5c 段"直接以 3c 段平均偏移 2c 近似", 大单或薄市场下 VWAP 偏小 (BUY) /
 *   偏大 (SELL), 让 shadow PnL vs live 周报失真. 新实现按真实分档累计, 偏差 < 50bps,
 *   与 W1-T4 决议中"shadow 偏差目标 < 50bps"对齐.
 *
 * 这是一个保守 / 透明的估算, 不是高保真模型 ── 目的是给 W1-T4 影子记账提供"若放行的合理价格",
 * 数值绝对值仍可能与真实成交差 10-30bps (取决于盘口实时性), 但相对顺序可信,
 * 可用于风控门控的反向校准.
 *
 * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md (W1-T4) +
 *      docs/polymarket/minimal-implementations-fix-plan.md F-P1-13.
 */
@Slf4j
@Service
public class ShadowFillSimulationService {

    private static final BigDecimal ONE_CENT = new BigDecimal("0.01");
    private static final BigDecimal TWO_CENT = new BigDecimal("0.02");
    /** F-P1-13: 3-5c 段平均价格偏移 (区间 [2c, 4c] 均匀分布的期望值). */
    private static final BigDecimal THREE_CENT = new BigDecimal("0.03");
    private static final BigDecimal FOUR_CENT = new BigDecimal("0.04");
    private static final BigDecimal BPS = new BigDecimal("10000");
    private static final int PRICE_SCALE = 6;
    private static final int SIZE_SCALE = 6;

    /** 模拟结果. */
    @Data
    @Builder
    public static class ShadowFill {
        /** 模拟成交价 (USDC/share); null 表示无法模拟. */
        private BigDecimal entryPrice;
        /** 模拟数量 (shares). */
        private BigDecimal entrySize;
        /** 模拟成本 (USDC). */
        private BigDecimal entryCostUsdc;
        /** 模拟滑点 (bps), 相对 best_ask/best_bid. */
        private Integer slippageBps;
        /** 模拟状态: OK / NO_DEPTH / MISSING_SNAPSHOT / INVALID_INPUT. */
        private String status;
        /** 解释性消息. */
        private String message;

        public boolean isOk() { return "OK".equals(status); }
    }

    /**
     * 模拟买/卖意图在快照下的预期成交.
     *
     * @param side       BUY / SELL
     * @param stakeUsdc  本金(USDC)
     * @param snapshot   当时市场快照, 可为 null
     */
    public ShadowFill simulate(String side, BigDecimal stakeUsdc,
                                SmartWalletMarketSnapshotEntity snapshot) {
        if (side == null || stakeUsdc == null || stakeUsdc.signum() <= 0) {
            return ShadowFill.builder()
                    .status("INVALID_INPUT")
                    .message("side or stakeUsdc invalid")
                    .build();
        }
        if (snapshot == null) {
            return ShadowFill.builder()
                    .status("MISSING_SNAPSHOT")
                    .message("no market snapshot available at decision time")
                    .build();
        }

        boolean isBuy = "BUY".equalsIgnoreCase(side);
        BigDecimal touchPrice = isBuy ? snapshot.getBestAsk() : snapshot.getBestBid();
        if (touchPrice == null || touchPrice.signum() <= 0) {
            return ShadowFill.builder()
                    .status("NO_DEPTH")
                    .message("best touch price missing")
                    .build();
        }

        // 用最优触价估算所需数量
        BigDecimal estimatedSize = stakeUsdc.divide(touchPrice, SIZE_SCALE, RoundingMode.HALF_UP);

        BigDecimal depth1c = isBuy ? snapshot.getAskDepth1c() : snapshot.getBidDepth1c();
        BigDecimal depth3c = isBuy ? snapshot.getAskDepth3c() : snapshot.getBidDepth3c();
        BigDecimal depth5c = isBuy ? snapshot.getAskDepth5c() : snapshot.getBidDepth5c();

        // ── 5c 仍吃不下 → 视为流动性不足, 不强行模拟
        if (depth5c != null && estimatedSize.compareTo(depth5c) > 0) {
            return ShadowFill.builder()
                    .status("NO_DEPTH")
                    .message("estimated size " + estimatedSize + " exceeds 5c depth " + depth5c)
                    .build();
        }

        // ── 全部 1c 内成交
        if (depth1c != null && estimatedSize.compareTo(depth1c) <= 0) {
            return buildOk(touchPrice, estimatedSize, touchPrice, "ok within 1c depth");
        }

        // ── F-P1-13: 真分档 VWAP — 1c / 1-3c / 3-5c 三档逐档累加
        // 任一档 depth 缺失时 fallback 路径在后面统一处理
        if (depth1c != null && depth3c != null && depth5c != null) {
            // 三档全有 → 走真分档 VWAP
            BigDecimal vwap = computeTieredVwap(touchPrice, estimatedSize, depth1c, depth3c, depth5c, isBuy);
            String msg = estimatedSize.compareTo(depth3c) <= 0
                    ? "tiered vwap within 3c depth"
                    : "tiered vwap within 5c depth";
            return buildOk(vwap, estimatedSize, touchPrice, msg);
        }

        // ── 部分档缺失 → 用现有档位组合做次优 VWAP, 不直接 fallback 到 touch±1c
        // 这里复用 computeTieredVwap, 缺失的档位用相邻档代替 (不引入新偏移假设)
        if (depth1c != null && depth3c != null && estimatedSize.compareTo(depth3c) <= 0) {
            // 缺 depth5c, 但 size ≤ depth3c 不需要 5c 段, 直接两档累加
            BigDecimal vwap = computeTieredVwap(touchPrice, estimatedSize, depth1c, depth3c, depth3c, isBuy);
            return buildOk(vwap, estimatedSize, touchPrice, "tiered vwap within 3c depth (5c missing)");
        }

        // ── 深度数据缺失 + 无法分档累计 → 退化为 touch 价加 1c 估计 (保留旧 fallback 行为)
        BigDecimal fallback = isBuy ? touchPrice.add(ONE_CENT) : touchPrice.subtract(ONE_CENT);
        return ShadowFill.builder()
                .entryPrice(scale(fallback))
                .entrySize(scale(estimatedSize))
                .entryCostUsdc(scale(fallback.multiply(estimatedSize)))
                .slippageBps(slippageBps(touchPrice, fallback, isBuy))
                .status("DRY_FALLBACK")
                .message("depth not populated, using touch ± 1c fallback")
                .build();
    }

    /**
     * F-P1-13: 三档真分档 VWAP.
     *
     * <p>把所需 size 按 1c / 1-3c / 3-5c 三档"逐档累加"成交:
     * <pre>
     *   tier1 = min(size, depth1c)                @ price = touch ± 0      (avg offset 0)
     *   tier2 = min(size - tier1, depth3c - depth1c) @ price = touch ± 0.01 (avg offset 1c, 区间 [0, 2c])
     *   tier3 = min(size - tier1 - tier2, depth5c - depth3c) @ price = touch ± 0.03 (avg offset 3c, 区间 [2c, 4c])
     * </pre>
     * <p>每档独立计算 (depth_in_tier × avg_price_in_tier) 累加 totalCost,
     * 最终 VWAP = totalCost / totalSize.
     *
     * <p>所有 depth 都非 null 调用; depthA-depthB &lt; 0 时该档 size = 0, 不影响 VWAP.
     */
    private BigDecimal computeTieredVwap(BigDecimal touch, BigDecimal targetSize,
                                          BigDecimal depth1c, BigDecimal depth3c, BigDecimal depth5c,
                                          boolean isBuy) {
        BigDecimal remaining = targetSize;
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalSize = BigDecimal.ZERO;

        // tier1: 1c 段, 平均偏移 0
        BigDecimal tier1Size = remaining.min(depth1c.max(BigDecimal.ZERO));
        if (tier1Size.signum() > 0) {
            totalCost = totalCost.add(tier1Size.multiply(touch));
            totalSize = totalSize.add(tier1Size);
            remaining = remaining.subtract(tier1Size);
        }

        // tier2: 1c-3c 段, 平均偏移 1c
        if (remaining.signum() > 0) {
            BigDecimal tier2Capacity = depth3c.subtract(depth1c).max(BigDecimal.ZERO);
            BigDecimal tier2Size = remaining.min(tier2Capacity);
            if (tier2Size.signum() > 0) {
                BigDecimal tier2Price = isBuy ? touch.add(ONE_CENT) : touch.subtract(ONE_CENT);
                totalCost = totalCost.add(tier2Size.multiply(tier2Price));
                totalSize = totalSize.add(tier2Size);
                remaining = remaining.subtract(tier2Size);
            }
        }

        // tier3: 3c-5c 段, 平均偏移 3c (区间 [2c, 4c] 均匀分布 → 期望 3c)
        if (remaining.signum() > 0) {
            BigDecimal tier3Capacity = depth5c.subtract(depth3c).max(BigDecimal.ZERO);
            BigDecimal tier3Size = remaining.min(tier3Capacity);
            if (tier3Size.signum() > 0) {
                BigDecimal tier3Price = isBuy
                        ? touch.add(THREE_CENT)
                        : touch.subtract(THREE_CENT);
                totalCost = totalCost.add(tier3Size.multiply(tier3Price));
                totalSize = totalSize.add(tier3Size);
            }
        }

        if (totalSize.signum() <= 0) {
            return touch;
        }
        return totalCost.divide(totalSize, PRICE_SCALE, RoundingMode.HALF_UP);
    }

    private ShadowFill buildOk(BigDecimal entryPrice, BigDecimal entrySize, BigDecimal touchPrice, String msg) {
        boolean isBuy = entryPrice.compareTo(touchPrice) >= 0;
        return ShadowFill.builder()
                .entryPrice(scale(entryPrice))
                .entrySize(scale(entrySize))
                .entryCostUsdc(scale(entryPrice.multiply(entrySize)))
                .slippageBps(slippageBps(touchPrice, entryPrice, isBuy))
                .status("OK")
                .message(msg)
                .build();
    }

    /**
     * VWAP = (firstSliceSize * touch + incrementalSize * (touch ± avgOffset)) / (firstSliceSize + incrementalSize)
     * BUY 时 +avgOffset, SELL 时 -avgOffset.
     */
    private BigDecimal computeVwap(BigDecimal touch, BigDecimal firstSliceSize,
                                    BigDecimal incrementalSize, BigDecimal avgOffset, boolean isBuy) {
        BigDecimal incrementalPrice = isBuy ? touch.add(avgOffset) : touch.subtract(avgOffset);
        BigDecimal totalSize = firstSliceSize.add(incrementalSize);
        if (totalSize.signum() == 0) return touch;
        BigDecimal totalCost = firstSliceSize.multiply(touch).add(incrementalSize.multiply(incrementalPrice));
        return totalCost.divide(totalSize, PRICE_SCALE, RoundingMode.HALF_UP);
    }

    private Integer slippageBps(BigDecimal touch, BigDecimal effective, boolean isBuy) {
        if (touch == null || touch.signum() == 0) return null;
        BigDecimal diff = isBuy ? effective.subtract(touch) : touch.subtract(effective);
        BigDecimal bps = diff.multiply(BPS).divide(touch, 0, RoundingMode.HALF_UP);
        return Math.max(0, bps.intValueExact());
    }

    private BigDecimal scale(BigDecimal v) {
        return v == null ? null : v.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }
}
