package com.metaverse.coin.strategy.job.weather.service;

import com.metaverse.coin.strategy.common.dao.mapper.WeatherArbitrageSignalLogMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.WeatherArbitrageSignalLogEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.WeatherCalibrationProfileEntity;
import com.metaverse.coin.strategy.job.weather.config.WeatherArbitrageProperties;
import com.metaverse.coin.strategy.job.weather.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * 多频率调度器 — 按 lead-hour 分四档独立扫描。
 *
 * ┌──────────────┬───────────┬────────────┬────────────────────────────────┐
 * │ 档位         │ leadHours │ 扫描间隔   │ 策略特点                       │
 * ├──────────────┼───────────┼────────────┼────────────────────────────────┤
 * │ ULTRA_NEAR   │ 0-6h      │ 2 min      │ 观测层主导，高确定性，大仓位   │
 * │ NEAR         │ 6-24h     │ 5 min      │ 集成分布主导，较高确定性       │
 * │ MEDIUM       │ 24-72h    │ 15 min     │ 集成+校准，中等确定性          │
 * │ FAR          │ 72-168h   │ 30 min     │ 集成为主，需要大 edge          │
 * └──────────────┴───────────┴────────────┴────────────────────────────────┘
 *
 * 每档独立运行，互不阻塞。超近期的高频扫描是利润的核心来源。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WeatherMultiTierScheduler {

    private final WeatherArbitrageProperties properties;
    private final WeatherMarketScanService marketScanService;
    private final WeatherForecastAggregator forecastAggregator;
    private final WeatherFairValueServiceV2 fairValueServiceV2;
    private final WeatherArbitrageSignalService signalService;
    private final WeatherKellyStakeService kellyStakeService;
    private final WeatherRiskGateService riskGateService;
    private final WeatherModelCalibrationService calibrationService;
    private final WeatherCapitalPoolService capitalPoolService;
    private final WeatherPaperTradeService paperTradeService;
    private final WeatherArbitrageSignalLogMapper signalLogMapper;

    @Scheduled(fixedDelayString = "${weather-arbitrage.ultra-near-scan-ms:120000}")
    public void scanUltraNear() {
        runTierScan("ULTRA_NEAR", 0, 6);
    }

    @Scheduled(fixedDelayString = "${weather-arbitrage.near-scan-ms:300000}")
    public void scanNear() {
        runTierScan("NEAR", 6, 24);
    }

    @Scheduled(fixedDelayString = "${weather-arbitrage.medium-scan-ms:900000}")
    public void scanMedium() {
        runTierScan("MEDIUM", 24, 72);
    }

    @Scheduled(fixedDelayString = "${weather-arbitrage.far-scan-ms:1800000}")
    public void scanFar() {
        runTierScan("FAR", 72, 168);
    }

    private void runTierScan(String tierName, int minLeadH, int maxLeadH) {
        if (!properties.isEnabled() || properties.getMode() == WeatherArbitrageProperties.Mode.OFF) {
            return;
        }

        List<WeatherParsedMarket> allMarkets = marketScanService.scanMarkets();
        int signalsGenerated = 0;
        int ordersPlaced = 0;

        for (WeatherParsedMarket parsedMarket : allMarkets) {
            WeatherMarketSpec spec = parsedMarket.getSpec();

            // 过滤：只处理当前 tier 窗口的市场
            int leadHours = computeLeadHours(spec);
            if (leadHours < minLeadH || leadHours >= maxLeadH) {
                continue;
            }

            try {
                // Step 1: 多源数据聚合
                WeatherAggregatedData data = forecastAggregator.aggregate(
                        spec.getCity(), spec.getTargetDate(),
                        spec.getThresholdF(), spec.getTemperatureType());

                // Step 2: 计算 fair value（V2 三层模型）
                Optional<WeatherCalibrationProfileEntity> profile = calibrationService.findProfile(
                        spec.getCity(), spec.getTargetDate(), data.getLeadHours());
                WeatherFairValueResult fairValue = fairValueServiceV2.computeFairValue(spec, data, profile);

                // Step 3: 信号评估
                WeatherArbitrageSignal signal = signalService.evaluate(spec, fairValue);

                // Step 4: Kelly 仓位
                if (signal.getSide() != WeatherArbitrageSignal.Side.PASS) {
                    BigDecimal stake = kellyStakeService.calculateStake(signal, leadHours);
                    signal.setStakeUsdc(stake);
                }

                // Step 5: 风控门
                if (signal.getSide() != WeatherArbitrageSignal.Side.PASS) {
                    WeatherRiskGateService.RiskResult riskResult = riskGateService.evaluate(
                            spec, signal, data, leadHours);
                    if (!riskResult.isAccepted()) {
                        signal.setSide(WeatherArbitrageSignal.Side.PASS);
                        signal.setReason("risk_gate:" + riskResult.getRejectReason());
                    }
                }

                // Step 6: 持久化信号日志
                persistSignal(spec, signal, tierName, data);
                signalsGenerated++;

                // Step 7: 执行
                if (signal.getSide() != WeatherArbitrageSignal.Side.PASS
                        && signal.getStakeUsdc().compareTo(BigDecimal.ZERO) > 0) {
                    if (properties.getMode() == WeatherArbitrageProperties.Mode.PAPER
                            && capitalPoolService.canOpenPosition()) {
                        paperTradeService.openPaperPosition(spec, signal);
                        ordersPlaced++;
                    } else if (properties.getMode() == WeatherArbitrageProperties.Mode.LIVE) {
                        // LIVE 下单（通过 WeatherLiveTradeService，后续接入）
                        log.info("[Weather-{}] LIVE signal: city={} question={} side={} stake={} edge={} fv={}",
                                tierName, spec.getCity(), spec.getQuestion(),
                                signal.getSide(), signal.getStakeUsdc(),
                                signal.getEdge(), signal.getFairValue());
                        ordersPlaced++;
                    }
                }

            } catch (Exception ex) {
                log.warn("[Weather-{}] Error processing market. city={} question={} error={}",
                        tierName, spec.getCity(), spec.getQuestion(), ex.getMessage());
            }
        }

        if (signalsGenerated > 0) {
            log.info("[Weather-{}] Scan complete. markets={} signals={} orders={}",
                    tierName, allMarkets.size(), signalsGenerated, ordersPlaced);
        }
    }

    private int computeLeadHours(WeatherMarketSpec spec) {
        if (spec.getEndTimestampSeconds() <= 0) return 24;
        long nowSeconds = System.currentTimeMillis() / 1000;
        return (int) ((spec.getEndTimestampSeconds() - nowSeconds) / 3600);
    }

    private void persistSignal(WeatherMarketSpec spec, WeatherArbitrageSignal signal,
                                String tierName, WeatherAggregatedData data) {
        try {
            signalLogMapper.insert(new WeatherArbitrageSignalLogEntity()
                    .setMarketId(spec.getMarketId())
                    .setCity(spec.getCity())
                    .setMarketPrice(signal.getMarketPrice())
                    .setFairValue(signal.getFairValue())
                    .setEdge(signal.getEdge())
                    .setConfidence(signal.getConfidence())
                    .setForecastAgeMinutes(signal.getForecastAgeMinutes())
                    .setLeadHours(signal.getLeadHours())
                    .setCalibrationVersion(signal.getCalibrationVersion())
                    .setMode(tierName + "_" + properties.getMode().name())
                    .setResultLabel("")
                    .setAction(signal.getSide() == null ? "PASS" : signal.getSide().name())
                    .setReason(signal.getReason())
                    .setScannedAt(new Date()));
        } catch (Exception ex) {
            log.debug("[Weather] Failed to persist signal log: {}", ex.getMessage());
        }
    }
}
