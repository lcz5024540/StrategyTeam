package com.metaverse.coin.strategy.common.service.smartwallet.service;
import com.metaverse.coin.strategy.common.service.smartwallet.model.MarketStateSnapshot;
import com.metaverse.coin.strategy.common.service.smartwallet.model.ConcentrationSnapshot;
import com.metaverse.coin.strategy.common.service.smartwallet.service.MarketConcentrationSnapshotService;
import com.metaverse.coin.strategy.common.service.smartwallet.service.MarketConcentrationSnapshotService.SnapshotView;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletMarketInfoService;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletMarketSnapshotMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketSnapshotEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.common.service.smartwallet.model.SmartWalletMarketMonitorItemView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.SmartWalletMarketMonitorTrendPointView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.SmartWalletMarketMonitoringSummaryView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.OffsetPageView;
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
import java.util.ArrayList;
import java.util.Collection;
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
public class SmartWalletMarketMonitoringReadService {

    private static final String SOURCE = "POLYMARKET";
    private static final List<String> ACTIVE_TASK_STATUSES = List.of("PENDING", "EXECUTING");
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter BJT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(SHANGHAI);
    private static final long DATASET_CACHE_TTL_MS = 60_000L;

    private final SmartWalletTradeEventMapper tradeEventMapper;
    private final SmartWalletFollowTaskMapper followTaskMapper;
    private final SmartWalletPositionMapper positionMapper;
    private final SmartWalletProperties properties;
    private final SmartWalletMarketInfoService marketInfoService;
    private final SmartWalletMarketSnapshotMapper marketSnapshotMapper;
    private final MarketConcentrationSnapshotService concentrationSnapshotService;

    private volatile MarketDataset cachedTableDataset;
    private volatile int cachedTableDatasetLookbackDays;
    private volatile long cachedTableDatasetExpiresAt;

    public SmartWalletMarketMonitoringSummaryView summary(Integer limit, Integer lookbackDays, Integer trendDays) {
        int safeLimit = limit == null ? 40 : Math.max(10, Math.min(limit, 120));
        int safeLookbackDays = lookbackDays == null ? 14 : Math.max(3, Math.min(lookbackDays, 90));
        int safeTrendDays = trendDays == null ? Math.min(7, safeLookbackDays) : Math.max(3, Math.min(trendDays, safeLookbackDays));
        MarketDataset dataset = buildDataset(safeLimit, safeLookbackDays);
        List<SmartWalletMarketMonitorItemView> analyzedRows = dataset.rows();
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
                .trend(buildTrendPoints(visibleByMarket, dataset.recentEvents(), dataset.recentTasks(), dataset.marketInfoById(), safeTrendDays))
                .items(visibleRows)
                .build();
    }

