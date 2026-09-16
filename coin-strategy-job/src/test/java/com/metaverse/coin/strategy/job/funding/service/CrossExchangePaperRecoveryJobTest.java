package com.metaverse.coin.strategy.job.funding.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbOpportunityMapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbPositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeArbOpportunityEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeArbPositionEntity;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangeArbitragePersistenceService;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangeMarketDataProvider;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangePaperTradingService;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangeRiskService;
import com.metaverse.coin.strategy.core.arbitrage.multi.Exchange;
import com.metaverse.coin.strategy.core.arbitrage.multi.NormalizedFundingRateSnapshot;
import com.metaverse.coin.strategy.core.arbitrage.multi.NormalizedOrderBookSnapshot;
import com.metaverse.coin.strategy.core.arbitrage.multi.PaperAtomicHedgeExecutor;
import com.metaverse.coin.strategy.core.arbitrage.multi.PaperPerpetualGateway;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CrossExchangePaperRecoveryJobTest {

    @Test
    void restoresOpenedPositionFromDatabase() {
        CrossExchangeArbPositionMapper positions = mock(CrossExchangeArbPositionMapper.class);
        CrossExchangeArbOpportunityMapper opportunities = mock(CrossExchangeArbOpportunityMapper.class);
        CrossExchangeArbitragePersistenceService persistence =
                mock(CrossExchangeArbitragePersistenceService.class);
        CrossExchangePaperTradingService paper = paperService();
        CrossExchangeArbPositionEntity row = new CrossExchangeArbPositionEntity()
                .setPositionCode("position-1").setOpportunityCode("opportunity-1")
                .setSymbol("BTCUSDT").setLongExchange("BINANCE").setShortExchange("OKX")
                .setHedgedQuantity(BigDecimal.ONE).setStatus("OPENED")
                .setOpenedAt(Date.from(Instant.now().minusSeconds(60)));
        when(positions.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(row));
        when(opportunities.selectOne(any(LambdaQueryWrapper.class))).thenReturn(
                new CrossExchangeArbOpportunityEntity().setOpportunityCode("opportunity-1")
                        .setEstimatedNetYield(new BigDecimal("0.0002"))
                        .setExecutableNotional(new BigDecimal("100")));
        CrossExchangePaperRecoveryJob job = new CrossExchangePaperRecoveryJob(positions,
                opportunities, paper, persistence,
                List.of(provider(Exchange.BINANCE), provider(Exchange.OKX)));

        job.recover();

        assertEquals(1, paper.positions().size());
        verify(persistence).saveRisk("position-1", "opportunity-1", "RECOVERY", true, null);
    }

    private CrossExchangePaperTradingService paperService() {
        CrossExchangeRiskService risk = new CrossExchangeRiskService(
                new CrossExchangeRiskService.Limits(new BigDecimal("1000"),
                        Duration.ofSeconds(10), Duration.ofDays(7), BigDecimal.ZERO));
        return new CrossExchangePaperTradingService(
                new PaperAtomicHedgeExecutor(new PaperPerpetualGateway()), risk);
    }

    private CrossExchangeMarketDataProvider provider(Exchange exchange) {
        return new CrossExchangeMarketDataProvider() {
            @Override public Exchange exchange() { return exchange; }
            @Override public List<String> supportedUsdtPerpetualSymbols() { return List.of("BTCUSDT"); }
            @Override public NormalizedFundingRateSnapshot fundingRate(String symbol) {
                Instant now = Instant.now();
                String rate = exchange == Exchange.BINANCE ? "-0.001" : "-0.0001";
                return new NormalizedFundingRateSnapshot(exchange, symbol, new BigDecimal(rate), 8,
                        now.plusSeconds(3600), new BigDecimal("100"), new BigDecimal("100"), now);
            }
            @Override public NormalizedOrderBookSnapshot orderBook(String symbol, int depth) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
