package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletMarketMonitorItemView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletMarketMonitorTrendPointView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletMarketMonitoringSummaryView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SmartWalletMarketMonitoringService {

    private static final String SOURCE = "POLYMARKET";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter BJT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(SHANGHAI);

    private final SmartWalletTradeEventMapper tradeEventMapper;
    private final SmartWalletFollowTaskMapper followTaskMapper;
    private final SmartWalletPositionMapper positionMapper;
    private final SmartWalletMarketInfoService marketInfoService;
    private final MarketConcentrationService marketConcentrationService;

    public SmartWalletMarketMonitoringSummaryView summary(Integer limit, Integer lookbackDays, Integer trendDays) {
        int safeLimit = limit == null ? 40 : Math.max(10, Math.min(limit, 120));
        int safeLookbackDays = lookbackDays == null ? 14 : Math.max(3, Math.min(lookbackDays, 90));
        int safeTrendDays = trendDays == null ? Math.min(7, safeLookbackDays) : Math.max(3, Math.min(trendDays, safeLookbackDays));
        Date since = Date.from(Instant.now().minus(safeLookbackDays, ChronoUnit.DAYS));

        List<SmartWalletTradeEventEntity> recentEvents = tradeEventMapper.selectList(new LambdaQueryWrapper<SmartWalletTradeEventEntity>()
                .eq(SmartWalletTradeEventEntity::getSource, SOURCE)
                .ge(SmartWalletTradeEventEntity::getOpenedAt, since)
                .orderByDesc(SmartWalletTradeEventEntity::getOpenedAt)
                .last("limit " + Math.max(safeLimit * 8, 800)));
        List<SmartWalletFollowTaskEntity> recentTasks = followTaskMapper.selectList(new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                .ge(SmartWalletFollowTaskEntity::getCreatedAt, since)
                .orderByDesc(SmartWalletFollowTaskEntity::getCreatedAt)
                .last("limit " + Math.max(safeLimit * 6, 600)));
        List<SmartWalletPositionEntity> activePositions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .in(SmartWalletPositionEntity::getStatus, List.of("OPEN", "PARTIALLY_CLOSED", "SETTLEMENT_PENDING_ORACLE"))
                .orderByDesc(SmartWalletPositionEntity::getUpdatedAt)
                .last("limit " + Math.max(safeLimit * 4, 300)));

        Map<Long, SmartWalletMarketInfoEntity> marketInfoById = loadMarketInfo(recentTasks, activePositions);
        LinkedHashMap<String, MarketAccumulator> accumulators = new LinkedHashMap<>();
        ingestEvents(accumulators, recentEvents);
        ingestTasks(accumulators, recentTasks, marketInfoById);
        ingestPositions(accumulators, activePositions, marketInfoById);

        LinkedHashSet<String> candidateMarketIds = new LinkedHashSet<>();
        accumulators.values().stream()
                .sorted(Comparator.comparing(MarketAccumulator::priorityScore).reversed()
                        .thenComparing(MarketAccumulator::lastSignalAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(Math.max(safeLimit * 3L, 90L))
                .map(MarketAccumulator::marketId)
                .forEach(candidateMarketIds::add);

        List<SmartWalletMarketMonitorItemView> analyzedRows = candidateMarketIds.stream()
                .map(accumulators::get)
                .filter(Objects::nonNull)
                .map(this::toItemView)
                .sorted(this::compareMarketRows)
                .toList();
        List<SmartWalletMarketMonitorItemView> visibleRows = analyzedRows.stream()
                .limit(safeLimit)
                .toList();

        Map<String, SmartWalletMarketMonitorItemView> visibleByMarket = visibleRows.stream()
                .collect(Collectors.toMap(SmartWalletMarketMonitorItemView::getMarketId, row -> row, (left, right) -> left, LinkedHashMap::new));

        return SmartWalletMarketMonitoringSummaryView.builder()
                .generatedAt(new Date())
                .lookbackDays(safeLookbackDays)
                .trendDays(safeTrendDays)
                .monitoredMarketCount(analyzedRows.size())
                .activeMarketCount((int) analyzedRows.stream().filter(row -> Boolean.TRUE.equals(row.getActiveMarket())).count())
                .effectiveMarketCount((int) analyzedRows.stream().filter(row -> Boolean.TRUE.equals(row.getEffectiveMarket())).count())
                .concentratedMarketCount((int) analyzedRows.stream().filter(row -> Boolean.TRUE.equals(row.getHighlyConcentrated())).count())
                .extremeConcentratedMarketCount((int) analyzedRows.stream().filter(row -> Boolean.TRUE.equals(row.getExtremelyConcentrated())).count())
                .openPositionMarketCount((int) analyzedRows.stream().filter(row -> safeInt(row.getOpenPositionCount()) > 0).count())
                .pendingTaskMarketCount((int) analyzedRows.stream().filter(row -> safeInt(row.getPendingTaskCount()) > 0).count())
                .signalDrivenMarketCount((int) analyzedRows.stream().filter(row -> safeInt(row.getSignalCount()) > 0).count())
                .concentrationCoverageRate(ratio(
                        analyzedRows.stream().mapToInt(row -> Boolean.TRUE.equals(row.getConcentrationAvailable()) ? 1 : 0).sum(),
                        analyzedRows.size()))
                .trend(buildTrendPoints(visibleByMarket, recentEvents, recentTasks, marketInfoById, safeTrendDays))
                .items(visibleRows)
                .build();
    }

    private Map<Long, SmartWalletMarketInfoEntity> loadMarketInfo(List<SmartWalletFollowTaskEntity> tasks,
                                                                  List<SmartWalletPositionEntity> positions) {
        LinkedHashSet<Long> marketInfoIds = new LinkedHashSet<>();
        tasks.stream().map(SmartWalletFollowTaskEntity::getMarketInfoId).filter(Objects::nonNull).forEach(marketInfoIds::add);
        positions.stream().map(SmartWalletPositionEntity::getMarketInfoId).filter(Objects::nonNull).forEach(marketInfoIds::add);
        return marketInfoService.batchGetByIds(marketInfoIds);
    }

    private void ingestEvents(Map<String, MarketAccumulator> accumulators, List<SmartWalletTradeEventEntity> recentEvents) {
        for (SmartWalletTradeEventEntity event : recentEvents) {
            if (event == null || !StringUtils.hasText(event.getMarketId())) {
                continue;
            }
            MarketAccumulator item = accumulators.computeIfAbsent(event.getMarketId().trim(),
                    key -> new MarketAccumulator(key, event.getQuestion()));
            item.question = firstNonBlank(item.question, event.getQuestion());
            item.signalCount++;
            if (StringUtils.hasText(event.getWalletAddress())) {
                item.uniqueWallets.add(event.getWalletAddress().trim().toLowerCase(Locale.ROOT));
            }
            if (event.getOpenedAt() != null && (item.lastSignalAt == null || event.getOpenedAt().after(item.lastSignalAt))) {
                item.lastSignalAt = event.getOpenedAt();
            }
        }
    }

    private void ingestTasks(Map<String, MarketAccumulator> accumulators,
                             List<SmartWalletFollowTaskEntity> recentTasks,
                             Map<Long, SmartWalletMarketInfoEntity> marketInfoById) {
        for (SmartWalletFollowTaskEntity task : recentTasks) {
            if (task == null || task.getMarketInfoId() == null) {
                continue;
            }
            SmartWalletMarketInfoEntity marketInfo = marketInfoById.get(task.getMarketInfoId());
            if (marketInfo == null || !StringUtils.hasText(marketInfo.getMarketId())) {
                continue;
            }
            MarketAccumulator item = accumulators.computeIfAbsent(marketInfo.getMarketId().trim(),
                    key -> new MarketAccumulator(key, marketInfo.getQuestion()));
            item.question = firstNonBlank(item.question, marketInfo.getQuestion());
            item.marketEndAt = firstNonNull(item.marketEndAt, marketInfo.getMarketEndAt());
            if ("PENDING".equalsIgnoreCase(task.getStatus())) {
                item.pendingTaskCount++;
            }
        }
    }

    private void ingestPositions(Map<String, MarketAccumulator> accumulators,
                                 List<SmartWalletPositionEntity> activePositions,
                                 Map<Long, SmartWalletMarketInfoEntity> marketInfoById) {
        for (SmartWalletPositionEntity position : activePositions) {
            if (position == null || position.getMarketInfoId() == null) {
                continue;
            }
            SmartWalletMarketInfoEntity marketInfo = marketInfoById.get(position.getMarketInfoId());
            if (marketInfo == null || !StringUtils.hasText(marketInfo.getMarketId())) {
                continue;
            }
            MarketAccumulator item = accumulators.computeIfAbsent(marketInfo.getMarketId().trim(),
                    key -> new MarketAccumulator(key, marketInfo.getQuestion()));
            item.question = firstNonBlank(item.question, marketInfo.getQuestion());
            item.marketEndAt = firstNonNull(item.marketEndAt, marketInfo.getMarketEndAt());
            item.openPositionCount++;
            item.openCostUsdc = item.openCostUsdc.add(safe(position.getCostBasisUsdc()));
            item.realizedPnlUsdc = item.realizedPnlUsdc.add(safe(position.getRealizedPnlUsdc()));
            item.unrealizedPnlUsdc = item.unrealizedPnlUsdc.add(safe(position.getUnrealizedPnlUsdc()));
        }
    }

    private SmartWalletMarketMonitorItemView toItemView(MarketAccumulator accumulator) {
        MarketConcentrationService.ConcentrationSnapshot concentration = marketConcentrationService.analyze(accumulator.marketId, null);
        boolean concentrationAvailable = concentration != null && concentration.isDataAvailable();
        boolean activeMarket = accumulator.marketEndAt == null || accumulator.marketEndAt > System.currentTimeMillis();
        boolean highlyConcentrated = concentrationAvailable && concentration.isHighlyConcentrated();
        boolean extremelyConcentrated = concentrationAvailable && concentration.isExtremelyConcentrated();
        boolean effectiveMarket = activeMarket && concentrationAvailable && !highlyConcentrated;
        return SmartWalletMarketMonitorItemView.builder()
                .marketId(accumulator.marketId)
                .question(accumulator.question)
                .marketEndAt(accumulator.marketEndAt)
                .marketEndAtBjt(formatEpoch(accumulator.marketEndAt))
                .lastSignalAt(accumulator.lastSignalAt)
                .lastSignalAtBjt(formatDate(accumulator.lastSignalAt))
                .signalCount(accumulator.signalCount)
                .uniqueWalletCount(accumulator.uniqueWallets.size())
                .pendingTaskCount(accumulator.pendingTaskCount)
                .openPositionCount(accumulator.openPositionCount)
                .openCostUsdc(scaleMoney(accumulator.openCostUsdc))
                .realizedPnlUsdc(scaleMoney(accumulator.realizedPnlUsdc))
                .unrealizedPnlUsdc(scaleMoney(accumulator.unrealizedPnlUsdc))
                .activeMarket(activeMarket)
                .effectiveMarket(effectiveMarket)
                .concentrationAvailable(concentrationAvailable)
                .observedHolders(concentrationAvailable ? concentration.observedHolders() : 0)
                .top1Pct(scaleRate(concentrationAvailable ? concentration.top1Pct() : BigDecimal.ZERO))
                .top3Pct(scaleRate(concentrationAvailable ? concentration.top3Pct() : BigDecimal.ZERO))
                .top5Pct(scaleRate(concentrationAvailable ? concentration.top5Pct() : BigDecimal.ZERO))
                .hhi(concentrationAvailable ? concentration.hhi() : 0)
                .highlyConcentrated(highlyConcentrated)
                .extremelyConcentrated(extremelyConcentrated)
                .concentrationBand(resolveBand(concentrationAvailable, highlyConcentrated, extremelyConcentrated))
                .topHolderAddresses(concentrationAvailable ? concentration.topHolderAddresses() : List.of())
                .build();
    }

    private List<SmartWalletMarketMonitorTrendPointView> buildTrendPoints(Map<String, SmartWalletMarketMonitorItemView> visibleByMarket,
                                                                          List<SmartWalletTradeEventEntity> recentEvents,
                                                                          List<SmartWalletFollowTaskEntity> recentTasks,
                                                                          Map<Long, SmartWalletMarketInfoEntity> marketInfoById,
                                                                          int trendDays) {
        LocalDate start = LocalDate.now(SHANGHAI).minusDays(trendDays - 1L);
        LinkedHashMap<LocalDate, TrendAccumulator> trend = new LinkedHashMap<>();
        for (int i = 0; i < trendDays; i++) {
            LocalDate day = start.plusDays(i);
            trend.put(day, new TrendAccumulator(day));
        }

        for (SmartWalletTradeEventEntity event : recentEvents) {
            if (event == null || event.getOpenedAt() == null || !StringUtils.hasText(event.getMarketId())) {
                continue;
            }
            SmartWalletMarketMonitorItemView row = visibleByMarket.get(event.getMarketId().trim());
            if (row == null) {
                continue;
            }
            LocalDate date = Instant.ofEpochMilli(event.getOpenedAt().getTime()).atZone(SHANGHAI).toLocalDate();
            TrendAccumulator item = trend.get(date);
            if (item == null) {
                continue;
            }
            item.signalCount++;
            item.marketIds.add(row.getMarketId());
            if (Boolean.TRUE.equals(row.getEffectiveMarket())) {
                item.effectiveMarketIds.add(row.getMarketId());
            }
            if (Boolean.TRUE.equals(row.getHighlyConcentrated())) {
                item.concentratedMarketIds.add(row.getMarketId());
            }
            if (Boolean.TRUE.equals(row.getExtremelyConcentrated())) {
                item.extremeMarketIds.add(row.getMarketId());
            }
        }

        for (SmartWalletFollowTaskEntity task : recentTasks) {
            if (task == null || task.getCreatedAt() == null || task.getMarketInfoId() == null) {
                continue;
            }
            SmartWalletMarketInfoEntity marketInfo = marketInfoById.get(task.getMarketInfoId());
            String marketId = marketInfo == null || !StringUtils.hasText(marketInfo.getMarketId())
                    ? null
                    : marketInfo.getMarketId().trim();
            if (!StringUtils.hasText(marketId)) {
                continue;
            }
            SmartWalletMarketMonitorItemView row = visibleByMarket.get(marketId);
            if (row == null) {
                continue;
            }
            LocalDate date = Instant.ofEpochMilli(task.getCreatedAt().getTime()).atZone(SHANGHAI).toLocalDate();
            TrendAccumulator item = trend.get(date);
            if (item == null) {
                continue;
            }
            if ("PENDING".equalsIgnoreCase(task.getStatus())) {
                item.pendingTaskCount++;
            }
            item.marketIds.add(marketId);
            if (Boolean.TRUE.equals(row.getEffectiveMarket())) {
                item.effectiveMarketIds.add(marketId);
            }
            if (Boolean.TRUE.equals(row.getHighlyConcentrated())) {
                item.concentratedMarketIds.add(marketId);
            }
            if (Boolean.TRUE.equals(row.getExtremelyConcentrated())) {
                item.extremeMarketIds.add(marketId);
            }
        }

        return trend.values().stream()
                .map(item -> SmartWalletMarketMonitorTrendPointView.builder()
                        .date(item.date.toString())
                        .monitoredMarketCount(item.marketIds.size())
                        .effectiveMarketCount(item.effectiveMarketIds.size())
                        .concentratedMarketCount(item.concentratedMarketIds.size())
                        .extremeConcentratedMarketCount(item.extremeMarketIds.size())
                        .signalCount(item.signalCount)
                        .pendingTaskCount(item.pendingTaskCount)
                        .build())
                .toList();
    }

    private int compareMarketRows(SmartWalletMarketMonitorItemView left, SmartWalletMarketMonitorItemView right) {
        int extremeCompare = Boolean.compare(Boolean.TRUE.equals(right.getExtremelyConcentrated()), Boolean.TRUE.equals(left.getExtremelyConcentrated()));
        if (extremeCompare != 0) return extremeCompare;
        int highCompare = Boolean.compare(Boolean.TRUE.equals(right.getHighlyConcentrated()), Boolean.TRUE.equals(left.getHighlyConcentrated()));
        if (highCompare != 0) return highCompare;
        int openCompare = Integer.compare(safeInt(right.getOpenPositionCount()), safeInt(left.getOpenPositionCount()));
        if (openCompare != 0) return openCompare;
        int pendingCompare = Integer.compare(safeInt(right.getPendingTaskCount()), safeInt(left.getPendingTaskCount()));
        if (pendingCompare != 0) return pendingCompare;
        int signalCompare = Integer.compare(safeInt(right.getSignalCount()), safeInt(left.getSignalCount()));
        if (signalCompare != 0) return signalCompare;
        return Comparator.nullsLast(Comparator.<Date>naturalOrder()).compare(right.getLastSignalAt(), left.getLastSignalAt());
    }

    private String resolveBand(boolean concentrationAvailable, boolean highlyConcentrated, boolean extremelyConcentrated) {
        if (!concentrationAvailable) {
            return "UNKNOWN";
        }
        if (extremelyConcentrated) {
            return "EXTREME";
        }
        if (highlyConcentrated) {
            return "HIGH";
        }
        return "HEALTHY";
    }

    private BigDecimal ratio(int numerator, int denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal scaleMoney(BigDecimal value) {
        return safe(value).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal scaleRate(BigDecimal value) {
        return safe(value).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private String formatDate(Date date) {
        return date == null ? null : BJT_FORMATTER.format(date.toInstant());
    }

    private String formatEpoch(Long epochMs) {
        return epochMs == null || epochMs <= 0 ? null : BJT_FORMATTER.format(Instant.ofEpochMilli(epochMs));
    }

    private <T> T firstNonNull(T left, T right) {
        return left != null ? left : right;
    }

    private String firstNonBlank(String left, String right) {
        return StringUtils.hasText(left) ? left : right;
    }

    private static final class TrendAccumulator {
        private final LocalDate date;
        private final Set<String> marketIds = new LinkedHashSet<>();
        private final Set<String> effectiveMarketIds = new LinkedHashSet<>();
        private final Set<String> concentratedMarketIds = new LinkedHashSet<>();
        private final Set<String> extremeMarketIds = new LinkedHashSet<>();
        private int signalCount;
        private int pendingTaskCount;

        private TrendAccumulator(LocalDate date) {
            this.date = date;
        }
    }

    private static final class MarketAccumulator {
        private final String marketId;
        private String question;
        private Long marketEndAt;
        private Date lastSignalAt;
        private int signalCount;
        private int pendingTaskCount;
        private int openPositionCount;
        private BigDecimal openCostUsdc = BigDecimal.ZERO;
        private BigDecimal realizedPnlUsdc = BigDecimal.ZERO;
        private BigDecimal unrealizedPnlUsdc = BigDecimal.ZERO;
        private final Set<String> uniqueWallets = new LinkedHashSet<>();

        private MarketAccumulator(String marketId, String question) {
            this.marketId = marketId;
            this.question = question;
        }

        private String marketId() {
            return marketId;
        }

        private Date lastSignalAt() {
            return lastSignalAt;
        }

        private BigDecimal priorityScore() {
            return BigDecimal.valueOf(signalCount)
                    .add(BigDecimal.valueOf(pendingTaskCount).multiply(new BigDecimal("1.5")))
                    .add(BigDecimal.valueOf(openPositionCount).multiply(new BigDecimal("2.5")))
                    .add(BigDecimal.valueOf(uniqueWallets.size()).multiply(new BigDecimal("0.5")));
        }
    }
}
