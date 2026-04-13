package com.metaverse.coin.strategy.job.hedge.service;

import com.metaverse.coin.strategy.job.hedge.config.HedgeProperties;
import com.metaverse.coin.strategy.job.hedge.model.HedgeOpportunity;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketApiClient;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketMarketDataCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 套利机会扫描定时任务
 *
 * 每 {@code polymarket.hedge.scan-fixed-delay-ms} 毫秒（默认 10 秒）扫描一次：
 *   1. 通过 PolymarketApiClient.searchMarkets() 拉取活跃二元市场列表
 *   2. 调用 ArbitrageDetectorService 检测套利机会（获取订单簿、计算边际）
 *   3. 对满足条件的机会调用 HedgeOrderExecutorService 执行建仓
 *
 * 数据隔离说明：
 *   - 读取市场列表是只读操作，不干扰跟单策略
 *   - 所有写操作（建仓、下单）走 hedge_* 表，与 smart_wallet_* 完全隔离
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HedgeScanJob {

    private final HedgeProperties properties;
    private final ArbitrageDetectorService detector;
    private final HedgeOrderExecutorService executor;
    private final GlobalRiskGuard globalRiskGuard;
    private final PolymarketMarketDataCacheService marketDataCache;

    @Scheduled(fixedDelayString = "${polymarket.hedge.scan-fixed-delay-ms:10000}")
    public void scan() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            doScan();
        } catch (Exception e) {
            log.error("[HedgeScanJob] Unexpected error during scan: {}", e.getMessage(), e);
        }
    }

    private void doScan() {
        // 从共享缓存读取活跃市场列表（由 PolymarketMarketDataCacheService 定时刷新，避免重复 REST 调用）
        List<PolymarketApiClient.MarketMetadata> markets = marketDataCache.getActiveMarkets();

        if (markets == null || markets.isEmpty()) {
            return;
        }

        // 筛选标准二元市场（YES + NO 各一个 token）并构造扫描输入
        List<ArbitrageDetectorService.MarketTokenPair> pairs = buildPairs(markets);
        if (pairs.isEmpty()) {
            return;
        }

        // 检测套利机会
        List<HedgeOpportunity> opportunities = detector.scanMarkets(pairs);
        if (opportunities.isEmpty()) {
            log.debug("[HedgeScanJob] No arbitrage opportunities in {} markets", pairs.size());
            return;
        }

        log.info("[HedgeScanJob] Found {} opportunities in {} scanned markets",
                opportunities.size(), pairs.size());

        // 估算当前可用资金上界（避免在执行循环内重复查库）
        GlobalRiskGuard.GlobalExposureSummary exposure = globalRiskGuard.getSummary();
        BigDecimal globalRemaining = exposure.globalMaxExposureUsdc() != null
                && exposure.globalMaxExposureUsdc().compareTo(BigDecimal.ZERO) > 0
                ? exposure.globalMaxExposureUsdc().subtract(exposure.totalExposureUsdc())
                : new BigDecimal("999999");
        BigDecimal hedgeRemaining = properties.getMaxTotalExposureUsdc()
                .subtract(exposure.hedgeExposureUsdc());
        BigDecimal availableCapital = globalRemaining.min(hedgeRemaining).max(BigDecimal.ZERO);

        int executed = 0;
        for (HedgeOpportunity opp : opportunities) {
            if (availableCapital.compareTo(properties.getMinSinglePositionUsdc()) < 0) {
                log.info("[HedgeScanJob] Available capital exhausted, stopping");
                break;
            }
            boolean success = executor.execute(opp, availableCapital);
            if (success) {
                executed++;
                // 保守扣减（避免重复查库），下次 scan 会用真实值
                availableCapital = availableCapital.subtract(properties.getMinSinglePositionUsdc());
            }
        }

        if (executed > 0) {
            log.info("[HedgeScanJob] Opened {} new hedge positions this scan", executed);
        }
    }

    /**
     * 将 MarketMetadata 转换为扫描输入对
     * 只处理有且仅有 YES/NO 两个结果的标准二元市场，排除 NegRisk 合约
     */
    private List<ArbitrageDetectorService.MarketTokenPair> buildPairs(
            List<PolymarketApiClient.MarketMetadata> markets) {
        List<ArbitrageDetectorService.MarketTokenPair> pairs = new ArrayList<>();
        for (PolymarketApiClient.MarketMetadata market : markets) {
            if (market.isResolved() || market.isClosed()) continue;
            if (!market.isActive() || !market.isAcceptingOrders()) continue;
            // NegRisk 市场结算机制不同（赢方 token 不一定 = $1），暂不支持套利
            if (market.isNegRisk()) continue;

            Map<String, String> tokenIds = market.getOutcomeTokenIds();
            if (tokenIds == null || tokenIds.size() != 2) continue;

            // 按 Yes/No 名称查找 token（Polymarket 标准 key 名称）
            String yesTokenId = findTokenId(tokenIds, "Yes", "YES", "yes");
            String noTokenId = findTokenId(tokenIds, "No", "NO", "no");
            if (yesTokenId == null || noTokenId == null) continue;

            Long endAt = market.getEndAt();
            pairs.add(new ArbitrageDetectorService.MarketTokenPair(
                    market.getConditionId(),
                    market.getQuestion(),
                    yesTokenId,
                    noTokenId,
                    endAt));

            // 按 scanBatchSize 截断（缓存可能返回 500 个市场，但每次只处理配置数量）
            if (pairs.size() >= properties.getScanBatchSize()) break;
        }
        return pairs;
    }

    private String findTokenId(Map<String, String> tokenIds, String... candidates) {
        for (String key : candidates) {
            if (tokenIds.containsKey(key)) return tokenIds.get(key);
        }
        return null;
    }
}
