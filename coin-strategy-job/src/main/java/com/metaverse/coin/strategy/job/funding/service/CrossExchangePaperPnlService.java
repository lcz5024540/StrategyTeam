package com.metaverse.coin.strategy.job.funding.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeFundingSettlementMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeArbOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeArbPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeFundingSettlementEntity;
import com.metaverse.coin.strategy.core.arbitrage.config.MultiExchangeFundingRateProperties;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangeMarketDataProvider;
import com.metaverse.coin.strategy.core.arbitrage.multi.Exchange;
import com.metaverse.coin.strategy.core.arbitrage.multi.NormalizedOrderBookSnapshot;
import com.metaverse.coin.strategy.core.arbitrage.multi.OrderBookSweep;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CrossExchangePaperPnlService {
    private static final String SOURCE_CASHFLOW = "PAPER_PUBLIC_CASHFLOW";
    private static final String SOURCE_MARK_TO_MARKET = "PAPER_PUBLIC_MARK_TO_MARKET";

    private final CrossExchangeArbPositionMapper positions;
    private final CrossExchangeArbOrderMapper orders;
    private final CrossExchangeFundingSettlementMapper settlements;
    private final Map<Exchange, CrossExchangeMarketDataProvider> providers;
    private final Duration maximumQuoteAge;
    private final int orderBookDepth;

    public CrossExchangePaperPnlService(CrossExchangeArbPositionMapper positions,
            CrossExchangeArbOrderMapper orders, CrossExchangeFundingSettlementMapper settlements,
            List<CrossExchangeMarketDataProvider> providers,
            MultiExchangeFundingRateProperties properties) {
        this.positions = positions;
        this.orders = orders;
        this.settlements = settlements;
        this.providers = providers.stream().collect(Collectors.toMap(
                CrossExchangeMarketDataProvider::exchange, Function.identity()));
        this.maximumQuoteAge = properties.getMaximumQuoteAge();
        this.orderBookDepth = properties.getCaptureOrderBookDepth();
    }

    CrossExchangePaperPnlService(CrossExchangeArbPositionMapper positions,
            CrossExchangeArbOrderMapper orders, CrossExchangeFundingSettlementMapper settlements,
            MultiExchangeFundingRateProperties properties) {
        this(positions, orders, settlements, List.of(), properties);
    }

    public PaperPnlSummary summarize(String positionId) {
        if (positionId == null || positionId.isBlank()) {
            throw new IllegalArgumentException("positionId is required");
        }
        CrossExchangeArbPositionEntity position = positions.selectOne(
                new LambdaQueryWrapper<CrossExchangeArbPositionEntity>()
                        .eq(CrossExchangeArbPositionEntity::getPositionCode, positionId)
                        .last("LIMIT 1"));
        if (position == null) {
            throw new IllegalArgumentException("paper position does not exist");
        }
        List<CrossExchangeArbOrderEntity> positionOrders = orders.selectList(
                new LambdaQueryWrapper<CrossExchangeArbOrderEntity>()
                        .eq(CrossExchangeArbOrderEntity::getPositionCode, positionId));
        List<CrossExchangeFundingSettlementEntity> positionSettlements = settlements.selectList(
                new LambdaQueryWrapper<CrossExchangeFundingSettlementEntity>()
                        .eq(CrossExchangeFundingSettlementEntity::getPositionCode, positionId));

        BigDecimal basisPnl = BigDecimal.ZERO;
        BigDecimal executionCost = BigDecimal.ZERO;
        for (CrossExchangeArbOrderEntity order : positionOrders) {
            basisPnl = basisPnl.add(signedCashflow(order));
            executionCost = executionCost.add(value(order.getFeeAmount()));
        }
        MarkToMarket markToMarket = markToMarket(position);
        basisPnl = basisPnl.add(markToMarket.basisPnl());
        BigDecimal fundingIncome = positionSettlements.stream()
                .map(CrossExchangeFundingSettlementEntity::getActualAmount)
                .map(CrossExchangePaperPnlService::value)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String source = markToMarket.included() ? SOURCE_MARK_TO_MARKET : SOURCE_CASHFLOW;
        return new PaperPnlSummary(positionId, source, basisPnl,
                fundingIncome, executionCost, basisPnl.add(fundingIncome).subtract(executionCost),
                positionOrders.size(), positionSettlements.size(), markToMarket.included(),
                markToMarket.message());
    }

    public PortfolioPnlSummary summarizePortfolio(int days) {
        int safeDays = days <= 0 ? 7 : Math.min(days, 30);
        Date openedAfter = Date.from(Instant.now().minus(safeDays, ChronoUnit.DAYS));
        List<CrossExchangeArbPositionEntity> recentPositions = positions.selectList(
                new LambdaQueryWrapper<CrossExchangeArbPositionEntity>()
                        .ge(CrossExchangeArbPositionEntity::getOpenedAt, openedAfter)
                        .orderByDesc(CrossExchangeArbPositionEntity::getOpenedAt)
                        .last("LIMIT 500"));
        BigDecimal basisPnl = BigDecimal.ZERO;
        BigDecimal fundingIncome = BigDecimal.ZERO;
        BigDecimal executionCost = BigDecimal.ZERO;
        int orderCount = 0;
        int settlementCount = 0;
        int openPositionCount = 0;
        int closedPositionCount = 0;
        boolean markToMarketIncluded = false;
        for (CrossExchangeArbPositionEntity position : recentPositions) {
            if ("OPENED".equals(position.getStatus())) {
                openPositionCount++;
            } else if ("CLOSED".equals(position.getStatus())) {
                closedPositionCount++;
            }
            PaperPnlSummary summary = summarize(position.getPositionCode());
            basisPnl = basisPnl.add(summary.basisPnl());
            fundingIncome = fundingIncome.add(summary.fundingIncome());
            executionCost = executionCost.add(summary.estimatedExecutionCost());
            markToMarketIncluded = markToMarketIncluded || summary.markToMarketIncluded();
            orderCount += summary.orderCount();
            settlementCount += summary.settlementCount();
        }
        String source = markToMarketIncluded ? SOURCE_MARK_TO_MARKET : SOURCE_CASHFLOW;
        return new PortfolioPnlSummary(source, basisPnl, fundingIncome,
                executionCost, basisPnl.add(fundingIncome).subtract(executionCost),
                recentPositions.size(), openPositionCount, closedPositionCount, orderCount,
                settlementCount, safeDays);
    }

    private MarkToMarket markToMarket(CrossExchangeArbPositionEntity position) {
        if (!"OPENED".equals(position.getStatus())) {
            return new MarkToMarket(BigDecimal.ZERO, false, "position is not open");
        }
        if (position.getHedgedQuantity() == null || position.getHedgedQuantity().signum() <= 0) {
            return new MarkToMarket(BigDecimal.ZERO, false, "hedged quantity is missing");
        }
        try {
            Exchange longExchange = Exchange.valueOf(position.getLongExchange());
            Exchange shortExchange = Exchange.valueOf(position.getShortExchange());
            CrossExchangeMarketDataProvider longProvider = providers.get(longExchange);
            CrossExchangeMarketDataProvider shortProvider = providers.get(shortExchange);
            if (longProvider == null || shortProvider == null) {
                return new MarkToMarket(BigDecimal.ZERO, false, "market provider is unavailable");
            }
            Instant now = Instant.now();
            NormalizedOrderBookSnapshot longBook = longProvider.orderBook(position.getSymbol(), orderBookDepth);
            NormalizedOrderBookSnapshot shortBook = shortProvider.orderBook(position.getSymbol(), orderBookDepth);
            if (isStale(longBook, now) || isStale(shortBook, now)) {
                return new MarkToMarket(BigDecimal.ZERO, false, "latest order book is stale");
            }
            BigDecimal quantity = position.getHedgedQuantity();
            BigDecimal longExit = OrderBookSweep.forQuantity(longBook.bidLevels(),
                    quantity, longBook.bestBid()).vwap().multiply(quantity);
            BigDecimal shortExit = OrderBookSweep.forQuantity(shortBook.askLevels(),
                    quantity, shortBook.bestAsk()).vwap().multiply(quantity);
            return new MarkToMarket(longExit.subtract(shortExit), true, null);
        } catch (RuntimeException exception) {
            return new MarkToMarket(BigDecimal.ZERO, false, exception.getMessage());
        }
    }

    private boolean isStale(NormalizedOrderBookSnapshot book, Instant now) {
        return book == null || book.receivedAt().plus(maximumQuoteAge).isBefore(now);
    }

    private BigDecimal signedCashflow(CrossExchangeArbOrderEntity order) {
        BigDecimal notional = value(order.getFilledQuantity()).multiply(value(order.getAveragePrice()));
        if ("SHORT".equals(order.getLegSide())) {
            return notional;
        }
        if ("LONG".equals(order.getLegSide())) {
            return notional.negate();
        }
        return BigDecimal.ZERO;
    }

    private static BigDecimal value(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public record PaperPnlSummary(String positionId, String source, BigDecimal basisPnl,
            BigDecimal fundingIncome, BigDecimal estimatedExecutionCost, BigDecimal totalPnl,
            int orderCount, int settlementCount, boolean markToMarketIncluded,
            String markToMarketMessage) {
    }

    private record MarkToMarket(BigDecimal basisPnl, boolean included, String message) {
    }

    public record PortfolioPnlSummary(String source, BigDecimal basisPnl,
            BigDecimal fundingIncome, BigDecimal estimatedExecutionCost, BigDecimal totalPnl,
            int positionCount, int openPositionCount, int closedPositionCount, int orderCount,
            int settlementCount, int windowDays) {
    }
}
