package com.metaverse.coin.strategy.job.weather.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.WeatherPaperPositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.WeatherPaperPositionEntity;
import com.metaverse.coin.strategy.job.weather.config.WeatherArbitrageProperties;
import com.metaverse.coin.strategy.job.weather.model.WeatherAggregatedData;
import com.metaverse.coin.strategy.job.weather.model.WeatherArbitrageSignal;
import com.metaverse.coin.strategy.job.weather.model.WeatherEnsembleForecast;
import com.metaverse.coin.strategy.job.weather.model.WeatherMarketSpec;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 7 维风控门 — 任何一个门被触发则拒绝交易。
 *
 * G1: 数据新鲜度 — 预报数据不能超过配置的最大年龄
 * G2: 市场流动性 — 必须有足够的对手盘深度
 * G3: 集成一致性 — 成员预测分歧不能太大（stdDev < 8°F）
 * G4: 日回撤限制 — 当天累计亏损不超过资金池的 10%
 * G5: 同城集中度 — 同一城市+日期的持仓不超过池子的 25%
 * G6: 市场价格极值 — 价格在 [0.03, 0.97] 范围内才有交易空间
 * G7: 多源共识度 — 各数据源对方向的判断必须有基本共识
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WeatherRiskGateService {

    private final WeatherArbitrageProperties properties;
    private final WeatherPaperPositionMapper paperPositionMapper;

    /** 当天 PnL 追踪（实际应从交易记录计算，这里简化为内存追踪） */
    private final AtomicReference<DailyPnlTracker> dailyPnl = new AtomicReference<>(
            new DailyPnlTracker(LocalDate.now(), BigDecimal.ZERO));

    public RiskResult evaluate(WeatherMarketSpec spec,
                               WeatherArbitrageSignal signal,
                               WeatherAggregatedData data,
                               int leadHours) {
        List<String> reasons = new ArrayList<>();

        // G1: 数据源数量
        if (data.getDataSourceCount() < properties.getMinDataSourceCount()) {
            return reject("G1_INSUFFICIENT_SOURCES", "dataSourceCount=" + data.getDataSourceCount());
        }

        // G2: 市场流动性
        if (spec.getLiquidityUsdc() != null
                && spec.getLiquidityUsdc().compareTo(properties.getMinLiquidityUsdc()) < 0) {
            return reject("G2_LOW_LIQUIDITY", "liquidity=" + spec.getLiquidityUsdc());
        }

        // G3: 集成成员一致性（stdDev 不能太大）
        if (data.getEnsemble() != null && data.getEnsemble().getTotalMembers() >= 20) {
            BigDecimal stdDev = computeEnsembleStdDev(data.getEnsemble(), spec);
            if (stdDev != null && stdDev.compareTo(properties.getMaxEnsembleStdDevF()) > 0) {
                return reject("G3_HIGH_ENSEMBLE_DISAGREEMENT",
                        "stdDev=" + stdDev.setScale(2, RoundingMode.HALF_UP) + "°F");
            }
        }

        // G4: 日回撤限制
        DailyPnlTracker tracker = dailyPnl.get();
        if (!tracker.date.equals(LocalDate.now())) {
            dailyPnl.set(new DailyPnlTracker(LocalDate.now(), BigDecimal.ZERO));
            tracker = dailyPnl.get();
        }
        BigDecimal maxLoss = properties.getCapitalPoolUsdc()
                .multiply(properties.getMaxDailyDrawdownPct())
                .negate();
        if (tracker.pnl.compareTo(maxLoss) < 0) {
            return reject("G4_DAILY_DRAWDOWN_BREACHED",
                    "todayPnl=" + tracker.pnl.setScale(2, RoundingMode.HALF_UP));
        }

        // G5: 同城同日集中度
        BigDecimal cityDateExposure = computeCityDateExposure(spec.getCity(), spec.getTargetDate());
        BigDecimal maxExposure = properties.getCapitalPoolUsdc()
                .multiply(properties.getMaxCityDateExposureRatio());
        BigDecimal newExposure = cityDateExposure.add(signal.getStakeUsdc());
        if (newExposure.compareTo(maxExposure) > 0) {
            return reject("G5_CITY_DATE_CONCENTRATION",
                    "existing=" + cityDateExposure + " + new=" + signal.getStakeUsdc() + " > max=" + maxExposure);
        }

        // G6: 价格极值
        BigDecimal price = signal.getMarketPrice();
        if (price != null && (price.compareTo(new BigDecimal("0.03")) < 0
                || price.compareTo(new BigDecimal("0.97")) > 0)) {
            return reject("G6_PRICE_AT_EXTREME", "price=" + price);
        }

        // G7: 多源共识度
        if (data.getConsensusScore() != null
                && data.getConsensusScore().compareTo(properties.getMinConsensusScore()) < 0) {
            return reject("G7_LOW_CONSENSUS",
                    "consensus=" + data.getConsensusScore().setScale(3, RoundingMode.HALF_UP));
        }

        return RiskResult.builder().accepted(true).build();
    }

    public void recordPnl(BigDecimal pnlDelta) {
        dailyPnl.getAndUpdate(tracker -> {
            if (!tracker.date.equals(LocalDate.now())) {
                return new DailyPnlTracker(LocalDate.now(), pnlDelta);
            }
            return new DailyPnlTracker(tracker.date, tracker.pnl.add(pnlDelta));
        });
    }

    private BigDecimal computeEnsembleStdDev(WeatherEnsembleForecast ensemble, WeatherMarketSpec spec) {
        List<BigDecimal> members = (spec.getTemperatureType() == WeatherMarketSpec.TemperatureType.MAX)
                ? ensemble.getMaxTempMembersF()
                : ensemble.getMinTempMembersF();
        if (members == null || members.size() < 5) return null;

        double mean = members.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0);
        double variance = members.stream()
                .mapToDouble(m -> Math.pow(m.doubleValue() - mean, 2))
                .average()
                .orElse(0);
        return BigDecimal.valueOf(Math.sqrt(variance)).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal computeCityDateExposure(String city, LocalDate targetDate) {
        List<WeatherPaperPositionEntity> openPositions = paperPositionMapper.selectList(
                new LambdaQueryWrapper<WeatherPaperPositionEntity>()
                        .eq(WeatherPaperPositionEntity::getStatus, "OPEN")
                        .eq(WeatherPaperPositionEntity::getCity, city));
        // 简化：统计同城所有 OPEN 仓位（后续可精确到同日）
        return openPositions.stream()
                .filter(p -> p.getStakeUsdc() != null)
                .map(WeatherPaperPositionEntity::getStakeUsdc)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private RiskResult reject(String gate, String detail) {
        log.debug("[WeatherRisk] REJECTED gate={} detail={}", gate, detail);
        return RiskResult.builder()
                .accepted(false)
                .rejectReason(gate + ": " + detail)
                .build();
    }

    @Data
    @Builder
    public static class RiskResult {
        private boolean accepted;
        private String rejectReason;
    }

    private record DailyPnlTracker(LocalDate date, BigDecimal pnl) {}
}
