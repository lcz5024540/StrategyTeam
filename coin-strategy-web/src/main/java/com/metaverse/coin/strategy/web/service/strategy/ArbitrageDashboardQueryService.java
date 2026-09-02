package com.metaverse.coin.strategy.web.service.strategy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbOpportunityMapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeFundingSettlementMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SingleExchangeArbitragePositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeArbOpportunityEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeArbOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeArbPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeFundingSettlementEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SingleExchangeArbitragePosition;
import com.metaverse.coin.strategy.web.model.ArbitrageDashboardViews;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.function.Supplier;

@Service
public class ArbitrageDashboardQueryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ArbitrageDashboardQueryService.class);

    private static final String TIMEZONE = "Asia/Shanghai";
    private static final String SOURCE = "PAPER_PUBLIC_ESTIMATE";
    private static final int MAX_PAGE_SIZE = 100;
    private static final BigDecimal PAPER_CAPITAL_PER_EXCHANGE = new BigDecimal("2000");
    private static final BigDecimal MAXIMUM_PAPER_LEVERAGE = new BigDecimal("3");
    private static final List<String> FUNDING_EXCHANGES = List.of("BINANCE", "OKX", "HTX", "BITGET", "HYPERLIQUID");
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withLocale(Locale.SIMPLIFIED_CHINESE).withZone(ZoneId.of(TIMEZONE));

    private final CrossExchangeArbOpportunityMapper opportunityMapper;
    private final CrossExchangeArbPositionMapper crossPositionMapper;
    private final CrossExchangeArbOrderMapper crossOrderMapper;
    private final CrossExchangeFundingSettlementMapper crossSettlementMapper;
    private final SingleExchangeArbitragePositionMapper singlePositionMapper;

    public ArbitrageDashboardQueryService(CrossExchangeArbOpportunityMapper opportunityMapper,
                                          CrossExchangeArbPositionMapper crossPositionMapper,
                                          CrossExchangeArbOrderMapper crossOrderMapper,
                                          CrossExchangeFundingSettlementMapper crossSettlementMapper,
                                          SingleExchangeArbitragePositionMapper singlePositionMapper) {
        this.opportunityMapper = opportunityMapper;
        this.crossPositionMapper = crossPositionMapper;
        this.crossOrderMapper = crossOrderMapper;
        this.crossSettlementMapper = crossSettlementMapper;
        this.singlePositionMapper = singlePositionMapper;
    }

    public ArbitrageDashboardViews.Envelope<ArbitrageDashboardViews.Overview> overview() {
        List<CrossExchangeArbOpportunityEntity> opportunities = readOrDefault("opportunities", List::of,
                () -> opportunityMapper.selectList(new LambdaQueryWrapper<>()));
        List<CrossExchangeArbPositionEntity> positions = readOrDefault("cross positions", List::of,
                () -> crossPositionMapper.selectList(new LambdaQueryWrapper<CrossExchangeArbPositionEntity>()
                        .in(CrossExchangeArbPositionEntity::getStatus,
                                List.of("OPENING", "OPENED", "HEDGED", "CLOSING"))));
        long executable = opportunities.stream().filter(this::isQualified).count();
        long rejected = opportunities.stream().filter(item -> !isQualified(item)).count();
        BigDecimal notional = opportunities.stream().filter(this::isQualified)
                .map(CrossExchangeArbOpportunityEntity::getExecutableNotional).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expectedYield = opportunities.stream().filter(this::isQualified)
                .map(CrossExchangeArbOpportunityEntity::getEstimatedNetYield).filter(Objects::nonNull)
                .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal fundingPnl = readOrDefault("funding settlements", List::<CrossExchangeFundingSettlementEntity>of,
                () -> crossSettlementMapper.selectList(new LambdaQueryWrapper<>())).stream()
                .map(CrossExchangeFundingSettlementEntity::getActualAmount).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return envelope(new ArbitrageDashboardViews.Overview(executable, positions.size(), notional,
                expectedYield, fundingPnl, BigDecimal.ZERO, "UNKNOWN", opportunities.size(), rejected,
                PAPER_CAPITAL_PER_EXCHANGE, MAXIMUM_PAPER_LEVERAGE,
                PAPER_CAPITAL_PER_EXCHANGE.multiply(MAXIMUM_PAPER_LEVERAGE)));
    }

    public ArbitrageDashboardViews.Envelope<ArbitrageDashboardViews.OpportunityPage> opportunities(
            String kind, String symbol, String status, BigDecimal minNetYield, BigDecimal minNotional, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        if (!"CROSS_EXCHANGE_FUNDING".equalsIgnoreCase(kind)) {
            return envelope(new ArbitrageDashboardViews.OpportunityPage(List.of(), 0, safePage, safeSize, 0, 0, 0));
        }
        LambdaQueryWrapper<CrossExchangeArbOpportunityEntity> query = new LambdaQueryWrapper<>();
        if (symbol != null && !symbol.isBlank()) {
            query.likeRight(CrossExchangeArbOpportunityEntity::getSymbol, symbol.trim().toUpperCase(Locale.ROOT));
        }
        if (status != null && !status.isBlank()) {
            query.eq(CrossExchangeArbOpportunityEntity::getStatus, status.trim().toUpperCase(Locale.ROOT));
        }
        if (minNetYield != null) {
            query.ge(CrossExchangeArbOpportunityEntity::getEstimatedNetYield, minNetYield);
        }
        if (minNotional != null) {
            query.ge(CrossExchangeArbOpportunityEntity::getExecutableNotional, minNotional);
        }
        query.orderByDesc(CrossExchangeArbOpportunityEntity::getEstimatedNetYield)
                .orderByDesc(CrossExchangeArbOpportunityEntity::getUpdatedAt);
        Page<CrossExchangeArbOpportunityEntity> result = readOrDefault("opportunity page",
                () -> new Page<>(safePage, safeSize),
                () -> opportunityMapper.selectPage(new Page<>(safePage, safeSize), query));
        List<ArbitrageDashboardViews.Opportunity> items = result.getRecords().stream().map(this::toOpportunity).toList();
        long qualified = items.stream().filter(item -> isQualified(item.status())).count();
        long rejected = items.stream().filter(item -> !isQualified(item.status())).count();
        return envelope(new ArbitrageDashboardViews.OpportunityPage(items, result.getTotal(), safePage, safeSize,
                qualified, rejected, 0));
    }

    /**
     * Projects pairwise scan records into one current row per canonical contract. Each symbol is
     * limited to its latest discoveredAt value before rates are combined, so a row never mixes
     * a fresh rate from one scan with a historic rate from another scan.
     */
    public ArbitrageDashboardViews.Envelope<ArbitrageDashboardViews.FundingRateMatrix> fundingRateMatrix() {
        List<CrossExchangeArbOpportunityEntity> opportunities = readOrDefault("funding-rate matrix", List::of,
                () -> opportunityMapper.selectList(new LambdaQueryWrapper<>()));
        Map<String, List<CrossExchangeArbOpportunityEntity>> bySymbol = opportunities.stream()
                .filter(item -> item.getSymbol() != null && !item.getSymbol().isBlank())
                .collect(Collectors.groupingBy(CrossExchangeArbOpportunityEntity::getSymbol));
        List<ArbitrageDashboardViews.FundingRateMatrixRow> items = bySymbol.entrySet().stream()
                .map(entry -> toFundingRateMatrixRow(entry.getKey(), entry.getValue()))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(ArbitrageDashboardViews.FundingRateMatrixRow::hourlyFundingSpread,
                                Comparator.nullsLast(BigDecimal::compareTo)).reversed()
                        .thenComparing(ArbitrageDashboardViews.FundingRateMatrixRow::symbol))
                .toList();
        return envelope(new ArbitrageDashboardViews.FundingRateMatrix(FUNDING_EXCHANGES, items));
    }

    public ArbitrageDashboardViews.Envelope<ArbitrageDashboardViews.Opportunity> opportunity(long id) {
        return envelope(toOpportunity(readOrDefault("opportunity detail", () -> null,
                () -> opportunityMapper.selectById(id))));
    }

    public ArbitrageDashboardViews.Envelope<List<ArbitrageDashboardViews.Position>> positions(String kind, String status) {
        List<ArbitrageDashboardViews.Position> result = new ArrayList<>();
        if (kind == null || kind.isBlank() || "CROSS_EXCHANGE_FUNDING".equalsIgnoreCase(kind)) {
            LambdaQueryWrapper<CrossExchangeArbPositionEntity> query = new LambdaQueryWrapper<>();
            if (status != null && !status.isBlank()) query.eq(CrossExchangeArbPositionEntity::getStatus, status);
            result.addAll(readOrDefault("cross positions", List::<CrossExchangeArbPositionEntity>of,
                    () -> crossPositionMapper.selectList(query)).stream().map(this::toCrossPosition).toList());
        }
        if (kind == null || kind.isBlank() || "SINGLE_EXCHANGE_SPOT_FUTURES".equalsIgnoreCase(kind)) {
            LambdaQueryWrapper<SingleExchangeArbitragePosition> query = new LambdaQueryWrapper<>();
            if (status != null && !status.isBlank()) query.eq(SingleExchangeArbitragePosition::getStatus, status);
            result.addAll(readOrDefault("single-exchange positions", List::<SingleExchangeArbitragePosition>of,
                    () -> singlePositionMapper.selectList(query)).stream().map(this::toSinglePosition).toList());
        }
        return envelope(result);
    }

    public ArbitrageDashboardViews.Envelope<ArbitrageDashboardViews.Position> position(String positionCode) {
        CrossExchangeArbPositionEntity cross = readOrDefault("cross position detail", () -> null,
                () -> crossPositionMapper.selectOne(new LambdaQueryWrapper<CrossExchangeArbPositionEntity>()
                        .eq(CrossExchangeArbPositionEntity::getPositionCode, positionCode).last("LIMIT 1")));
        if (cross != null) return envelope(toCrossPosition(cross));
        SingleExchangeArbitragePosition single = readOrDefault("single-exchange position detail", () -> null,
                () -> singlePositionMapper.selectOne(new LambdaQueryWrapper<SingleExchangeArbitragePosition>()
                        .eq(SingleExchangeArbitragePosition::getPositionCode, positionCode).last("LIMIT 1")));
        return envelope(single == null ? null : toSinglePosition(single));
    }

    public ArbitrageDashboardViews.Envelope<List<ArbitrageDashboardViews.TimePoint>> timeseries() {
        Map<LocalDate, DailyPnl> daily = new TreeMap<>();
        readOrDefault("funding timeseries",
                List::<CrossExchangeFundingSettlementEntity>of,
                () -> crossSettlementMapper.selectList(new LambdaQueryWrapper<CrossExchangeFundingSettlementEntity>()
                        .orderByAsc(CrossExchangeFundingSettlementEntity::getSettlementTime)))
                .forEach(item -> daily.computeIfAbsent(toDate(item.getSettlementTime()), ignored -> new DailyPnl())
                        .addFunding(zero(item.getActualAmount())));
        readOrDefault("paper orders", List::<CrossExchangeArbOrderEntity>of,
                () -> crossOrderMapper.selectList(new LambdaQueryWrapper<CrossExchangeArbOrderEntity>()
                        .gt(CrossExchangeArbOrderEntity::getFilledQuantity, BigDecimal.ZERO)
                        .orderByAsc(CrossExchangeArbOrderEntity::getCreatedAt)))
                .forEach(item -> {
                    BigDecimal notional = zero(item.getFilledQuantity()).multiply(zero(item.getAveragePrice()));
                    BigDecimal signedCashflow = "SHORT".equalsIgnoreCase(item.getLegSide())
                            ? notional : notional.negate();
                    DailyPnl pnl = daily.computeIfAbsent(toDate(item.getCreatedAt()), ignored -> new DailyPnl());
                    pnl.addTradingCashflow(signedCashflow);
                    pnl.addFee(zero(item.getFeeAmount()));
                });
        BigDecimal cumulative = BigDecimal.ZERO;
        List<ArbitrageDashboardViews.TimePoint> points = new ArrayList<>(daily.size());
        for (Map.Entry<LocalDate, DailyPnl> entry : daily.entrySet()) {
            DailyPnl value = entry.getValue();
            BigDecimal netPnl = value.fundingPnl.add(value.tradingCashflowPnl).subtract(value.fee);
            cumulative = cumulative.add(netPnl);
            points.add(new ArbitrageDashboardViews.TimePoint(entry.getKey().toString(), value.fundingPnl,
                    value.tradingCashflowPnl, value.fee, netPnl, cumulative));
        }
        return envelope(points);
    }

    private LocalDate toDate(Date value) {
        Instant instant = value == null ? Instant.EPOCH : value.toInstant();
        return instant.atZone(ZoneId.of(TIMEZONE)).toLocalDate();
    }

    private static final class DailyPnl {
        private BigDecimal fundingPnl = BigDecimal.ZERO;
        private BigDecimal tradingCashflowPnl = BigDecimal.ZERO;
        private BigDecimal fee = BigDecimal.ZERO;

        private void addFunding(BigDecimal amount) {
            fundingPnl = fundingPnl.add(amount);
        }

        private void addTradingCashflow(BigDecimal amount) {
            tradingCashflowPnl = tradingCashflowPnl.add(amount);
        }

        private void addFee(BigDecimal amount) {
            fee = fee.add(amount);
        }
    }

    public ArbitrageDashboardViews.Envelope<List<ArbitrageDashboardViews.MarketHealth>> marketHealth() {
        List<String> exchanges = List.of("BINANCE", "OKX", "HTX", "BITGET", "HYPERLIQUID");
        List<ArbitrageDashboardViews.MarketHealth> health = exchanges.stream()
                .map(exchange -> new ArbitrageDashboardViews.MarketHealth(exchange, "UNKNOWN", null, null,
                        null, null, null, "market health snapshot not persisted"))
                .toList();
        return envelope(health);
    }

    public ArbitrageDashboardViews.Envelope<ArbitrageDashboardViews.DataQuality> dataQuality() {
        List<CrossExchangeArbOpportunityEntity> opportunities = readOrDefault("data quality opportunities", List::of,
                () -> opportunityMapper.selectList(new LambdaQueryWrapper<>()));
        Map<String, Long> reasons = opportunities.stream().filter(item -> item.getRejectionReason() != null)
                .collect(Collectors.groupingBy(CrossExchangeArbOpportunityEntity::getRejectionReason, Collectors.counting()));
        List<ArbitrageDashboardViews.ReasonCount> reasonCounts = reasons.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(entry -> new ArbitrageDashboardViews.ReasonCount(entry.getKey(), entry.getValue())).toList();
        long symbols = opportunities.stream().map(CrossExchangeArbOpportunityEntity::getSymbol).distinct().count();
        long qualified = opportunities.stream().filter(this::isQualified).count();
        return envelope(new ArbitrageDashboardViews.DataQuality(symbols, opportunities.size(), opportunities.size(),
                qualified, 0, 0, 0, reasonCounts));
    }

    private ArbitrageDashboardViews.Opportunity toOpportunity(CrossExchangeArbOpportunityEntity item) {
        if (item == null) return null;
        return new ArbitrageDashboardViews.Opportunity(item.getId(), item.getOpportunityCode(),
                "CROSS_EXCHANGE_FUNDING", item.getSymbol(), item.getLongExchange(), item.getShortExchange(),
                item.getLongFundingRate(), item.getShortFundingRate(), item.getHourlyFundingSpread(),
                item.getEstimatedNetYield(), item.getExecutableNotional(), item.getStatus(), item.getRejectionReason(),
                format(item.getDiscoveredAt()), format(item.getUpdatedAt()), null, null, null, null,
                null, null, null, null, null, null, null);
    }

    private Optional<ArbitrageDashboardViews.FundingRateMatrixRow> toFundingRateMatrixRow(String symbol,
            List<CrossExchangeArbOpportunityEntity> symbolOpportunities) {
        Date sampledAt = symbolOpportunities.stream().map(CrossExchangeArbOpportunityEntity::getDiscoveredAt)
                .filter(Objects::nonNull).max(Date::compareTo).orElse(null);
        List<CrossExchangeArbOpportunityEntity> current = symbolOpportunities.stream()
                .filter(item -> sameTimestamp(item.getDiscoveredAt(), sampledAt)).toList();
        CrossExchangeArbOpportunityEntity best = current.stream()
                .max(Comparator.comparing(CrossExchangeArbOpportunityEntity::getHourlyFundingSpread,
                        Comparator.nullsLast(BigDecimal::compareTo))
                        .thenComparing(CrossExchangeArbOpportunityEntity::getUpdatedAt,
                                Comparator.nullsLast(Date::compareTo)))
                .orElse(null);
        if (best == null) {
            return Optional.empty();
        }
        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        Map<String, Integer> intervals = new LinkedHashMap<>();
        current.forEach(item -> {
            putRate(rates, intervals, item.getLongExchange(), item.getLongFundingRate(),
                    item.getLongFundingIntervalHours());
            putRate(rates, intervals, item.getShortExchange(), item.getShortFundingRate(),
                    item.getShortFundingIntervalHours());
        });
        return Optional.of(new ArbitrageDashboardViews.FundingRateMatrixRow(symbol, rates, intervals,
                best.getLongExchange(), best.getShortExchange(), best.getHourlyFundingSpread(),
                best.getEstimatedNetYield(), best.getExecutableNotional(), best.getStatus(),
                best.getRejectionReason(), format(sampledAt)));
    }

    private boolean sameTimestamp(Date value, Date expected) {
        return expected == null ? value == null : expected.equals(value);
    }

    private void putRate(Map<String, BigDecimal> rates, Map<String, Integer> intervals,
            String exchange, BigDecimal rate, Integer intervalHours) {
        if (exchange != null && rate != null) {
            rates.putIfAbsent(exchange, rate);
            if (intervalHours != null && intervalHours > 0) {
                intervals.putIfAbsent(exchange, intervalHours);
            }
        }
    }

    private ArbitrageDashboardViews.Position toCrossPosition(CrossExchangeArbPositionEntity item) {
        return new ArbitrageDashboardViews.Position(item.getPositionCode(), "CROSS_EXCHANGE_FUNDING", item.getSymbol(),
                item.getLongExchange(), item.getShortExchange(), item.getHedgedQuantity(), item.getHedgedQuantity(),
                BigDecimal.ZERO, null, settlementTotal(item.getPositionCode()), BigDecimal.ZERO, item.getStatus(),
                null, format(item.getOpenedAt()), item.getMessage());
    }

    private ArbitrageDashboardViews.Position toSinglePosition(SingleExchangeArbitragePosition item) {
        return new ArbitrageDashboardViews.Position(item.getPositionCode(), "SINGLE_EXCHANGE_SPOT_FUTURES", item.getSymbol(),
                item.getExchangeName() + " SPOT", item.getExchangeName() + " PERP", item.getQuantity(), item.getQuantity(),
                BigDecimal.ZERO, item.getTotalInvestment(), zero(item.getFundingFeesEarned()), zero(item.getRealizedPnl()),
                item.getStatus(), item.getEntryNextFundingTime() == null ? null : FORMATTER.format(Instant.ofEpochMilli(item.getEntryNextFundingTime())),
                format(item.getEntryTime()), item.getErrorMessage());
    }

    private BigDecimal settlementTotal(String positionCode) {
        return readOrDefault("position settlements", List::<CrossExchangeFundingSettlementEntity>of,
                () -> crossSettlementMapper.selectList(new LambdaQueryWrapper<CrossExchangeFundingSettlementEntity>()
                        .eq(CrossExchangeFundingSettlementEntity::getPositionCode, positionCode))).stream()
                .map(CrossExchangeFundingSettlementEntity::getActualAmount).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private boolean isQualified(CrossExchangeArbOpportunityEntity item) { return isQualified(item.getStatus()); }
    private boolean isQualified(String status) { return "QUALIFIED".equalsIgnoreCase(status) || "APPROVED".equalsIgnoreCase(status) || "PAPER_READY".equalsIgnoreCase(status); }
    private BigDecimal zero(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private String format(Date value) { return value == null ? null : FORMATTER.format(value.toInstant()); }
    private <T> T readOrDefault(String projection, Supplier<T> fallback, Supplier<T> query) {
        try {
            return query.get();
        } catch (DataAccessException exception) {
            LOGGER.warn("Arbitrage dashboard projection '{}' is unavailable; returning partial data: {}",
                    projection, exception.getMostSpecificCause().getMessage());
            return fallback.get();
        }
    }
    private <T> ArbitrageDashboardViews.Envelope<T> envelope(T data) {
        return new ArbitrageDashboardViews.Envelope<>(FORMATTER.format(Instant.now()), TIMEZONE, SOURCE, true,
                List.of("BINANCE"), data);
    }
}
