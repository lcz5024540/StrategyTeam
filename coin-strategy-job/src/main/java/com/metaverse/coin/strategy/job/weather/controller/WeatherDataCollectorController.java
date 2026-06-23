package com.metaverse.coin.strategy.job.weather.controller;

import com.metaverse.coin.strategy.job.weather.service.WeatherMarketAnalysisService;
import com.metaverse.coin.strategy.job.weather.service.WeatherMarketDataCollectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 天气市场数据采集 & 分析 REST 接口。
 *
 * 用于手动触发采集和查看分析结果。
 *
 * POST /api/weather/data-collector/collect    — 立即执行一次采集
 * GET  /api/weather/data-collector/analyze    — 运行分析并返回报告
 */
@RestController
@RequestMapping("/api/weather/data-collector")
@Slf4j
@RequiredArgsConstructor
public class WeatherDataCollectorController {

    private final WeatherMarketDataCollectorService collectorService;
    private final WeatherMarketAnalysisService analysisService;

    /**
     * 手动触发一次天气市场快照采集。
     */
    @PostMapping("/collect")
    public Map<String, Object> collect() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String batchId = collectorService.collectSnapshot();
            result.put("success", true);
            result.put("batchId", batchId);
            result.put("message", "snapshot collected successfully");
        } catch (Exception ex) {
            log.error("[WeatherDataCollector] manual collect failed", ex);
            result.put("success", false);
            result.put("error", ex.getMessage());
        }
        return result;
    }

    /**
     * 运行分析并返回完整报告。
     *
     * 建议至少采集 3-7 天的数据后再调用此接口，
     * 以获得有统计意义的结果。
     */
    @GetMapping("/analyze")
    public WeatherMarketAnalysisService.MarketAnalysisReport analyze() {
        return analysisService.analyze();
    }

    /**
     * 一键：采集 + 分析。
     * 先执行一次采集，然后立即运行分析。
     */
    @PostMapping("/collect-and-analyze")
    public Map<String, Object> collectAndAnalyze() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String batchId = collectorService.collectSnapshot();
            result.put("batchId", batchId);

            WeatherMarketAnalysisService.MarketAnalysisReport report = analysisService.analyze();
            result.put("success", true);
            result.put("report", report);
        } catch (Exception ex) {
            log.error("[WeatherDataCollector] collect-and-analyze failed", ex);
            result.put("success", false);
            result.put("error", ex.getMessage());
        }
        return result;
    }
}
