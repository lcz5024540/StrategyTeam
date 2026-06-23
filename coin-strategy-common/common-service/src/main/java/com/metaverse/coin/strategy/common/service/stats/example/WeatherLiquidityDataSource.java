package com.metaverse.coin.strategy.common.service.stats.example;

import com.metaverse.coin.strategy.common.service.stats.PercentileDataSource;

import java.math.BigDecimal;
import java.util.Collection;

/**
 * Tier B (第十八批): 天气策略复用 {@link PercentileDataSource} 的 PoC.
 *
 * <p>用最小骨架证明: 同一 {@link PercentileDataSource} 接口可以承载非跟单策略的样本.
 * 跟单 {@code MarketLiquidityPercentileService} 已在使用本接口; 天气策略真正上线时只需:</p>
 *
 * <ol>
 *   <li>把这里的 {@code WeatherMarketSample} 换成自己的真实 entity / DTO,</li>
 *   <li>把 samples() 接到自己的 snapshot service / DB,</li>
 *   <li>调用 {@link com.metaverse.coin.strategy.common.service.stats.CategoryPercentileAggregator#aggregate}.</li>
 * </ol>
 *
 * <p>无 Spring 装配, 无 DB, 无 IO — 纯 PoC, 可被单元测试 new 实例化.</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 二十七节.</p>
 */
public final class WeatherLiquidityDataSource implements PercentileDataSource<WeatherLiquidityDataSource.WeatherMarketSample> {

    /** 天气策略一个市场样本的最小骨架. */
    public static final class WeatherMarketSample {
        public final String city;
        public final BigDecimal liquidityUsdc;

        public WeatherMarketSample(String city, BigDecimal liquidityUsdc) {
            this.city = city;
            this.liquidityUsdc = liquidityUsdc;
        }
    }

    private final Collection<WeatherMarketSample> samples;

    public WeatherLiquidityDataSource(Collection<WeatherMarketSample> samples) {
        this.samples = samples;
    }

    @Override
    public Collection<WeatherMarketSample> samples() {
        return samples;
    }

    @Override
    public String classify(WeatherMarketSample sample) {
        if (sample == null || sample.city == null || sample.city.isBlank()) {
            return "OTHER";
        }
        return sample.city.trim().toUpperCase(java.util.Locale.ROOT);
    }

    @Override
    public BigDecimal value(WeatherMarketSample sample) {
        return sample == null ? null : sample.liquidityUsdc;
    }
}
