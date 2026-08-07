package com.metaverse.coin.strategy.htx.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.mock.env.MockEnvironment;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HtxMarketMakerPropertiesTest {

    private HtxMarketMakerProperties bind(MockEnvironment env) {
        Binder binder = new Binder(ConfigurationPropertySources.get(env));
        return binder.bind("htx.market-maker", Bindable.of(HtxMarketMakerProperties.class))
                .orElse(new HtxMarketMakerProperties());
    }

    @Test void defaults_are_conservative() {
        HtxMarketMakerProperties p = new HtxMarketMakerProperties();
        assertFalse(p.isEnabled());                                   // 默认关（急停语义）
        assertEquals(List.of("btcusdt", "ethusdt", "xautusdt"), p.getSymbols());  // 白名单
        assertEquals(10_000L, p.getPollIntervalMs());
        assertEquals(0, new BigDecimal("0.003").compareTo(p.getSpreadPct()));
        assertEquals(0, new BigDecimal("0.002").compareTo(p.getFeeRatePerSide()));
        assertEquals(0, new BigDecimal("20").compareTo(p.getPerCellQuoteNotional()));
        assertEquals(3, p.getMaxConcurrentCellsPerSymbol());
        assertEquals(0, new BigDecimal("0.003").compareTo(p.getMinGapPct()));
        assertEquals(900_000L, p.getBuyTtlMs());
        assertEquals(0, new BigDecimal("0.001").compareTo(p.getRepriceThresholdPct()));
        assertEquals(300_000L, p.getInsufficientBalanceBackoffMs());
        assertEquals(0L, p.getSellTtlMs());
    }

    @Test void binds_overrides_from_environment() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("htx.market-maker.enabled", "true");
        env.setProperty("htx.market-maker.symbols", "btcusdt");
        env.setProperty("htx.market-maker.spread-pct", "0.008");
        env.setProperty("htx.market-maker.max-concurrent-cells-per-symbol", "5");
        env.setProperty("htx.market-maker.insufficient-balance-backoff-ms", "60000");
        HtxMarketMakerProperties p = bind(env);
        assertTrue(p.isEnabled());
        assertEquals(List.of("btcusdt"), p.getSymbols());
        assertEquals(0, new BigDecimal("0.008").compareTo(p.getSpreadPct()));
        assertEquals(5, p.getMaxConcurrentCellsPerSymbol());
        assertEquals(60_000L, p.getInsufficientBalanceBackoffMs());
    }

    @Test void sellFollowDefaultsEnabledWithStopLossAndThreshold() {
        HtxMarketMakerProperties p = new HtxMarketMakerProperties();
        assertTrue(p.isSellFollowEnabled());
        assertEquals(0, new BigDecimal("0.005").compareTo(p.getStopLossPct()));
        assertEquals(0, new BigDecimal("0.001").compareTo(p.getSellRepriceThresholdPct()));
    }
}
