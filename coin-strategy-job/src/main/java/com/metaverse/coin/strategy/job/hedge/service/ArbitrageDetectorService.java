package com.metaverse.coin.strategy.job.hedge.service;

import com.metaverse.coin.strategy.job.hedge.config.HedgeProperties;
import com.metaverse.coin.strategy.job.hedge.model.HedgeOpportunity;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketApiClient;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketApiClient.OrderBook;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketApiClient.OrderBookLevel;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketMarketDataCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 套利机会识别服务
 *
 * 核心逻辑：
 *   在 Polymarket 二元市场中，YES + NO = $1（结算时赢家收回 $1）。
 *   若以 ask_yes + ask_no < 1 同时买入双边，则无论结果如何均盈利。
 *   净边际 = 1 - ask_yes - ask_no - 2 × feeRate（扣除双边手续费）
 *
 * 使用 PolymarketApiClient.fetchOrderBook()（只读，与跟单策略共享数据源，不干扰跟单逻辑）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArbitrageDetectorService {

    private final PolymarketMarketDataCacheService marketDataCache;
    private final HedgeProperties properties;

    /**
     * 扫描给定市场列表，返回满足套利条件的机会列表
     */
    public List<HedgeOpportunity> scanMarkets(List<MarketTokenPair> marketTokenPairs) {
        List<HedgeOpportunity> opportunities = new ArrayList<>();
        for (MarketTokenPair pair : marketTokenPairs) {
            try {
                detectOpportunity(pair).ifPresent(opportunities::add);
            } catch (Exception e) {
                log.debug("[ArbitrageDetector] Error scanning market={}: {}", pair.marketId(), e.getMessage());
            }
        }
        return opportunities;
    }

    /**
     * 对单个市场执行套利检测
     */
    public Optional<HedgeOpportunity> detectOpportunity(MarketTokenPair pair) {
        if (!StringUtils.hasText(pair.yesTokenId()) || !StringUtils.hasText(pair.noTokenId())) {
            return Optional.empty();
        }

        // 分别从缓存获取 YES 和 NO 两个 token 的订单簿（避免与跟单策略重复调用同一端点）
        OrderBook yesBook = marketDataCache.getOrderBook(pair.yesTokenId());
        OrderBook noBook = marketDataCache.getOrderBook(pair.noTokenId());

        if (yesBook == null || noBook == null) {
            return Optional.empty();
        }

        // 买入方向：吃对方挂单的卖出价（asks 中价格最低的档位 = best ask）
        BigDecimal yesBestAsk = bestAsk(yesBook);
        BigDecimal noBestAsk = bestAsk(noBook);

        if (yesBestAsk == null || noBestAsk == null
                || yesBestAsk.compareTo(BigDecimal.ZERO) <= 0
                || noBestAsk.compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.empty();
        }

        // 价格范围保护：单边价格不应超过 0.97（防止市场已接近结束）
        if (yesBestAsk.compareTo(new BigDecimal("0.97")) >= 0
                || noBestAsk.compareTo(new BigDecimal("0.97")) >= 0) {
            return Optional.empty();
        }

        BigDecimal totalImpliedProb = yesBestAsk.add(noBestAsk);
        BigDecimal probDeviation = BigDecimal.ONE.subtract(totalImpliedProb);

        if (probDeviation.compareTo(properties.getMinGrossEdgeRate()) < 0) {
            return Optional.empty();
        }

        // 扣除双边手续费
        BigDecimal totalFeeRate = properties.getEstimatedFeeRate().multiply(new BigDecimal("2"));
        BigDecimal netEdgeRate = probDeviation.subtract(totalFeeRate).setScale(6, RoundingMode.HALF_UP);

        if (netEdgeRate.compareTo(properties.getMinNetEdgeRate()) < 0) {
            return Optional.empty();
        }

        // 流动性检查
        BigDecimal yesLiquidity = estimateLiquidityUsdc(yesBook, yesBestAsk);
        BigDecimal noLiquidity = estimateLiquidityUsdc(noBook, noBestAsk);

        if (yesLiquidity.compareTo(properties.getMinSideLiquidityUsdc()) < 0
                || noLiquidity.compareTo(properties.getMinSideLiquidityUsdc()) < 0) {
            return Optional.empty();
        }

        log.info("[ArbitrageDetector] Opportunity: market={} yesBestAsk={} noBestAsk={} grossEdge={} netEdge={}",
                pair.marketId(), yesBestAsk, noBestAsk, probDeviation.setScale(4, RoundingMode.HALF_UP), netEdgeRate);

        return Optional.of(HedgeOpportunity.builder()
                .marketId(pair.marketId())
                .question(pair.question())
                .yesTokenId(pair.yesTokenId())
                .noTokenId(pair.noTokenId())
                .yesBestAsk(yesBestAsk)
                .noBestAsk(noBestAsk)
                .yesLiquidityUsdc(yesLiquidity)
                .noLiquidityUsdc(noLiquidity)
                .totalImpliedProb(totalImpliedProb.setScale(6, RoundingMode.HALF_UP))
                .probDeviation(probDeviation.setScale(6, RoundingMode.HALF_UP))
                .grossEdgeRate(probDeviation.setScale(6, RoundingMode.HALF_UP))
                .netEdgeRate(netEdgeRate)
                .marketEndAt(pair.marketEndAt())
                .build());
    }

    // ---- private helpers ----

    /**
     * 取 asks 中最低价（第一档）作为 best ask
     * OrderBook.asks 由 PolymarketApiClient 填充，顺序已排好（价格升序）
     */
    private BigDecimal bestAsk(OrderBook book) {
        if (book == null || book.getAsks() == null || book.getAsks().isEmpty()) {
            return null;
        }
        return book.getAsks().get(0).getPrice();
    }

    /**
     * 估算 best ask 附近 1% 滑点范围内的可用 USDC 深度（取前 5 档）
     */
    private BigDecimal estimateLiquidityUsdc(OrderBook book, BigDecimal bestAsk) {
        if (book.getAsks() == null || book.getAsks().isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal slippageCap = bestAsk.multiply(new BigDecimal("1.01"));
        BigDecimal total = BigDecimal.ZERO;
        int levels = 0;
        for (OrderBookLevel level : book.getAsks()) {
            if (levels >= 5) break;
            if (level.getPrice() == null || level.getSize() == null) continue;
            if (level.getPrice().compareTo(slippageCap) > 0) break;
            total = total.add(level.getPrice().multiply(level.getSize()));
            levels++;
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /** 市场 token 对（扫描输入，不可变） */
    public record MarketTokenPair(String marketId, String question,
                                   String yesTokenId, String noTokenId,
                                   Long marketEndAt) {
    }
}
