package com.metaverse.coin.strategy.job.weather.service;

import com.metaverse.coin.strategy.common.dao.mapper.WeatherMarketLifecycleLogMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.WeatherMarketLifecycleLogEntity;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketApiClient;
import com.metaverse.coin.strategy.job.weather.config.WeatherArbitrageProperties;
import com.metaverse.coin.strategy.job.weather.model.WeatherMarketSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * 天气市场生命周期数据采集服务。
 *
 * 扫描 Polymarket 上所有天气相关市场（含活跃、已关闭、已结算），
 * 记录每个市场的当前状态快照到 DB。
 *
 * 通过持续采集 + 历史分析，得出关键参数：
 * 1. 天气市场提前多久上线（firstSeenLeadHours 分布）
 * 2. 市场何时停止接单（acceptingOrders 从 true→false 的时间点）
 * 3. Lead time 分布（活跃市场中 lead hours 的统计）
 * 4. 各城市的市场频率
 * 5. 市场持续时长（从 first seen 到 resolution）
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WeatherMarketDataCollectorService {

    private final PolymarketApiClient polymarketApiClient;
    private final WeatherArbitrageProperties properties;
    private final WeatherMarketTitleParser titleParser;
    private final WeatherMarketLifecycleLogMapper lifecycleLogMapper;

    /**
     * 执行一次完整的天气市场快照采集。
     *
     * @return 本次采集的 batchId
     */
    public String collectSnapshot() {
        String batchId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Date now = new Date();
        log.info("[WeatherDataCollector] starting batch={}", batchId);

        int totalScanned = 0;
        int weatherFound = 0;
        int saved = 0;

        // ── 1. 抓取活跃市场 (active=true, closed=false) ──
        List<PolymarketApiClient.MarketMetadata> activeMarkets = fetchMarkets(true, false);
        totalScanned += activeMarkets.size();

        // ── 2. 抓取已关闭但未结算市场 (active=false, closed=true) ──
        List<PolymarketApiClient.MarketMetadata> closedMarkets = fetchMarkets(false, true);
        totalScanned += closedMarkets.size();

        // ── 合并去重 ──
        Map<String, PolymarketApiClient.MarketMetadata> allMarkets = new LinkedHashMap<>();
        for (PolymarketApiClient.MarketMetadata m : activeMarkets) {
            if (m.getConditionId() != null) allMarkets.putIfAbsent(m.getConditionId(), m);
        }
        for (PolymarketApiClient.MarketMetadata m : closedMarkets) {
            if (m.getConditionId() != null) allMarkets.putIfAbsent(m.getConditionId(), m);
        }

        // ── 筛选天气市场 + 记录快照 ──
        for (PolymarketApiClient.MarketMetadata market : allMarkets.values()) {
            if (!isWeatherCandidate(market)) continue;
            weatherFound++;

            WeatherMarketLifecycleLogEntity record = buildRecord(market, batchId, now);
            if (record != null) {
                lifecycleLogMapper.insert(record);
                saved++;
            }
        }

        log.info("[WeatherDataCollector] batch={} done. totalScanned={} weatherFound={} saved={}",
                batchId, totalScanned, weatherFound, saved);
        return batchId;
    }

    /**
     * 调用 Gamma API 获取市场列表。
     * 分页拉取最多 500 条。
     */
    private List<PolymarketApiClient.MarketMetadata> fetchMarkets(boolean active, boolean closed) {
        try {
            // 复用已有的 fetchActiveMarkets，但需要支持 closed 参数
            // 直接调用 fetchActiveMarkets 只能获取 active=true 的
            // 对于 closed 市场，通过搜索 tag=weather 获取
            if (active && !closed) {
                return polymarketApiClient.fetchActiveMarkets(500);
            } else {
                // 已关闭市场：尝试通过 event slug 搜索天气相关
                return fetchClosedWeatherMarkets();
            }
        } catch (Exception ex) {
            log.warn("[WeatherDataCollector] fetch failed. active={} closed={} error={}",
                    active, closed, ex.getMessage());
            return List.of();
        }
    }

    /**
     * 获取已关闭的天气市场。
     * Gamma API 支持 tag 过滤：/markets?tag=weather&closed=true
     */
    private List<PolymarketApiClient.MarketMetadata> fetchClosedWeatherMarkets() {
        // 通过已有活跃市场的 event slugs 搜索同一事件下的已结算市场
        // 天气市场通常在同一事件组下（如 "NYC Weather May 2026"）
        List<PolymarketApiClient.MarketMetadata> results = new ArrayList<>();

        // 获取活跃天气市场的 slugs
        List<PolymarketApiClient.MarketMetadata> activeWeather = polymarketApiClient.fetchActiveMarkets(500);
        Set<String> seenSlugs = new HashSet<>();

        for (PolymarketApiClient.MarketMetadata market : activeWeather) {
            if (!isWeatherCandidate(market)) continue;
            String slug = market.getSlug();
            if (slug != null && seenSlugs.add(slug)) {
                try {
                    List<PolymarketApiClient.MarketMetadata> eventMarkets =
                            polymarketApiClient.fetchEventMarketsBySlug(slug);
                    for (PolymarketApiClient.MarketMetadata em : eventMarkets) {
                        if (em.getConditionId() != null) {
                            results.add(em);
                        }
                    }
                } catch (Exception ex) {
                    log.debug("[WeatherDataCollector] event slug fetch failed: slug={} error={}",
                            slug, ex.getMessage());
                }
            }
            // 限制请求量，避免 API 限流
            if (seenSlugs.size() >= 20) break;
        }

        return results;
    }

    private WeatherMarketLifecycleLogEntity buildRecord(PolymarketApiClient.MarketMetadata market,
                                                         String batchId, Date now) {
        // 解析市场标题
        String yesToken = resolveYesToken(market);
        BigDecimal yesPrice = resolvePrice(market, 0);
        BigDecimal noPrice = resolvePrice(market, 1);
        long endTimestampSeconds = market.getEndAt() != null ? market.getEndAt() / 1000L : 0L;

        Optional<WeatherMarketSpec> parsed = titleParser.parse(
                market.getConditionId(), yesToken, market.getQuestion(),
                yesPrice, noPrice, BigDecimal.ZERO, endTimestampSeconds);

        // 计算 lead hours
        Integer leadHours = null;
        if (market.getEndAt() != null) {
            long diffMs = market.getEndAt() - System.currentTimeMillis();
            leadHours = (int) (diffMs / 3_600_000L);
        }

        // 是否首次出现
        boolean firstSeen = false;
        Integer firstSeenLeadHours = null;
        if (market.getConditionId() != null) {
            int existingCount = lifecycleLogMapper.countByMarketId(market.getConditionId());
            if (existingCount == 0) {
                firstSeen = true;
                firstSeenLeadHours = leadHours;
            }
        }

        WeatherMarketLifecycleLogEntity entity = new WeatherMarketLifecycleLogEntity()
                .setMarketId(market.getConditionId())
                .setQuestion(market.getQuestion())
                .setActive(market.isActive())
                .setClosed(market.isClosed())
                .setAcceptingOrders(market.isAcceptingOrders())
                .setResolutionStatus(market.getResolutionStatus())
                .setResolvedOutcome(market.getResolvedOutcome())
                .setEndAtMs(market.getEndAt())
                .setLeadHours(leadHours)
                .setFirstSeenLeadHours(firstSeenLeadHours)
                .setYesPrice(yesPrice)
                .setNoPrice(noPrice)
                .setBatchId(batchId)
                .setScannedAt(now)
                .setFirstSeen(firstSeen);

        // 填入解析出的天气信息
        if (parsed.isPresent()) {
            WeatherMarketSpec spec = parsed.get();
            entity.setCity(spec.getCity());
            entity.setTemperatureType(spec.getTemperatureType() != null ? spec.getTemperatureType().name() : null);
            entity.setThresholdF(spec.getThresholdF());
            entity.setTargetDate(spec.getTargetDate() != null ? spec.getTargetDate().toString() : null);
        }

        return entity;
    }

    private boolean isWeatherCandidate(PolymarketApiClient.MarketMetadata market) {
        String text = ((market.getCategory() == null ? "" : market.getCategory()) + " "
                + (market.getQuestion() == null ? "" : market.getQuestion())).toLowerCase();
        boolean categoryMatch = text.contains("weather");
        boolean cityMatch = properties.getCities().stream()
                .flatMap(rule -> rule.getAliases().stream())
                .anyMatch(alias -> text.contains(alias.toLowerCase()));
        return categoryMatch || cityMatch;
    }

    private String resolveYesToken(PolymarketApiClient.MarketMetadata market) {
        return market.getOutcomeTokenIds().entrySet().stream()
                .filter(entry -> "YES".equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseGet(() -> market.getOutcomeTokenIds().values().stream().findFirst().orElse(null));
    }

    private BigDecimal resolvePrice(PolymarketApiClient.MarketMetadata market, int index) {
        if (market.getOutcomePrices() == null || market.getOutcomePrices().size() <= index) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(market.getOutcomePrices().get(index));
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }
}
