package com.metaverse.coin.strategy.job.weather;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.WeatherPaperPositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.WeatherPaperPositionEntity;
import com.metaverse.coin.strategy.job.engine.PolymarketStrategy;
import com.metaverse.coin.strategy.job.engine.StrategyMode;
import com.metaverse.coin.strategy.job.engine.model.StrategyCycleResult;
import com.metaverse.coin.strategy.job.engine.model.StrategyExitResult;
import com.metaverse.coin.strategy.job.engine.model.StrategyHealthSnapshot;
import com.metaverse.coin.strategy.job.weather.config.WeatherArbitrageProperties;
import com.metaverse.coin.strategy.job.weather.service.WeatherPositionSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * 天气预测策略的 {@link PolymarketStrategy} 适配器。
 *
 * <p><b>零侵入设计</b>：不修改现有的 {@link WeatherArbitrageJob} 或任何 weather 服务类。
 * 通过委托模式将现有功能包装为统一的策略接口。</p>
 *
 * <p>现有的 {@code WeatherArbitrageJob.run()} 方法继续由 {@code @Scheduled} 驱动，
 * 本 Adapter 的 {@code runCycle()} 也委托给同一个方法。</p>
 *
 * <p>这样 StrategyRegistry 可以发现天气策略，healthCheckAll 可以汇总其状态，
 * 同时不影响天气策略原有的运行方式。</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class WeatherStrategyAdapter implements PolymarketStrategy {

    private static final String LOG_PREFIX = "[WEATHER_ADAPTER]";
    private static final String STRATEGY_ID = "WEATHER_ARB";

    private final WeatherArbitrageProperties properties;
    private final WeatherArbitrageJob weatherArbitrageJob;
    private final WeatherPaperPositionMapper paperPositionMapper;

    // ── PolymarketStrategy 接口实现 ──────────────────────────────────

    @Override
    public String getStrategyId() {
        return STRATEGY_ID;
    }

    @Override
    public String getDisplayName() {
        return "天气预测套利策略";
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    @Override
    public String getWalletId() {
        // 天气策略暂无独立 wallet_id 配置，使用默认值
        return "weather-1";
    }

    @Override
    public StrategyMode getMode() {
        WeatherArbitrageProperties.Mode mode = properties.getMode();
        if (mode == null) return StrategyMode.OFF;
        switch (mode) {
            case OFF: return StrategyMode.OFF;
            case SHADOW: return StrategyMode.SHADOW;
            case PAPER: return StrategyMode.PAPER;
            case LIVE: return StrategyMode.LIVE;
            default: return StrategyMode.OFF;
        }
    }

    /**
     * 委托给现有的 WeatherArbitrageJob.run()。
     *
     * <p>注意：天气策略自己有 @Scheduled 定时器（15分钟），
     * 此方法仅在手动调用或 StrategyRegistry 批量触发时使用。</p>
     */
    @Override
    public StrategyCycleResult runCycle() {
        Instant start = Instant.now();
        try {
            weatherArbitrageJob.run();
            return StrategyCycleResult.builder()
                    .strategyId(STRATEGY_ID)
                    .startedAt(start)
                    .duration(Duration.between(start, Instant.now()))
                    .signalsFound(0) // 天气策略内部记录信号日志
                    .ordersPlaced(0)
                    .errors(0)
                    .fatalError(false)
                    .build();
        } catch (Exception e) {
            log.error("{} runCycle 异常: {}", LOG_PREFIX, e.getMessage(), e);
            return StrategyCycleResult.builder()
                    .strategyId(STRATEGY_ID)
                    .startedAt(start)
                    .duration(Duration.between(start, Instant.now()))
                    .errors(1)
                    .errorMessages(List.of(e.getMessage()))
                    .fatalError(false)
                    .build();
        }
    }

    @Override
    public StrategyExitResult runExitScan() {
        // 天气策略的仓位同步由 WeatherPositionSyncService 的 @Scheduled 处理
        return StrategyExitResult.builder()
                .strategyId(STRATEGY_ID)
                .scannedAt(Instant.now())
                .positionsScanned(0)
                .exitsTriggered(0)
                .exitDetails(Collections.emptyList())
                .build();
    }

    @Override
    public StrategyHealthSnapshot health() {
        try {
            List<WeatherPaperPositionEntity> openPositions = paperPositionMapper.selectList(
                    new LambdaQueryWrapper<WeatherPaperPositionEntity>()
                            .eq(WeatherPaperPositionEntity::getStatus, "OPEN"));

            BigDecimal totalExposure = openPositions.stream()
                    .filter(p -> p.getStakeUsdc() != null)
                    .map(WeatherPaperPositionEntity::getStakeUsdc)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            return StrategyHealthSnapshot.builder()
                    .strategyId(STRATEGY_ID)
                    .checkedAt(Instant.now())
                    .healthy(true)
                    .openPositions(openPositions.size())
                    .totalExposureUsdc(totalExposure)
                    .walletBalanceUsdc(properties.getCapitalPoolUsdc())
                    .notes(String.format("mode=%s, %d cities configured",
                            properties.getMode(), properties.getCities().size()))
                    .build();
        } catch (Exception e) {
            log.error("{} 健康检查异常: {}", LOG_PREFIX, e.getMessage(), e);
            return StrategyHealthSnapshot.builder()
                    .strategyId(STRATEGY_ID)
                    .checkedAt(Instant.now())
                    .healthy(false)
                    .notes("健康检查异常: " + e.getMessage())
                    .build();
        }
    }
}