    public OffsetPageView<SmartWalletMarketMonitorItemView> tablePage(Integer page,
                                                                      Integer pageSize,
                                                                      Integer lookbackDays,
                                                                      String keyword,
                                                                      String category,
                                                                      String concentrationBand,
                                                                      Boolean hasPosition,
                                                                      String positionDirection) {
        int safeLookbackDays = lookbackDays == null ? 14 : Math.max(3, Math.min(lookbackDays, 90));
        int safePageSize = pageSize == null ? 10 : Math.max(5, Math.min(pageSize, 100));
        int safePage = page == null ? 1 : Math.max(page, 1);
        MarketDataset dataset = getOrBuildTableDataset(safeLookbackDays);

        List<SmartWalletMarketMonitorItemView> filteredRows = dataset.rows().stream()
                .filter(row -> matchesKeyword(row, keyword))
                .filter(row -> matchesCategory(row, category))
                .filter(row -> matchesConcentrationBand(row, concentrationBand))
                .filter(row -> matchesHasPosition(row, hasPosition))
                .filter(row -> matchesPositionDirection(row, positionDirection))
                .toList();

        int totalItems = filteredRows.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) totalItems / safePageSize));
        int resolvedPage = Math.min(safePage, totalPages);
        int start = Math.min((resolvedPage - 1) * safePageSize, totalItems);
        int end = Math.min(start + safePageSize, totalItems);
        List<SmartWalletMarketMonitorItemView> pageItems = new ArrayList<>(filteredRows.subList(start, end));
        // Populate outcome prices for page items
        enrichOutcomePrices(pageItems);
        return OffsetPageView.of(pageItems, resolvedPage, safePageSize, totalItems);
    }

    private MarketDataset getOrBuildTableDataset(int lookbackDays) {
        long now = System.currentTimeMillis();
        MarketDataset cached = this.cachedTableDataset;
        if (cached != null && now < cachedTableDatasetExpiresAt && lookbackDays == cachedTableDatasetLookbackDays) {
            return cached;
        }
        MarketDataset fresh = buildDataset(500, lookbackDays);
        this.cachedTableDataset = fresh;
        this.cachedTableDatasetLookbackDays = lookbackDays;
        this.cachedTableDatasetExpiresAt = now + DATASET_CACHE_TTL_MS;
        return fresh;
    }

    private void enrichOutcomePrices(List<SmartWalletMarketMonitorItemView> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        // 批量获取所有需要的 marketId 的快照，避免 N+1 查询
        List<String> marketIds = items.stream()
                .map(SmartWalletMarketMonitorItemView::getMarketId)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        Map<String, BigDecimal> yesPriceByMarket = new LinkedHashMap<>();
        Map<String, BigDecimal> noPriceByMarket = new LinkedHashMap<>();
        Date now = new Date();
        for (String marketId : marketIds) {
            try {
                var yesSnapshot = findLatestSnapshotAtOrBefore(marketId, "YES", now);
                var noSnapshot = findLatestSnapshotAtOrBefore(marketId, "NO", now);
                if (yesSnapshot != null && yesSnapshot.getMidPrice() != null) {
                    yesPriceByMarket.put(marketId, yesSnapshot.getMidPrice());
                }
                if (noSnapshot != null && noSnapshot.getMidPrice() != null) {
                    noPriceByMarket.put(marketId, noSnapshot.getMidPrice());
                }
            } catch (Exception ignored) {
            }
        }
        for (SmartWalletMarketMonitorItemView item : items) {
            BigDecimal yesPrice = yesPriceByMarket.get(item.getMarketId());
            BigDecimal noPrice = noPriceByMarket.get(item.getMarketId());
            if (yesPrice != null || noPrice != null) {
                String yes = yesPrice != null ? yesPrice.setScale(2, RoundingMode.HALF_UP).toPlainString() : "-";
                String no = noPrice != null ? noPrice.setScale(2, RoundingMode.HALF_UP).toPlainString() : "-";
                item.setOutcomePrices(yes + " / " + no);
            }
        }
    }

    public List<String> listCandidateMarketIds(Integer limit, Integer lookbackDays) {
        int safeLimit = limit == null ? 60 : Math.max(10, Math.min(limit, 160));
        int safeLookbackDays = lookbackDays == null ? 30 : Math.max(3, Math.min(lookbackDays, 90));
        MonitoringBaseData dataset = buildBaseData(safeLimit, safeLookbackDays);
        Map<String, MarketStateSnapshot> stateByMarketId = loadMarketStateSnapshots(
                dataset.accumulators().stream().map(MarketAccumulator::marketId).toList()
        );
        return dataset.accumulators().stream()
                .filter(accumulator -> shouldTrackCandidateMarket(
                        accumulator,
                        stateByMarketId.get(accumulator.marketId())))
                .map(MarketAccumulator::marketId)
                .limit(safeLimit)
                .toList();
    }

    private MarketDataset buildDataset(int fetchHint, int lookbackDays) {
        MonitoringBaseData baseData = buildBaseData(fetchHint, lookbackDays);
        Map<String, MarketStateSnapshot> stateByMarketId =
                loadMarketStateSnapshots(
                        baseData.accumulators().stream().map(MarketAccumulator::marketId).toList());
        Map<String, SnapshotView> concentrationSnapshots =
                concentrationSnapshotService.batchGetLatestSnapshots(
                        baseData.accumulators().stream()
                                .filter(accumulator -> shouldTrackCandidateMarket(
                                        accumulator,
                                        stateByMarketId.get(accumulator.marketId())))
                                .map(MarketAccumulator::marketId)
                                .toList(),
                        null);
        List<SmartWalletMarketMonitorItemView> analyzedRows = baseData.accumulators().stream()
                .map(accumulator -> toItemView(
                        accumulator,
                        stateByMarketId.get(accumulator.marketId()),
                        concentrationSnapshots.get(accumulator.marketId())))
                .filter(Objects::nonNull)
                .sorted(this::compareMarketRows)
                .toList();
        return new MarketDataset(baseData.recentEvents(), baseData.recentTasks(), baseData.marketInfoById(), analyzedRows);
    }

    private MonitoringBaseData buildBaseData(int fetchHint, int lookbackDays) {
        int safeLookbackDays = Math.max(3, Math.min(lookbackDays, 90));
        int safeFetchHint = Math.max(fetchHint, 40);
        Instant now = Instant.now();
        Date since = Date.from(now.minus(safeLookbackDays, ChronoUnit.DAYS));
        Date activeTaskSince = Date.from(now.minus(resolveActiveTaskMaxAgeSeconds(), ChronoUnit.SECONDS));

        List<SmartWalletTradeEventEntity> recentEvents = loadTradeEvents(since, safeFetchHint);
        List<SmartWalletFollowTaskEntity> recentTasks = loadFollowTasks(activeTaskSince, safeFetchHint);
        List<SmartWalletFollowTaskEntity> activeTasks = filterActiveRecentTasks(recentTasks, activeTaskSince);
        List<SmartWalletPositionEntity> activePositions = loadPositions(safeFetchHint);

        Map<Long, SmartWalletMarketInfoEntity> marketInfoById = loadMarketInfo(activeTasks, activePositions);
        Map<String, SmartWalletMarketInfoEntity> primaryInfoByMarketId = marketInfoService.batchGetPrimaryByMarketIds(
                collectCandidateMarketIds(recentEvents, activeTasks, activePositions, marketInfoById)
        );
        LinkedHashMap<String, MarketAccumulator> accumulators = new LinkedHashMap<>();
        ingestEvents(accumulators, recentEvents, primaryInfoByMarketId);
        ingestTasks(accumulators, activeTasks, marketInfoById, primaryInfoByMarketId);
        ingestPositions(accumulators, activePositions, marketInfoById, primaryInfoByMarketId);
        accumulators.values().forEach(accumulator -> applyPrimaryMarketInfo(
                accumulator,
                primaryInfoByMarketId.get(accumulator.marketId())));
        List<MarketAccumulator> prioritizedAccumulators = accumulators.values().stream()
                .filter(this::shouldTrackByKnownFacts)
                .sorted(Comparator.comparing(MarketAccumulator::priorityScore).reversed()
                        .thenComparing(MarketAccumulator::lastSignalAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        return new MonitoringBaseData(recentEvents, activeTasks, marketInfoById, prioritizedAccumulators);
    }

    private List<SmartWalletFollowTaskEntity> filterActiveRecentTasks(List<SmartWalletFollowTaskEntity> tasks,
                                                                      Date activeTaskSince) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        return tasks.stream()
                .filter(task -> task != null)
                .filter(task -> isActiveTaskStatus(task.getStatus()))
                .filter(task -> task.getCreatedAt() != null && !task.getCreatedAt().before(activeTaskSince))
                .toList();
    }

    private boolean isActiveTaskStatus(String status) {
        return StringUtils.hasText(status)
                && ACTIVE_TASK_STATUSES.stream().anyMatch(activeStatus -> activeStatus.equalsIgnoreCase(status.trim()));
    }

    private long resolveActiveTaskMaxAgeSeconds() {
        SmartWalletProperties.MarketMonitoring marketMonitoring = properties.getMarketMonitoring();
        Integer maxAgeSeconds = marketMonitoring == null ? null : marketMonitoring.getActiveTaskMaxAgeSeconds();
        return maxAgeSeconds == null || maxAgeSeconds <= 0 ? 10800L : maxAgeSeconds.longValue();
    }

    private List<String> collectCandidateMarketIds(List<SmartWalletTradeEventEntity> recentEvents,
                                                   List<SmartWalletFollowTaskEntity> recentTasks,
                                                   List<SmartWalletPositionEntity> activePositions,
                                                   Map<Long, SmartWalletMarketInfoEntity> marketInfoById) {
        LinkedHashSet<String> marketIds = new LinkedHashSet<>();
        for (SmartWalletTradeEventEntity event : recentEvents) {
            if (event != null && StringUtils.hasText(event.getMarketId())) {
                marketIds.add(event.getMarketId().trim());
            }
        }
        for (SmartWalletFollowTaskEntity task : recentTasks) {
            if (task == null || task.getMarketInfoId() == null) {
                continue;
            }
            SmartWalletMarketInfoEntity marketInfo = marketInfoById.get(task.getMarketInfoId());
            if (marketInfo != null && StringUtils.hasText(marketInfo.getMarketId())) {
                marketIds.add(marketInfo.getMarketId().trim());
            }
        }
        for (SmartWalletPositionEntity position : activePositions) {
            if (position == null || position.getMarketInfoId() == null) {
                continue;
            }
            SmartWalletMarketInfoEntity marketInfo = marketInfoById.get(position.getMarketInfoId());
            if (marketInfo != null && StringUtils.hasText(marketInfo.getMarketId())) {
                marketIds.add(marketInfo.getMarketId().trim());
            }
        }
        return marketIds.stream().toList();
    }

    private Map<Long, SmartWalletMarketInfoEntity> loadMarketInfo(List<SmartWalletFollowTaskEntity> tasks,
                                                                  List<SmartWalletPositionEntity> positions) {
        LinkedHashSet<Long> marketInfoIds = new LinkedHashSet<>();
        tasks.stream().map(SmartWalletFollowTaskEntity::getMarketInfoId).filter(Objects::nonNull).forEach(marketInfoIds::add);
        positions.stream().map(SmartWalletPositionEntity::getMarketInfoId).filter(Objects::nonNull).forEach(marketInfoIds::add);
        return marketInfoService.batchGetByIds(marketInfoIds);
    }

    private void ingestEvents(Map<String, MarketAccumulator> accumulators,
                              List<SmartWalletTradeEventEntity> recentEvents,
                              Map<String, SmartWalletMarketInfoEntity> primaryInfoByMarketId) {
        for (SmartWalletTradeEventEntity event : recentEvents) {
            if (event == null || !StringUtils.hasText(event.getMarketId())) {
                continue;
            }
            String marketId = event.getMarketId().trim();
            if (!shouldTrackKnownMarket(primaryInfoByMarketId.get(marketId))) {
                continue;
            }
            MarketAccumulator item = accumulators.computeIfAbsent(marketId,
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
                             Map<Long, SmartWalletMarketInfoEntity> marketInfoById,
                             Map<String, SmartWalletMarketInfoEntity> primaryInfoByMarketId) {
        for (SmartWalletFollowTaskEntity task : recentTasks) {
            if (task == null || task.getMarketInfoId() == null) {
                continue;
            }
            SmartWalletMarketInfoEntity marketInfo = marketInfoById.get(task.getMarketInfoId());
            if (marketInfo == null || !StringUtils.hasText(marketInfo.getMarketId())) {
                continue;
            }
            String marketId = marketInfo.getMarketId().trim();
            if (!shouldTrackKnownMarket(primaryInfoByMarketId.get(marketId))) {
                continue;
            }
            MarketAccumulator item = accumulators.computeIfAbsent(marketId,
                    key -> new MarketAccumulator(key, marketInfo.getQuestion()));
            applyPrimaryMarketInfo(item, marketInfo);
            if ("PENDING".equalsIgnoreCase(task.getStatus())) {
                item.pendingTaskCount++;
            }
        }
    }

    private void ingestPositions(Map<String, MarketAccumulator> accumulators,
                                 List<SmartWalletPositionEntity> activePositions,
                                 Map<Long, SmartWalletMarketInfoEntity> marketInfoById,
                                 Map<String, SmartWalletMarketInfoEntity> primaryInfoByMarketId) {
        for (SmartWalletPositionEntity position : activePositions) {
            if (position == null || position.getMarketInfoId() == null) {
                continue;
            }
            SmartWalletMarketInfoEntity marketInfo = marketInfoById.get(position.getMarketInfoId());
            if (marketInfo == null || !StringUtils.hasText(marketInfo.getMarketId())) {
                continue;
            }
            String marketId = marketInfo.getMarketId().trim();
            if (!shouldTrackKnownMarket(primaryInfoByMarketId.get(marketId))) {
                continue;
            }
            MarketAccumulator item = accumulators.computeIfAbsent(marketId,
                    key -> new MarketAccumulator(key, marketInfo.getQuestion()));
            applyPrimaryMarketInfo(item, marketInfo);
            item.openPositionCount++;
            String positionDirection = resolvePositionDirection(position, marketInfo);
            if (StringUtils.hasText(positionDirection)) {
                item.positionDirections.add(positionDirection);
            }
            item.openCostUsdc = item.openCostUsdc.add(safe(position.getCostBasisUsdc()));
            item.realizedPnlUsdc = item.realizedPnlUsdc.add(safe(position.getRealizedPnlUsdc()));
            item.unrealizedPnlUsdc = item.unrealizedPnlUsdc.add(safe(position.getUnrealizedPnlUsdc()));
        }
    }

    private void applyPrimaryMarketInfo(MarketAccumulator accumulator, SmartWalletMarketInfoEntity marketInfo) {
        if (accumulator == null || marketInfo == null) {
            return;
        }
        accumulator.question = firstNonBlank(accumulator.question, marketInfo.getQuestion());
        accumulator.marketEndAt = firstNonNull(accumulator.marketEndAt, marketInfo.getMarketEndAt());
        accumulator.sourceCategory = firstNonBlank(accumulator.sourceCategory, marketInfo.getSourceCategory());
        accumulator.normalizedCategory = firstNonBlank(accumulator.normalizedCategory, marketInfo.getNormalizedCategory());
        accumulator.marketLifecycle = firstNonBlank(accumulator.marketLifecycle, marketInfo.getMarketLifecycle());
    }

    private SmartWalletMarketMonitorItemView toItemView(MarketAccumulator accumulator,
                                                        MarketStateSnapshot stateSnapshot,
                                                        SnapshotView concentrationView) {
        MarketStateSnapshot snapshot = stateSnapshot == null
                ? MarketStateSnapshot.unknown(accumulator.marketId)
                : stateSnapshot;
        Long displayMarketEndAt = firstNonNull(snapshot.marketEndAt(), accumulator.marketEndAt);
        if (!shouldDisplayActiveMarket(snapshot, displayMarketEndAt)) {
            return null;
        }
        ConcentrationSnapshot concentration =
                concentrationView == null ? null : concentrationView.snapshot();
        boolean concentrationAvailable = concentration != null && concentration.isDataAvailable();
        boolean activeMarket = snapshot.isActive();
        boolean highlyConcentrated = concentrationAvailable && concentration.isHighlyConcentrated();
        boolean extremelyConcentrated = concentrationAvailable && concentration.isExtremelyConcentrated();
        boolean effectiveMarket = activeMarket && concentrationAvailable && !highlyConcentrated;
        String positionDirectionCode = resolvePositionDirectionCode(accumulator.positionDirections);
        Date lastMetadataSyncAt = snapshot.lastMetadataSyncAt();
        Date lastConcentrationSnapshotAt = concentrationView == null ? null : concentrationView.snapshotAt();
        return SmartWalletMarketMonitorItemView.builder()
                .marketId(accumulator.marketId)
                .question(firstNonBlank(snapshot.question(), accumulator.question))
                .marketSlug(snapshot.marketSlug())
                .marketEndAt(displayMarketEndAt)
                .marketEndAtBjt(formatEpoch(displayMarketEndAt))
                .marketState("ACTIVE")
                .marketLifecycle(snapshot.marketLifecycle())
                .sourceCategory(firstNonBlank(snapshot.sourceCategory(), accumulator.sourceCategory))
                .normalizedCategory(firstNonBlank(snapshot.normalizedCategory(), accumulator.normalizedCategory, MarketStateSnapshot.CATEGORY_OTHER))
                .lastMetadataSyncAt(lastMetadataSyncAt)
                .metadataStale(isDateStale(lastMetadataSyncAt, properties.getMarketMonitoring().getMetadataRefresh().getMaxStaleMs(), true))
                .lastSignalAt(accumulator.lastSignalAt)
                .lastSignalAtBjt(formatDate(accumulator.lastSignalAt))
                .signalCount(accumulator.signalCount)
                .uniqueWalletCount(accumulator.uniqueWallets.size())
                .pendingTaskCount(accumulator.pendingTaskCount)
                .openPositionCount(accumulator.openPositionCount)
                .openCostUsdc(scaleMoney(accumulator.openCostUsdc))
                .positionDirection(resolvePositionDirectionText(accumulator.positionDirections))
                .positionDirectionCode(positionDirectionCode)
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
                .lastConcentrationSnapshotAt(lastConcentrationSnapshotAt)
                .concentrationStale(concentrationView != null && concentrationView.stale())
                .topHolderAddresses(concentrationAvailable ? concentration.topHolderAddresses() : List.of())
                .build();
    }

    private boolean shouldDisplayActiveMarket(MarketStateSnapshot snapshot,
                                              Long marketEndAt) {
        return snapshot != null && snapshot.isActive();
    }

    private boolean shouldTrackCandidateMarket(MarketAccumulator accumulator,
                                               MarketStateSnapshot snapshot) {
        if (accumulator == null) {
            return false;
        }
        if (snapshot != null) {
            return snapshot.isActive();
        }
        return isActiveLifecycle(accumulator.marketLifecycle);
    }

    private boolean shouldTrackByKnownFacts(MarketAccumulator accumulator) {
        if (accumulator == null) {
            return false;
        }
        return isActiveLifecycle(accumulator.marketLifecycle);
    }

    private boolean shouldTrackKnownMarket(SmartWalletMarketInfoEntity marketInfo) {
        if (marketInfo == null) {
            return true;
        }
        if (StringUtils.hasText(marketInfo.getResolvedOutcome())) {
            return false;
        }
        if (Boolean.TRUE.equals(marketInfo.getClosed())) {
            return false;
        }
        if (marketInfo.getAcceptingOrders() != null && !marketInfo.getAcceptingOrders()) {
            return false;
        }
        if (!isActiveLifecycle(marketInfo.getMarketLifecycle())) {
            return false;
        }
        return true;
    }

    private boolean isActiveLifecycle(String lifecycle) {
        return !StringUtils.hasText(lifecycle)
                || MarketStateSnapshot.LIFECYCLE_ACTIVE.equalsIgnoreCase(lifecycle);
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

    private boolean matchesKeyword(SmartWalletMarketMonitorItemView row, String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return true;
        }
        String normalized = keyword.trim().toLowerCase(Locale.ROOT);
        return contains(row.getQuestion(), normalized)
                || contains(row.getMarketId(), normalized)
                || contains(row.getPositionDirection(), normalized)
                || contains(row.getNormalizedCategory(), normalized)
                || contains(row.getSourceCategory(), normalized)
                || contains(row.getConcentrationBand(), normalized);
    }

    private boolean matchesCategory(SmartWalletMarketMonitorItemView row, String category) {
        if (!StringUtils.hasText(category)) {
            return true;
        }
        String normalized = category.trim();
        return normalized.equalsIgnoreCase(row.getNormalizedCategory())
                || normalized.equalsIgnoreCase(row.getSourceCategory());
    }

    private boolean matchesConcentrationBand(SmartWalletMarketMonitorItemView row, String concentrationBand) {
        if (!StringUtils.hasText(concentrationBand)) {
            return true;
        }
        return concentrationBand.trim().equalsIgnoreCase(row.getConcentrationBand());
    }

    private boolean matchesHasPosition(SmartWalletMarketMonitorItemView row, Boolean hasPosition) {
        if (hasPosition == null) {
            return true;
        }
        boolean rowHasPosition = safeInt(row.getOpenPositionCount()) > 0;
        return hasPosition == rowHasPosition;
    }

    private boolean matchesPositionDirection(SmartWalletMarketMonitorItemView row, String positionDirection) {
        if (!StringUtils.hasText(positionDirection)) {
            return true;
        }
        return positionDirection.trim().equalsIgnoreCase(row.getPositionDirectionCode());
    }

    private boolean contains(String text, String keyword) {
        return StringUtils.hasText(text) && text.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private String resolvePositionDirection(SmartWalletPositionEntity position, SmartWalletMarketInfoEntity marketInfo) {
        String outcome = marketInfo == null ? position.getOutcome() : marketInfo.getOutcome();
        if (!StringUtils.hasText(outcome)) {
            return null;
        }
        return outcome.trim().toUpperCase(Locale.ROOT);
    }

    private String resolvePositionDirectionText(Set<String> positionDirections) {
        if (positionDirections == null || positionDirections.isEmpty()) {
            return "-";
        }
        return String.join(" / ", positionDirections);
    }

    private String resolvePositionDirectionCode(Set<String> positionDirections) {
        if (positionDirections == null || positionDirections.isEmpty()) {
            return "NONE";
        }
        if (positionDirections.size() > 1) {
            return "MULTI";
        }
        return positionDirections.iterator().next();
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

    private String firstNonBlank(String left, String middle, String right) {
        return StringUtils.hasText(left)
                ? left
                : StringUtils.hasText(middle) ? middle : right;
    }

    private List<SmartWalletTradeEventEntity> loadTradeEvents(Date since, int fetchHint) {
        final int pageSize = 1000;
        List<SmartWalletTradeEventEntity> all = new ArrayList<>();
        Long lastId = null;
        
        while (true) {
            LambdaQueryWrapper<SmartWalletTradeEventEntity> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(SmartWalletTradeEventEntity::getSource, SOURCE)
                   .ge(SmartWalletTradeEventEntity::getOpenedAt, since);
            
            if (lastId != null) {
                wrapper.lt(SmartWalletTradeEventEntity::getId, lastId);
            }
            
            wrapper.orderByDesc(SmartWalletTradeEventEntity::getId)
                   .last("limit " + pageSize);
            
            List<SmartWalletTradeEventEntity> chunk = tradeEventMapper.selectList(wrapper);
            if (chunk == null || chunk.isEmpty()) {
                break;
            }
            
            all.addAll(chunk);
            lastId = chunk.get(chunk.size() - 1).getId();
            
            if (chunk.size() < pageSize || all.size() >= Math.max(fetchHint * 10, 2500)) {
                break;
            }
        }
        
        return all.stream()
                  .sorted(Comparator.comparing(SmartWalletTradeEventEntity::getOpenedAt).reversed())
                  .limit(Math.max(fetchHint * 10, 2500))
                  .collect(Collectors.toList());
    }
    
    private List<SmartWalletFollowTaskEntity> loadFollowTasks(Date since, int fetchHint) {
        final int pageSize = 1000;
        List<SmartWalletFollowTaskEntity> all = new ArrayList<>();
        Long lastId = null;
        
        while (true) {
            LambdaQueryWrapper<SmartWalletFollowTaskEntity> wrapper = new LambdaQueryWrapper<>();
            wrapper.in(SmartWalletFollowTaskEntity::getStatus, ACTIVE_TASK_STATUSES)
                   .ge(SmartWalletFollowTaskEntity::getCreatedAt, since);
            
            if (lastId != null) {
                wrapper.lt(SmartWalletFollowTaskEntity::getId, lastId);
            }
            
            wrapper.orderByDesc(SmartWalletFollowTaskEntity::getId)
                   .last("limit " + pageSize);
            
            List<SmartWalletFollowTaskEntity> chunk = followTaskMapper.selectList(wrapper);
            if (chunk == null || chunk.isEmpty()) {
                break;
            }
            
            all.addAll(chunk);
            lastId = chunk.get(chunk.size() - 1).getId();
            
            if (chunk.size() < pageSize || all.size() >= Math.max(fetchHint * 8, 1800)) {
                break;
            }
        }
        
        return all.stream()
                  .sorted(Comparator.comparing(SmartWalletFollowTaskEntity::getCreatedAt).reversed())
                  .limit(Math.max(fetchHint * 8, 1800))
                  .collect(Collectors.toList());
    }
    
    private List<SmartWalletPositionEntity> loadPositions(int fetchHint) {
        final int pageSize = 1000;
        List<SmartWalletPositionEntity> all = new ArrayList<>();
        Long lastId = null;
        
        while (true) {
            LambdaQueryWrapper<SmartWalletPositionEntity> wrapper = new LambdaQueryWrapper<>();
            wrapper.in(SmartWalletPositionEntity::getStatus, List.of("OPEN", "PARTIALLY_CLOSED"));
            
            if (lastId != null) {
                wrapper.lt(SmartWalletPositionEntity::getId, lastId);
            }
            
            wrapper.orderByDesc(SmartWalletPositionEntity::getId)
                   .last("limit " + pageSize);
            
            List<SmartWalletPositionEntity> chunk = positionMapper.selectList(wrapper);
            if (chunk == null || chunk.isEmpty()) {
                break;
            }
            
            all.addAll(chunk);
            lastId = chunk.get(chunk.size() - 1).getId();
            
            if (chunk.size() < pageSize || all.size() >= Math.max(fetchHint * 6, 1200)) {
                break;
            }
        }
        
        return all.stream()
                  .sorted(Comparator.comparing(SmartWalletPositionEntity::getUpdatedAt).reversed())
                  .limit(Math.max(fetchHint * 6, 1200))
                  .collect(Collectors.toList());
    }
    
    private boolean isDateStale(Date value, Long maxStaleMs, boolean treatMissingAsStale) {
        if (value == null) {
            return treatMissingAsStale;
        }
        if (maxStaleMs == null || maxStaleMs <= 0) {
            return false;
        }
        return System.currentTimeMillis() - value.getTime() > maxStaleMs;
    }

    // ─────────────────────────────── 替换 job 端 cross-package 依赖的内嵌 helper ───────────────────────────────
    //  - loadMarketStateSnapshots() 替代 job 端 SmartWalletMarketStateService.loadPersistedMarkets()
    //  - findLatestSnapshotAtOrBefore() 替代 job 端 MarketSnapshotCollectorService.findLatestSnapshotAtOrBefore()
    // 二者逻辑均与 job 端等价，仅去掉跨包依赖、改走 common-dao mapper / common-service marketInfoService。

    private Map<String, MarketStateSnapshot> loadMarketStateSnapshots(Collection<String> marketIds) {
        List<String> normalizedIds = normalizeMarketIdsCollection(marketIds);
        if (normalizedIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<SmartWalletMarketInfoEntity>> rowsByMarketId =
                marketInfoService.batchListByMarketIds(normalizedIds);
        Map<String, MarketStateSnapshot> result = new LinkedHashMap<>();
        for (String marketId : normalizedIds) {
            MarketStateSnapshot snapshot = snapshotFromRows(rowsByMarketId.get(marketId));
            if (snapshot != null) {
                result.put(marketId, snapshot);
            }
        }
        return result;
    }

    private SmartWalletMarketSnapshotEntity findLatestSnapshotAtOrBefore(String marketId, String outcome, Date targetTime) {
        if (!StringUtils.hasText(marketId) || targetTime == null) {
            return null;
        }
        LambdaQueryWrapper<SmartWalletMarketSnapshotEntity> query = new LambdaQueryWrapper<SmartWalletMarketSnapshotEntity>()
                .eq(SmartWalletMarketSnapshotEntity::getMarketId, marketId.trim())
                .eq(SmartWalletMarketSnapshotEntity::getOutcome, normalizeOutcomeForSnapshot(outcome))
                .le(SmartWalletMarketSnapshotEntity::getSnapshotAt, targetTime)
                .orderByDesc(SmartWalletMarketSnapshotEntity::getSnapshotAt)
                .last("limit 1");
        return marketSnapshotMapper.selectOne(query);
    }

    private MarketStateSnapshot snapshotFromRows(List<SmartWalletMarketInfoEntity> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        SmartWalletMarketInfoEntity primary = primaryRow(rows);
        String resolvedOutcome = normalizeValue(primary.getResolvedOutcome());
        boolean closed = Boolean.TRUE.equals(primary.getClosed());
        boolean acceptingOrders = primary.getAcceptingOrders() == null || primary.getAcceptingOrders();
        String lifecycle = resolvePersistedLifecycle(primary, resolvedOutcome, closed, acceptingOrders);
        Date lastSyncAt = rows.stream()
                .map(SmartWalletMarketInfoEntity::getLastMetadataSyncAt)
                .filter(Objects::nonNull)
                .max(Date::compareTo)
                .orElse(primary.getLastMetadataSyncAt());
        return MarketStateSnapshot.builder()
                .marketId(primary.getMarketId())
                .question(firstNonBlank(primary.getQuestion(), primary.getMarketId()))
                .marketSlug(primary.getMarketSlug())
                .marketEndAt(primary.getMarketEndAt())
                .sourceCategory(primary.getSourceCategory())
                .normalizedCategory(firstNonBlank(primary.getNormalizedCategory(), MarketStateSnapshot.CATEGORY_OTHER))
                .marketLifecycle(lifecycle)
                .closed(closed)
                .acceptingOrders(acceptingOrders)
                .resolvedOutcome(resolvedOutcome)
                .lastMetadataSyncAt(lastSyncAt)
                .build();
    }

    private SmartWalletMarketInfoEntity primaryRow(List<SmartWalletMarketInfoEntity> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        return rows.stream()
                .sorted(Comparator.comparing(SmartWalletMarketInfoEntity::getUpdatedAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(SmartWalletMarketInfoEntity::getId,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .findFirst()
                .orElse(rows.get(0));
    }

    private String resolvePersistedLifecycle(SmartWalletMarketInfoEntity primary,
                                             String resolvedOutcome,
                                             boolean closed,
                                             boolean acceptingOrders) {
        String persistedLifecycle = firstNonBlank(
                primary == null ? null : primary.getMarketLifecycle(),
                MarketStateSnapshot.LIFECYCLE_ACTIVE
        );
        if (primary == null) {
            return persistedLifecycle;
        }
        if (StringUtils.hasText(resolvedOutcome)
                || MarketStateSnapshot.LIFECYCLE_RESOLVED.equalsIgnoreCase(persistedLifecycle)) {
            return MarketStateSnapshot.LIFECYCLE_RESOLVED;
        }
        if (closed) {
            return MarketStateSnapshot.LIFECYCLE_CLOSED_UNRESOLVED;
        }
        if (!acceptingOrders) {
            return MarketStateSnapshot.LIFECYCLE_CLOSED_UNRESOLVED;
        }
        return persistedLifecycle;
    }

    private String normalizeValue(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private List<String> normalizeMarketIdsCollection(Collection<String> marketIds) {
        if (marketIds == null || marketIds.isEmpty()) {
            return List.of();
        }
        return marketIds.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private String normalizeOutcomeForSnapshot(String outcome) {
        if (!StringUtils.hasText(outcome)) {
            return "YES";
        }
        String normalized = outcome.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("NO")) {
            return "NO";
        }
        return "YES";
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
        private String sourceCategory;
        private String normalizedCategory;
        private String marketLifecycle;
        private Date lastSignalAt;
        private int signalCount;
        private int pendingTaskCount;
        private int openPositionCount;
        private BigDecimal openCostUsdc = BigDecimal.ZERO;
        private BigDecimal realizedPnlUsdc = BigDecimal.ZERO;
        private BigDecimal unrealizedPnlUsdc = BigDecimal.ZERO;
        private final Set<String> uniqueWallets = new LinkedHashSet<>();
        private final Set<String> positionDirections = new LinkedHashSet<>();

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

    private record MarketDataset(
            List<SmartWalletTradeEventEntity> recentEvents,
            List<SmartWalletFollowTaskEntity> recentTasks,
            Map<Long, SmartWalletMarketInfoEntity> marketInfoById,
            List<SmartWalletMarketMonitorItemView> rows
    ) {
    }

    private record MonitoringBaseData(
            List<SmartWalletTradeEventEntity> recentEvents,
            List<SmartWalletFollowTaskEntity> recentTasks,
            Map<Long, SmartWalletMarketInfoEntity> marketInfoById,
            List<MarketAccumulator> accumulators
    ) {
    }
}
