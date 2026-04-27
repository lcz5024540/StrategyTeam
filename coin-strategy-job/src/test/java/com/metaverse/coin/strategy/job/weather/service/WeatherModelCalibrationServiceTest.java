package com.metaverse.coin.strategy.job.weather.service;

import com.metaverse.coin.strategy.job.weather.config.WeatherArbitrageProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WeatherModelCalibrationServiceTest {

    @Test
    void normalizeLeadHourBucket_picksNearestConfiguredBucket() {
        WeatherArbitrageProperties properties = new WeatherArbitrageProperties();
        WeatherModelCalibrationService service = new WeatherModelCalibrationService(null, null, null, properties);

        assertEquals(24, service.normalizeLeadHourBucket(26));
        assertEquals(72, service.normalizeLeadHourBucket(60));
    }
}
