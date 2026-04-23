package com.metaverse.coin.strategy.job.weather.service;

import com.metaverse.coin.strategy.job.weather.config.WeatherArbitrageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class WeatherPositionSyncService {

    private final WeatherArbitrageProperties properties;
    private final WeatherPaperTradeService paperTradeService;

    @Scheduled(fixedDelayString = "${weather-arbitrage.sync-fixed-delay-ms:300000}")
    public void syncPositions() {
        if (!properties.isEnabled()) {
            return;
        }
        if (properties.getMode() == WeatherArbitrageProperties.Mode.SHADOW
                || properties.getMode() == WeatherArbitrageProperties.Mode.OFF) {
            return;
        }
        paperTradeService.closeExpiredPositions();
        log.debug("[WeatherArbitrage] Synced paper/live weather positions");
    }
}
