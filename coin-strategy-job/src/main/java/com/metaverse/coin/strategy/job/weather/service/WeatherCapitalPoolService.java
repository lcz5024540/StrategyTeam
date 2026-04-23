package com.metaverse.coin.strategy.job.weather.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.WeatherPaperPositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.WeatherPaperPositionEntity;
import com.metaverse.coin.strategy.job.weather.config.WeatherArbitrageProperties;
import com.metaverse.coin.strategy.job.weather.model.WeatherArbitrageSignal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WeatherCapitalPoolService {

    private final WeatherArbitrageProperties properties;
    private final WeatherPaperPositionMapper weatherPaperPositionMapper;

    public BigDecimal calculateStake(WeatherArbitrageSignal signal) {
        BigDecimal edge = signal.getEdge() == null ? BigDecimal.ZERO : signal.getEdge().max(BigDecimal.ZERO);
        BigDecimal desired = edge.multiply(properties.getCapitalPoolUsdc())
                .multiply(new BigDecimal("0.25"))
                .setScale(8, RoundingMode.HALF_UP);
        BigDecimal stake = desired.min(properties.getMaxSingleStakeUsdc());
        BigDecimal available = properties.getCapitalPoolUsdc().subtract(allocatedUsdc()).max(BigDecimal.ZERO);
        return stake.min(available).max(BigDecimal.ZERO);
    }

    public BigDecimal allocatedUsdc() {
        List<WeatherPaperPositionEntity> openPositions = weatherPaperPositionMapper.selectList(new LambdaQueryWrapper<WeatherPaperPositionEntity>()
                .eq(WeatherPaperPositionEntity::getStatus, "OPEN"));
        return openPositions.stream()
                .map(WeatherPaperPositionEntity::getStakeUsdc)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public boolean canOpenPosition() {
        Long openCount = weatherPaperPositionMapper.selectCount(new LambdaQueryWrapper<WeatherPaperPositionEntity>()
                .eq(WeatherPaperPositionEntity::getStatus, "OPEN"));
        return openCount < properties.getMaxOpenPositions()
                && allocatedUsdc().compareTo(properties.getCapitalPoolUsdc()) < 0;
    }
}
