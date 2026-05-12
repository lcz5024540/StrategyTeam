package com.metaverse.coin.strategy.job.weather;

import com.metaverse.coin.strategy.job.weather.config.WeatherArbitrageProperties;
import com.metaverse.coin.strategy.job.weather.service.WeatherMarketAnalysisService;
import com.metaverse.coin.strategy.job.weather.service.WeatherMarketDataCollectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 天气市场生命周期数据采集定时任务。
 *
 * 每小时执行一次采集快照，持续积累市场状态变化数据。
 * 通过连续多天的采集，可以完整观测到：
 * - 市场从上线到结算的完整生命周期
 * - acceptingOrders 从 true→false 的精确时间点
 * - 各时间段的价格变化
 *
 * 建议至少采集 7 天以上再运行分析。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WeatherMarketDataCollectorJob {

    private final WeatherArbitrageProperties properties;
    private final WeatherMarketDataCollectorService collectorService;
    private final WeatherMarketAnalysisService analysisService;

    /**
     * 每小时执行一次数据采集。
     * 通过 weather-arbitrage.data-collector-delay-ms 配置间隔。
     */
    @Scheduled(fixedDelayString = "${weather-arbitrage.data-collector-delay-ms:3600000}")
    public void collectData() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            String batchId = collectorService.collectSnapshot();
            log.info("[WeatherDataCollectorJob] snapshot done. batchId={}", batchId);
        } catch (Exception ex) {
            log.error("[WeatherDataCollectorJob] snapshot failed", ex);
        }
    }

    /**
     * 每天运行一次分析报告（UTC 08:00）。
     */
    @Scheduled(cron = "${weather-arbitrage.analysis-cron:0 0 8 * * ?}")
    public void runDailyAnalysis() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            WeatherMarketAnalysisService.MarketAnalysisReport report = analysisService.analyze();
            log.info("[WeatherDataCollectorJob] daily analysis done. uniqueMarkets={} resolved={}",
                    report.getTotalUniqueMarkets(), report.getTotalResolvedMarkets());
        } catch (Exception ex) {
            log.error("[WeatherDataCollectorJob] analysis failed", ex);
        }
    }
}
