package com.metaverse.coin.strategy.web.service.strategy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbOpportunityMapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeFundingSettlementMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SingleExchangeArbitragePositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeArbOpportunityEntity;
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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.function.Supplier;

@Service
public class ArbitrageDashboardQueryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ArbitrageDashboardQueryService.class);

    private static final String TIMEZONE = "Asia/Shanghai";
    private static final String SOURCE = "PAPER_PUBLIC_ESTIMATE";
    private static final int MAX_PAGE_SIZE = 100;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withLocale(Locale.SIMPLIFIED_CHINESE).withZone(ZoneId.of(TIMEZONE));

    private final CrossExchangeArbOpportunityMapper opportunityMapper;
    private final CrossExchangeArbPositionMapper crossPositionMapper;
    private final CrossExchangeFundingSettlementMapper crossSettlementMapper;
    private final SingleExchangeArbitragePositionMapper singlePositionMapper;

    public ArbitrageDashboardQueryService(CrossExchangeArbOpportunityMapper opportunityMapper,
                                          CrossExchangeArbPositionMapper crossPositionMapper,
                                          CrossExchangeFundingSettlementMapper crossSettlementMapper,
                                          SingleExchangeArbitragePositionMapper singlePositionMapper) {
        this.opportunityMapper = opportunityMapper;
        this.crossPositionMapper = crossPositionMapper;
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
                expectedYield, fundingPnl, BigDecimal.ZERO, "UNKNOWN", opportunities.size(), rejected));
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
        List<ArbitrageDashboardViews.TimePoint> points = readOrDefault("funding timeseries",
                List::<CrossExchangeFundingSettlementEntity>of,
                () -> crossSettlementMapper.selectList(new LambdaQueryWrapper<CrossExchangeFundingSettlementEntity>()
                        .orderByAsc(CrossExchangeFundingSettlementEntity::getSettlementTime)))
                .stream().map(item -> new ArbitrageDashboardViews.TimePoint(format(item.getSettlementTime()),
                        zero(item.getActualAmount()), BigDecimal.ZERO, BigDecimal.ZERO, zero(item.getActualAmount()))).toList();
        return envelope(points);
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
