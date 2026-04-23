package com.metaverse.coin.strategy.job.weather;

import com.metaverse.coin.strategy.common.dao.mapper.WeatherArbitrageSignalLogMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.WeatherArbitrageSignalLogEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.WeatherCalibrationProfileEntity;
import com.metaverse.coin.strategy.job.weather.config.WeatherArbitrageProperties;
import com.metaverse.coin.strategy.job.weather.model.WeatherArbitrageSignal;
import com.metaverse.coin.strategy.job.weather.model.WeatherFairValueResult;
import com.metaverse.coin.strategy.job.weather.model.WeatherForecast;
import com.metaverse.coin.strategy.job.weather.model.WeatherParsedMarket;
import com.metaverse.coin.strategy.job.weather.service.WeatherArbitrageSignalService;
import com.metaverse.coin.strategy.job.weather.service.WeatherCapitalPoolService;
import com.metaverse.coin.strategy.job.weather.service.WeatherFairValueService;
import com.metaverse.coin.strategy.job.weather.service.WeatherForecastProvider;
import com.metaverse.coin.strategy.job.weather.service.WeatherForecastSnapshotService;
import com.metaverse.coin.strategy.job.weather.service.WeatherMarketScanService;
import com.metaverse.coin.strategy.job.weather.service.WeatherModelCalibrationService;
import com.metaverse.coin.strategy.job.weather.service.WeatherPaperTradeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class WeatherArbitrageJob {

    private final WeatherArbitrageProperties properties;
    private final WeatherForecastProvider forecastProvider;
    private final WeatherForecastSnapshotService snapshotService;
    private final WeatherModelCalibrationService calibrationService;
    private final WeatherFairValueService fairValueService;
    private final WeatherArbitrageSignalService signalService;
    private final WeatherMarketScanService marketScanService;
    private final WeatherCapitalPoolService capitalPoolService;
    private final WeatherPaperTradeService paperTradeService;
    private final WeatherArbitrageSignalLogMapper signalLogMapper;

    @Scheduled(fixedDelayString = "${weather-arbitrage.scan-fixed-delay-ms:900000}")
    public void run() {
        if (!properties.isEnabled() || properties.getMode() == WeatherArbitrageProperties.Mode.OFF) {
            return;
        }
        List<WeatherParsedMarket> markets = marketScanService.scanMarkets();
        for (WeatherParsedMarket parsedMarket : markets) {
            Optional<WeatherForecast> forecast = forecastProvider.fetchForecast(
                    parsedMarket.getSpec().getCity(),
                    parsedMarket.getSpec().getTargetDate());
            if (forecast.isEmpty()) {
                persistSignal(parsedMarket, WeatherArbitrageSignal.builder()
                        .marketId(parsedMarket.getSpec().getMarketId())
                        .tokenId(parsedMarket.getSpec().getTokenId())
                        .city(parsedMarket.getSpec().getCity())
                        .question(parsedMarket.getSpec().getQuestion())
                        .side(WeatherArbitrageSignal.Side.PASS)
                        .reason("no_forecast")
                        .mode(properties.getMode().name())
                        .build());
                continue;
            }
            snapshotService.saveSnapshot(forecast.get());
            Optional<WeatherCalibrationProfileEntity> profile = calibrationService.findProfile(
                    parsedMarket.getSpec().getCity(),
                    parsedMarket.getSpec().getTargetDate(),
                    forecast.get().getLeadHours());
            WeatherFairValueResult fairValue = fairValueService.computeFairValue(parsedMarket.getSpec(), forecast.get(), profile);
            WeatherArbitrageSignal signal = signalService.evaluate(parsedMarket.getSpec(), fairValue);
            signal.setStakeUsdc(signal.getSide() == WeatherArbitrageSignal.Side.PASS ? java.math.BigDecimal.ZERO : capitalPoolService.calculateStake(signal));
            persistSignal(parsedMarket, signal);
            if (signal.getSide() == WeatherArbitrageSignal.Side.PASS) {
                continue;
            }
            if (properties.getMode() == WeatherArbitrageProperties.Mode.PAPER && capitalPoolService.canOpenPosition()) {
                paperTradeService.openPaperPosition(parsedMarket.getSpec(), signal);
            } else if (properties.getMode() == WeatherArbitrageProperties.Mode.LIVE) {
                log.warn("[WeatherArbitrage] LIVE mode is wired but should remain disabled until validation gates are complete. marketId={}", signal.getMarketId());
            }
        }
    }

    private void persistSignal(WeatherParsedMarket parsedMarket, WeatherArbitrageSignal signal) {
        persistSignal(parsedMarket.getSpec().getMarketId(), parsedMarket.getSpec().getCity(), signal);
    }

    private void persistSignal(String marketId, String city, WeatherArbitrageSignal signal) {
        signalLogMapper.insert(new WeatherArbitrageSignalLogEntity()
                .setMarketId(marketId)
                .setCity(city)
                .setMarketPrice(signal.getMarketPrice())
                .setFairValue(signal.getFairValue())
                .setEdge(signal.getEdge())
                .setConfidence(signal.getConfidence())
                .setForecastAgeMinutes(signal.getForecastAgeMinutes())
                .setLeadHours(signal.getLeadHours())
                .setCalibrationVersion(signal.getCalibrationVersion())
                .setMode(signal.getMode())
                .setResultLabel("")
                .setAction(signal.getSide() == null ? "PASS" : signal.getSide().name())
                .setReason(signal.getReason())
                .setScannedAt(new java.util.Date()));
    }
}
