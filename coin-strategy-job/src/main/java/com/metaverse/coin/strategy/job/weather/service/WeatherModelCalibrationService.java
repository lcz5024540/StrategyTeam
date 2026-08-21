package com.metaverse.coin.strategy.job.weather.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.WeatherCalibrationProfileMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.WeatherCalibrationProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.WeatherForecastSnapshotEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.WeatherObservationEntity;
import com.metaverse.coin.strategy.job.weather.config.WeatherArbitrageProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class WeatherModelCalibrationService {

    private final WeatherCalibrationProfileMapper calibrationProfileMapper;
    private final WeatherForecastSnapshotService forecastSnapshotService;
    private final WeatherObservationService observationService;
    private final WeatherArbitrageProperties properties;

    public Optional<WeatherCalibrationProfileEntity> findProfile(String city, LocalDate targetDate, int leadHours) {
        int month = targetDate.getMonthValue();
        int bucket = normalizeLeadHourBucket(leadHours);
        return calibrationProfileMapper.selectList(new LambdaQueryWrapper<WeatherCalibrationProfileEntity>()
                        .eq(WeatherCalibrationProfileEntity::getCity, city)
                        .eq(WeatherCalibrationProfileEntity::getMonth, month)
                        .eq(WeatherCalibrationProfileEntity::getLeadHourBucket, bucket)
                        .orderByDesc(WeatherCalibrationProfileEntity::getUpdatedAt)
                        .last("limit 1"))
                .stream()
                .findFirst();
    }

    public List<WeatherCalibrationProfileEntity> rebuildProfiles() {
        List<WeatherForecastSnapshotEntity> snapshots = forecastSnapshotService.listAllSnapshots();
        List<WeatherObservationEntity> observations = observationService.listAllObservations();
        List<WeatherCalibrationProfileEntity> saved = new ArrayList<>();
        for (WeatherArbitrageProperties.CityRule cityRule : properties.getCities()) {
            String city = cityRule.getName();
            for (int month = 1; month <= 12; month++) {
                for (Integer bucket : properties.getAllowedLeadHourBuckets()) {
                    List<Sample> samples = collectSamples(snapshots, observations, city, month, bucket);
                    if (samples.isEmpty()) {
                        continue;
                    }
                    WeatherCalibrationProfileEntity entity = calibrationProfileMapper.selectOne(new LambdaQueryWrapper<WeatherCalibrationProfileEntity>()
                            .eq(WeatherCalibrationProfileEntity::getCity, city)
                            .eq(WeatherCalibrationProfileEntity::getMonth, month)
                            .eq(WeatherCalibrationProfileEntity::getLeadHourBucket, bucket)
                            .last("limit 1"));
                    if (entity == null) {
                        entity = new WeatherCalibrationProfileEntity();
                    }
                    entity.setCity(city)
                            .setMonth(month)
                            .setLeadHourBucket(bucket)
                            .setSigmaMaxF(sampleStdDev(samples.stream().map(Sample::maxError).toList()))
                            .setSigmaMinF(sampleStdDev(samples.stream().map(Sample::minError).toList()))
                            .setSampleSize(samples.size())
                            .setVersion("city-" + city.replace(' ', '_') + "-m" + month + "-h" + bucket)
                            .setUpdatedAt(new java.util.Date());
                    if (entity.getId() == null) {
                        calibrationProfileMapper.insert(entity);
                    } else {
                        calibrationProfileMapper.updateById(entity);
                    }
                    saved.add(entity);
                }
            }
        }
        saved.sort(Comparator.comparing(WeatherCalibrationProfileEntity::getCity));
        return saved;
    }

    public int normalizeLeadHourBucket(int leadHours) {
        return properties.getAllowedLeadHourBuckets().stream()
                .min(Comparator.<Integer>comparingInt(bucket -> Math.abs(bucket - leadHours))
                        .thenComparing(Comparator.reverseOrder()))
                .orElse(24);
    }

    private List<Sample> collectSamples(List<WeatherForecastSnapshotEntity> snapshots,
                                        List<WeatherObservationEntity> observations,
                                        String city,
                                        int month,
                                        int bucket) {
        List<Sample> samples = new ArrayList<>();
        for (WeatherForecastSnapshotEntity snapshot : snapshots) {
            LocalDate targetDate = WeatherForecastSnapshotService.toLocalDate(snapshot.getTargetDate());
            if (!city.equals(snapshot.getCity()) || targetDate == null || targetDate.getMonthValue() != month) {
                continue;
            }
            int snapshotBucket = normalizeLeadHourBucket(snapshot.getLeadHours() == null ? 0 : snapshot.getLeadHours());
            if (snapshotBucket != bucket) {
                continue;
            }
            for (WeatherObservationEntity observation : observations) {
                if (!city.equals(observation.getCity())) {
                    continue;
                }
                LocalDate observationDate = WeatherForecastSnapshotService.toLocalDate(observation.getTargetDate());
                if (!targetDate.equals(observationDate)) {
                    continue;
                }
                samples.add(new Sample(
                        abs(snapshot.getTempMaxF().subtract(observation.getActualMaxF())),
                        abs(snapshot.getTempMinF().subtract(observation.getActualMinF()))
                ));
            }
        }
        return samples;
    }

    private BigDecimal sampleStdDev(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            return properties.getSigmaF();
        }
        if (values.size() == 1) {
            return values.get(0).max(new BigDecimal("0.5"));
        }
        BigDecimal mean = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 8, RoundingMode.HALF_UP);
        BigDecimal variance = values.stream()
                .map(value -> value.subtract(mean))
                .map(value -> value.multiply(value))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size() - 1L), 8, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(Math.sqrt(variance.doubleValue())).max(new BigDecimal("0.5"));
    }

    private BigDecimal abs(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.abs();
    }

    private record Sample(BigDecimal maxError, BigDecimal minError) {
    }
}
