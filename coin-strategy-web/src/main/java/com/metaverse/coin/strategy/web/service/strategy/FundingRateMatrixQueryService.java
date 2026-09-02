package com.metaverse.coin.strategy.web.service.strategy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbOpportunityMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeArbOpportunityEntity;
import com.metaverse.coin.strategy.web.model.FundingRateMatrixViews;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/** Read-only projection of the Paper scanner's pairwise records for the web dashboard. */
@Service
public class FundingRateMatrixQueryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(FundingRateMatrixQueryService.class);
    private static final String TIMEZONE = "Asia/Shanghai";
    private static final String SOURCE = "PAPER_PUBLIC_ESTIMATE";
    private static final List<String> EXCHANGES = List.of("BINANCE", "OKX", "HTX", "BITGET", "HYPERLIQUID");
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withLocale(Locale.SIMPLIFIED_CHINESE)
            .withZone(ZoneId.of(TIMEZONE));

    private final CrossExchangeArbOpportunityMapper opportunityMapper;

    public FundingRateMatrixQueryService(CrossExchangeArbOpportunityMapper opportunityMapper) {
        this.opportunityMapper = opportunityMapper;
    }

    public FundingRateMatrixViews.Envelope<FundingRateMatrixViews.Matrix> fundingRateMatrix() {
        try {
            List<CrossExchangeArbOpportunityEntity> opportunities = opportunityMapper.selectList(
                    new LambdaQueryWrapper<>());
            return envelope(toMatrix(opportunities), false, List.of());
        } catch (DataAccessException exception) {
            LOGGER.warn("Funding-rate matrix is unavailable; returning an empty partial projection: {}",
                    exception.getMostSpecificCause().getMessage());
            return envelope(new FundingRateMatrixViews.Matrix(EXCHANGES, List.of()), true, EXCHANGES);
        }
    }

    private FundingRateMatrixViews.Matrix toMatrix(List<CrossExchangeArbOpportunityEntity> opportunities) {
        Map<String, List<CrossExchangeArbOpportunityEntity>> bySymbol = opportunities.stream()
                .filter(item -> item.getSymbol() != null && !item.getSymbol().isBlank())
                .collect(Collectors.groupingBy(CrossExchangeArbOpportunityEntity::getSymbol));
        List<FundingRateMatrixViews.Row> items = bySymbol.entrySet().stream()
                .map(entry -> toRow(entry.getKey(), entry.getValue()))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(FundingRateMatrixViews.Row::hourlyFundingSpread,
                                Comparator.nullsLast(BigDecimal::compareTo)).reversed()
                        .thenComparing(FundingRateMatrixViews.Row::symbol))
                .toList();
        return new FundingRateMatrixViews.Matrix(EXCHANGES, items);
    }

    private Optional<FundingRateMatrixViews.Row> toRow(String symbol,
            List<CrossExchangeArbOpportunityEntity> symbolOpportunities) {
        Date sampledAt = symbolOpportunities.stream().map(CrossExchangeArbOpportunityEntity::getDiscoveredAt)
                .filter(Objects::nonNull)
                .max(Date::compareTo)
                .orElse(null);
        List<CrossExchangeArbOpportunityEntity> current = symbolOpportunities.stream()
                .filter(item -> sameTimestamp(item.getDiscoveredAt(), sampledAt))
                .toList();
        CrossExchangeArbOpportunityEntity best = current.stream()
                .max(Comparator.comparing(CrossExchangeArbOpportunityEntity::getHourlyFundingSpread,
                        Comparator.nullsLast(BigDecimal::compareTo)))
                .orElse(null);
        if (best == null) {
            return Optional.empty();
        }
        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        current.forEach(item -> {
            putRate(rates, item.getLongExchange(), item.getLongFundingRate());
            putRate(rates, item.getShortExchange(), item.getShortFundingRate());
        });
        return Optional.of(new FundingRateMatrixViews.Row(symbol, rates, best.getLongExchange(),
                best.getShortExchange(), best.getHourlyFundingSpread(), best.getEstimatedNetYield(),
                best.getExecutableNotional(), best.getStatus(), best.getRejectionReason(), format(sampledAt)));
    }

    private boolean sameTimestamp(Date value, Date expected) {
        return expected == null ? value == null : expected.equals(value);
    }

    private void putRate(Map<String, BigDecimal> rates, String exchange, BigDecimal rate) {
        if (exchange != null && rate != null) {
            rates.putIfAbsent(exchange, rate);
        }
    }

    private FundingRateMatrixViews.Envelope<FundingRateMatrixViews.Matrix> envelope(
            FundingRateMatrixViews.Matrix data, boolean partial, List<String> missingExchanges) {
        return new FundingRateMatrixViews.Envelope<>(FORMATTER.format(Instant.now()), TIMEZONE, SOURCE,
                partial, missingExchanges, data);
    }

    private String format(Date value) {
        return value == null ? null : FORMATTER.format(value.toInstant());
    }
}
