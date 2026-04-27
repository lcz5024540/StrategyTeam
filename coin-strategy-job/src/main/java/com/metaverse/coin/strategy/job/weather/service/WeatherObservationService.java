package com.metaverse.coin.strategy.job.weather.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.WeatherObservationMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.WeatherObservationEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class WeatherObservationService {

    private final WeatherObservationMapper observationMapper;

    public WeatherObservationEntity upsertObservation(String city,
                                                     LocalDate targetDate,
                                                     BigDecimal actualMaxF,
                                                     BigDecimal actualMinF,
                                                     String source,
                                                     Date resolvedAt) {
        WeatherObservationEntity existing = observationMapper.selectOne(new LambdaQueryWrapper<WeatherObservationEntity>()
                .eq(WeatherObservationEntity::getCity, city)
                .eq(WeatherObservationEntity::getTargetDate, toDate(targetDate))
                .last("limit 1"));
        if (existing == null) {
            existing = new WeatherObservationEntity()
                    .setCity(city)
                    .setTargetDate(toDate(targetDate))
                    .setActualMaxF(actualMaxF)
                    .setActualMinF(actualMinF)
                    .setSource(source)
                    .setResolvedAt(resolvedAt == null ? new Date() : resolvedAt);
            observationMapper.insert(existing);
            return existing;
        }
        existing.setActualMaxF(actualMaxF)
                .setActualMinF(actualMinF)
                .setSource(source)
                .setResolvedAt(resolvedAt == null ? new Date() : resolvedAt);
        observationMapper.updateById(existing);
        return existing;
    }

    public Optional<WeatherObservationEntity> findObservation(String city, LocalDate targetDate) {
        return Optional.ofNullable(observationMapper.selectOne(new LambdaQueryWrapper<WeatherObservationEntity>()
                .eq(WeatherObservationEntity::getCity, city)
                .eq(WeatherObservationEntity::getTargetDate, toDate(targetDate))
                .last("limit 1")));
    }

    public List<WeatherObservationEntity> listAllObservations() {
        return observationMapper.selectList(new LambdaQueryWrapper<>());
    }

    private Date toDate(LocalDate localDate) {
        return Date.from(localDate.atStartOfDay(ZoneOffset.UTC).toInstant());
    }
}
