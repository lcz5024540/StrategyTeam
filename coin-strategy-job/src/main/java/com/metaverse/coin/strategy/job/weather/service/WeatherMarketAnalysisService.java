package com.metaverse.coin.strategy.job.weather.service;

import com.metaverse.coin.strategy.common.dao.mapper.WeatherMarketLifecycleLogMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.WeatherMarketLifecycleLogEntity;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 天气市场生命周期统计分析服务。
 *
 * 从采集到的快照数据中提取市场运行规律，输出关键参数建议：
 *
 * 1. 市场上线提前量分布 (firstSeenLeadHours)
 *    → 决定何时开始关注市场
 *
 * 2. 市场停止接单时间点 (acceptingOrders=true 的最后快照 leadHours)
 *    → 决定最晚入场时间
 *
 * 3. 活跃市场 lead time 分布
 *    → 决定策略分层 (ultra-near / near / medium / far)
 *
 * 4. 各城市/温度类型的市场频率
 *    → 指导城市配置优先级
 *
 * 5. 市场结算结果分布 (YES/NO 比例)
 *    → 评估市场偏向
 *
 * 6. 停止接单到 endAt 的时间差
 *    → 决定何时必须退出
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WeatherMarketAnalysisService {

    private final WeatherMarketLifecycleLogMapper lifecycleLogMapper;

    /**
     * 生成完整的统计分析报告。
     */
    public MarketAnalysisReport analyze() {
        log.info("[WeatherMarketAnalysis] starting analysis...");

        // 获取所有首次扫描记录（了解市场上线时间）
        List<WeatherMarketLifecycleLogEntity> firstSeenRecords = lifecycleLogMapper.selectFirstSeenRecords();

        // 获取已结算市场（了解市场全生命周期）
        List<WeatherMarketLifecycleLogEntity> resolvedRecords = lifecycleLogMapper.selectResolvedMarkets();

        // 获取所有独立市场 ID 的全部快照（了解 acceptingOrders 变化）
        Set<String> allMarketIds = new HashSet<>();
        firstSeenRecords.forEach(r -> { if (r.getMarketId() != null) allMarketIds.add(r.getMarketId()); });
        resolvedRecords.forEach(r -> { if (r.getMarketId() != null) allMarketIds.add(r.getMarketId()); });

        // ── 1. 市场上线提前量分布 ──
        LeadTimeDistribution firstSeenDistribution = computeLeadTimeDistribution(
                firstSeenRecords.stream()
                        .filter(r -> r.getFirstSeenLeadHours() != null)
                        .map(r -> r.getFirstSeenLeadHours())
                        .collect(Collectors.toList()),
                "first_seen_lead_hours"
        );

        // ── 2. 活跃市场 lead time 分布 ──
        LeadTimeDistribution activeLeadDistribution = computeLeadTimeDistribution(
                firstSeenRecords.stream()
                        .filter(r -> r.getLeadHours() != null && Boolean.TRUE.equals(r.getActive()))
                        .map(r -> r.getLeadHours())
                        .collect(Collectors.toList()),
                "active_market_lead_hours"
        );

        // ── 3. 停止接单分析 ──
        AcceptingOrdersAnalysis acceptingOrdersAnalysis = analyzeAcceptingOrders(allMarketIds);

        // ── 4. 城市频率统计 ──
        Map<String, Integer> cityFrequency = new LinkedHashMap<>();
        for (WeatherMarketLifecycleLogEntity r : firstSeenRecords) {
            if (r.getCity() != null) {
                cityFrequency.merge(r.getCity(), 1, Integer::sum);
            }
        }

        // ── 5. 温度类型分布 ──
        Map<String, Integer> tempTypeFrequency = new LinkedHashMap<>();
        for (WeatherMarketLifecycleLogEntity r : firstSeenRecords) {
            if (r.getTemperatureType() != null) {
                tempTypeFrequency.merge(r.getTemperatureType(), 1, Integer::sum);
            }
        }

        // ── 6. 结算结果分布 ──
        Map<String, Integer> outcomeDistribution = new LinkedHashMap<>();
        for (WeatherMarketLifecycleLogEntity r : resolvedRecords) {
            String outcome = r.getResolvedOutcome() != null ? r.getResolvedOutcome() : "UNKNOWN";
            outcomeDistribution.merge(outcome, 1, Integer::sum);
        }

        // ── 7. 价格分布（结算市场的最终价格与结果的关系）──
        List<BigDecimal> resolvedYesPrices = resolvedRecords.stream()
                .filter(r -> r.getYesPrice() != null)
                .map(WeatherMarketLifecycleLogEntity::getYesPrice)
                .collect(Collectors.toList());

        // ── 8. 阈值温度分布 ──
        Map<String, List<BigDecimal>> thresholdByCity = new LinkedHashMap<>();
        for (WeatherMarketLifecycleLogEntity r : firstSeenRecords) {
            if (r.getCity() != null && r.getThresholdF() != null) {
                thresholdByCity.computeIfAbsent(r.getCity(), k -> new ArrayList<>()).add(r.getThresholdF());
            }
        }

        MarketAnalysisReport report = MarketAnalysisReport.builder()
                .totalUniqueMarkets(allMarketIds.size())
                .totalFirstSeenRecords(firstSeenRecords.size())
                .totalResolvedMarkets(resolvedRecords.size())
                .firstSeenLeadHourDistribution(firstSeenDistribution)
                .activeLeadHourDistribution(activeLeadDistribution)
                .acceptingOrdersAnalysis(acceptingOrdersAnalysis)
                .cityFrequency(cityFrequency)
                .temperatureTypeFrequency(tempTypeFrequency)
                .outcomeDistribution(outcomeDistribution)
                .thresholdByCity(thresholdByCity)
                .build();

        logReport(report);
        return report;
    }

    /**
     * 分析 acceptingOrders 的变化时点。
     * 遍历每个市场的快照序列，找到 acceptingOrders 从 true→false 的转换点。
     */
    private AcceptingOrdersAnalysis analyzeAcceptingOrders(Set<String> marketIds) {
        List<Integer> lastAcceptingLeadHours = new ArrayList<>();
        List<Integer> stopAcceptingToEndHours = new ArrayList<>();
        int marketsWithTransition = 0;
        int marketsAlwaysAccepting = 0;
        int marketsNeverAccepting = 0;

        for (String marketId : marketIds) {
            List<WeatherMarketLifecycleLogEntity> snapshots =
                    lifecycleLogMapper.selectByMarketIdOrdered(marketId);
            if (snapshots.isEmpty()) continue;

            boolean everAccepting = snapshots.stream().anyMatch(s -> Boolean.TRUE.equals(s.getAcceptingOrders()));
            boolean everNotAccepting = snapshots.stream().anyMatch(s -> Boolean.FALSE.equals(s.getAcceptingOrders()));

            if (!everAccepting) {
                marketsNeverAccepting++;
                continue;
            }

            if (!everNotAccepting) {
                marketsAlwaysAccepting++;
                continue;
            }

            // 找到最后一个 acceptingOrders=true 的快照
            WeatherMarketLifecycleLogEntity lastAccepting = null;
            for (int i = snapshots.size() - 1; i >= 0; i--) {
                if (Boolean.TRUE.equals(snapshots.get(i).getAcceptingOrders())) {
                    lastAccepting = snapshots.get(i);
                    break;
                }
            }

            if (lastAccepting != null && lastAccepting.getLeadHours() != null) {
                lastAcceptingLeadHours.add(lastAccepting.getLeadHours());
                marketsWithTransition++;

                if (lastAccepting.getEndAtMs() != null) {
                    long diffMs = lastAccepting.getEndAtMs() - lastAccepting.getScannedAt().getTime();
                    int hoursToEnd = (int) (diffMs / 3_600_000L);
                    stopAcceptingToEndHours.add(hoursToEnd);
                }
            }
        }

        return AcceptingOrdersAnalysis.builder()
                .marketsWithTransition(marketsWithTransition)
                .marketsAlwaysAccepting(marketsAlwaysAccepting)
                .marketsNeverAccepting(marketsNeverAccepting)
                .lastAcceptingLeadHoursDistribution(
                        computeLeadTimeDistribution(lastAcceptingLeadHours, "last_accepting_lead_hours"))
                .stopAcceptingToEndHoursDistribution(
                        computeLeadTimeDistribution(stopAcceptingToEndHours, "stop_accepting_to_end_hours"))
                .build();
    }

    private LeadTimeDistribution computeLeadTimeDistribution(List<Integer> values, String label) {
        if (values == null || values.isEmpty()) {
            return LeadTimeDistribution.builder()
                    .label(label)
                    .count(0)
                    .build();
        }

        Collections.sort(values);
        int count = values.size();
        double mean = values.stream().mapToInt(Integer::intValue).average().orElse(0);
        int median = values.get(count / 2);
        int min = values.get(0);
        int max = values.get(count - 1);
        int p10 = values.get(Math.min(count - 1, (int) (count * 0.10)));
        int p25 = values.get(Math.min(count - 1, (int) (count * 0.25)));
        int p75 = values.get(Math.min(count - 1, (int) (count * 0.75)));
        int p90 = values.get(Math.min(count - 1, (int) (count * 0.90)));

        // 分桶统计
        Map<String, Integer> buckets = new LinkedHashMap<>();
        buckets.put("0-6h", 0);
        buckets.put("6-12h", 0);
        buckets.put("12-24h", 0);
        buckets.put("24-48h", 0);
        buckets.put("48-72h", 0);
        buckets.put("72-168h", 0);
        buckets.put(">168h", 0);

        for (int v : values) {
            if (v < 0) continue; // 已过期
            if (v < 6) buckets.merge("0-6h", 1, Integer::sum);
            else if (v < 12) buckets.merge("6-12h", 1, Integer::sum);
            else if (v < 24) buckets.merge("12-24h", 1, Integer::sum);
            else if (v < 48) buckets.merge("24-48h", 1, Integer::sum);
            else if (v < 72) buckets.merge("48-72h", 1, Integer::sum);
            else if (v < 168) buckets.merge("72-168h", 1, Integer::sum);
            else buckets.merge(">168h", 1, Integer::sum);
        }

        return LeadTimeDistribution.builder()
                .label(label)
                .count(count)
                .mean(BigDecimal.valueOf(mean).setScale(1, RoundingMode.HALF_UP))
                .median(median)
                .min(min)
                .max(max)
                .p10(p10)
                .p25(p25)
                .p75(p75)
                .p90(p90)
                .buckets(buckets)
                .build();
    }

    private void logReport(MarketAnalysisReport report) {
        log.info("╔═══════════════════════════════════════════════════════════════╗");
        log.info("║          WEATHER MARKET LIFECYCLE ANALYSIS REPORT            ║");
        log.info("╠═══════════════════════════════════════════════════════════════╣");
        log.info("║ Total unique markets:   {}", report.totalUniqueMarkets);
        log.info("║ Total first-seen:       {}", report.totalFirstSeenRecords);
        log.info("║ Total resolved:         {}", report.totalResolvedMarkets);
        log.info("╠═══════════════════════════════════════════════════════════════╣");
        log.info("║ 1. MARKET FIRST-SEEN LEAD HOURS (how early markets appear)");
        logDistribution(report.firstSeenLeadHourDistribution);
        log.info("╠═══════════════════════════════════════════════════════════════╣");
        log.info("║ 2. ACTIVE MARKET LEAD HOURS DISTRIBUTION");
        logDistribution(report.activeLeadHourDistribution);
        log.info("╠═══════════════════════════════════════════════════════════════╣");
        log.info("║ 3. ACCEPTING ORDERS ANALYSIS");
        if (report.acceptingOrdersAnalysis != null) {
            log.info("║   Markets with true→false transition: {}", report.acceptingOrdersAnalysis.marketsWithTransition);
            log.info("║   Markets always accepting:           {}", report.acceptingOrdersAnalysis.marketsAlwaysAccepting);
            log.info("║   Markets never accepting:            {}", report.acceptingOrdersAnalysis.marketsNeverAccepting);
            log.info("║   Last accepting lead hours:");
            logDistribution(report.acceptingOrdersAnalysis.lastAcceptingLeadHoursDistribution);
            log.info("║   Stop-accepting to endAt hours:");
            logDistribution(report.acceptingOrdersAnalysis.stopAcceptingToEndHoursDistribution);
        }
        log.info("╠═══════════════════════════════════════════════════════════════╣");
        log.info("║ 4. CITY FREQUENCY: {}", report.cityFrequency);
        log.info("║ 5. TEMP TYPE:      {}", report.temperatureTypeFrequency);
        log.info("║ 6. OUTCOMES:       {}", report.outcomeDistribution);
        log.info("╚═══════════════════════════════════════════════════════════════╝");
    }

    private void logDistribution(LeadTimeDistribution dist) {
        if (dist == null || dist.count == 0) {
            log.info("║     (no data)");
            return;
        }
        log.info("║     count={} mean={} median={}h min={}h max={}h",
                dist.count, dist.mean, dist.median, dist.min, dist.max);
        log.info("║     p10={}h p25={}h p75={}h p90={}h",
                dist.p10, dist.p25, dist.p75, dist.p90);
        if (dist.buckets != null) {
            log.info("║     buckets: {}", dist.buckets);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Result models
    // ════════════════════════════════════════════════════════════════════

    @Data
    @Builder
    public static class MarketAnalysisReport {
        private int totalUniqueMarkets;
        private int totalFirstSeenRecords;
        private int totalResolvedMarkets;
        private LeadTimeDistribution firstSeenLeadHourDistribution;
        private LeadTimeDistribution activeLeadHourDistribution;
        private AcceptingOrdersAnalysis acceptingOrdersAnalysis;
        private Map<String, Integer> cityFrequency;
        private Map<String, Integer> temperatureTypeFrequency;
        private Map<String, Integer> outcomeDistribution;
        private Map<String, List<BigDecimal>> thresholdByCity;
    }

    @Data
    @Builder
    public static class LeadTimeDistribution {
        private String label;
        private int count;
        private BigDecimal mean;
        private Integer median;
        private Integer min;
        private Integer max;
        private Integer p10;
        private Integer p25;
        private Integer p75;
        private Integer p90;
        private Map<String, Integer> buckets;
    }

    @Data
    @Builder
    public static class AcceptingOrdersAnalysis {
        /** 有 true→false 转换的市场数量 */
        private int marketsWithTransition;
        /** 始终接受订单（直到被采集到已结算）的市场数量 */
        private int marketsAlwaysAccepting;
        /** 从未接受订单的市场数量 */
        private int marketsNeverAccepting;
        /** 最后一次 acceptingOrders=true 时的 leadHours 分布 */
        private LeadTimeDistribution lastAcceptingLeadHoursDistribution;
        /** 停止接单到 endAt 的时间间隔分布 (小时) */
        private LeadTimeDistribution stopAcceptingToEndHoursDistribution;
    }
}
