package com.metaverse.coin.strategy.job;

import org.junit.jupiter.api.Test;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class FundingOnlyComponentExcludeFilterTest {

    private final CachingMetadataReaderFactory metadataReaderFactory =
            new CachingMetadataReaderFactory();

    @Test
    void excludesPolymarketComponentsInFundingOnlyMode() throws Exception {
        FundingOnlyComponentExcludeFilter filter = filterFor("FUNDING_ONLY");

        assertThat(matches(filter,
                "com.metaverse.coin.strategy.job.smartwallet.service.PolymarketWebSocketClient"))
                .isTrue();
        assertThat(matches(filter,
                "com.metaverse.coin.strategy.job.btcminute.BtcMinuteStrategyJob"))
                .isTrue();
        assertThat(matches(filter,
                "com.metaverse.coin.strategy.job.execution.application.ExecutionApplicationService"))
                .isTrue();
        assertThat(matches(filter,
                "com.metaverse.coin.strategy.job.marketdata.application.PredictionMarketDataApplicationService"))
                .isTrue();
        assertThat(matches(filter,
                "com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletMarketInfoService"))
                .isTrue();
        assertThat(matches(filter,
                "com.metaverse.coin.strategy.job.grid.GridTradingStrategyJob"))
                .isTrue();
        assertThat(matches(filter,
                "com.metaverse.coin.strategy.job.weather.service.WeatherMarketScanService"))
                .isTrue();
    }

    @Test
    void excludesEveryPrivateTradingEntryPointInFundingOnlyMode() throws Exception {
        FundingOnlyComponentExcludeFilter filter = filterFor("FUNDING_ONLY");

        assertThat(matches(filter,
                "com.metaverse.coin.strategy.job.controller.BinanceController"))
                .isTrue();
        assertThat(matches(filter,
                "com.metaverse.coin.strategy.job.hedge.controller.BinanceHedgeController"))
                .isTrue();
        assertThat(matches(filter,
                "com.metaverse.coin.strategy.job.service.BinanceTradingService"))
                .isTrue();
        assertThat(matches(filter,
                "com.metaverse.coin.strategy.job.config.BinanceConfig"))
                .isTrue();
        assertThat(matches(filter,
                "com.metaverse.coin.strategy.job.funding.controller.BinanceSingleExchangeArbitrageRuntimeController"))
                .isTrue();
        assertThat(matches(filter,
                "com.metaverse.coin.strategy.job.funding.service.BinanceWebSocketStreamService"))
                .isTrue();
    }

    @Test
    void keepsFundingArbitrageComponentsInFundingOnlyMode() throws Exception {
        FundingOnlyComponentExcludeFilter filter = filterFor("FUNDING_ONLY");

        assertThat(matches(filter,
                "com.metaverse.coin.strategy.job.funding.service.CrossExchangeFundingScanJob"))
                .isFalse();
        assertThat(matches(filter,
                "com.metaverse.coin.strategy.job.config.DataSourceConfig"))
                .isFalse();
        assertThat(matches(filter,
                "com.metaverse.coin.strategy.job.funding.service.FundingArbSpotPerpDryRunJob"))
                .isFalse();
        assertThat(matches(filter,
                "com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpDryRunEngine"))
                .isFalse();
    }

    @Test
    void keepsAllComponentsInFullMode() throws Exception {
        FundingOnlyComponentExcludeFilter filter = filterFor("FULL");

        assertThat(matches(filter,
                "com.metaverse.coin.strategy.job.smartwallet.service.PolymarketWebSocketClient"))
                .isFalse();
    }

    private FundingOnlyComponentExcludeFilter filterFor(String mode) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(FundingOnlyComponentExcludeFilter.DEPLOYMENT_MODE_PROPERTY, mode);
        return new FundingOnlyComponentExcludeFilter(environment);
    }

    private boolean matches(FundingOnlyComponentExcludeFilter filter, String className)
            throws Exception {
        return filter.match(metadataReaderFactory.getMetadataReader(className),
                metadataReaderFactory);
    }
}
