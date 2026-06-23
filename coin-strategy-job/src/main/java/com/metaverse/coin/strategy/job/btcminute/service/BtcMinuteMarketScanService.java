package com.metaverse.coin.strategy.job.btcminute.service;

import com.metaverse.coin.strategy.job.btcminute.config.BtcMinuteStrategyProperties;
import com.metaverse.coin.strategy.job.btcminute.model.BtcMinuteMarketSpec;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BTC 5 分钟市场扫描服务。
 *
 * <p>扫描 Polymarket 上与 BTC 短期价格预测相关的市场，
 * 过滤出符合流动性和时间窗口条件的市场。</p>
 *
 * <p><b>注意</b>：此服务引用 {@code PolymarketApiClient}（位于 smartwallet.service 包）。
 * 这是 Phase 0 中标记的"事实上的共享基础设施"，在 Phase 1 后续迁移时
 * 将移到 engine.infra 包。当前直接引用以跑通功能。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BtcMinuteMarketScanService {

    private static final String LOG_PREFIX = "[BTC_MARKET_SCAN]";

    /** 匹配 "Bitcoin above $X" 或 "BTC > $X" 等标题模式 */
    private static final Pattern PRICE_PATTERN = Pattern.compile(
            "(?:Bitcoin|BTC).*?(?:above|below|over|under|>|<).*?\\$?([\\d,]+\\.?\\d*)",
            Pattern.CASE_INSENSITIVE);

    private final PolymarketApiClient polymarketApiClient;
    private final BtcMinuteStrategyProperties properties;

    /**
     * 扫描所有符合条件的 BTC 短期市场。
     *
     * @return 符合流动性和时间窗口条件的市场列表
     */
    public List<BtcMinuteMarketSpec> scanEligibleMarkets() {
        try {
            BtcMinuteStrategyProperties.MarketScan scanConfig = properties.getMarketScan();

            // 通过 Polymarket Gamma API 搜索 BTC 相关市场
            List<PolymarketApiClient.MarketMetadata> markets =
                    polymarketApiClient.searchMarkets(scanConfig.getSearchKeyword(), 50, false);

            if (markets == null || markets.isEmpty()) {
                log.debug("{} 未找到 BTC 相关市场", LOG_PREFIX);
                return Collections.emptyList();
            }

            List<BtcMinuteMarketSpec> eligible = new ArrayList<>();
            Instant now = Instant.now();

            for (PolymarketApiClient.MarketMetadata market : markets) {
                try {
                    BtcMinuteMarketSpec spec = evaluateMarket(market, now, scanConfig);
                    if (spec != null) {
                        eligible.add(spec);
                    }
                } catch (Exception e) {
                    log.debug("{} 评估市场失败: conditionId={} error={}",
                            LOG_PREFIX, market.getConditionId(), e.getMessage());
                }
            }

            log.info("{} 扫描完成: total={} eligible={}", LOG_PREFIX, markets.size(), eligible.size());
            return eligible;

        } catch (Exception e) {
            log.error("{} 市场扫描异常: {}", LOG_PREFIX, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    // ── 单个市场评估 ──────────────────────────────────────────────────

    private BtcMinuteMarketSpec evaluateMarket(PolymarketApiClient.MarketMetadata market,
                                                Instant now,
                                                BtcMinuteStrategyProperties.MarketScan scanConfig) {
        String title = market.getQuestion();
        if (title == null) return null;

        // 解析目标价格
        Matcher matcher = PRICE_PATTERN.matcher(title);
        if (!matcher.find()) return null;

        BigDecimal targetPrice;
        try {
            String priceStr = matcher.group(1).replace(",", "");
            targetPrice = new BigDecimal(priceStr);
        } catch (NumberFormatException e) {
            return null;
        }

        // 判断方向
        String direction = title.toLowerCase().contains("above") || title.toLowerCase().contains("over")
                || title.contains(">") ? "UP" : "DOWN";

        // 检查结算时间（endAt 是 epoch 秒或毫秒）
        Long endAt = market.getEndAt();
        if (endAt == null) return null;
        Instant endTime = endAt > 1_000_000_000_000L
                ? Instant.ofEpochMilli(endAt)
                : Instant.ofEpochSecond(endAt);

        long minutesToSettlement = Duration.between(now, endTime).toMinutes();
        if (minutesToSettlement < scanConfig.getMinMinutesToSettlement()
                || minutesToSettlement > scanConfig.getMaxMinutesToSettlement()) {
            return null;
        }

        // 获取价格信息（outcomePrices 是 List<String>）
        List<String> prices = market.getOutcomePrices();
        BigDecimal yesPrice = prices != null && !prices.isEmpty()
                ? parseBigDecimal(prices.get(0)) : null;
        BigDecimal noPrice = prices != null && prices.size() > 1
                ? parseBigDecimal(prices.get(1)) : null;

        if (yesPrice == null) return null;

        // 获取 token IDs（outcomeTokenIds 是 Map<String, String>）
        java.util.Map<String, String> tokenIds = market.getOutcomeTokenIds();
        String yesTokenId = tokenIds != null ? tokenIds.get("Yes") : null;
        String noTokenId = tokenIds != null ? tokenIds.get("No") : null;

        // 构建 spec
        return BtcMinuteMarketSpec.builder()
                .conditionId(market.getConditionId())
                .yesTokenId(yesTokenId)
                .noTokenId(noTokenId)
                .title(title)
                .targetPrice(targetPrice)
                .direction(direction)
                .windowMinutes((int) minutesToSettlement)
                .settlementTime(endTime)
                .yesPrice(yesPrice)
                .noPrice(noPrice)
                .category(market.getCategory())
                .build();
    }

    private static BigDecimal parseBigDecimal(String s) {
        try {
            return s == null || s.isBlank() ? null : new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
