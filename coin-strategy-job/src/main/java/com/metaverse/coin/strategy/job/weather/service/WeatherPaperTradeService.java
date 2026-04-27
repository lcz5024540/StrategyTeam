package com.metaverse.coin.strategy.job.weather.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.WeatherPaperPositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.WeatherPaperPositionEntity;
import com.metaverse.coin.strategy.job.weather.model.WeatherArbitrageSignal;
import com.metaverse.coin.strategy.job.weather.model.WeatherMarketSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Date;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WeatherPaperTradeService {

    private final WeatherPaperPositionMapper paperPositionMapper;

    public WeatherPaperPositionEntity openPaperPosition(WeatherMarketSpec spec, WeatherArbitrageSignal signal) {
        WeatherPaperPositionEntity existing = paperPositionMapper.selectOne(new LambdaQueryWrapper<WeatherPaperPositionEntity>()
                .eq(WeatherPaperPositionEntity::getMarketId, spec.getMarketId())
                .eq(WeatherPaperPositionEntity::getStatus, "OPEN")
                .last("limit 1"));
        if (existing != null) {
            return existing;
        }
        BigDecimal quantity = signal.getStakeUsdc().compareTo(BigDecimal.ZERO) <= 0 || signal.getMarketPrice().compareTo(BigDecimal.ZERO) <= 0
                ? BigDecimal.ZERO
                : signal.getStakeUsdc().divide(signal.getMarketPrice(), 8, RoundingMode.HALF_UP);
        WeatherPaperPositionEntity entity = new WeatherPaperPositionEntity()
                .setMarketId(spec.getMarketId())
                .setTokenId(spec.getTokenId())
                .setCity(spec.getCity())
                .setQuestion(spec.getQuestion())
                .setSide(signal.getSide().name())
                .setEntryPrice(signal.getMarketPrice())
                .setQuantity(quantity)
                .setStakeUsdc(signal.getStakeUsdc())
                .setFairValue(signal.getFairValue())
                .setConfidence(signal.getConfidence())
                .setMarketEndAt(Date.from(Instant.ofEpochSecond(spec.getEndTimestampSeconds())))
                .setStatus("OPEN")
                .setOpenedAt(new Date());
        paperPositionMapper.insert(entity);
        return entity;
    }

    public List<WeatherPaperPositionEntity> listOpenPositions() {
        return paperPositionMapper.selectList(new LambdaQueryWrapper<WeatherPaperPositionEntity>()
                .eq(WeatherPaperPositionEntity::getStatus, "OPEN"));
    }

    public void closeExpiredPositions() {
        Date now = new Date();
        List<WeatherPaperPositionEntity> openPositions = paperPositionMapper.selectList(new LambdaQueryWrapper<WeatherPaperPositionEntity>()
                .eq(WeatherPaperPositionEntity::getStatus, "OPEN")
                .le(WeatherPaperPositionEntity::getMarketEndAt, now));
        for (WeatherPaperPositionEntity position : openPositions) {
            position.setStatus("CLOSED")
                    .setClosedAt(now)
                    .setExitReason("MARKET_SETTLED");
            paperPositionMapper.updateById(position);
        }
    }
}
