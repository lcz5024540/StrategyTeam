package com.metaverse.coin.strategy.job.weather.service;

import com.metaverse.coin.strategy.job.weather.model.WeatherForecast;

import java.time.LocalDate;
import java.util.Optional;

public interface WeatherForecastProvider {

    Optional<WeatherForecast> fetchForecast(String city, LocalDate targetDate);

    String providerName();
}
