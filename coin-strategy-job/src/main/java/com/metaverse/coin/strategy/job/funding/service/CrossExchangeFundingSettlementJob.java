package com.metaverse.coin.strategy.job.funding.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeFundingSettlementMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeFundingSettlementEntity;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangePaperTradingService;
import com.metaverse.coin.strategy.core.arbitrage.multi.NormalizedFundingRateSnapshot;
import com.metaverse.coin.strategy.core.arbitrage.multi.PaperHedgePosition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;

@Component
@ConditionalOnProperty(prefix = "arbitrage.multi-exchange", name = "settlement-enabled", havingValue = "true")
public class CrossExchangeFundingSettlementJob {
    private final CrossExchangePaperTradingService paper;
    private final CrossExchangeFundingSettlementMapper mapper;

    public CrossExchangeFundingSettlementJob(CrossExchangePaperTradingService paper,
            CrossExchangeFundingSettlementMapper mapper) {
        this.paper = paper;
        this.mapper = mapper;
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
            record(position, position.opportunity().longQuote(), fundingAmount(position,
                    position.opportunity().longQuote()).negate(), now);
            record(position, position.opportunity().shortQuote(), fundingAmount(position,
                    position.opportunity().shortQuote()), now);
        }
    }

    private BigDecimal fundingAmount(PaperHedgePosition position, NormalizedFundingRateSnapshot quote) {
        return position.hedgedQuantity().multiply(quote.markPrice()).multiply(quote.rawFundingRate());
    }

    private void record(PaperHedgePosition position, NormalizedFundingRateSnapshot quote,
            BigDecimal amount, Instant now) {
        for (Instant settlementAt = quote.nextFundingTime(); !settlementAt.isAfter(now);
                settlementAt = settlementAt.plusSeconds(quote.fundingIntervalHours() * 3600L)) {
            if (position.openedAt() != null && settlementAt.isBefore(position.openedAt())) {
                continue;
            }
            Date settlementTime = Date.from(settlementAt);
            Long existing = mapper.selectCount(new LambdaQueryWrapper<CrossExchangeFundingSettlementEntity>()
                    .eq(CrossExchangeFundingSettlementEntity::getPositionCode, position.positionId())
                    .eq(CrossExchangeFundingSettlementEntity::getExchangeName, quote.exchange().name())
                    .eq(CrossExchangeFundingSettlementEntity::getSettlementTime, settlementTime));
            if (existing != null && existing > 0L) {
                continue;
            }
            mapper.insert(new CrossExchangeFundingSettlementEntity()
                    .setPositionCode(position.positionId())
                    .setExchangeName(quote.exchange().name())
                    .setSymbol(position.opportunity().symbol())
                    .setFundingRate(quote.rawFundingRate())
                    .setExpectedAmount(amount)
                    .setActualAmount(amount)
                    .setSettlementTime(settlementTime)
                    .setCreatedAt(new Date()));
        }
    }
}
