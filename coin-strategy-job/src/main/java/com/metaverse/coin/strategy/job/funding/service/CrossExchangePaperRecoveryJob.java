package com.metaverse.coin.strategy.job.funding.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbOpportunityMapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbPositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeArbOpportunityEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeArbPositionEntity;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangeArbitragePersistenceService;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangeMarketDataProvider;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangeOpportunity;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangePaperTradingService;
import com.metaverse.coin.strategy.core.arbitrage.multi.Exchange;
import com.metaverse.coin.strategy.core.arbitrage.multi.PaperHedgePosition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(prefix = "arbitrage.multi-exchange", name = "recovery-enabled",
        havingValue = "true")
public class CrossExchangePaperRecoveryJob {
    private final CrossExchangeArbPositionMapper positions;
    private final CrossExchangeArbOpportunityMapper opportunities;
    private final CrossExchangePaperTradingService paper;
    private final CrossExchangeArbitragePersistenceService persistence;
    private final Map<Exchange, CrossExchangeMarketDataProvider> providers;

    public CrossExchangePaperRecoveryJob(CrossExchangeArbPositionMapper positions,
            CrossExchangeArbOpportunityMapper opportunities, CrossExchangePaperTradingService paper,
            CrossExchangeArbitragePersistenceService persistence,
            List<CrossExchangeMarketDataProvider> providers) {
        this.positions = positions;
        this.opportunities = opportunities;
        this.paper = paper;
        this.persistence = persistence;
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(
                CrossExchangeMarketDataProvider::exchange, Function.identity()));
    }

    @Scheduled(fixedDelayString = "${arbitrage.multi-exchange.recovery-delay-ms:60000}")
    public void recover() {
        List<CrossExchangeArbPositionEntity> active = positions.selectList(
                new LambdaQueryWrapper<CrossExchangeArbPositionEntity>()
                        .eq(CrossExchangeArbPositionEntity::getStatus,
                                PaperHedgePosition.Status.OPENED.name()));
        for (CrossExchangeArbPositionEntity row : active) {
            if (paper.positions().stream().anyMatch(position ->
                    position.positionId().equals(row.getPositionCode()))) {
                continue;
            }
            try {
                paper.restore(restore(row));
                persistence.saveRisk(row.getPositionCode(), row.getOpportunityCode(),
                        "RECOVERY", true, null);
            } catch (RuntimeException exception) {
                persistence.saveRisk(row.getPositionCode(), row.getOpportunityCode(),
                        "RECOVERY", false, exception.getMessage());
            }
        }
    }

    private PaperHedgePosition restore(CrossExchangeArbPositionEntity row) {
        Exchange longExchange = Exchange.valueOf(row.getLongExchange());
        Exchange shortExchange = Exchange.valueOf(row.getShortExchange());
        CrossExchangeMarketDataProvider longProvider = requiredProvider(longExchange);
        CrossExchangeMarketDataProvider shortProvider = requiredProvider(shortExchange);
        Instant now = Instant.now();
        var longQuote = longProvider.fundingRate(row.getSymbol());
        var shortQuote = shortProvider.fundingRate(row.getSymbol());
        CrossExchangeArbOpportunityEntity savedOpportunity = opportunities.selectOne(
                new LambdaQueryWrapper<CrossExchangeArbOpportunityEntity>()
                        .eq(CrossExchangeArbOpportunityEntity::getOpportunityCode,
                                row.getOpportunityCode()));
        BigDecimal netYield = savedOpportunity == null ? BigDecimal.ZERO
                : savedOpportunity.getEstimatedNetYield();
        BigDecimal executableNotional = savedOpportunity == null
                ? row.getHedgedQuantity().multiply(longQuote.markPrice())
                : savedOpportunity.getExecutableNotional();
        CrossExchangeOpportunity opportunity = new CrossExchangeOpportunity(
                row.getOpportunityCode(), row.getSymbol(), longQuote, shortQuote,
                shortQuote.hourlyFundingRate().subtract(longQuote.hourlyFundingRate()),
                netYield, executableNotional, CrossExchangeOpportunity.Status.APPROVED,
                null, now);
        Date openedAt = row.getOpenedAt();
        Instant opened = openedAt == null ? now : openedAt.toInstant();
        return new PaperHedgePosition(row.getPositionCode(), opportunity, null, null,
                row.getHedgedQuantity(), PaperHedgePosition.Status.OPENED, opened, now,
                "restored from persistence");
    }

    private CrossExchangeMarketDataProvider requiredProvider(Exchange exchange) {
        CrossExchangeMarketDataProvider provider = providers.get(exchange);
        if (provider == null) {
            throw new IllegalStateException("missing market provider for " + exchange);
        }
        return provider;
    }
}
