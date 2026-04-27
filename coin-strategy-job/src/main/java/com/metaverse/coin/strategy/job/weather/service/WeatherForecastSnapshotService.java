package com.metaverse.coin.strategy.job.weather.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.WeatherForecastSnapshotMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.WeatherForecastSnapshotEntity;
import com.metaverse.coin.strategy.job.weather.model.WeatherForecast;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class WeatherForecastSnapshotService {

    private final WeatherForecastSnapshotMapper snapshotMapper;

    public WeatherForecastSnapshotEntity saveSnapshot(WeatherForecast forecast) {
        WeatherForecastSnapshotEntity entity = new WeatherForecastSnapshotEntity()
                .setCity(forecast.getCity())
                .setForecastDate(toDate(forecast.getForecastDate()))
                .setTargetDate(toDate(forecast.getTargetDate()))
                .setTempMaxF(forecast.getForecastTempMaxF())
                .setTempMinF(forecast.getForecastTempMinF())
                .setTempAvgF(forecast.getForecastTempAvgF())
                .setProvider(forecast.getProvider())
                .setLeadHours(forecast.getLeadHours())
                .setDataSourceCount(forecast.getDataSourceCount())
                .setFetchedAt(Date.from(forecast.getFetchedAt()));
        snapshotMapper.insert(entity);
        return entity;
    }

    public Optional<WeatherForecastSnapshotEntity> findLatestSnapshot(String city, LocalDate targetDate) {
        return snapshotMapper.selectList(new LambdaQueryWrapper<WeatherForecastSnapshotEntity>()
                        .eq(WeatherForecastSnapshotEntity::getCity, city)
                        .eq(WeatherForecastSnapshotEntity::getTargetDate, toDate(targetDate))
                        .orderByDesc(WeatherForecastSnapshotEntity::getFetchedAt)
                        .last("limit 1"))
                .stream()
                .findFirst();
    }

    public List<WeatherForecastSnapshotEntity> listAllSnapshots() {
        return snapshotMapper.selectList(new LambdaQueryWrapper<>());
    }

    private Date toDate(LocalDate localDate) {
        return Date.from(localDate.atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    public static WeatherForecast toModel(WeatherForecastSnapshotEntity entity) {
        return WeatherForecast.builder()
                .city(entity.getCity())
                .forecastDate(toLocalDate(entity.getForecastDate()))
                .targetDate(toLocalDate(entity.getTargetDate()))
                .forecastTempMaxF(entity.getTempMaxF())
                .forecastTempMinF(entity.getTempMinF())
                .forecastTempAvgF(entity.getTempAvgF())
                .provider(entity.getProvider())
                .leadHours(entity.getLeadHours() == null ? 0 : entity.getLeadHours())
                .dataSourceCount(entity.getDataSourceCount() == null ? 1 : entity.getDataSourceCount())
                .fetchedAt(entity.getFetchedAt() == null ? Instant.EPOCH : entity.getFetchedAt().toInstant())
                .build();
    }

    public static LocalDate toLocalDate(Date date) {
        return date == null ? null : date.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
    }
}
