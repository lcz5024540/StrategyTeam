package com.metaverse.coin.strategy.web.service.strategy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbOpportunityMapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeFundingSettlementMapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeExecutionFillMapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeRiskSnapshotMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SingleExchangeArbitragePositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeArbOpportunityEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeArbOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeArbPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeFundingSettlementEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeExecutionFillEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeRiskSnapshotEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SingleExchangeArbitragePosition;
import com.metaverse.coin.strategy.web.model.ArbitrageDashboardViews;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
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
    private static final String STAGE_PAPER_PNL_PORTFOLIO = "PAPER_PNL_PORTFOLIO";
    private static final int MAX_PAGE_SIZE = 100;
    private static final BigDecimal PAPER_CAPITAL_PER_EXCHANGE = new BigDecimal("2000");
    private static final BigDecimal MAXIMUM_PAPER_LEVERAGE = new BigDecimal("25");
    private static final List<String> FUNDING_EXCHANGES = List.of("BINANCE", "OKX", "HTX", "BITGET", "HYPERLIQUID");
    private static final Duration MAX_SNAPSHOT_AGE = Duration.ofMinutes(30);
    private static final Duration PAPER_PNL_OVERVIEW_WINDOW = Duration.ofDays(7);
    private static final int MAX_OVERVIEW_OPPORTUNITY_ROWS = 10_000;
    private static final int MAX_OVERVIEW_LEDGER_ROWS = 10_000;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withLocale(Locale.SIMPLIFIED_CHINESE).withZone(ZoneId.of(TIMEZONE));

    private final CrossExchangeArbOpportunityMapper opportunityMapper;
    private final CrossExchangeArbPositionMapper crossPositionMapper;
    private final CrossExchangeArbOrderMapper crossOrderMapper;
    private final CrossExchangeFundingSettlementMapper crossSettlementMapper;
    private final CrossExchangeExecutionFillMapper executionFillMapper;
    private final CrossExchangeRiskSnapshotMapper riskSnapshotMapper;
    private final SingleExchangeArbitragePositionMapper singlePositionMapper;
    private final ObjectMapper objectMapper;

    public ArbitrageDashboardQueryService(CrossExchangeArbOpportunityMapper opportunityMapper,
                                          CrossExchangeArbPositionMapper crossPositionMapper,
                                          CrossExchangeArbOrderMapper crossOrderMapper,
                                          CrossExchangeFundingSettlementMapper crossSettlementMapper,
                                          CrossExchangeExecutionFillMapper executionFillMapper,
                                          CrossExchangeRiskSnapshotMapper riskSnapshotMapper,
                                          SingleExchangeArbitragePositionMapper singlePositionMapper,
                                          ObjectMapper objectMapper) {
        this.opportunityMapper = opportunityMapper;
        this.crossPositionMapper = crossPositionMapper;
        this.crossOrderMapper = crossOrderMapper;
        this.crossSettlementMapper = crossSettlementMapper;
        this.executionFillMapper = executionFillMapper;
        this.riskSnapshotMapper = riskSnapshotMapper;
        this.singlePositionMapper = singlePositionMapper;
        this.objectMapper = objectMapper;
    }

    public ArbitrageDashboardViews.Envelope<ArbitrageDashboardViews.Overview> overview() {
        Date opportunitySince = Date.from(Instant.now().minus(MAX_SNAPSHOT_AGE));
        List<CrossExchangeArbOpportunityEntity> opportunities = readOrDefault("opportunities", List::of,
                () -> opportunityMapper.selectList(new LambdaQueryWrapper<CrossExchangeArbOpportunityEntity>()
                        .ge(CrossExchangeArbOpportunityEntity::getDiscoveredAt, opportunitySince)
                        .orderByDesc(CrossExchangeArbOpportunityEntity::getUpdatedAt)
                        .last("LIMIT " + MAX_OVERVIEW_OPPORTUNITY_ROWS)));
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
        PnlParts pnl = latestPaperPnlSnapshot().orElseGet(this::recentLedgerPnl);
        return envelope(new ArbitrageDashboardViews.Overview(executable, positions.size(), notional,
                expectedYield, pnl.fundingPnl, pnl.executionCost, pnl.totalPnl(), BigDecimal.ZERO,
                "UNKNOWN", opportunities.size(), rejected,
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
                () -> opportunityMapper.selectList(new LambdaQueryWrapper<CrossExchangeArbOpportunityEntity>()
                        .ge(CrossExchangeArbOpportunityEntity::getDiscoveredAt,
                                Date.from(Instant.now().minus(MAX_SNAPSHOT_AGE)))));
        Map<String, List<CrossExchangeArbOpportunityEntity>> bySymbol = opportunities.stream()
                .filter(item -> item.getSymbol() != null && !item.getSymbol().isBlank())
                .collect(Collectors.groupingBy(CrossExchangeArbOpportunityEntity::getSymbol));
        List<ArbitrageDashboardViews.FundingRateMatrixRow> items = bySymbol.entrySet().stream()
                .map(entry -> toFundingRateMatrixRow(entry.getKey(), entry.getValue()))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing((ArbitrageDashboardViews.FundingRateMatrixRow row) -> rankSpread(row),
                                Comparator.nullsLast(Comparator.reverseOrder()))
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
        List<ArbitrageDashboardViews.TimePoint> paperPnlSnapshots = paperPnlSnapshotTimeseries();
        if (!paperPnlSnapshots.isEmpty()) {
            return envelope(paperPnlSnapshots);
        }
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

    private List<ArbitrageDashboardViews.TimePoint> paperPnlSnapshotTimeseries() {
        Date since = Date.from(Instant.now().minus(Duration.ofDays(7)));
        List<CrossExchangeRiskSnapshotEntity> snapshots = readOrDefault("paper pnl snapshots", List::of,
                () -> riskSnapshotMapper.selectList(new LambdaQueryWrapper<CrossExchangeRiskSnapshotEntity>()
                        .eq(CrossExchangeRiskSnapshotEntity::getCheckStage, STAGE_PAPER_PNL_PORTFOLIO)
                        .ge(CrossExchangeRiskSnapshotEntity::getCreatedAt, since)
                        .orderByAsc(CrossExchangeRiskSnapshotEntity::getCreatedAt)
                        .last("LIMIT 10080")));
        if (snapshots == null || snapshots.isEmpty()) {
            return List.of();
        }
        List<ArbitrageDashboardViews.TimePoint> result = new ArrayList<>(snapshots.size());
        BigDecimal previousTotal = BigDecimal.ZERO;
        for (CrossExchangeRiskSnapshotEntity snapshot : snapshots) {
            try {
                JsonNode payload = objectMapper.readTree(snapshot.getDetailsJson());
                BigDecimal fundingPnl = decimal(payload, "fundingIncome");
                BigDecimal tradingCashflowPnl = decimal(payload, "basisPnl");
                BigDecimal fee = decimal(payload, "executionCost");
                BigDecimal totalPnl = decimal(payload, "totalPnl");
                BigDecimal intervalPnl = totalPnl.subtract(previousTotal);
                previousTotal = totalPnl;
                result.add(new ArbitrageDashboardViews.TimePoint(format(snapshot.getCreatedAt()),
                        fundingPnl, tradingCashflowPnl, fee, intervalPnl, totalPnl));
            } catch (Exception exception) {
                LOGGER.warn("Skip malformed paper pnl snapshot id={}: {}", snapshot.getId(),
                        exception.getMessage());
            }
        }
        return result;
    }

    private static BigDecimal decimal(JsonNode payload, String field) {
        JsonNode value = payload == null ? null : payload.get(field);
        if (value == null || value.isNull()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value.asText());
    }

    public ArbitrageDashboardViews.Envelope<List<ArbitrageDashboardViews.ExecutionOrder>> executions(
            String positionCode) {
        List<CrossExchangeArbOrderEntity> positionOrders = readOrDefault("position orders", List::of,
                () -> crossOrderMapper.selectList(new LambdaQueryWrapper<CrossExchangeArbOrderEntity>()
                        .eq(CrossExchangeArbOrderEntity::getPositionCode, positionCode)
                        .orderByAsc(CrossExchangeArbOrderEntity::getCreatedAt)));
        List<CrossExchangeExecutionFillEntity> fills = readOrDefault("execution fills", List::of,
                () -> executionFillMapper.selectList(new LambdaQueryWrapper<CrossExchangeExecutionFillEntity>()
                        .eq(CrossExchangeExecutionFillEntity::getPositionCode, positionCode)
                        .orderByAsc(CrossExchangeExecutionFillEntity::getClientOrderId)
                        .orderByAsc(CrossExchangeExecutionFillEntity::getLevelNo)));
        Map<String, List<CrossExchangeExecutionFillEntity>> byOrder = fills.stream()
                .collect(Collectors.groupingBy(CrossExchangeExecutionFillEntity::getClientOrderId));
        List<ArbitrageDashboardViews.ExecutionOrder> result = positionOrders.stream().map(order ->
                new ArbitrageDashboardViews.ExecutionOrder(order.getClientOrderId(), order.getExchangeName(),
                        order.getSymbol(), order.getLegSide(), Boolean.TRUE.equals(order.getReduceOnly()),
                        order.getFilledQuantity(), order.getAveragePrice(), order.getBestPrice(),
                        order.getWorstPrice(), order.getImpactBps(), order.getSlippageAmount(),
                        order.getFeeAmount(), order.getLevelsConsumed(), format(order.getBookReceivedAt()),
                        byOrder.getOrDefault(order.getClientOrderId(), List.of()).stream()
                                .map(fill -> new ArbitrageDashboardViews.ExecutionFill(fill.getLevelNo(),
                                        fill.getPrice(), fill.getFilledQuantity(), fill.getFilledNotional()))
                                .toList())).toList();
        return envelope(result);
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
        Date opportunitySince = Date.from(Instant.now().minus(MAX_SNAPSHOT_AGE));
        List<CrossExchangeArbOpportunityEntity> opportunities = readOrDefault("data quality opportunities", List::of,
                () -> opportunityMapper.selectList(new LambdaQueryWrapper<CrossExchangeArbOpportunityEntity>()
                        .ge(CrossExchangeArbOpportunityEntity::getDiscoveredAt, opportunitySince)
                        .orderByDesc(CrossExchangeArbOpportunityEntity::getUpdatedAt)
                        .last("LIMIT " + MAX_OVERVIEW_OPPORTUNITY_ROWS)));
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
                .max(Comparator.comparing((CrossExchangeArbOpportunityEntity item) -> rankSpread(item),
                        Comparator.nullsFirst(BigDecimal::compareTo))
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
        BigDecimal rawFundingSpread = rawFundingSpread(best);
        boolean cadenceAdjusted = isCadenceAdjusted(best);
        Integer settlementWindowHours = cadenceAdjusted ? settlementWindowHours(best) : null;
        BigDecimal settlementWindowSpread = cadenceAdjusted ? settlementWindowSpread(best, settlementWindowHours) : null;
        return Optional.of(new ArbitrageDashboardViews.FundingRateMatrixRow(symbol, rates, intervals,
                best.getLongExchange(), best.getShortExchange(), rawFundingSpread, settlementWindowHours,
                settlementWindowSpread, cadenceAdjusted, best.getHourlyFundingSpread(), best.getEstimatedNetYield(),
                best.getExecutableNotional(), best.getStatus(), best.getRejectionReason(), format(sampledAt)));
    }

    private static BigDecimal rankSpread(ArbitrageDashboardViews.FundingRateMatrixRow row) {
        return row.cadenceAdjusted() && row.settlementWindowSpread() != null
                ? row.settlementWindowSpread()
                : row.rawFundingSpread();
    }

    private static BigDecimal rankSpread(CrossExchangeArbOpportunityEntity opportunity) {
        if (isCadenceAdjusted(opportunity)) {
            BigDecimal settlementWindowSpread = settlementWindowSpread(opportunity, settlementWindowHours(opportunity));
            if (settlementWindowSpread != null) {
                return settlementWindowSpread;
            }
        }
        return rawFundingSpread(opportunity);
    }

    private static BigDecimal rawFundingSpread(CrossExchangeArbOpportunityEntity opportunity) {
        if (opportunity.getShortFundingRate() == null || opportunity.getLongFundingRate() == null) {
            return null;
        }
        return opportunity.getShortFundingRate().subtract(opportunity.getLongFundingRate());
    }

    private static BigDecimal settlementWindowSpread(CrossExchangeArbOpportunityEntity opportunity,
            Integer settlementWindowHours) {
        Integer longIntervalHours = opportunity.getLongFundingIntervalHours();
        Integer shortIntervalHours = opportunity.getShortFundingIntervalHours();
        if (opportunity.getShortFundingRate() == null || opportunity.getLongFundingRate() == null
                || settlementWindowHours == null || longIntervalHours == null || shortIntervalHours == null
                || longIntervalHours <= 0 || shortIntervalHours <= 0) {
            return null;
        }
        BigDecimal window = BigDecimal.valueOf(settlementWindowHours);
        BigDecimal shortSettlements = window.divide(BigDecimal.valueOf(shortIntervalHours));
        BigDecimal longSettlements = window.divide(BigDecimal.valueOf(longIntervalHours));
        return opportunity.getShortFundingRate().multiply(shortSettlements)
                .subtract(opportunity.getLongFundingRate().multiply(longSettlements));
    }

    private static Integer settlementWindowHours(CrossExchangeArbOpportunityEntity opportunity) {
        Integer longIntervalHours = opportunity.getLongFundingIntervalHours();
        Integer shortIntervalHours = opportunity.getShortFundingIntervalHours();
        if (longIntervalHours == null || shortIntervalHours == null || longIntervalHours <= 0
                || shortIntervalHours <= 0) {
            return null;
        }
        return lcm(longIntervalHours, shortIntervalHours);
    }

    private static boolean isCadenceAdjusted(CrossExchangeArbOpportunityEntity opportunity) {
        Integer longIntervalHours = opportunity.getLongFundingIntervalHours();
        Integer shortIntervalHours = opportunity.getShortFundingIntervalHours();
        return longIntervalHours != null && shortIntervalHours != null && longIntervalHours > 0
                && shortIntervalHours > 0 && !longIntervalHours.equals(shortIntervalHours);
    }

    private static int lcm(int left, int right) {
        return Math.abs(left / gcd(left, right) * right);
    }

    private static int gcd(int left, int right) {
        int a = Math.abs(left);
        int b = Math.abs(right);
        while (b != 0) {
            int next = a % b;
            a = b;
            b = next;
        }
        return a;
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
        PnlParts pnl = pnlForPosition(item.getPositionCode());
        return new ArbitrageDashboardViews.Position(item.getPositionCode(), "CROSS_EXCHANGE_FUNDING", item.getSymbol(),
                item.getLongExchange(), item.getShortExchange(), item.getHedgedQuantity(), item.getHedgedQuantity(),
                BigDecimal.ZERO, null, pnl.fundingPnl, pnl.basisPnl, pnl.executionCost,
                pnl.totalPnl(), item.getStatus(), null, format(item.getOpenedAt()), item.getMessage());
    }

    private ArbitrageDashboardViews.Position toSinglePosition(SingleExchangeArbitragePosition item) {
        return new ArbitrageDashboardViews.Position(item.getPositionCode(), "SINGLE_EXCHANGE_SPOT_FUTURES", item.getSymbol(),
                item.getExchangeName() + " SPOT", item.getExchangeName() + " PERP", item.getQuantity(), item.getQuantity(),
                BigDecimal.ZERO, item.getTotalInvestment(), zero(item.getFundingFeesEarned()), zero(item.getRealizedPnl()),
                BigDecimal.ZERO, zero(item.getFundingFeesEarned()).add(zero(item.getRealizedPnl())),
                item.getStatus(), item.getEntryNextFundingTime() == null ? null : FORMATTER.format(Instant.ofEpochMilli(item.getEntryNextFundingTime())),
                format(item.getEntryTime()), item.getErrorMessage());
    }

    private PnlParts pnlForPosition(String positionCode) {
        BigDecimal fundingPnl = settlementTotal(positionCode);
        PnlParts trading = pnlFromOrders(readOrDefault("position orders", List::<CrossExchangeArbOrderEntity>of,
                () -> crossOrderMapper.selectList(new LambdaQueryWrapper<CrossExchangeArbOrderEntity>()
                        .eq(CrossExchangeArbOrderEntity::getPositionCode, positionCode))));
        return new PnlParts(trading.basisPnl, fundingPnl, trading.executionCost);
    }

    private Optional<PnlParts> latestPaperPnlSnapshot() {
        List<CrossExchangeRiskSnapshotEntity> snapshots = readOrDefault("latest paper pnl snapshot", List::of,
                () -> riskSnapshotMapper.selectList(new LambdaQueryWrapper<CrossExchangeRiskSnapshotEntity>()
                        .eq(CrossExchangeRiskSnapshotEntity::getCheckStage, STAGE_PAPER_PNL_PORTFOLIO)
                        .orderByDesc(CrossExchangeRiskSnapshotEntity::getCreatedAt)
                        .last("LIMIT 1")));
        if (snapshots == null || snapshots.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonNode payload = objectMapper.readTree(snapshots.get(0).getDetailsJson());
            return Optional.of(new PnlParts(decimal(payload, "basisPnl"),
                    decimal(payload, "fundingIncome"), decimal(payload, "executionCost")));
        } catch (Exception exception) {
            LOGGER.warn("Skip malformed latest paper pnl snapshot id={}: {}", snapshots.get(0).getId(),
                    exception.getMessage());
            return Optional.empty();
        }
    }

    private PnlParts recentLedgerPnl() {
        Date since = Date.from(Instant.now().minus(PAPER_PNL_OVERVIEW_WINDOW));
        BigDecimal fundingPnl = readOrDefault("recent funding settlements", List::<CrossExchangeFundingSettlementEntity>of,
                () -> crossSettlementMapper.selectList(new LambdaQueryWrapper<CrossExchangeFundingSettlementEntity>()
                        .ge(CrossExchangeFundingSettlementEntity::getSettlementTime, since)
                        .orderByDesc(CrossExchangeFundingSettlementEntity::getSettlementTime)
                        .last("LIMIT " + MAX_OVERVIEW_LEDGER_ROWS))).stream()
                .map(CrossExchangeFundingSettlementEntity::getActualAmount).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        PnlParts trading = pnlFromOrders(readOrDefault("recent paper orders", List::<CrossExchangeArbOrderEntity>of,
                () -> crossOrderMapper.selectList(new LambdaQueryWrapper<CrossExchangeArbOrderEntity>()
                        .gt(CrossExchangeArbOrderEntity::getFilledQuantity, BigDecimal.ZERO)
                        .ge(CrossExchangeArbOrderEntity::getCreatedAt, since)
                        .orderByDesc(CrossExchangeArbOrderEntity::getCreatedAt)
                        .last("LIMIT " + MAX_OVERVIEW_LEDGER_ROWS))));
        return new PnlParts(trading.basisPnl, fundingPnl, trading.executionCost);
    }

    private PnlParts pnlFromOrders(List<CrossExchangeArbOrderEntity> orders) {
        BigDecimal basisPnl = BigDecimal.ZERO;
        BigDecimal executionCost = BigDecimal.ZERO;
        if (orders == null) {
            return new PnlParts(basisPnl, BigDecimal.ZERO, executionCost);
        }
        for (CrossExchangeArbOrderEntity order : orders) {
            BigDecimal notional = zero(order.getFilledQuantity()).multiply(zero(order.getAveragePrice()));
            if ("SHORT".equalsIgnoreCase(order.getLegSide())) {
                basisPnl = basisPnl.add(notional);
            } else if ("LONG".equalsIgnoreCase(order.getLegSide())) {
                basisPnl = basisPnl.subtract(notional);
            }
            executionCost = executionCost.add(zero(order.getFeeAmount()));
        }
        return new PnlParts(basisPnl, BigDecimal.ZERO, executionCost);
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

    private static final class PnlParts {
        private final BigDecimal basisPnl;
        private final BigDecimal fundingPnl;
        private final BigDecimal executionCost;

        private PnlParts(BigDecimal basisPnl, BigDecimal fundingPnl, BigDecimal executionCost) {
            this.basisPnl = basisPnl;
            this.fundingPnl = fundingPnl;
            this.executionCost = executionCost;
        }

        private BigDecimal totalPnl() {
            return basisPnl.add(fundingPnl).subtract(executionCost);
        }
    }

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
