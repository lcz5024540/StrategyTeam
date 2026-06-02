package com.metaverse.coin.strategy.job.smartwallet.service;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletMarketInfoService;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketSnapshotEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletSignal;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class PolymarketOrderRequestFactory {

    private static final String LOG_PREFIX = "[POLY_FOLLOW]";

    private final PolymarketMarketMetadataService marketMetadataService;
    private final SmartWalletMarketInfoService marketInfoService;
    private final MarketSnapshotCollectorService marketSnapshotCollectorService;
    private final SmartWalletProperties smartWalletProperties;

    public PreparedPolymarketOrder prepare(SmartWalletSignal signal,
                                           ExecutionWalletContextLoader.ExecutionWalletContext wallet) {
        String outcome = signal.getFollowOutcome() == null ? "YES" : signal.getFollowOutcome().trim().toUpperCase();
        String conditionId = signal.getMarket() == null ? null : signal.getMarket().getMarketId();
        log.info("{} action=准备Polymarket跟单买入订单 result=开始 signalId={} marketId={} outcome={} requestedStakeUsdc={} requestedPrice={}",
                LOG_PREFIX, signal.getSignalId(), conditionId, outcome, signal.getRecommendedStakeUsdc(),
                signal.getMarket() == null ? null : signal.getMarket().getPrice());
        if ("YES".equals(outcome) && !wallet.isAllowYes()) {
            throw new IllegalStateException("Execution wallet is configured to block YES orders.");
        }
        if ("NO".equals(outcome) && !wallet.isAllowNo()) {
            throw new IllegalStateException("Execution wallet is configured to block NO orders.");
        }

        PolymarketApiClient.MarketMetadata metadata = marketMetadataService.getByConditionId(conditionId);
        String tokenId = resolveTokenId(metadata, outcome);
        BigDecimal minimumTickSize = metadata.getMinimumTickSize() == null || metadata.getMinimumTickSize().compareTo(BigDecimal.ZERO) <= 0
                ? new BigDecimal("0.01")
                : metadata.getMinimumTickSize();
        PreparedPolymarketOrder prepared = prepareLocalDraft(
                signal.getSignalId(),
                conditionId,
                metadata.getQuestion() == null && signal.getMarket() != null ? signal.getMarket().getQuestion() : metadata.getQuestion(),
                outcome,
                signal.getRecommendedStakeUsdc(),
                signal.getMarket() == null ? null : signal.getMarket().getPrice(),
                tokenId,
                minimumTickSize,
                metadata.isNegRisk(),
                wallet,
                signal.getPriceImpactExecutionMode() // W2-T4.3: 价格冲击门导出的下游路由提示
        );
        log.info("{} action=准备Polymarket跟单买入订单 result=完成 signalId={} marketId={} outcome={} tokenId={} limitPrice={} size={} stakeUsdc={} tickSize={} negRisk={}",
                LOG_PREFIX, signal.getSignalId(), prepared.getMarketId(), prepared.getOutcome(), prepared.getTokenId(),
                prepared.getLimitPrice(), prepared.getSize(), prepared.getStakeUsdc(), prepared.getTickSize(),
                prepared.isNegRisk());
        return prepared;
    }

    public PreparedPolymarketOrder prepareExit(SmartWalletPositionEntity position,
                                               BigDecimal requestedPrice,
                                               ExecutionWalletContextLoader.ExecutionWalletContext wallet) {
        return prepareExit(position, requestedPrice, wallet, null);
    }

    /**
     * F-P0-1: 部分退出 overload —— 允许调用方按 ratio 指定退出数量。
     *
     * @param overrideQuantity 若非空且 > 0，使用该数量替代 position.getQuantity()；
     *                         否则按全仓退出（兼容旧行为）。
     */
    public PreparedPolymarketOrder prepareExit(SmartWalletPositionEntity position,
                                               BigDecimal requestedPrice,
                                               ExecutionWalletContextLoader.ExecutionWalletContext wallet,
                                               BigDecimal overrideQuantity) {
        if (position == null) {
            throw new IllegalArgumentException("position must be provided for exit order preparation.");
        }
        log.info("{} action=准备Polymarket退出订单 result=开始 positionId={} marketId={} outcome={} requestedPrice={} overrideQuantity={}",
                LOG_PREFIX, position.getId(), position.getMarketId(), position.getOutcome(), requestedPrice, overrideQuantity);
        // Populate position with market info to enable delegate getters
        marketInfoService.populate(position);
        if (!StringUtils.hasText(position.getMarketId())) {
            throw new IllegalStateException("Position is missing marketId.");
        }
        if (!StringUtils.hasText(position.getOutcome())) {
            throw new IllegalStateException("Position is missing outcome.");
        }
        BigDecimal totalQuantity = position.getQuantity();
        if (totalQuantity == null || totalQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Position has no remaining quantity to exit.");
        }
        BigDecimal quantity;
        if (overrideQuantity != null && overrideQuantity.compareTo(BigDecimal.ZERO) > 0) {
            // 不允许超过仓位剩余量
            quantity = overrideQuantity.min(totalQuantity);
        } else {
            quantity = totalQuantity;
        }

        String outcome = position.getOutcome().trim().toUpperCase(Locale.ROOT);
        PolymarketApiClient.MarketMetadata metadata = marketMetadataService.getBestEffortByConditionId(
                position.getMarketId(),
                false,
                position.getQuestion(),
                List.of(outcome)
        );
        String tokenId = StringUtils.hasText(position.getTokenId())
                ? position.getTokenId().trim()
                : resolveTokenId(metadata, outcome);
        BigDecimal minimumTickSize = metadata.getMinimumTickSize() == null || metadata.getMinimumTickSize().compareTo(BigDecimal.ZERO) <= 0
                ? new BigDecimal("0.01")
                : metadata.getMinimumTickSize();
        BigDecimal referencePrice = requestedPrice == null ? position.getLastMarkPrice() : requestedPrice;
        if (referencePrice == null || referencePrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Exit price must be provided, or the position must have a usable lastMarkPrice.");
        }
        BigDecimal slippage = referencePrice.multiply(wallet.getPriceSlippageRate());
        BigDecimal limitPrice = referencePrice.subtract(slippage).max(minimumTickSize).setScale(4, RoundingMode.HALF_UP);
        BigDecimal normalizedLimitPrice = snapToTick(limitPrice, minimumTickSize);
        BigDecimal normalizedSize = quantity.setScale(6, RoundingMode.DOWN);
        BigDecimal exitNotional = normalizedSize.multiply(normalizedLimitPrice).setScale(8, RoundingMode.HALF_UP);

        boolean partial = quantity.compareTo(totalQuantity) < 0;
        String signalId = partial
                ? "position-exit-partial-" + position.getId()
                : "position-exit-" + position.getId();
        PreparedPolymarketOrder prepared = PreparedPolymarketOrder.builder()
                .signalId(signalId)
                .marketId(position.getMarketId())
                .question(position.getQuestion())
                .outcome(outcome)
                .side("SELL")
                .stakeUsdc(exitNotional)
                .limitPrice(normalizedLimitPrice)
                .size(normalizedSize)
                .tokenId(tokenId)
                .tickSize(minimumTickSize)
                .negRisk(metadata != null && metadata.isNegRisk())
                .executionAddress(wallet.getAddress())
                .funderAddress(wallet.getFunderAddress())
                .build();
        log.info("{} action=准备Polymarket退出订单 result=完成 positionId={} marketId={} outcome={} tokenId={} limitPrice={} size={} notionalUsdc={} tickSize={} negRisk={} partial={} totalQuantity={}",
                LOG_PREFIX, position.getId(), prepared.getMarketId(), prepared.getOutcome(), prepared.getTokenId(),
                prepared.getLimitPrice(), prepared.getSize(), prepared.getStakeUsdc(), prepared.getTickSize(),
                prepared.isNegRisk(), partial, totalQuantity);
        return prepared;
    }

    public PreparedPolymarketOrder prepareLocalDraft(String signalId,
                                                     String conditionId,
                                                     String question,
                                                     String outcome,
                                                     BigDecimal requestedStakeUsdc,
                                                     BigDecimal requestedPrice,
                                                     String tokenId,
                                                     BigDecimal tickSize,
                                                    boolean negRisk,
                                                    ExecutionWalletContextLoader.ExecutionWalletContext wallet) {
        // 兼容 overload: 历史调用方（BtcMinuteStrategyJob、PolymarketClobExecutionService.prepareExit 等）
        // 不知晓价格冲击门，所以走 null 路径（沿用钱包默认 orderStrategy）。
        return prepareLocalDraft(signalId, conditionId, question, outcome, requestedStakeUsdc, requestedPrice,
                tokenId, tickSize, negRisk, wallet, null);
    }

    /**
     * W2-T4.3 扩展 overload：接受价格冲击门导出的 {@code priceImpactExecutionMode} 提示
     * （{@code MAKER_FIRST} / {@code MAKER_ONLY} / {@code NORMAL} / {@code null}），
     * 强制将下单 strategy 切到 MAKER 路径，并在订单上打 reasonTag 供下游订单工厂消费。
     */
    public PreparedPolymarketOrder prepareLocalDraft(String signalId,
                                                     String conditionId,
                                                     String question,
                                                     String outcome,
                                                     BigDecimal requestedStakeUsdc,
                                                     BigDecimal requestedPrice,
                                                     String tokenId,
                                                     BigDecimal tickSize,
                                                     boolean negRisk,
                                                     ExecutionWalletContextLoader.ExecutionWalletContext wallet,
                                                     String priceImpactExecutionMode) {
        log.info("{} action=构造Polymarket本地下单草稿 result=开始 signalId={} marketId={} outcome={} requestedStakeUsdc={} requestedPrice={} tokenId={} tickSize={} priceImpactMode={}",
                LOG_PREFIX, signalId, conditionId, outcome, requestedStakeUsdc, requestedPrice, tokenId, tickSize,
                priceImpactExecutionMode);
        if ("YES".equalsIgnoreCase(outcome) && !wallet.isAllowYes()) {
            throw new IllegalStateException("Execution wallet is configured to block YES orders.");
        }
        if ("NO".equalsIgnoreCase(outcome) && !wallet.isAllowNo()) {
            throw new IllegalStateException("Execution wallet is configured to block NO orders.");
        }
        BigDecimal recommendedStake = requestedStakeUsdc == null ? wallet.getMinOrderUsdc() : requestedStakeUsdc;
        BigDecimal cappedStake = recommendedStake
                .max(wallet.getMinOrderUsdc())
                .min(wallet.getMaxOrderUsdc())
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal rawPrice = requestedPrice == null ? BigDecimal.ZERO : requestedPrice;
        BigDecimal minimumTickSize = tickSize == null || tickSize.compareTo(BigDecimal.ZERO) <= 0
                ? new BigDecimal("0.01")
                : tickSize;

        // ── W2-T4.3 路由覆写：价格冲击门为 MAKER_FIRST / MAKER_ONLY 时强制使用 MAKER ──
        // 否则回到 wallet.orderStrategy 配置 (TAKER / MAKER / ADAPTIVE)
        String effectiveStrategy;
        boolean priceImpactOverride = false;
        if ("MAKER_FIRST".equalsIgnoreCase(priceImpactExecutionMode)
                || "MAKER_ONLY".equalsIgnoreCase(priceImpactExecutionMode)) {
            effectiveStrategy = "MAKER";
            priceImpactOverride = true;
            log.info("{} action=OrderStrategy signalId={} strategy=MAKER overrideReason=PRICE_IMPACT_{} (wallet.orderStrategy={} 被忽略)",
                    LOG_PREFIX, signalId, priceImpactExecutionMode.toUpperCase(Locale.ROOT), wallet.getOrderStrategy());
        } else {
            // Fix-B3: 决定订单策略（TAKER / MAKER / ADAPTIVE）
            effectiveStrategy = resolveOrderStrategy(wallet, conditionId, outcome);
        }

        BigDecimal limitPrice;
        if ("MAKER".equals(effectiveStrategy)) {
            // F-P1-15: MAKER 模式取 min(rawPrice + tickBuffer × tick, bestBid + offset × tick)
            //   - tickBuffer 防止 rawPrice 严重滞后于盘口时挂单成 stale；
            //   - bestBid + offset 避免追高把单子推进 taker 区（仍是 maker，不付 taker fee）。
            // 当 bestBid 不可用 / snapshot stale 时退回旧逻辑 rawPrice + 1 tick。
            limitPrice = resolveMakerLimitPrice(signalId, rawPrice, minimumTickSize, conditionId, outcome);
        } else {
            // Taker 模式（默认）：limitPrice = rawPrice + slippage
            BigDecimal effectiveSlippageRate = resolveSlippageRate(wallet, conditionId, outcome);
            BigDecimal slippage = rawPrice.multiply(effectiveSlippageRate);
            limitPrice = rawPrice.add(slippage).min(BigDecimal.ONE).setScale(4, RoundingMode.HALF_UP);
            // Fix 3: 钳到 bestAsk × (1 + premium), 防止 leader 价远高于本地 bestAsk 时挂孤儿单。
            // 与 maker/slippage 互补: maker 不付 taker fee, 钳制不影响 maker 路径; slippage 看深度,
            // 钳制看价位锚, 两个维度独立。
            SmartWalletProperties.SignalRisk riskCfg = smartWalletProperties == null ? null
                    : smartWalletProperties.getSignalRisk();
            if (riskCfg != null && riskCfg.isEnableEntryAskPriceCap()) {
                BigDecimal bestAsk = fetchBestAsk(conditionId, outcome,
                        smartWalletProperties.getExecutionWallet() == null ? 60_000L
                                : smartWalletProperties.getExecutionWallet().getMakerBestBidSnapshotMaxAgeMs());
                if (bestAsk != null && bestAsk.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal premium = riskCfg.getMaxBuyPremiumVsBestAsk() == null
                            ? BigDecimal.ZERO : riskCfg.getMaxBuyPremiumVsBestAsk();
                    BigDecimal cap = bestAsk.multiply(BigDecimal.ONE.add(premium))
                            .min(BigDecimal.ONE)
                            .setScale(4, RoundingMode.HALF_UP);
                    if (limitPrice.compareTo(cap) > 0) {
                        log.info("{} action=OrderStrategy signalId={} strategy=TAKER slippageRate={} "
                                        + "limitPriceRaw={} bestAsk={} premium={} capPrice={} → CAPPED",
                                LOG_PREFIX, signalId, effectiveSlippageRate, limitPrice, bestAsk, premium, cap);
                        limitPrice = cap;
                    } else {
                        log.info("{} action=OrderStrategy signalId={} strategy=TAKER slippageRate={} "
                                        + "limitPrice={} bestAsk={} cap={} → within cap",
                                LOG_PREFIX, signalId, effectiveSlippageRate, limitPrice, bestAsk, cap);
                    }
                } else {
                    log.info("{} action=OrderStrategy signalId={} strategy=TAKER slippageRate={} limitPrice={} (no bestAsk for cap, fallback)",
                            LOG_PREFIX, signalId, effectiveSlippageRate, limitPrice);
                }
            } else {
                log.info("{} action=OrderStrategy signalId={} strategy=TAKER slippageRate={} limitPrice={}",
                        LOG_PREFIX, signalId, effectiveSlippageRate, limitPrice);
            }
        }

        BigDecimal normalizedLimitPrice = snapToTick(limitPrice, minimumTickSize);
        BigDecimal size = normalizedLimitPrice.compareTo(BigDecimal.ZERO) <= 0
                ? BigDecimal.ZERO
                : cappedStake.divide(normalizedLimitPrice, 6, RoundingMode.DOWN);

        PreparedPolymarketOrder prepared = PreparedPolymarketOrder.builder()
                .signalId(signalId)
                .marketId(conditionId)
                .question(question)
                .outcome(outcome)
                .side("BUY")
                .stakeUsdc(cappedStake)
                .limitPrice(normalizedLimitPrice)
                .size(size)
                .tokenId(StringUtils.hasText(tokenId) ? tokenId.trim() : null)
                .tickSize(minimumTickSize)
                .negRisk(negRisk)
                .executionAddress(wallet.getAddress())
                .funderAddress(wallet.getFunderAddress())
                .orderStrategy(effectiveStrategy)
                // W2-T4.3: 透传价格冲击门导出的下游路由提示, 用于运维/审计 + 后续 MAKER_ONLY 超时撤单
                .priceImpactExecutionMode(priceImpactExecutionMode)
                .priceImpactOverride(priceImpactOverride)
                .build();
        log.info("{} action=构造Polymarket本地下单草稿 result=完成 signalId={} marketId={} outcome={} side={} strategy={} priceImpactMode={} priceImpactOverride={} requestedStakeUsdc={} cappedStakeUsdc={} rawPrice={} limitPrice={} size={} tokenId={}",
                LOG_PREFIX, signalId, conditionId, outcome, prepared.getSide(), effectiveStrategy,
                prepared.getPriceImpactExecutionMode(), prepared.isPriceImpactOverride(),
                requestedStakeUsdc, cappedStake, rawPrice, prepared.getLimitPrice(), prepared.getSize(),
                prepared.getTokenId());
        return prepared;
    }

    // ── Fix-C: 动态滑点计算 ──────────────────────────────────────────────

    /**
     * 根据 orderbook 深度动态计算滑点率。
     * <p>规则：
     * <ul>
     *   <li>askDepth3c >= highLiquidityThreshold → 使用紧滑点（默认 1%）</li>
     *   <li>askDepth3c >= lowLiquidityThreshold → 使用中等滑点（默认 2%）</li>
     *   <li>askDepth3c < lowLiquidityThreshold → 使用宽滑点（默认 3%）</li>
     * </ul>
     * 未启用动态滑点或快照不可用时，回退到固定 priceSlippageRate。
     */
    private BigDecimal resolveSlippageRate(ExecutionWalletContextLoader.ExecutionWalletContext wallet,
                                           String conditionId, String outcome) {
        if (!wallet.isEnableDynamicSlippage()) {
            return wallet.getPriceSlippageRate();
        }
        BigDecimal depth = fetchAskDepth3c(conditionId, outcome);
        if (depth == null) {
            // 快照不可用，fall back to 固定值
            return wallet.getPriceSlippageRate();
        }
        if (depth.compareTo(wallet.getHighLiquidityThresholdUsdc()) >= 0) {
            return wallet.getHighLiquiditySlippageRate();
        }
        if (depth.compareTo(wallet.getLowLiquidityThresholdUsdc()) >= 0) {
            return wallet.getMediumLiquiditySlippageRate();
        }
        return wallet.getLowLiquiditySlippageRate();
    }

    // ── Fix-B3: 订单策略路由 ─────────────────────────────────────────────

    /**
     * 根据配置和市场流动性决定使用 TAKER 还是 MAKER 策略。
     * <p>ADAPTIVE 模式下同时检查 askDepth3c 和 spreadBps：
     * 只有深度充足且价差窄的市场才使用 MAKER（挂单不立即成交的风险较小）。</p>
     */
    private String resolveOrderStrategy(ExecutionWalletContextLoader.ExecutionWalletContext wallet,
                                         String conditionId, String outcome) {
        String strategy = wallet.getOrderStrategy();
        if (!"ADAPTIVE".equals(strategy)) {
            // 固定 TAKER 或固定 MAKER
            return "MAKER".equals(strategy) ? "MAKER" : "TAKER";
        }
        // ADAPTIVE 模式：根据市场流动性决定
        try {
            SmartWalletMarketSnapshotEntity snapshot = marketSnapshotCollectorService
                    .findLatestSnapshotAtOrBefore(conditionId, outcome, new Date());
            if (snapshot == null || snapshot.getSnapshotAt() == null
                    || System.currentTimeMillis() - snapshot.getSnapshotAt().getTime() > 300_000L) {
                // 无有效快照，保守走 taker
                return "TAKER";
            }
            BigDecimal askDepth = snapshot.getAskDepth3c();
            BigDecimal spreadBps = snapshot.getSpreadBps();
            boolean depthOk = askDepth != null
                    && askDepth.compareTo(wallet.getMakerStrategyMinDepthUsdc()) >= 0;
            boolean spreadOk = spreadBps != null
                    && spreadBps.compareTo(wallet.getMakerStrategyMaxSpreadBps()) <= 0;
            if (depthOk && spreadOk) {
                log.debug("{} ADAPTIVE → MAKER: askDepth3c={}, spreadBps={}", LOG_PREFIX, askDepth, spreadBps);
                return "MAKER";
            }
            log.debug("{} ADAPTIVE → TAKER: askDepth3c={}, spreadBps={} (thresholds: depth>={}, spread<={})",
                    LOG_PREFIX, askDepth, spreadBps,
                    wallet.getMakerStrategyMinDepthUsdc(), wallet.getMakerStrategyMaxSpreadBps());
            return "TAKER";
        } catch (Exception e) {
            log.debug("{} ADAPTIVE → TAKER (snapshot lookup failed): {}", LOG_PREFIX, e.getMessage());
            return "TAKER";
        }
    }

    /**
     * F-P1-15: MAKER 限价计算 —— 在 rawPrice 与 bestBid 之间取 maker-safe 限价。
     *
     * <p>规则（按 ExecutionWallet 配置 enableMakerBestBidPricing）：
     * <ul>
     *   <li>读取最近 makerBestBidSnapshotMaxAgeMs 以内的 snapshot；</li>
     *   <li>有效 bestBid：limitPrice = min(rawPrice + tickBuffer × tick, bestBid + offset × tick)；</li>
     *   <li>无 bestBid / snapshot stale / 开关关闭：回退 rawPrice + 1 tick（旧行为）。</li>
     * </ul>
     * 返回值已按 [0, 1] 截断 + 4 位小数四舍五入，调用方仍需走 snapToTick 对齐到 tickSize。
     */
    private BigDecimal resolveMakerLimitPrice(String signalId,
                                              BigDecimal rawPrice,
                                              BigDecimal tickSize,
                                              String conditionId,
                                              String outcome) {
        SmartWalletProperties.ExecutionWallet ew =
                smartWalletProperties == null ? null : smartWalletProperties.getExecutionWallet();
        int tickBuffer = ew == null ? 1 : Math.max(1, ew.getMakerTickBufferTicks());
        int offset = ew == null ? 1 : Math.max(0, ew.getMakerBestBidOffsetTicks());
        boolean enableBestBid = ew == null || ew.isEnableMakerBestBidPricing();

        BigDecimal cappedRaw = rawPrice
                .add(tickSize.multiply(new BigDecimal(tickBuffer)))
                .min(BigDecimal.ONE)
                .max(tickSize);
        if (!enableBestBid) {
            BigDecimal fallback = cappedRaw.setScale(4, RoundingMode.HALF_UP);
            log.info("{} action=OrderStrategy signalId={} strategy=MAKER limitPrice={} (best-bid pricing disabled, rawPrice + {}tick)",
                    LOG_PREFIX, signalId, fallback, tickBuffer);
            return fallback;
        }
        BigDecimal bestBid = fetchBestBid(conditionId, outcome,
                ew == null ? 30_000L : ew.getMakerBestBidSnapshotMaxAgeMs());
        if (bestBid == null || bestBid.compareTo(BigDecimal.ZERO) <= 0) {
            // Fix 4: 链式 fallback — bestBid 空时优先用 bestAsk - 1 tick 作为锚
            // (仍是 maker, 仍在 ask 之下, 但比 rawPrice + 1tick 更贴近实际市场)
            BigDecimal bestAsk = fetchBestAsk(conditionId, outcome,
                    ew == null ? 30_000L : ew.getMakerBestBidSnapshotMaxAgeMs());
            if (bestAsk != null && bestAsk.compareTo(tickSize) > 0) {
                BigDecimal askAnchored = bestAsk
                        .subtract(tickSize)
                        .max(tickSize)
                        .min(BigDecimal.ONE)
                        .setScale(4, RoundingMode.HALF_UP);
                // 与 cappedRaw (rawPrice + tickBuffer*tick) 取小, 避免买在 raw 之上
                BigDecimal chosen = cappedRaw.min(askAnchored).setScale(4, RoundingMode.HALF_UP);
                log.info("{} action=OrderStrategy signalId={} strategy=MAKER limitPrice={} "
                                + "(bestBid 不可用, fallback bestAsk={} - 1tick → {}, cappedRaw={}, picked=min)",
                        LOG_PREFIX, signalId, chosen, bestAsk, askAnchored, cappedRaw);
                return chosen;
            }
            BigDecimal fallback = rawPrice
                    .add(tickSize)
                    .min(BigDecimal.ONE)
                    .setScale(4, RoundingMode.HALF_UP);
            log.info("{} action=OrderStrategy signalId={} strategy=MAKER limitPrice={} "
                            + "(bestBid 和 bestAsk 都不可用, fallback rawPrice + 1tick)",
                    LOG_PREFIX, signalId, fallback);
            return fallback;
        }
        BigDecimal bestBidCap = bestBid
                .add(tickSize.multiply(new BigDecimal(offset)))
                .min(BigDecimal.ONE)
                .max(tickSize);
        BigDecimal chosen = cappedRaw.min(bestBidCap).setScale(4, RoundingMode.HALF_UP);
        log.info("{} action=OrderStrategy signalId={} strategy=MAKER limitPrice={} (rawPrice={} + {}tick → {}, bestBid={} + {}tick → {}, picked=min)",
                LOG_PREFIX, signalId, chosen, rawPrice, tickBuffer, cappedRaw, bestBid, offset, bestBidCap);
        return chosen;
    }

    /**
     * F-P1-15: 抓取最近 N 毫秒内的 bestBid（指定 outcome 的 YES/NO token）。
     * <p>独立于 {@link #fetchAskDepth3c} 的 5 分钟窗口，因为 MAKER 限价对盘口时效更敏感。
     */
    private BigDecimal fetchBestBid(String conditionId, String outcome, long maxAgeMs) {
        try {
            SmartWalletMarketSnapshotEntity snapshot = marketSnapshotCollectorService
                    .findLatestSnapshotAtOrBefore(conditionId, outcome, new Date());
            if (snapshot == null || snapshot.getSnapshotAt() == null) {
                return null;
            }
            if (System.currentTimeMillis() - snapshot.getSnapshotAt().getTime() > maxAgeMs) {
                return null;
            }
            return snapshot.getBestBid();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Fix 4 二级 fallback: 抓取最近 N 毫秒内的 bestAsk, 与 fetchBestBid 对称。
     */
    private BigDecimal fetchBestAsk(String conditionId, String outcome, long maxAgeMs) {
        try {
            SmartWalletMarketSnapshotEntity snapshot = marketSnapshotCollectorService
                    .findLatestSnapshotAtOrBefore(conditionId, outcome, new Date());
            if (snapshot == null || snapshot.getSnapshotAt() == null) {
                return null;
            }
            if (System.currentTimeMillis() - snapshot.getSnapshotAt().getTime() > maxAgeMs) {
                return null;
            }
            return snapshot.getBestAsk();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从最近 5 分钟的 snapshot 获取 askDepth3c。
     */
    private BigDecimal fetchAskDepth3c(String conditionId, String outcome) {
        try {
            SmartWalletMarketSnapshotEntity snapshot = marketSnapshotCollectorService
                    .findLatestSnapshotAtOrBefore(conditionId, outcome, new Date());
            if (snapshot == null || snapshot.getSnapshotAt() == null
                    || System.currentTimeMillis() - snapshot.getSnapshotAt().getTime() > 300_000L) {
                return null;
            }
            return snapshot.getAskDepth3c();
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveTokenId(PolymarketApiClient.MarketMetadata metadata, String outcome) {
        if (metadata == null || metadata.getOutcomeTokenIds() == null || metadata.getOutcomeTokenIds().isEmpty()) {
            throw new IllegalStateException("Polymarket market metadata is missing outcome token ids.");
        }
        String tokenId = findTokenId(metadata.getOutcomeTokenIds(), outcome);
        if (!StringUtils.hasText(tokenId)) {
            String supportedOutcomes = metadata.getOutcomeTokenIds().keySet().stream()
                    .filter(StringUtils::hasText)
                    .collect(Collectors.joining(", "));
            throw new IllegalStateException("Polymarket token id not found for outcome " + outcome
                    + " on market " + metadata.getConditionId()
                    + ". Supported outcomes: [" + supportedOutcomes + "].");
        }
        return tokenId.trim();
    }

    private String findTokenId(Map<String, String> outcomeTokenIds, String outcome) {
        if (!StringUtils.hasText(outcome) || outcomeTokenIds == null || outcomeTokenIds.isEmpty()) {
            return null;
        }
        String exact = outcomeTokenIds.get(outcome);
        if (StringUtils.hasText(exact)) {
            return exact;
        }
        String normalized = outcome.trim().toUpperCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : outcomeTokenIds.entrySet()) {
            if (entry.getKey() != null && normalized.equals(entry.getKey().trim().toUpperCase(Locale.ROOT))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private BigDecimal snapToTick(BigDecimal price, BigDecimal tickSize) {
        if (price == null || tickSize == null || tickSize.compareTo(BigDecimal.ZERO) <= 0) {
            return price;
        }
        return price.divide(tickSize, 0, RoundingMode.HALF_UP)
                .multiply(tickSize)
                .min(BigDecimal.ONE)
                .max(BigDecimal.ZERO)
                .setScale(Math.max(tickSize.scale(), 4), RoundingMode.HALF_UP);
    }

    @Data
    @Builder
    public static class PreparedPolymarketOrder {
        private String signalId;
        private String marketId;
        private String question;
        private String outcome;
        private String side;
        private BigDecimal stakeUsdc;
        private BigDecimal limitPrice;
        private BigDecimal size;
        private String tokenId;
        private BigDecimal tickSize;
        private boolean negRisk;
        private String executionAddress;
        private String funderAddress;
        /** 实际使用的订单策略：TAKER / MAKER */
        private String orderStrategy;
        /**
         * W2-T4.3: 价格冲击门导出的下游路由提示。
         * 取值：{@code MAKER_FIRST} / {@code MAKER_ONLY} / {@code NORMAL} / {@code null}。
         * 配合 {@link #priceImpactOverride} 区分 "门控判定 NORMAL" 与 "无门控数据"。
         */
        private String priceImpactExecutionMode;
        /**
         * W2-T4.3: 当 {@code true} 表示本订单的 orderStrategy 是被价格冲击门强制覆写为 MAKER 的，
         * 而非来源于钱包默认 {@code orderStrategy} 配置。供下游订单工厂区分 "MAKER_ONLY 超时撤单" 等场景。
         */
        private boolean priceImpactOverride;
    }
}
