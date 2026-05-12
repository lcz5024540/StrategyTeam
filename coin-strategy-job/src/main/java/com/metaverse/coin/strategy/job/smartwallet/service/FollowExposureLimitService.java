package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeOrderMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeOrderEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 跟单暴露上限控制服务。
 *
 * 解决白名单地址持续高频开仓导致我方 TVL 被打满的问题。
 *
 * 三层暴露上限：
 *
 * ┌──────────────────────────────────────────────────────────────┐
 * │ Layer 1: 单市场上限 (maxPerMarketUsdc)                      │
 * │   - 同一 conditionId 下所有持仓的总成本不超过 X USDC         │
 * │   - 防止单个天气市场吃掉过多资金                            │
 * │                                                              │
 * │ Layer 2: 单跟单地址上限 (maxPerFollowWalletUsdc)            │
 * │   - 跟同一个白名单地址产生的所有持仓总成本不超过 Y USDC      │
 * │   - 防止对单一地址的过度依赖                                │
 * │                                                              │
 * │ Layer 3: 总 TVL 利用率上限 (maxTvlUtilizationPct)           │
 * │   - 所有跟单持仓的总成本 / 总 TVL 不超过 Z%                │
 * │   - 确保留有足够的安全边际和资金灵活性                      │
 * └──────────────────────────────────────────────────────────────┘
 *
 * 每层返回：剩余可用额度（remainingBudget），
 * 最终可用额度 = min(Layer1, Layer2, Layer3)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FollowExposureLimitService {

    private final SmartWalletTradeOrderMapper tradeOrderMapper;
    private final SmartWalletProperties properties;

    /**
     * 检查是否允许在该 market+wallet 上开新仓，并返回最大可用金额。
     *
     * @param marketId        Polymarket conditionId
     * @param outcome         YES / NO
     * @param walletAddress   被跟单的白名单地址
     * @param proposedStake   建议下单金额
     * @param availableCapital 当前可用资金总额
     * @return 暴露检查结果
     */
    public ExposureCheckResult check(String marketId, String outcome, String walletAddress,
                                      BigDecimal proposedStake, BigDecimal availableCapital) {
        SmartWalletProperties.ExposureLimitConfig config = getExposureLimitConfig();
        List<String> reasons = new ArrayList<>();

        // ── Layer 1: 单市场持仓上限 ──
        BigDecimal marketExposure = sumOpenCostByMarket(marketId);
        BigDecimal maxPerMarket = config.getMaxPerMarketUsdc();
        BigDecimal marketRemaining = maxPerMarket.subtract(marketExposure).max(BigDecimal.ZERO);

        if (marketRemaining.compareTo(BigDecimal.ZERO) <= 0) {
            reasons.add("[MARKET_EXPOSURE_FULL] marketId=" + marketId
                    + " exposure=" + marketExposure.setScale(2, RoundingMode.HALF_UP)
                    + " >= maxPerMarket=" + maxPerMarket.setScale(2, RoundingMode.HALF_UP));
            return ExposureCheckResult.builder()
                    .allowed(false)
                    .remainingBudget(BigDecimal.ZERO)
                    .reason("MARKET_EXPOSURE_LIMIT")
                    .reasons(reasons)
                    .marketExposure(marketExposure)
                    .walletExposure(BigDecimal.ZERO)
                    .totalExposure(BigDecimal.ZERO)
                    .build();
        }

        // ── Layer 2: 单跟单地址持仓上限 ──
        BigDecimal walletExposure = sumOpenCostByFollowWallet(walletAddress);
        BigDecimal maxPerWallet = config.getMaxPerFollowWalletUsdc();
        BigDecimal walletRemaining = maxPerWallet.subtract(walletExposure).max(BigDecimal.ZERO);

        if (walletRemaining.compareTo(BigDecimal.ZERO) <= 0) {
            reasons.add("[WALLET_EXPOSURE_FULL] wallet=" + walletAddress
                    + " exposure=" + walletExposure.setScale(2, RoundingMode.HALF_UP)
                    + " >= maxPerWallet=" + maxPerWallet.setScale(2, RoundingMode.HALF_UP));
            return ExposureCheckResult.builder()
                    .allowed(false)
                    .remainingBudget(BigDecimal.ZERO)
                    .reason("WALLET_EXPOSURE_LIMIT")
                    .reasons(reasons)
                    .marketExposure(marketExposure)
                    .walletExposure(walletExposure)
                    .totalExposure(BigDecimal.ZERO)
                    .build();
        }

        // ── Layer 3: 总 TVL 利用率上限 ──
        BigDecimal totalExposure = sumAllOpenCost();
        BigDecimal maxTotalExposure = availableCapital
                .multiply(config.getMaxTvlUtilizationPct())
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalRemaining = maxTotalExposure.subtract(totalExposure).max(BigDecimal.ZERO);

        if (totalRemaining.compareTo(BigDecimal.ZERO) <= 0) {
            reasons.add("[TVL_UTILIZATION_FULL] totalExposure=" + totalExposure.setScale(2, RoundingMode.HALF_UP)
                    + " >= maxTotalExposure=" + maxTotalExposure.setScale(2, RoundingMode.HALF_UP)
                    + " (tvl=" + availableCapital.setScale(2, RoundingMode.HALF_UP)
                    + " × " + config.getMaxTvlUtilizationPct() + ")");
            return ExposureCheckResult.builder()
                    .allowed(false)
                    .remainingBudget(BigDecimal.ZERO)
                    .reason("TVL_UTILIZATION_LIMIT")
                    .reasons(reasons)
                    .marketExposure(marketExposure)
                    .walletExposure(walletExposure)
                    .totalExposure(totalExposure)
                    .build();
        }

        // ── 综合：取三层最小可用额度 ──
        BigDecimal effectiveBudget = marketRemaining.min(walletRemaining).min(totalRemaining);

        // 如果 proposedStake > effectiveBudget，裁剪到 budget
        BigDecimal finalStake = proposedStake.min(effectiveBudget).setScale(2, RoundingMode.HALF_UP);

        boolean clipped = proposedStake.compareTo(effectiveBudget) > 0;
        if (clipped) {
            reasons.add("[EXPOSURE_CLIPPED] proposedStake=" + proposedStake.setScale(2, RoundingMode.HALF_UP)
                    + " clipped to " + finalStake.setScale(2, RoundingMode.HALF_UP)
                    + " (marketRemaining=" + marketRemaining.setScale(2, RoundingMode.HALF_UP)
                    + ", walletRemaining=" + walletRemaining.setScale(2, RoundingMode.HALF_UP)
                    + ", totalRemaining=" + totalRemaining.setScale(2, RoundingMode.HALF_UP) + ")");
        }

        return ExposureCheckResult.builder()
                .allowed(true)
                .remainingBudget(effectiveBudget)
                .clippedStake(finalStake)
                .clipped(clipped)
                .reason(clipped ? "EXPOSURE_CLIPPED" : "OK")
                .reasons(reasons)
                .marketExposure(marketExposure)
                .walletExposure(walletExposure)
                .totalExposure(totalExposure)
                .build();
    }

    // ════════════════════════════════════════════════════════════════════
    // DB queries: 通过 trade_order 表查询当前暴露
    // ════════════════════════════════════════════════════════════════════
    // 注：SmartWalletTradeOrderEntity 有 walletAddress（被跟单地址）、
    //     marketInfoId（关联市场信息）、remainingCostBasisUsdc（剩余成本）、
    //     status（OPEN/CLOSED/...）。
    //     通过 trade_order 查询比 position 更精确，因为 position 不直接存
    //     sourceWalletAddress。

    /**
     * 查询某市场所有未平仓 trade orders 的剩余成本总额。
     * 通过 market_info_id IN (SELECT id FROM smart_wallet_market_info WHERE market_id = ?)
     * 但由于 MyBatis-Plus lambda 不支持子查询，改用 marketInfoId 列表方式。
     * 为了简洁高效，直接用 SQL 注解查询。
     */
    private BigDecimal sumOpenCostByMarket(String marketId) {
        LambdaQueryWrapper<SmartWalletTradeOrderEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SmartWalletTradeOrderEntity::getStatus, "OPEN")
                .apply("market_info_id IN (SELECT id FROM smart_wallet_market_info WHERE market_id = {0})", marketId);
        List<SmartWalletTradeOrderEntity> orders = tradeOrderMapper.selectList(wrapper);
        return orders.stream()
                .map(o -> o.getRemainingCostBasisUsdc() != null ? o.getRemainingCostBasisUsdc() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 查询某跟单钱包地址产生的所有未平仓 trade orders 的剩余成本总额。
     */
    private BigDecimal sumOpenCostByFollowWallet(String walletAddress) {
        LambdaQueryWrapper<SmartWalletTradeOrderEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SmartWalletTradeOrderEntity::getWalletAddress, walletAddress)
                .eq(SmartWalletTradeOrderEntity::getStatus, "OPEN");
        List<SmartWalletTradeOrderEntity> orders = tradeOrderMapper.selectList(wrapper);
        return orders.stream()
                .map(o -> o.getRemainingCostBasisUsdc() != null ? o.getRemainingCostBasisUsdc() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 查询所有未平仓 trade orders 的剩余成本总额。
     */
    private BigDecimal sumAllOpenCost() {
        LambdaQueryWrapper<SmartWalletTradeOrderEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SmartWalletTradeOrderEntity::getStatus, "OPEN");
        List<SmartWalletTradeOrderEntity> orders = tradeOrderMapper.selectList(wrapper);
        return orders.stream()
                .map(o -> o.getRemainingCostBasisUsdc() != null ? o.getRemainingCostBasisUsdc() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ════════════════════════════════════════════════════════════════════
    // Config
    // ════════════════════════════════════════════════════════════════════

    private SmartWalletProperties.ExposureLimitConfig getExposureLimitConfig() {
        SmartWalletProperties.ExposureLimitConfig config = properties.getExposureLimit();
        return config != null ? config : new SmartWalletProperties.ExposureLimitConfig();
    }

    // ════════════════════════════════════════════════════════════════════
    // Result model
    // ════════════════════════════════════════════════════════════════════

    @Data
    @Builder
    public static class ExposureCheckResult {
        /** 是否允许开仓 */
        private boolean allowed;
        /** 剩余可用额度 */
        private BigDecimal remainingBudget;
        /** 裁剪后的下单金额（如果被上限裁剪） */
        private BigDecimal clippedStake;
        /** 是否被裁剪 */
        private boolean clipped;
        /** 拒绝/裁剪原因 */
        private String reason;
        private List<String> reasons;
        /** 该市场当前总暴露 */
        private BigDecimal marketExposure;
        /** 该地址当前总暴露 */
        private BigDecimal walletExposure;
        /** 全部持仓当前总暴露 */
        private BigDecimal totalExposure;
    }
}
