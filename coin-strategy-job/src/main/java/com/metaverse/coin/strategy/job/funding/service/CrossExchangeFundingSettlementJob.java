package com.metaverse.coin.strategy.job.funding.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeFundingSettlementMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeFundingSettlementEntity;
import com.metaverse.coin.strategy.core.arbitrage.config.MultiExchangeFundingRateProperties;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangeMarketDataProvider;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangePaperTradingService;
import com.metaverse.coin.strategy.core.arbitrage.multi.Exchange;
import com.metaverse.coin.strategy.core.arbitrage.multi.NormalizedFundingRateSnapshot;
import com.metaverse.coin.strategy.core.arbitrage.multi.PaperHedgePosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(prefix = "arbitrage.multi-exchange", name = "settlement-enabled", havingValue = "true")
public class CrossExchangeFundingSettlementJob {
    private static final Logger LOG = LoggerFactory.getLogger(CrossExchangeFundingSettlementJob.class);
    private final CrossExchangePaperTradingService paper;
    private final CrossExchangeFundingSettlementMapper mapper;
    private final Map<Exchange, CrossExchangeMarketDataProvider> providers;
    private final Duration maximumQuoteAge;

    @Autowired
    public CrossExchangeFundingSettlementJob(CrossExchangePaperTradingService paper,
            CrossExchangeFundingSettlementMapper mapper,
            List<CrossExchangeMarketDataProvider> providers,
            MultiExchangeFundingRateProperties properties) {
        this(paper, mapper, providers, properties.getMaximumQuoteAge());
    }

    CrossExchangeFundingSettlementJob(CrossExchangePaperTradingService paper,
            CrossExchangeFundingSettlementMapper mapper,
            List<CrossExchangeMarketDataProvider> providers) {
        this(paper, mapper, providers, Duration.ofDays(2));
    }

    private CrossExchangeFundingSettlementJob(CrossExchangePaperTradingService paper,
            CrossExchangeFundingSettlementMapper mapper,
            List<CrossExchangeMarketDataProvider> providers, Duration maximumQuoteAge) {
        this.paper = paper;
        this.mapper = mapper;
        this.providers = providers.stream().collect(Collectors.toMap(
                CrossExchangeMarketDataProvider::exchange, Function.identity()));
        this.maximumQuoteAge = maximumQuoteAge;
    }

    @Scheduled(fixedDelayString = "${arbitrage.multi-exchange.settlement-delay-ms:60000}")
    public void settle() {
        settle(Instant.now());
    }

    void settle(Instant now) {
        for (PaperHedgePosition position : paper.positions()) {
            if (position.status() != PaperHedgePosition.Status.OPENED) {
                continue;
            }
            settleLeg(position, position.opportunity().longQuote(), true, now);
            settleLeg(position, position.opportunity().shortQuote(), false, now);
        }
    }

    private void settleLeg(PaperHedgePosition position, NormalizedFundingRateSnapshot openingQuote,
            boolean longLeg, Instant now) {
        CrossExchangeMarketDataProvider provider = providers.get(openingQuote.exchange());
        if (provider == null) {
            return;
        }
        try {
            NormalizedFundingRateSnapshot latestQuote = provider.fundingRate(position.opportunity().symbol());
            if (latestQuote.isStale(now, maximumQuoteAge)) {
                throw new IllegalStateException("latest funding quote is stale");
            }
            BigDecimal expectedAmount = fundingAmount(position, openingQuote);
            BigDecimal actualAmount = fundingAmount(position, latestQuote);
            record(position, openingQuote, latestQuote.rawFundingRate(),
                    longLeg ? expectedAmount.negate() : expectedAmount,
                    longLeg ? actualAmount.negate() : actualAmount, now);
        } catch (RuntimeException exception) {
            LOG.warn("Skip paper funding settlement because public quote is unavailable: position={}, exchange={}",
                    position.positionId(), openingQuote.exchange(), exception);
        }
    }

    private BigDecimal fundingAmount(PaperHedgePosition position, NormalizedFundingRateSnapshot quote) {
        return position.hedgedQuantity().multiply(quote.markPrice()).multiply(quote.rawFundingRate());
    }

    private void record(PaperHedgePosition position, NormalizedFundingRateSnapshot scheduleQuote,
            BigDecimal actualFundingRate, BigDecimal expectedAmount, BigDecimal actualAmount, Instant now) {
        long intervalSeconds = scheduleQuote.fundingIntervalHours() * 3600L;
        Instant settlementAt = scheduleQuote.nextFundingTime();
        while (!settlementAt.isAfter(now)) {
            if (position.openedAt() == null || !settlementAt.isBefore(position.openedAt())) {
                recordEstimatedSettlement(position, scheduleQuote, actualFundingRate,
                        expectedAmount, actualAmount, now, settlementAt);
            }
            settlementAt = settlementAt.plusSeconds(intervalSeconds);
        }
    }

    private void recordEstimatedSettlement(PaperHedgePosition position,
            NormalizedFundingRateSnapshot scheduleQuote, BigDecimal fundingRate,
            BigDecimal expectedAmount, BigDecimal estimatedAmount, Instant observedAt,
            Instant settlementAt) {
        Date settlementTime = Date.from(settlementAt);
        Long existing = mapper.selectCount(new LambdaQueryWrapper<CrossExchangeFundingSettlementEntity>()
                .eq(CrossExchangeFundingSettlementEntity::getPositionCode, position.positionId())
                .eq(CrossExchangeFundingSettlementEntity::getExchangeName, scheduleQuote.exchange().name())
                .eq(CrossExchangeFundingSettlementEntity::getSettlementTime, settlementTime));
        if (existing != null && existing > 0L) {
            return;
        }
        mapper.insert(new CrossExchangeFundingSettlementEntity()
                .setPositionCode(position.positionId())
                .setExchangeName(scheduleQuote.exchange().name())
                .setSymbol(position.opportunity().symbol())
                .setFundingRate(fundingRate)
                .setExpectedAmount(expectedAmount)
                .setActualAmount(estimatedAmount)
                .setSettlementSource("PAPER_PUBLIC_ESTIMATE")
                .setObservedAt(Date.from(observedAt))
                .setSettlementTime(settlementTime)
                .setCreatedAt(new Date()));
    }
}
