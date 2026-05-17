package com.metaverse.coin.strategy.web.service.smartwallet;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFillMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletCashLedgerMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletDailyReportMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletMarketInfoMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletProfileMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletWalletFeatureDailyMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletCashLedgerEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletDailyReportEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFillEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletWalletFeatureDailyEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.report.SmartWalletReportMath;
import com.metaverse.coin.strategy.common.service.smartwallet.report.SmartWalletReportDtos.AttributionRow;
import com.metaverse.coin.strategy.common.service.smartwallet.report.SmartWalletReportDtos.ConflictMarketRow;
import com.metaverse.coin.strategy.common.service.smartwallet.report.SmartWalletReportDtos.DailyReportRow;
import com.metaverse.coin.strategy.common.service.smartwallet.report.SmartWalletReportDtos.FollowAddressDailyReportRow;
import com.metaverse.coin.strategy.common.service.smartwallet.report.SmartWalletReportDtos.TimelinePoint;
import com.metaverse.coin.strategy.web.model.CursorPageView;
import com.metaverse.coin.strategy.web.model.smartwallet.ExecutionWalletStatusView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletFillView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletMarketLinkView;
import com.metaverse.coin.strategy.web.model.smartwallet.PolymarketProductOverviewView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletBlockerStatView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletCashLedgerView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletFollowTaskView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletOrderPnlCategoryAttributionView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletOrderView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletPositionView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletTradeOrderView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletTradeView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
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
public class SmartWalletReadService {

    private static final DateTimeFormatter BJT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withLocale(Locale.SIMPLIFIED_CHINESE)
            .withZone(ZoneId.of("Asia/Shanghai"));

    private static final List<String> OPEN_POSITION_STATUSES = List.of("OPEN", "PARTIALLY_CLOSED");
    private static final List<String> NEXT_SETTLEMENT_POSITION_STATUSES = List.of(
            "OPEN", "PARTIALLY_CLOSED", "SETTLEMENT_PENDING_ORACLE", "RESOLVED_UNCLAIMED"
    );
    private static final String PAPER_BOOK_EXECUTION_ADDRESS = "paper-book";

    private final SmartWalletProfileMapper profileMapper;
    private final SmartWalletTradeEventMapper tradeEventMapper;
    private final SmartWalletFollowTaskMapper followTaskMapper;
    private final SmartWalletFillMapper fillMapper;
    private final SmartWalletCashLedgerMapper cashLedgerMapper;
    private final SmartWalletDailyReportMapper dailyReportMapper;
    private final SmartWalletOrderMapper orderMapper;
    private final SmartWalletTradeOrderMapper tradeOrderMapper;
    private final SmartWalletMarketInfoMapper marketInfoMapper;
    private final SmartWalletPositionMapper positionMapper;
    private final SmartWalletWalletFeatureDailyMapper walletFeatureDailyMapper;

    @Value("${POLYMARKET_EXECUTION_WALLET_ADDRESS:}")
    private String executionWalletAddress;

    @Value("${POLYMARKET_EXECUTION_FUNDER_ADDRESS:}")
    private String executionWalletFunderAddress;

    public SmartWalletReadService(SmartWalletProfileMapper profileMapper,
                                  SmartWalletTradeEventMapper tradeEventMapper,
                                  SmartWalletFollowTaskMapper followTaskMapper,
                                  SmartWalletFillMapper fillMapper,
                                  SmartWalletCashLedgerMapper cashLedgerMapper,
                                  SmartWalletDailyReportMapper dailyReportMapper,
                                  SmartWalletOrderMapper orderMapper,
                                  SmartWalletTradeOrderMapper tradeOrderMapper,
                                  SmartWalletMarketInfoMapper marketInfoMapper,
                                  SmartWalletPositionMapper positionMapper,
                                  SmartWalletWalletFeatureDailyMapper walletFeatureDailyMapper) {
        this.profileMapper = profileMapper;
        this.tradeEventMapper = tradeEventMapper;
        this.followTaskMapper = followTaskMapper;
        this.fillMapper = fillMapper;
        this.cashLedgerMapper = cashLedgerMapper;
        this.dailyReportMapper = dailyReportMapper;
        this.orderMapper = orderMapper;
        this.tradeOrderMapper = tradeOrderMapper;
        this.marketInfoMapper = marketInfoMapper;
        this.positionMapper = positionMapper;
        this.walletFeatureDailyMapper = walletFeatureDailyMapper;
    }

    /**
     * 钱包特征日表最近 N 条（按 featureDate desc, id desc）。
     * <p>从 job 端 {@code WalletFeatureDailyService.listRecentFeatures} 等价复制：
     * 仅一次 mapper.selectList，无任何 in-process 缓存或外部依赖。</p>
     */
    public List<SmartWalletWalletFeatureDailyEntity> walletFeaturesDaily(Integer limit) {
        int safeLimit = limit == null ? 100 : Math.max(1, Math.min(limit, 500));
        return walletFeatureDailyMapper.selectList(new LambdaQueryWrapper<SmartWalletWalletFeatureDailyEntity>()
                .orderByDesc(SmartWalletWalletFeatureDailyEntity::getFeatureDate)
                .orderByDesc(SmartWalletWalletFeatureDailyEntity::getId)
                .last("limit " + safeLimit));
    }

    public Map<String, Object> health() {
        long profileCount = profileMapper.selectCount(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                .eq(SmartWalletProfileEntity::getSource, "POLYMARKET"));
        return Map.of(
                "service", "smart-wallet",
                "profiles", profileCount,
                "status", "UP"
        );
    }

    public PolymarketProductOverviewView overview() {
        Map<String, Long> taskStatusCounts = new LinkedHashMap<>();
        for (String status : List.of("PENDING", "FOLLOWED", "SKIPPED", "FAILED")) {
            long count = followTaskMapper.selectCount(new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                    .eq(SmartWalletFollowTaskEntity::getStatus, status));
            taskStatusCounts.put(status, count);
        }

        List<SmartWalletProfileEntity> allProfiles = profileMapper.selectList(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                .eq(SmartWalletProfileEntity::getSource, "POLYMARKET"));
        int candidateWatchlist = 0;
        int executableWatchlist = 0;
        int warmWatchlist = 0;
        int coldProfiles = 0;
        for (SmartWalletProfileEntity profile : allProfiles) {
            String tier = profile.getMonitorTier();
            if ("HOT".equalsIgnoreCase(tier)) {
                executableWatchlist++;
            } else if ("WARM".equalsIgnoreCase(tier)) {
                warmWatchlist++;
            } else if ("COLD".equalsIgnoreCase(tier)) {
                coldProfiles++;
            }
            if (Boolean.TRUE.equals(profile.getQualified())) {
                candidateWatchlist++;
            }
        }

        long tradeEvents = tradeEventMapper.selectCount(new LambdaQueryWrapper<>());
        List<SmartWalletPositionEntity> openPositions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .in(SmartWalletPositionEntity::getStatus, OPEN_POSITION_STATUSES));
        BigDecimal totalOpenCost = openPositions.stream()
                .map(SmartWalletPositionEntity::getCostBasisUsdc)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        boolean walletConfigured = StringUtils.hasText(executionWalletAddress);
        ExecutionWalletStatusView executionWallet = new ExecutionWalletStatusView(
                walletConfigured,
                walletConfigured,
                walletConfigured,
                "polygon",
                executionWalletAddress,
                executionWalletFunderAddress,
                null,
                null,
                null,
                List.of(walletConfigured
                        ? "Execution wallet address is configured in web environment."
                        : "Execution wallet details are not configured in web environment.")
        );

        return new PolymarketProductOverviewView(
                walletConfigured ? "LIVE" : "PAPER",
                true,
                true,
                walletConfigured,
                allProfiles.size(),
                candidateWatchlist,
                executableWatchlist,
                warmWatchlist,
                coldProfiles,
                executableWatchlist,
                tradeEvents,
                totalOpenCost,
                openPositions.size(),
                taskStatusCounts,
                executionWallet,
                List.of(
                        "This overview is served directly by coin-strategy-web using database reads.",
                        "Executable/opportunity follow-task lanes may still rely on job-side runtime risk evaluation."
                )
        );
    }

    public List<SmartWalletFollowTaskView> followTasks(Integer limit, boolean includeRiskEvaluation) {
        int safeLimit = normalizeLimit(limit, 50);
        List<SmartWalletFollowTaskEntity> tasks = followTaskMapper.selectList(new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                .orderByDesc(SmartWalletFollowTaskEntity::getCreatedAt)
                .last("limit " + safeLimit));
        populateMarketInfo(tasks);
        return tasks.stream()
                .map(task -> toView(task, includeRiskEvaluation))
                .toList();
    }

    public List<SmartWalletBlockerStatView> blockerStats(Integer taskLimit) {
        int safeLimit = normalizeLimit(taskLimit, 200);
        List<SmartWalletFollowTaskEntity> tasks = followTaskMapper.selectList(new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                .in(SmartWalletFollowTaskEntity::getStatus, List.of("PENDING", "SKIPPED"))
                .orderByDesc(SmartWalletFollowTaskEntity::getCreatedAt)
                .last("limit " + safeLimit));
        Map<String, MutableBlockerStat> grouped = new LinkedHashMap<>();
        for (SmartWalletFollowTaskEntity task : tasks) {
            List<String> categories = blockerCategories(task);
            if (categories.isEmpty()) {
                categories = List.of("PENDING".equalsIgnoreCase(task.getStatus()) ? "EXECUTABLE" : "OTHER");
            }
            for (String category : categories) {
                MutableBlockerStat stat = grouped.computeIfAbsent(category, key -> new MutableBlockerStat());
                stat.taskCount += 1;
                if ("PENDING".equalsIgnoreCase(task.getStatus())) {
                    stat.executableCount += 1;
                } else {
                    stat.blockedCount += 1;
                }
            }
        }
        return grouped.entrySet().stream()
                .map(entry -> new SmartWalletBlockerStatView(
                        entry.getKey(),
                        entry.getValue().taskCount,
                        entry.getValue().executableCount,
                        entry.getValue().blockedCount
                ))
                .sorted(Comparator.comparing(SmartWalletBlockerStatView::taskCount,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public List<SmartWalletPositionView> livePositions(String mode) {
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .in(SmartWalletPositionEntity::getStatus, OPEN_POSITION_STATUSES)
                .orderByDesc(SmartWalletPositionEntity::getLastFilledAt)
                .orderByDesc(SmartWalletPositionEntity::getId));
        populateMarketInfoForPositions(positions);
        return positions.stream()
                .filter(position -> matchesDashboardMode(position.getExecutionAddress(), mode))
                .map(this::toPositionView)
                .toList();
    }

    public List<SmartWalletPositionView> nextSettlements(Integer limit, String mode) {
        int safeLimit = normalizeLimit(limit, 10);
        long now = System.currentTimeMillis();
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .in(SmartWalletPositionEntity::getStatus, NEXT_SETTLEMENT_POSITION_STATUSES)
                .orderByDesc(SmartWalletPositionEntity::getLastFilledAt)
                .orderByDesc(SmartWalletPositionEntity::getId));
        populateMarketInfoForPositions(positions);
        return positions.stream()
                .filter(position -> matchesDashboardMode(position.getExecutionAddress(), mode))
                .filter(position -> isSettlementCandidate(position, now))
                .sorted((left, right) -> {
                    boolean leftResolved = "RESOLVED_UNCLAIMED".equalsIgnoreCase(left.getStatus());
                    boolean rightResolved = "RESOLVED_UNCLAIMED".equalsIgnoreCase(right.getStatus());
                    if (leftResolved != rightResolved) {
                        return leftResolved ? -1 : 1;
                    }
                    Long leftEnd = left.getMarketEndAt();
                    Long rightEnd = right.getMarketEndAt();
                    if (leftEnd == null && rightEnd == null) {
                        return 0;
                    }
                    if (leftEnd == null) {
                        return 1;
                    }
                    if (rightEnd == null) {
                        return -1;
                    }
                    return Long.compare(leftEnd, rightEnd);
                })
                .limit(safeLimit)
                .map(this::toPositionView)
                .toList();
    }

    public List<SmartWalletFillView> fills(Integer limit, String mode) {
        int safeLimit = normalizeLimit(limit, 100);
        List<SmartWalletFillEntity> fills = fillMapper.selectList(new LambdaQueryWrapper<SmartWalletFillEntity>()
                .orderByDesc(SmartWalletFillEntity::getFilledAt)
                .last("limit " + safeLimit));
        populateMarketInfoForFills(fills);
        return fills.stream()
                .filter(fill -> matchesDashboardMode(fill.getExecutionAddress(), mode))
                .map(this::toFillView)
                .toList();
    }

    public List<SmartWalletOrderView> orders(Integer limit, String mode) {
        return ordersCursor(limit, null, mode, null, null, null, null, null).items();
    }

    public CursorPageView<SmartWalletOrderView> ordersCursor(Integer limit,
                                                             Long lastId,
                                                             String mode,
                                                             String pnl,
                                                             String settlement,
                                                             String side,
                                                             String status,
                                                             String q) {
        int safeLimit = Math.max(limit == null ? 50 : limit, 1);
        int fetchSize = safeLimit + 1;
        LambdaQueryWrapper<SmartWalletOrderEntity> wrapper = new LambdaQueryWrapper<SmartWalletOrderEntity>()
                .orderByDesc(SmartWalletOrderEntity::getPlacedAt)
                .orderByDesc(SmartWalletOrderEntity::getId)
                .last("limit " + fetchSize);
        applyOrderModeFilter(wrapper, mode);
        applyOrderCursorFilters(wrapper, settlement, side, status, q);
        if (lastId != null) {
            wrapper.lt(SmartWalletOrderEntity::getId, lastId);
        }
        List<SmartWalletOrderEntity> orders = orderMapper.selectList(wrapper);
        populateMarketInfoForOrders(orders);
        Map<Long, SmartWalletFollowTaskEntity> followTasksById = loadFollowTasks(orders);
        Map<Long, SmartWalletTradeEventEntity> sourceEventsById = loadSourceEvents(followTasksById.values());
        List<SmartWalletOrderView> filtered = orders.stream()
                .map(order -> toOrderView(order, followTasksById, sourceEventsById))
                .filter(view -> matchesOrderPnl(view, pnl))
                .limit(fetchSize)
                .toList();
        boolean hasMore = filtered.size() > safeLimit;
        List<SmartWalletOrderView> items = hasMore ? filtered.subList(0, safeLimit) : filtered;
        String nextCursor = hasMore && !items.isEmpty() ? String.valueOf(items.get(items.size() - 1).id()) : null;
        return CursorPageView.of(items, nextCursor);
    }

    public CursorPageView<SmartWalletTradeOrderView> tradeOrdersCursor(Integer limit,
                                                                       Long lastId,
                                                                       String mode,
                                                                       String pnl,
                                                                       String settlement,
                                                                       String status,
                                                                       String q) {
        int safeLimit = Math.max(limit == null ? 50 : limit, 1);
        int fetchSize = safeLimit + 1;
        LambdaQueryWrapper<SmartWalletTradeOrderEntity> wrapper = new LambdaQueryWrapper<SmartWalletTradeOrderEntity>()
                .lt(lastId != null && lastId > 0, SmartWalletTradeOrderEntity::getId, lastId)
                .eq(StringUtils.hasText(status), SmartWalletTradeOrderEntity::getStatus, normalize(status))
                .orderByDesc(SmartWalletTradeOrderEntity::getId)
                .last("limit " + (fetchSize * 4));
        applyTradeOrderModeFilter(wrapper, mode);
        List<SmartWalletTradeOrderEntity> entities = tradeOrderMapper.selectList(wrapper);
        populateMarketInfoForTradeOrders(entities);
        Date now = new Date();
        Set<Long> positionIds = entities.stream()
                .filter(entity -> !isFutureCompletedTradeOrder(entity, now))
                .map(SmartWalletTradeOrderEntity::getPositionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, List<SmartWalletOrderEntity>> ordersByPosition = batchLoadOrders(positionIds);
        Map<Long, List<SmartWalletFillEntity>> fillsByPosition = batchLoadFills(positionIds);
        List<SmartWalletTradeOrderView> filtered = entities.stream()
                .filter(entity -> !isFutureCompletedTradeOrder(entity, now))
                .map(entity -> toTradeOrderView(entity,
                        ordersByPosition.getOrDefault(entity.getPositionId(), List.of()),
                        fillsByPosition.getOrDefault(entity.getPositionId(), List.of())))
                .filter(view -> matchesTradeOrderPnl(view, pnl))
                .filter(view -> matchesTradeOrderSettlement(view, settlement))
                .filter(view -> matchesTradeOrderQuery(view, q))
                .limit(fetchSize)
                .toList();
        boolean hasMore = filtered.size() > safeLimit;
        List<SmartWalletTradeOrderView> items = hasMore ? filtered.subList(0, safeLimit) : filtered;
        String nextCursor = hasMore && !items.isEmpty() ? String.valueOf(items.get(items.size() - 1).id()) : null;
        return CursorPageView.of(items, nextCursor);
    }

    public CursorPageView<SmartWalletTradeView> tradesCursor(Integer limit,
                                                             Long lastId,
                                                             String mode,
                                                             String pnl,
                                                             String settlement,
                                                             String status,
                                                             String q) {
        int safeLimit = Math.max(limit == null ? 50 : limit, 1);
        int fetchSize = safeLimit + 1;
        LambdaQueryWrapper<SmartWalletPositionEntity> wrapper = new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .orderByDesc(SmartWalletPositionEntity::getOpenedAt)
                .orderByDesc(SmartWalletPositionEntity::getId)
                .last("limit " + fetchSize);
        applyPositionModeFilter(wrapper, mode);
        if (StringUtils.hasText(status)) {
            wrapper.eq(SmartWalletPositionEntity::getStatus, normalize(status));
        } else {
            applyTradeSettlementSqlFilter(wrapper, settlement);
        }
        if (lastId != null && lastId > 0) {
            wrapper.lt(SmartWalletPositionEntity::getId, lastId);
        }
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(wrapper);
        populateMarketInfoForPositions(positions);
        Set<Long> positionIds = positions.stream()
                .map(SmartWalletPositionEntity::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, List<SmartWalletOrderEntity>> ordersByPosition = batchLoadOrders(positionIds);
        List<SmartWalletTradeView> filtered = positions.stream()
                .map(position -> toTradeView(position, ordersByPosition.getOrDefault(position.getId(), List.of())))
                .filter(view -> matchesTradeFilters(view, pnl, settlement, q))
                .limit(fetchSize)
                .toList();
        boolean hasMore = filtered.size() > safeLimit;
        List<SmartWalletTradeView> items = hasMore ? filtered.subList(0, safeLimit) : filtered;
        String nextCursor = hasMore && !items.isEmpty() ? String.valueOf(items.get(items.size() - 1).tradeId()) : null;
        return CursorPageView.of(items, nextCursor);
    }

    public SmartWalletMarketLinkView marketLink(String conditionId, String question, String outcome) {
        if (!StringUtils.hasText(conditionId)) {
            return new SmartWalletMarketLinkView(
                    null, question, outcome, null, null, null, null,
                    "INTERNAL_FALLBACK", "EMPTY_CONDITION_ID", null, null,
                    null, null, null, null, null, null, List.of(), Map.of()
            );
        }
        List<SmartWalletMarketInfoEntity> rows = marketInfoMapper.selectList(new LambdaQueryWrapper<SmartWalletMarketInfoEntity>()
                .eq(SmartWalletMarketInfoEntity::getMarketId, conditionId.trim()));
        if (rows == null || rows.isEmpty()) {
            String internal = buildInternalMarketUrl(conditionId);
            return new SmartWalletMarketLinkView(
                    conditionId.trim(),
                    question,
                    outcome,
                    null,
                    internal,
                    null,
                    internal,
                    "INTERNAL_FALLBACK",
                    "DB_MISS",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    Map.of()
            );
        }
        SmartWalletMarketInfoEntity selected = selectMarketInfo(rows, outcome);
        String external = buildExternalMarketUrl(selected.getMarketSlug());
        String internal = buildInternalMarketUrl(conditionId);
        Map<String, String> outcomeTokenIds = rows.stream()
                .filter(item -> StringUtils.hasText(item.getOutcome()))
                .collect(Collectors.toMap(
                        SmartWalletMarketInfoEntity::getOutcome,
                        item -> item.getTokenId() == null ? "" : item.getTokenId(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        List<String> outcomes = rows.stream()
                .map(SmartWalletMarketInfoEntity::getOutcome)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        String marketLifecycle = selected.getMarketLifecycle();
        return new SmartWalletMarketLinkView(
                conditionId.trim(),
                firstText(selected.getQuestion(), question),
                firstText(outcome, selected.getOutcome()),
                selected.getMarketSlug(),
                StringUtils.hasText(external) ? external : internal,
                external,
                internal,
                StringUtils.hasText(external) ? "EXTERNAL_READY" : "INTERNAL_FALLBACK",
                "DB",
                firstText(selected.getNormalizedCategory(), selected.getSourceCategory()),
                selected.getMarketEndAt(),
                "ACTIVE".equalsIgnoreCase(marketLifecycle),
                selected.getClosed(),
                selected.getAcceptingOrders(),
                marketLifecycle,
                selected.getResolvedOutcome(),
                null,
                outcomes,
                outcomeTokenIds
        );
    }

    public SmartWalletOrderPnlCategoryAttributionView orderPnlCategoryAttribution(Integer limit, String mode) {
        int safeLimit = Math.max(1, Math.min(limit == null ? 12 : limit, 100));
        List<SmartWalletTradeOrderEntity> tradeOrders = tradeOrderMapper.selectList(new LambdaQueryWrapper<SmartWalletTradeOrderEntity>()
                .orderByDesc(SmartWalletTradeOrderEntity::getOpenedAt)
                .orderByDesc(SmartWalletTradeOrderEntity::getId));
        populateMarketInfoForTradeOrders(tradeOrders);
        List<SmartWalletTradeOrderEntity> filtered = tradeOrders.stream()
                .filter(order -> matchesDashboardMode(order.getExecutionAddress(), mode))
                .toList();
        List<SmartWalletOrderPnlCategoryAttributionView.WindowBucket> windows = List.of(
                buildAttributionWindow("30d", "最近 30 天", 30, filtered, safeLimit),
                buildAttributionWindow("7d", "最近 7 天", 7, filtered, safeLimit),
                buildAttributionWindow("all", "全部样本", Integer.MAX_VALUE, filtered, safeLimit)
        );
        return new SmartWalletOrderPnlCategoryAttributionView(windows);
    }

    public List<AttributionRow> walletReports(Integer limit, String mode) {
        int safeLimit = Math.max(1, Math.min(limit == null ? 20 : limit, 200));
        List<SmartWalletOrderEntity> orders = orderMapper.selectList(new LambdaQueryWrapper<>());
        List<SmartWalletCashLedgerEntity> ledgerEntries = cashLedgerMapper.selectList(new LambdaQueryWrapper<>());
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<>());
        populateMarketInfoForOrders(orders);
        populateMarketInfoForPositions(positions);
        Date now = new Date();
        Map<String, AttributionAccumulator> grouped = new LinkedHashMap<>();
        for (SmartWalletOrderEntity order : orders) {
            if (!matchesDashboardMode(order.getExecutionAddress(), mode)) {
                continue;
            }
            String key = order.getExecutionAddress();
            AttributionAccumulator acc = grouped.computeIfAbsent(key, ignored -> new AttributionAccumulator(key, key));
            acc.addOrder(order);
        }
        for (SmartWalletCashLedgerEntity entry : ledgerEntries) {
            if (isFutureLedgerEntry(entry, now)) {
                continue;
            }
            if (!matchesDashboardMode(entry.getExecutionAddress(), mode)) {
                continue;
            }
            String key = entry.getExecutionAddress();
            AttributionAccumulator acc = grouped.computeIfAbsent(key, ignored -> new AttributionAccumulator(key, key));
            acc.addLedger(entry);
        }
        for (SmartWalletPositionEntity position : positions) {
            if (isFuturePositionOutcome(position, now)) {
                continue;
            }
            if (!matchesDashboardMode(position.getExecutionAddress(), mode)) {
                continue;
            }
            String key = position.getExecutionAddress();
            AttributionAccumulator acc = grouped.computeIfAbsent(key, ignored -> new AttributionAccumulator(key, key));
            acc.addExposure(position, pendingSettlementNetPnlForPosition(position));
        }
        return grouped.values().stream()
                .sorted(Comparator.comparing(AttributionAccumulator::netPnl).reversed())
                .limit(safeLimit)
                .map(AttributionAccumulator::toView)
                .toList();
    }

    public List<AttributionRow> marketReports(Integer limit, String mode) {
        int safeLimit = Math.max(1, Math.min(limit == null ? 20 : limit, 200));
        List<SmartWalletOrderEntity> orders = orderMapper.selectList(new LambdaQueryWrapper<>());
        List<SmartWalletCashLedgerEntity> ledgerEntries = cashLedgerMapper.selectList(new LambdaQueryWrapper<>());
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<>());
        populateMarketInfoForOrders(orders);
        populateMarketInfoForPositions(positions);
        Date now = new Date();
        Map<String, AttributionAccumulator> grouped = new LinkedHashMap<>();
        for (SmartWalletOrderEntity order : orders) {
            if (!matchesDashboardMode(order.getExecutionAddress(), mode)) {
                continue;
            }
            String key = order.getMarketId();
            AttributionAccumulator acc = grouped.computeIfAbsent(key,
                    ignored -> new AttributionAccumulator(key, firstText(order.getQuestion(), key)));
            acc.addOrder(order);
        }
        for (SmartWalletCashLedgerEntity entry : ledgerEntries) {
            if (isFutureLedgerEntry(entry, now)) {
                continue;
            }
            if (!matchesDashboardMode(entry.getExecutionAddress(), mode)) {
                continue;
            }
            String key = entry.getMarketId();
            AttributionAccumulator acc = grouped.computeIfAbsent(key,
                    ignored -> new AttributionAccumulator(key, firstText(entry.getQuestion(), key)));
            acc.addLedger(entry);
        }
        for (SmartWalletPositionEntity position : positions) {
            if (isFuturePositionOutcome(position, now)) {
                continue;
            }
            if (!matchesDashboardMode(position.getExecutionAddress(), mode)) {
                continue;
            }
            String key = position.getMarketId();
            AttributionAccumulator acc = grouped.computeIfAbsent(key,
                    ignored -> new AttributionAccumulator(key, firstText(position.getQuestion(), key)));
            acc.addExposure(position, pendingSettlementNetPnlForPosition(position));
        }
        return grouped.values().stream()
                .sorted(Comparator.comparing(AttributionAccumulator::netPnl).reversed())
                .limit(safeLimit)
                .map(AttributionAccumulator::toView)
                .toList();
    }

    public List<DailyReportRow> dailyReports(Integer limit, String mode) {
        int safeLimit = Math.max(1, Math.min(limit == null ? 30 : limit, 180));
        List<SmartWalletDailyReportEntity> reports = dailyReportMapper.selectList(new LambdaQueryWrapper<SmartWalletDailyReportEntity>()
                .orderByDesc(SmartWalletDailyReportEntity::getReportDate));
        if (reports == null) {
            reports = List.of();
        }
        List<SmartWalletDailyReportEntity> filtered = reports.stream()
                .filter(report -> matchesDashboardMode(report.getExecutionAddress(), mode))
                .filter(report -> !isFutureReportDate(report.getReportDate()))
                .limit(safeLimit)
                .toList();
        Map<LocalDate, DailyOutcomeBreakdown> outcomeBreakdowns = loadDailyOutcomeBreakdowns(filtered, mode);
        return filtered.stream()
                .map(report -> toDailyReportView(report, outcomeBreakdowns.get(toLocalDate(report.getReportDate()))))
                .toList();
    }

    public List<TimelinePoint> pnlTimeline(Integer days, Integer bucketHours, String mode) {
        int safeDays = Math.max(1, Math.min(days == null ? 30 : days, 180));
        int safeBucketHours = normalizeTimelineBucketHours(bucketHours);
        // Polymarket 总览页面（bucketHours >= 24）按 trade_order 结算时间逐日聚合，
        // 完全绕过 cash_ledger，避免与"总收益"口径不一致。
        if (safeBucketHours >= 24) {
            return dailyReportTimeline(safeDays, mode);
        }
        // Sub-day buckets 仍走 cash_ledger 路径（细颗粒度场景仅供调试，未在总览页面使用）。
        ZoneId zoneId = ZoneId.systemDefault();
        LocalDateTime now = LocalDateTime.now(zoneId);
        LocalDateTime requestedStartBucket = floorToTimelineBucket(now.minusDays(safeDays), safeBucketHours);
        Date startDate = Date.from(requestedStartBucket.atZone(zoneId).toInstant());
        List<SmartWalletCashLedgerEntity> entries = cashLedgerMapper.selectList(new LambdaQueryWrapper<SmartWalletCashLedgerEntity>()
                .in(SmartWalletCashLedgerEntity::getEntryType, List.of("REALIZED_PNL", "FEE", "NETWORK_FEE"))
                .ge(SmartWalletCashLedgerEntity::getOccurredAt, startDate)
                .le(SmartWalletCashLedgerEntity::getOccurredAt, Date.from(now.atZone(zoneId).toInstant()))
                .orderByAsc(SmartWalletCashLedgerEntity::getOccurredAt)
                .orderByAsc(SmartWalletCashLedgerEntity::getId));
        if (entries == null || entries.isEmpty()) {
            return dailyReportTimeline(safeDays, mode);
        }
        List<TimelineLedgerEntry> timelineEntries = new ArrayList<>();
        LocalDateTime firstDataBucket = null;
        for (SmartWalletCashLedgerEntity entry : entries) {
            if (entry == null || entry.getOccurredAt() == null || !matchesDashboardMode(entry.getExecutionAddress(), mode)) {
                continue;
            }
            BigDecimal delta = ledgerPnlDelta(entry);
            // FEE / NETWORK_FEE 在 ledgerPnlDelta 中返回 0，但累加器仍会把它累入手续费总额，
            // 因此 delta==0 时只有 entry_type 既非 REALIZED_PNL 又非 FEE/NETWORK_FEE 时才跳过，
            // 避免累积净收益曲线漏算手续费导致与"总收益"对不上。
            boolean isFeeEntry = entry.getEntryType() != null
                    && ("FEE".equalsIgnoreCase(entry.getEntryType())
                    || "NETWORK_FEE".equalsIgnoreCase(entry.getEntryType()));
            if (delta.compareTo(BigDecimal.ZERO) == 0 && !isFeeEntry) {
                continue;
            }
            LocalDateTime bucketAt = floorToTimelineBucket(LocalDateTime.ofInstant(entry.getOccurredAt().toInstant(), zoneId), safeBucketHours);
            if (firstDataBucket == null) {
                firstDataBucket = bucketAt;
            }
            timelineEntries.add(new TimelineLedgerEntry(entry, bucketAt, delta));
        }
        if (timelineEntries.isEmpty() || firstDataBucket == null) {
            return dailyReportTimeline(safeDays, mode);
        }
        Map<LocalDateTime, TimelineBucketAccumulator> buckets = new LinkedHashMap<>();
        for (LocalDateTime cursor = firstDataBucket; !cursor.isAfter(now); cursor = cursor.plusHours(safeBucketHours)) {
            buckets.put(cursor, new TimelineBucketAccumulator());
        }
        for (TimelineLedgerEntry entry : timelineEntries) {
            TimelineBucketAccumulator bucket = buckets.get(entry.bucketAt());
            if (bucket != null) {
                bucket.add(entry.entity(), entry.delta());
            }
        }
        List<TimelinePoint> points = new ArrayList<>();
        TimelineBaseline baseline = timelineBaselineBefore(startDate, mode);
        BigDecimal cumulativeLoss = baseline.cumulativeLossPnlUsdc;
        BigDecimal cumulativeRealized = baseline.cumulativeRealizedPnlUsdc;
        BigDecimal cumulativeFee = baseline.cumulativeFeeUsdc;
        BigDecimal cumulativeNet = baseline.cumulativeNetPnlUsdc;
        for (Map.Entry<LocalDateTime, TimelineBucketAccumulator> entry : buckets.entrySet()) {
            TimelineBucketAccumulator bucket = entry.getValue();
            cumulativeLoss = cumulativeLoss.add(bucket.lossPnl());
            cumulativeRealized = cumulativeRealized.add(bucket.realizedPnl);
            cumulativeFee = cumulativeFee.add(bucket.fee);
            cumulativeNet = cumulativeNet.add(bucket.netPnl());
            points.add(new TimelinePoint(
                    Date.from(entry.getKey().atZone(zoneId).toInstant()),
                    bucket.profitPnl(),
                    bucket.lossPnl(),
                    bucket.realizedPnl,
                    bucket.fee,
                    bucket.netPnl(),
                    cumulativeLoss,
                    cumulativeRealized,
                    cumulativeFee,
                    cumulativeNet
            ));
        }
        return points;
    }

    public List<ConflictMarketRow> conflictingMarkets(Integer limit, boolean openOnly, String mode) {
        int safeLimit = Math.max(1, Math.min(limit == null ? 20 : limit, 200));
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .orderByDesc(SmartWalletPositionEntity::getLastFilledAt));
        populateMarketInfoForPositions(positions);
        Map<String, ConflictAccumulator> grouped = new LinkedHashMap<>();
        for (SmartWalletPositionEntity position : positions) {
            if (!matchesDashboardMode(position.getExecutionAddress(), mode)) {
                continue;
            }
            if (openOnly && !isActivePositionStatus(position.getStatus())) {
                continue;
            }
            if (!StringUtils.hasText(position.getMarketId())) {
                continue;
            }
            grouped.computeIfAbsent(position.getMarketId(),
                            key -> new ConflictAccumulator(position.getMarketId(), position.getQuestion()))
                    .add(position, realizedPnlForPosition(position));
        }
        return grouped.values().stream()
                .filter(item -> item.outcomes.size() >= 2)
                .map(ConflictAccumulator::toView)
                .sorted(Comparator.comparing(ConflictMarketRow::netPnlUsdc).reversed())
                .limit(safeLimit)
                .toList();
    }

    public List<FollowAddressDailyReportRow> followAddressDailyReports(LocalDate reportDate, Integer limit, String mode) {
        LocalDate targetDate = reportDate == null ? LocalDate.now(ZoneId.systemDefault()) : reportDate;
        Date start = Date.from(targetDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
        Date end = Date.from(targetDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant());
        boolean currentDay = targetDate.equals(LocalDate.now(ZoneId.systemDefault()));

        List<SmartWalletOrderEntity> allOrders = orderMapper.selectList(new LambdaQueryWrapper<SmartWalletOrderEntity>()
                .orderByDesc(SmartWalletOrderEntity::getPlacedAt)
                .orderByDesc(SmartWalletOrderEntity::getId));
        List<SmartWalletFillEntity> allFills = fillMapper.selectList(new LambdaQueryWrapper<SmartWalletFillEntity>()
                .orderByDesc(SmartWalletFillEntity::getFilledAt)
                .orderByDesc(SmartWalletFillEntity::getId));
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .orderByDesc(SmartWalletPositionEntity::getLastFilledAt)
                .orderByDesc(SmartWalletPositionEntity::getId));
        populateMarketInfoForOrders(allOrders);
        populateMarketInfoForFills(allFills);
        populateMarketInfoForPositions(positions);

        Map<String, String> positionWalletMap = buildPositionWalletMap(allOrders, allFills);
        Map<String, FollowAddressDailyAccumulator> grouped = new LinkedHashMap<>();

        for (SmartWalletOrderEntity order : allOrders) {
            if (!matchesDashboardMode(order.getExecutionAddress(), mode) || !isWithin(order.getPlacedAt(), start, end)) {
                continue;
            }
            FollowAddressDailyAccumulator acc = grouped.computeIfAbsent(normalizeWalletAddress(order.getWalletAddress()),
                    FollowAddressDailyAccumulator::new);
            acc.addOrder(order);
        }

        for (SmartWalletFillEntity fill : allFills) {
            if (!matchesDashboardMode(fill.getExecutionAddress(), mode) || !isWithin(fill.getFilledAt(), start, end)) {
                continue;
            }
            FollowAddressDailyAccumulator acc = grouped.computeIfAbsent(normalizeWalletAddress(fill.getWalletAddress()),
                    FollowAddressDailyAccumulator::new);
            acc.addFill(fill);
        }

        for (SmartWalletPositionEntity position : positions) {
            if (!matchesDashboardMode(position.getExecutionAddress(), mode)) {
                continue;
            }
            String walletAddress = resolvePositionWalletAddress(position, positionWalletMap);
            FollowAddressDailyAccumulator acc = grouped.computeIfAbsent(walletAddress, FollowAddressDailyAccumulator::new);
            if (position.getClosedAt() != null && targetDate.equals(toLocalDate(position.getClosedAt()))) {
                acc.addClosedPosition(position);
            }
            if (currentDay && isActivePositionStatus(position.getStatus())) {
                markToMarket(position);
                acc.addOpenPosition(position);
            }
        }

        return grouped.values().stream()
                .sorted(Comparator.comparing(FollowAddressDailyAccumulator::netPnl).reversed())
                .limit(Math.max(normalizeLimit(limit, 100), 1))
                .map(acc -> acc.toView(java.sql.Date.valueOf(targetDate)))
                .toList();
    }

    private void populateMarketInfo(List<SmartWalletFollowTaskEntity> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        List<Long> marketInfoIds = tasks.stream()
                .map(SmartWalletFollowTaskEntity::getMarketInfoId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (marketInfoIds.isEmpty()) {
            return;
        }
        Map<Long, SmartWalletMarketInfoEntity> marketInfoById = marketInfoMapper.selectBatchIds(marketInfoIds).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(SmartWalletMarketInfoEntity::getId, item -> item));
        for (SmartWalletFollowTaskEntity task : tasks) {
            if (task.getMarketInfoId() != null) {
                task.setMarketInfo(marketInfoById.get(task.getMarketInfoId()));
            }
        }
    }

    private void populateMarketInfoForPositions(List<SmartWalletPositionEntity> positions) {
        if (positions == null || positions.isEmpty()) {
            return;
        }
        List<Long> marketInfoIds = positions.stream()
                .map(SmartWalletPositionEntity::getMarketInfoId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (marketInfoIds.isEmpty()) {
            return;
        }
        Map<Long, SmartWalletMarketInfoEntity> marketInfoById = marketInfoMapper.selectBatchIds(marketInfoIds).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(SmartWalletMarketInfoEntity::getId, item -> item));
        for (SmartWalletPositionEntity position : positions) {
            if (position.getMarketInfoId() != null) {
                position.setMarketInfo(marketInfoById.get(position.getMarketInfoId()));
            }
        }
    }

    private void populateMarketInfoForOrders(List<SmartWalletOrderEntity> orders) {
        if (orders == null || orders.isEmpty()) {
            return;
        }
        List<Long> marketInfoIds = orders.stream()
                .map(SmartWalletOrderEntity::getMarketInfoId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (marketInfoIds.isEmpty()) {
            return;
        }
        Map<Long, SmartWalletMarketInfoEntity> marketInfoById = marketInfoMapper.selectBatchIds(marketInfoIds).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(SmartWalletMarketInfoEntity::getId, item -> item));
        for (SmartWalletOrderEntity order : orders) {
            if (order.getMarketInfoId() != null) {
                order.setMarketInfo(marketInfoById.get(order.getMarketInfoId()));
            }
        }
    }

    private void populateMarketInfoForTradeOrders(List<SmartWalletTradeOrderEntity> tradeOrders) {
        if (tradeOrders == null || tradeOrders.isEmpty()) {
            return;
        }
        List<Long> marketInfoIds = tradeOrders.stream()
                .map(SmartWalletTradeOrderEntity::getMarketInfoId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (marketInfoIds.isEmpty()) {
            return;
        }
        Map<Long, SmartWalletMarketInfoEntity> marketInfoById = marketInfoMapper.selectBatchIds(marketInfoIds).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(SmartWalletMarketInfoEntity::getId, item -> item));
        for (SmartWalletTradeOrderEntity entity : tradeOrders) {
            if (entity.getMarketInfoId() != null) {
                entity.setMarketInfo(marketInfoById.get(entity.getMarketInfoId()));
            }
        }
    }

    private void populateMarketInfoForFills(List<SmartWalletFillEntity> fills) {
        if (fills == null || fills.isEmpty()) {
            return;
        }
        List<Long> marketInfoIds = fills.stream()
                .map(SmartWalletFillEntity::getMarketInfoId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (marketInfoIds.isEmpty()) {
            return;
        }
        Map<Long, SmartWalletMarketInfoEntity> marketInfoById = marketInfoMapper.selectBatchIds(marketInfoIds).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(SmartWalletMarketInfoEntity::getId, item -> item));
        for (SmartWalletFillEntity fill : fills) {
            if (fill.getMarketInfoId() != null) {
                fill.setMarketInfo(marketInfoById.get(fill.getMarketInfoId()));
            }
        }
    }

    private SmartWalletFollowTaskView toView(SmartWalletFollowTaskEntity task, boolean includeRiskEvaluation) {
        List<String> skipReasonTags = parseCsv(task.getSkipReasonTags());
        List<String> blockerCategories = blockerCategories(task);
        List<String> riskReasons = includeRiskEvaluation
                ? splitReasons(task.getErrorMessage())
                : null;
        Integer blockerCount = riskReasons == null ? null : riskReasons.size();
        String status = task.getStatus();
        return new SmartWalletFollowTaskView(
                task.getId(),
                task.getWalletAddress(),
                task.getMarketId(),
                task.getQuestion(),
                task.getOutcome(),
                task.getMarketEndAt(),
                formatEpochMillis(task.getMarketEndAt()),
                task.getSide(),
                task.getTriggerPrice(),
                task.getTriggerSizeUsdc(),
                task.getRecommendedStakeUsdc(),
                null,
                null,
                null,
                status,
                task.getExecutionMode(),
                task.getExternalOrderId(),
                task.getErrorMessage(),
                task.getPrimarySkipReason(),
                skipReasonTags,
                task.getWouldHaveSkippedReason(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "PENDING".equalsIgnoreCase(status),
                null,
                null,
                null,
                null,
                null,
                blockerCount,
                blockerCategories.isEmpty() ? null : blockerCategories.get(0),
                blockerCategories.isEmpty() ? null : blockerCategories,
                riskReasons,
                task.getScheduledAt(),
                task.getExecutedAt()
        );
    }

    private SmartWalletPositionView toPositionView(SmartWalletPositionEntity position) {
        BigDecimal claimableProceedsUsdc = claimableProceeds(position);
        BigDecimal pendingSettlementNetPnlUsdc = claimableProceedsUsdc == null
                ? safe(position.getUnrealizedPnlUsdc())
                : claimableProceedsUsdc.subtract(safe(position.getCostBasisUsdc()));
        return new SmartWalletPositionView(
                position.getId(),
                position.getExecutionAddress(),
                position.getMarketId(),
                position.getTokenId(),
                position.getQuestion(),
                position.getOutcome(),
                position.getMarketEndAt(),
                formatEpochMillis(position.getMarketEndAt()),
                position.getStatus(),
                position.getQuantity(),
                position.getAverageEntryPrice(),
                position.getCostBasisUsdc(),
                position.getOriginalCostBasisUsdc(),
                position.getFeePolTotal(),
                position.getRealizedPnlUsdc(),
                position.getUnrealizedPnlUsdc(),
                pendingSettlementNetPnlUsdc,
                claimableProceedsUsdc,
                position.getLastMarkPrice(),
                position.getNetSide(),
                position.getOpenedAt(),
                position.getLastFilledAt(),
                position.getClosedAt()
        );
    }

    private SmartWalletFillView toFillView(SmartWalletFillEntity fill) {
        return new SmartWalletFillView(
                fill.getId(),
                fill.getOrderId(),
                fill.getExecutionAddress(),
                fill.getMarketId(),
                fill.getTokenId(),
                fill.getQuestion(),
                fill.getOutcome(),
                fill.getSide(),
                fill.getPrice(),
                fill.getSize(),
                fill.getNotionalUsdc(),
                fill.getFeeUsdc(),
                fill.getTradingFeeUsdc(),
                fill.getFeePol(),
                fill.getGasFeeUsdc(),
                fill.getRealizedPnlUsdc(),
                fill.getFilledAt()
        );
    }

    private SmartWalletOrderView toOrderView(SmartWalletOrderEntity order,
                                             Map<Long, SmartWalletFollowTaskEntity> followTasksById,
                                             Map<Long, SmartWalletTradeEventEntity> sourceEventsById) {
        SmartWalletFollowTaskEntity task = order.getFollowTaskId() == null ? null : followTasksById.get(order.getFollowTaskId());
        SmartWalletTradeEventEntity sourceEvent = task == null || task.getSourceEventId() == null
                ? null
                : sourceEventsById.get(task.getSourceEventId());
        String marketLifecycle = resolveMarketLifecycle(order.getMarketLifecycle(), order.getClosed(), order.getAcceptingOrders());
        boolean activeMarket = isActiveLifecycle(marketLifecycle, order.getClosed(), order.getAcceptingOrders());
        String externalMarketUrl = buildExternalMarketUrl(order.getMarketSlug());
        String internalMarketUrl = buildInternalMarketUrl(order.getMarketId());
        String marketUrl = StringUtils.hasText(externalMarketUrl) ? externalMarketUrl : internalMarketUrl;
        boolean earlyExit = "EXIT".equalsIgnoreCase(order.getOrderType());
        BigDecimal realizedPnl = safe(order.getRealizedPnlUsdc());
        BigDecimal costBasisUsdc = resolveOrderCostBasis(order, realizedPnl);
        return new SmartWalletOrderView(
                order.getId(),
                order.getFollowTaskId(),
                order.getWalletAddress(),
                order.getExecutionMode(),
                order.getExecutionAddress(),
                order.getExternalOrderId(),
                sourceEvent == null ? null : sourceEvent.getId(),
                sourceEvent == null ? null : sourceEvent.getExternalTradeId(),
                order.getMarketId(),
                order.getMarketSlug(),
                marketUrl,
                externalMarketUrl,
                internalMarketUrl,
                StringUtils.hasText(externalMarketUrl) ? "EXTERNAL_READY" : "INTERNAL_FALLBACK",
                order.getTokenId(),
                order.getQuestion(),
                order.getOutcome(),
                order.getMarketEndAt(),
                formatEpochMillis(order.getMarketEndAt()),
                marketLifecycle,
                activeMarket,
                Boolean.TRUE.equals(order.getClosed()),
                order.getAcceptingOrders() == null || order.getAcceptingOrders(),
                order.getMarketInfo() == null,
                false,
                null,
                order.getNormalizedCategory(),
                order.getSourceCategory(),
                order.getSide(),
                order.getOrderType(),
                earlyExit,
                order.getExitReason(),
                order.getStatus(),
                order.getPrice(),
                order.getSize(),
                order.getNotionalUsdc(),
                order.getSize(),
                order.getPrice(),
                order.getNotionalUsdc(),
                costBasisUsdc,
                null,
                costBasisUsdc,
                order.getFeeUsdc(),
                order.getTradingFeeUsdc(),
                order.getFeePol(),
                order.getGasFeeUsdc(),
                realizedPnl,
                null,
                null,
                null,
                null,
                null,
                null,
                order.getErrorMessage(),
                order.getPlacedAt(),
                order.getFilledAt()
        );
    }

    private SmartWalletTradeOrderView toTradeOrderView(SmartWalletTradeOrderEntity entity,
                                                       List<SmartWalletOrderEntity> orders,
                                                       List<SmartWalletFillEntity> fills) {
        String externalMarketUrl = buildExternalMarketUrl(entity.getMarketSlug());
        String internalMarketUrl = buildInternalMarketUrl(entity.getMarketId());
        String marketUrl = StringUtils.hasText(externalMarketUrl) ? externalMarketUrl : internalMarketUrl;
        return new SmartWalletTradeOrderView(
                entity.getId(),
                entity.getPositionId(),
                entity.getMarketInfoId(),
                entity.getExecutionAddress(),
                entity.getWalletAddress(),
                entity.getSourceFollowTaskId(),
                entity.getMarketId(),
                entity.getMarketSlug(),
                marketUrl,
                externalMarketUrl,
                internalMarketUrl,
                StringUtils.hasText(externalMarketUrl) ? "EXTERNAL_READY" : "INTERNAL_FALLBACK",
                entity.getTokenId(),
                entity.getQuestion(),
                entity.getOutcome(),
                entity.getMarketEndAt(),
                formatEpochMillis(entity.getMarketEndAt()),
                entity.getMarketLifecycle(),
                isActiveLifecycle(entity.getMarketLifecycle(), entity.getClosed(), entity.getAcceptingOrders()),
                Boolean.TRUE.equals(entity.getClosed()),
                entity.getAcceptingOrders() == null || entity.getAcceptingOrders(),
                entity.getNormalizedCategory(),
                entity.getSourceCategory(),
                entity.getEntryQuantity(),
                entity.getEntryAvgPrice(),
                entity.getEntryCostUsdc(),
                entity.getEntryFeeUsdc(),
                entity.getOpenedAt(),
                entity.getExitQuantity(),
                entity.getExitAvgPrice(),
                entity.getExitProceedsUsdc(),
                entity.getExitRealizedPnlUsdc(),
                entity.getExitFeeUsdc(),
                entity.getLastExitAt(),
                entity.getRemainingQuantity(),
                entity.getRemainingCostBasisUsdc(),
                entity.getLastMarkPrice(),
                entity.getUnrealizedPnlUsdc(),
                entity.getSettlementPrice(),
                entity.getSettlementProceedsUsdc(),
                entity.getSettlementPnlUsdc(),
                entity.getSettledAt(),
                entity.getTotalRealizedPnlUsdc(),
                entity.getTotalPnlUsdc(),
                entity.getRoiRate(),
                entity.getStatus(),
                orders.stream().filter(order -> !"EXIT".equalsIgnoreCase(order.getOrderType())).map(SmartWalletOrderEntity::getId).toList(),
                orders.stream().filter(order -> "EXIT".equalsIgnoreCase(order.getOrderType())).map(SmartWalletOrderEntity::getId).toList(),
                fills.stream().map(SmartWalletFillEntity::getId).toList()
        );
    }

    private SmartWalletTradeView toTradeView(SmartWalletPositionEntity position,
                                             List<SmartWalletOrderEntity> orders) {
        List<SmartWalletOrderEntity> entryOrders = orders.stream()
                .filter(order -> order != null && !"EXIT".equalsIgnoreCase(order.getOrderType()))
                .toList();
        List<SmartWalletOrderEntity> exitOrders = orders.stream()
                .filter(order -> order != null && "EXIT".equalsIgnoreCase(order.getOrderType()) && !"FAILED".equalsIgnoreCase(order.getStatus()))
                .toList();
        BigDecimal entryQuantity = entryOrders.stream()
                .map(SmartWalletOrderEntity::getSize)
                .map(this::safe)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal entryCost = entryOrders.stream()
                .map(SmartWalletOrderEntity::getNotionalUsdc)
                .map(this::safe)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal exitQuantity = exitOrders.stream()
                .map(SmartWalletOrderEntity::getSize)
                .map(this::safe)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal exitProceeds = exitOrders.stream()
                .map(SmartWalletOrderEntity::getNotionalUsdc)
                .map(this::safe)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal realized = safe(position.getRealizedPnlUsdc());
        BigDecimal unrealized = safe(position.getUnrealizedPnlUsdc());
        BigDecimal totalPnl = realized.add(unrealized);
        BigDecimal roiBase = entryCost.compareTo(BigDecimal.ZERO) > 0
                ? entryCost
                : safe(position.getOriginalCostBasisUsdc());
        BigDecimal roiRate = roiBase.compareTo(BigDecimal.ZERO) > 0
                ? totalPnl.divide(roiBase, 8, java.math.RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        return new SmartWalletTradeView(
                position.getId(),
                position.getExecutionAddress(),
                position.getMarketId(),
                position.getMarketInfo() == null ? null : position.getMarketInfo().getMarketSlug(),
                position.getTokenId(),
                position.getQuestion(),
                position.getOutcome(),
                position.getStatus(),
                position.getNetSide(),
                entryQuantity,
                weightedAverage(entryCost, entryQuantity),
                entryCost,
                exitQuantity,
                weightedAverage(exitProceeds, exitQuantity),
                exitProceeds,
                safe(position.getQuantity()),
                realized,
                unrealized,
                totalPnl,
                roiRate,
                roiBase,
                tradeRoiAnomalyReason(entryQuantity, entryCost, totalPnl, roiRate),
                safe(position.getLastMarkPrice()),
                resolveTradeExitReason(exitOrders),
                entryOrders.size(),
                exitOrders.size(),
                entryOrders.stream().map(SmartWalletOrderEntity::getId).filter(Objects::nonNull).toList(),
                exitOrders.stream().map(SmartWalletOrderEntity::getId).filter(Objects::nonNull).toList(),
                position.getMarketEndAt(),
                formatEpochMillis(position.getMarketEndAt()),
                position.getOpenedAt(),
                position.getLastFilledAt(),
                position.getClosedAt()
        );
    }

    private DailyReportRow toDailyReportView(SmartWalletDailyReportEntity report, DailyOutcomeBreakdown breakdown) {
        DailyOutcomeBreakdown effective = breakdown != null && breakdown.hasOutcomeEntries()
                ? breakdown
                : DailyOutcomeBreakdown.fromStoredReport(report);
        ConflictSummary conflictSummary = calculateDailyConflictSummary(report);
        BigDecimal realized = safe(report.getRealizedPnlUsdc());
        BigDecimal unrealized = safe(report.getUnrealizedPnlUsdc());
        return new DailyReportRow(
                report.getId(),
                report.getReportDate(),
                report.getExecutionAddress(),
                report.getTotalOrders(),
                report.getFilledOrders(),
                report.getTotalVolumeUsdc(),
                BigDecimal.ZERO,
                effective.profitPnlUsdc,
                effective.lossPnlUsdc,
                realized,
                unrealized,
                realized.add(unrealized),
                effective.winningPositions,
                effective.losingPositions,
                effective.winRate(),
                conflictSummary.conflictingMarkets,
                conflictSummary.conflictingPositions,
                conflictSummary.conflictNetPnlUsdc
        );
    }

    private SmartWalletOrderPnlCategoryAttributionView.WindowBucket buildAttributionWindow(String key,
                                                                                           String label,
                                                                                           int days,
                                                                                           List<SmartWalletTradeOrderEntity> tradeOrders,
                                                                                           int limit) {
        long minOpenedAt = days == Integer.MAX_VALUE ? Long.MIN_VALUE : System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L;
        Map<String, PnlOrderCounter> marketCounters = new LinkedHashMap<>();
        Map<String, PnlOrderCounter> walletCounters = new LinkedHashMap<>();
        for (SmartWalletTradeOrderEntity tradeOrder : tradeOrders) {
            if (!withinWindow(tradeOrder, minOpenedAt) || safe(tradeOrder.getTotalPnlUsdc()).compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            String category = normalizeCategory(tradeOrder);
            marketCounters.computeIfAbsent(category, item -> new PnlOrderCounter(category, category, null, category))
                    .add(tradeOrder.getTotalPnlUsdc());

            String walletAddress = firstText(tradeOrder.getWalletAddress(), tradeOrder.getExecutionAddress(), "UNKNOWN");
            String walletKey = walletAddress + "|" + category;
            walletCounters.computeIfAbsent(walletKey,
                            item -> new PnlOrderCounter(walletKey, shortAddress(walletAddress) + " / " + category, walletAddress, category))
                    .add(tradeOrder.getTotalPnlUsdc());
        }
        return new SmartWalletOrderPnlCategoryAttributionView.WindowBucket(
                key,
                label,
                toCategoryRows(marketCounters, limit),
                toCategoryRows(walletCounters, limit)
        );
    }

    private boolean matchesDashboardMode(String executionAddress, String mode) {
        String normalized = mode == null ? null : mode.trim().toUpperCase(Locale.ROOT);
        if ("PAPER".equals(normalized)) {
            return PAPER_BOOK_EXECUTION_ADDRESS.equalsIgnoreCase(executionAddress);
        }
        if ("LIVE".equals(normalized)) {
            return executionAddress != null && !PAPER_BOOK_EXECUTION_ADDRESS.equalsIgnoreCase(executionAddress);
        }
        return true;
    }

    private void applyOrderModeFilter(LambdaQueryWrapper<SmartWalletOrderEntity> wrapper, String mode) {
        String normalized = normalize(mode);
        if ("PAPER".equals(normalized)) {
            wrapper.eq(SmartWalletOrderEntity::getExecutionAddress, PAPER_BOOK_EXECUTION_ADDRESS);
        } else if ("LIVE".equals(normalized)) {
            wrapper.ne(SmartWalletOrderEntity::getExecutionAddress, PAPER_BOOK_EXECUTION_ADDRESS);
        }
    }

    private void applyTradeOrderModeFilter(LambdaQueryWrapper<SmartWalletTradeOrderEntity> wrapper, String mode) {
        String normalized = normalize(mode);
        if ("PAPER".equals(normalized)) {
            wrapper.eq(SmartWalletTradeOrderEntity::getExecutionAddress, PAPER_BOOK_EXECUTION_ADDRESS);
        } else if ("LIVE".equals(normalized)) {
            wrapper.ne(SmartWalletTradeOrderEntity::getExecutionAddress, PAPER_BOOK_EXECUTION_ADDRESS);
        }
    }

    private void applyOrderCursorFilters(LambdaQueryWrapper<SmartWalletOrderEntity> wrapper,
                                         String settlement,
                                         String side,
                                         String status,
                                         String q) {
        String normalizedSettlement = normalize(settlement);
        if ("SETTLED".equals(normalizedSettlement)) {
            wrapper.in(SmartWalletOrderEntity::getStatus, List.of("SETTLED_WIN", "SETTLED_LOSS"));
        } else if ("UNSETTLED".equals(normalizedSettlement)) {
            wrapper.notIn(SmartWalletOrderEntity::getStatus, List.of("SETTLED_WIN", "SETTLED_LOSS"));
        } else if ("EARLY_EXIT".equals(normalizedSettlement)) {
            wrapper.and(condition -> condition
                    .isNotNull(SmartWalletOrderEntity::getExitReason)
                    .or()
                    .eq(SmartWalletOrderEntity::getOrderType, "EXIT"));
        }
        if (StringUtils.hasText(side)) {
            wrapper.eq(SmartWalletOrderEntity::getSide, normalize(side));
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(SmartWalletOrderEntity::getStatus, normalize(status));
        }
        if (StringUtils.hasText(q)) {
            String keyword = q.trim();
            wrapper.and(condition -> condition
                    .like(SmartWalletOrderEntity::getExternalOrderId, keyword)
                    .or()
                    .like(SmartWalletOrderEntity::getExecutionAddress, keyword)
                    .or()
                    .like(SmartWalletOrderEntity::getWalletAddress, keyword)
                    .or()
                    .apply("market_info_id IN (SELECT id FROM smart_wallet_market_info WHERE market_id LIKE {0} OR question LIKE {0} OR outcome LIKE {0})",
                            "%" + keyword + "%")
                    .or()
                    .apply("CAST(id AS CHAR) LIKE {0}", "%" + keyword + "%"));
        }
    }

    private Map<Long, SmartWalletFollowTaskEntity> loadFollowTasks(List<SmartWalletOrderEntity> orders) {
        List<Long> followTaskIds = orders.stream()
                .map(SmartWalletOrderEntity::getFollowTaskId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (followTaskIds.isEmpty()) {
            return Map.of();
        }
        return followTaskMapper.selectBatchIds(followTaskIds).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(SmartWalletFollowTaskEntity::getId, item -> item));
    }

    private Map<Long, SmartWalletTradeEventEntity> loadSourceEvents(java.util.Collection<SmartWalletFollowTaskEntity> tasks) {
        List<Long> sourceEventIds = tasks.stream()
                .map(SmartWalletFollowTaskEntity::getSourceEventId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (sourceEventIds.isEmpty()) {
            return Map.of();
        }
        return tradeEventMapper.selectBatchIds(sourceEventIds).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(SmartWalletTradeEventEntity::getId, item -> item));
    }

    private Map<Long, List<SmartWalletOrderEntity>> batchLoadOrders(Set<Long> positionIds) {
        if (positionIds == null || positionIds.isEmpty()) {
            return Map.of();
        }
        return orderMapper.selectList(new LambdaQueryWrapper<SmartWalletOrderEntity>()
                        .in(SmartWalletOrderEntity::getPositionId, positionIds)
                        .orderByAsc(SmartWalletOrderEntity::getPlacedAt)
                        .orderByAsc(SmartWalletOrderEntity::getId))
                .stream()
                .collect(Collectors.groupingBy(SmartWalletOrderEntity::getPositionId));
    }

    private Map<Long, List<SmartWalletFillEntity>> batchLoadFills(Set<Long> positionIds) {
        if (positionIds == null || positionIds.isEmpty()) {
            return Map.of();
        }
        return fillMapper.selectList(new LambdaQueryWrapper<SmartWalletFillEntity>()
                        .in(SmartWalletFillEntity::getPositionId, positionIds)
                        .orderByAsc(SmartWalletFillEntity::getFilledAt)
                        .orderByAsc(SmartWalletFillEntity::getId))
                .stream()
                .collect(Collectors.groupingBy(SmartWalletFillEntity::getPositionId));
    }

    private boolean matchesOrderPnl(SmartWalletOrderView view, String pnl) {
        String normalized = normalize(pnl);
        BigDecimal value = safe(view == null ? null : view.realizedPnlUsdc());
        if ("PROFIT".equals(normalized)) return value.compareTo(BigDecimal.ZERO) > 0;
        if ("LOSS".equals(normalized)) return value.compareTo(BigDecimal.ZERO) < 0;
        if ("ZERO".equals(normalized)) return value.compareTo(BigDecimal.ZERO) == 0;
        return true;
    }

    private boolean isFutureCompletedTradeOrder(SmartWalletTradeOrderEntity order, Date now) {
        if (order == null || now == null || "OPEN".equalsIgnoreCase(order.getStatus())) {
            return false;
        }
        Date outcomeAt = order.getSettledAt() != null ? order.getSettledAt() : order.getLastExitAt();
        return outcomeAt != null && outcomeAt.after(now);
    }

    private boolean isOpenTradeOrder(SmartWalletTradeOrderEntity order) {
        return order != null && "OPEN".equalsIgnoreCase(order.getStatus());
    }

    private boolean isFutureLedgerEntry(SmartWalletCashLedgerEntity entry, Date now) {
        return entry != null && now != null && entry.getOccurredAt() != null && entry.getOccurredAt().after(now);
    }

    private boolean isFuturePositionOutcome(SmartWalletPositionEntity position, Date now) {
        if (position == null || now == null || isActivePositionStatus(position.getStatus())) {
            return false;
        }
        Date outcomeAt = position.getClosedAt();
        return outcomeAt != null && outcomeAt.after(now);
    }

    private boolean isFutureReportDate(Date reportDate) {
        LocalDate date = toLocalDate(reportDate);
        return date != null && date.isAfter(LocalDate.now(ZoneId.systemDefault()));
    }

    private boolean matchesTradeOrderPnl(SmartWalletTradeOrderView view, String pnl) {
        String normalized = normalize(pnl);
        BigDecimal value = safe(view == null ? null : view.totalPnlUsdc());
        if ("PROFIT".equals(normalized)) return value.compareTo(BigDecimal.ZERO) > 0;
        if ("LOSS".equals(normalized)) return value.compareTo(BigDecimal.ZERO) < 0;
        if ("ZERO".equals(normalized)) return value.compareTo(BigDecimal.ZERO) == 0;
        return true;
    }

    private boolean matchesTradeOrderSettlement(SmartWalletTradeOrderView view, String settlement) {
        String normalized = normalize(settlement);
        if ("SETTLED".equals(normalized)) return isSettledTradeOrderStatus(view.status());
        if ("UNSETTLED".equals(normalized)) return !isSettledTradeOrderStatus(view.status());
        if ("EARLY_EXIT".equals(normalized)) return safe(view.exitQuantity()).compareTo(BigDecimal.ZERO) > 0;
        return true;
    }

    private boolean matchesTradeOrderQuery(SmartWalletTradeOrderView view, String q) {
        if (!StringUtils.hasText(q)) return true;
        String needle = q.trim().toLowerCase(Locale.ROOT);
        return contains(view.question(), needle)
                || contains(view.outcome(), needle)
                || contains(view.marketId(), needle)
                || contains(view.status(), needle);
    }

    private boolean contains(String value, String needle) {
        return StringUtils.hasText(value) && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private boolean isSettledTradeOrderStatus(String status) {
        String normalized = normalize(status);
        return "RESOLVED_UNCLAIMED".equals(normalized) || "CLAIMED".equals(normalized) || "SETTLED".equals(normalized);
    }

    private BigDecimal resolveOrderCostBasis(SmartWalletOrderEntity order, BigDecimal realizedPnlUsdc) {
        if (order == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal notional = safe(order.getNotionalUsdc());
        if ("SELL".equalsIgnoreCase(order.getSide())) {
            BigDecimal cost = notional.subtract(safe(realizedPnlUsdc)).subtract(safe(order.getTradingFeeUsdc()));
            return cost.max(BigDecimal.ZERO);
        }
        return notional;
    }

    private String resolveMarketLifecycle(String lifecycle, Boolean closed, Boolean acceptingOrders) {
        if (Boolean.TRUE.equals(closed) || (acceptingOrders != null && !acceptingOrders)) {
            return "CLOSED_UNRESOLVED";
        }
        return StringUtils.hasText(lifecycle) ? lifecycle : "ACTIVE";
    }

    private boolean isActiveLifecycle(String lifecycle, Boolean closed, Boolean acceptingOrders) {
        if (!"ACTIVE".equalsIgnoreCase(lifecycle)) {
            return false;
        }
        if (Boolean.TRUE.equals(closed)) {
            return false;
        }
        return acceptingOrders == null || acceptingOrders;
    }

    private String buildExternalMarketUrl(String marketSlug) {
        if (!StringUtils.hasText(marketSlug)) {
            return null;
        }
        return "https://polymarket.com/event/" + marketSlug.trim();
    }

    private String buildInternalMarketUrl(String marketId) {
        if (!StringUtils.hasText(marketId)) {
            return null;
        }
        return "/dashboard/polymarket/markets/" + marketId.trim();
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private int normalizeTimelineBucketHours(Integer bucketHours) {
        if (bucketHours == null) {
            return 24;
        }
        return switch (bucketHours) {
            case 8, 12, 24, 72 -> bucketHours;
            default -> 24;
        };
    }

    private List<TimelinePoint> dailyReportTimeline(int days, String mode) {
        int safeDays = Math.max(days, 1);
        ZoneId zoneId = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zoneId);
        LocalDate startDate = today.minusDays(safeDays - 1L);
        Map<LocalDate, TradeOrderTimelineBucket> buckets = new LinkedHashMap<>();
        for (LocalDate cursor = startDate; !cursor.isAfter(today); cursor = cursor.plusDays(1)) {
            buckets.put(cursor, new TradeOrderTimelineBucket());
        }
        List<SmartWalletTradeOrderEntity> tradeOrders = tradeOrderMapper.selectList(new LambdaQueryWrapper<>());
        if (tradeOrders == null) {
            tradeOrders = List.of();
        }
        Date now = new Date();
        TradeOrderTimelineBucket baseline = new TradeOrderTimelineBucket();
        BigDecimal openUnrealized = BigDecimal.ZERO;
        for (SmartWalletTradeOrderEntity order : tradeOrders) {
            if (order == null || !matchesDashboardMode(order.getExecutionAddress(), mode)) {
                continue;
            }
            BigDecimal pnl = safe(order.getTotalPnlUsdc());
            if (isOpenTradeOrder(order)) {
                openUnrealized = openUnrealized.add(pnl);
                continue;
            }
            Date outcomeAt = tradeOrderOutcomeAt(order);
            if (outcomeAt == null || outcomeAt.after(now)) {
                continue;
            }
            LocalDate outcomeDate = toLocalDate(outcomeAt);
            if (outcomeDate == null || outcomeDate.isAfter(today)) {
                continue;
            }
            if (outcomeDate.isBefore(startDate)) {
                baseline.addRealized(pnl);
                continue;
            }
            TradeOrderTimelineBucket bucket = buckets.get(outcomeDate);
            if (bucket != null) {
                bucket.addRealized(pnl);
            }
        }
        TradeOrderTimelineBucket todayBucket = buckets.get(today);
        if (todayBucket != null) {
            todayBucket.addUnrealized(openUnrealized);
        }
        List<TimelinePoint> points = new ArrayList<>();
        BigDecimal cumulativeLoss = baseline.lossPnl;
        BigDecimal cumulativeRealized = baseline.realizedPnl;
        BigDecimal cumulativeFee = BigDecimal.ZERO;
        BigDecimal cumulativeNet = baseline.realizedPnl;
        for (Map.Entry<LocalDate, TradeOrderTimelineBucket> entry : buckets.entrySet()) {
            TradeOrderTimelineBucket bucket = entry.getValue();
            BigDecimal net = bucket.netPnl();
            cumulativeLoss = cumulativeLoss.add(bucket.lossPnl);
            cumulativeRealized = cumulativeRealized.add(bucket.realizedPnl);
            cumulativeNet = cumulativeNet.add(net);
            points.add(new TimelinePoint(
                    Date.from(entry.getKey().atStartOfDay(zoneId).toInstant()),
                    bucket.profitPnl,
                    bucket.lossPnl,
                    bucket.realizedPnl,
                    BigDecimal.ZERO,
                    net,
                    cumulativeLoss,
                    cumulativeRealized,
                    cumulativeFee,
                    cumulativeNet
            ));
        }
        return points;
    }

    private Date tradeOrderOutcomeAt(SmartWalletTradeOrderEntity order) {
        if (order == null) {
            return null;
        }
        return order.getSettledAt() != null ? order.getSettledAt() : order.getLastExitAt();
    }

    private Map<LocalDate, DailyOutcomeBreakdown> loadDailyOutcomeBreakdowns(List<SmartWalletDailyReportEntity> reports, String mode) {
        if (reports == null || reports.isEmpty()) {
            return Map.of();
        }
        LocalDate minDate = null;
        LocalDate maxDate = null;
        for (SmartWalletDailyReportEntity report : reports) {
            LocalDate reportDate = toLocalDate(report.getReportDate());
            if (reportDate == null) {
                continue;
            }
            if (minDate == null || reportDate.isBefore(minDate)) {
                minDate = reportDate;
            }
            if (maxDate == null || reportDate.isAfter(maxDate)) {
                maxDate = reportDate;
            }
        }
        if (minDate == null || maxDate == null) {
            return Map.of();
        }
        ZoneId zoneId = ZoneId.systemDefault();
        Date start = Date.from(minDate.atStartOfDay(zoneId).toInstant());
        Date endExclusive = Date.from(maxDate.plusDays(1).atStartOfDay(zoneId).toInstant());
        List<SmartWalletCashLedgerEntity> entries = cashLedgerMapper.selectList(new LambdaQueryWrapper<SmartWalletCashLedgerEntity>()
                .eq(SmartWalletCashLedgerEntity::getEntryType, "REALIZED_PNL")
                .ge(SmartWalletCashLedgerEntity::getOccurredAt, start)
                .lt(SmartWalletCashLedgerEntity::getOccurredAt, endExclusive)
                .orderByAsc(SmartWalletCashLedgerEntity::getOccurredAt)
                .orderByAsc(SmartWalletCashLedgerEntity::getId));
        if (entries == null || entries.isEmpty()) {
            return Map.of();
        }
        Map<LocalDate, Map<String, BigDecimal>> grouped = new LinkedHashMap<>();
        for (SmartWalletCashLedgerEntity entry : entries) {
            if (entry == null || entry.getOccurredAt() == null || !matchesDashboardMode(entry.getExecutionAddress(), mode)) {
                continue;
            }
            LocalDate reportDate = toLocalDate(entry.getOccurredAt());
            if (reportDate == null || reportDate.isBefore(minDate) || reportDate.isAfter(maxDate)) {
                continue;
            }
            grouped.computeIfAbsent(reportDate, key -> new LinkedHashMap<>())
                    .merge(outcomeReferenceKey(entry), ledgerPnlDelta(entry), BigDecimal::add);
        }
        Map<LocalDate, DailyOutcomeBreakdown> result = new LinkedHashMap<>();
        for (LocalDate cursor = minDate; !cursor.isAfter(maxDate); cursor = cursor.plusDays(1)) {
            result.put(cursor, deriveDailyOutcomeBreakdown(grouped.get(cursor)));
        }
        return result;
    }

    private DailyOutcomeBreakdown deriveDailyOutcomeBreakdown(Map<String, BigDecimal> grouped) {
        if (grouped == null || grouped.isEmpty()) {
            return DailyOutcomeBreakdown.empty();
        }
        BigDecimal profit = BigDecimal.ZERO;
        BigDecimal loss = BigDecimal.ZERO;
        int wins = 0;
        int losses = 0;
        for (BigDecimal pnl : grouped.values()) {
            BigDecimal signedPnl = safe(pnl);
            if (signedPnl.compareTo(BigDecimal.ZERO) > 0) {
                profit = profit.add(signedPnl);
                wins++;
            } else if (signedPnl.compareTo(BigDecimal.ZERO) < 0) {
                loss = loss.add(signedPnl);
                losses++;
            }
        }
        return new DailyOutcomeBreakdown(profit, loss, wins, losses);
    }

    private BigDecimal ledgerPnlDelta(SmartWalletCashLedgerEntity entry) {
        if (entry == null || !StringUtils.hasText(entry.getEntryType())) {
            return BigDecimal.ZERO;
        }
        if ("REALIZED_PNL".equalsIgnoreCase(entry.getEntryType())) {
            return "OUT".equalsIgnoreCase(entry.getDirection()) ? safe(entry.getAmount()).negate() : safe(entry.getAmount());
        }
        return BigDecimal.ZERO;
    }

    private TimelineBaseline timelineBaselineBefore(Date startDate, String mode) {
        List<SmartWalletCashLedgerEntity> entries = cashLedgerMapper.selectList(new LambdaQueryWrapper<SmartWalletCashLedgerEntity>()
                .in(SmartWalletCashLedgerEntity::getEntryType, List.of("REALIZED_PNL", "FEE", "NETWORK_FEE"))
                .lt(SmartWalletCashLedgerEntity::getOccurredAt, startDate)
                .orderByAsc(SmartWalletCashLedgerEntity::getOccurredAt)
                .orderByAsc(SmartWalletCashLedgerEntity::getId));
        TimelineBucketAccumulator accumulator = new TimelineBucketAccumulator();
        if (entries != null) {
            for (SmartWalletCashLedgerEntity entry : entries) {
                if (!matchesDashboardMode(entry.getExecutionAddress(), mode)) {
                    continue;
                }
                BigDecimal delta = ledgerPnlDelta(entry);
                if (delta.compareTo(BigDecimal.ZERO) != 0) {
                    accumulator.add(entry, delta);
                }
            }
        }
        return new TimelineBaseline(accumulator.lossPnl(), accumulator.realizedPnl, accumulator.fee, accumulator.netPnl());
    }

    private LocalDateTime floorToTimelineBucket(LocalDateTime value, int bucketHours) {
        if (bucketHours >= 24) {
            int bucketDays = Math.max(bucketHours / 24, 1);
            LocalDate epoch = LocalDate.of(1970, 1, 1);
            long daysSinceEpoch = java.time.temporal.ChronoUnit.DAYS.between(epoch, value.toLocalDate());
            long bucketStartDays = Math.floorDiv(daysSinceEpoch, bucketDays) * (long) bucketDays;
            return epoch.plusDays(bucketStartDays).atStartOfDay();
        }
        int hour = Math.floorDiv(value.getHour(), bucketHours) * bucketHours;
        return value.withHour(hour).withMinute(0).withSecond(0).withNano(0);
    }

    private BigDecimal pendingSettlementNetPnlForPosition(SmartWalletPositionEntity position) {
        if (position == null) {
            return BigDecimal.ZERO;
        }
        if (isActivePositionStatus(position.getStatus())) {
            return safe(position.getUnrealizedPnlUsdc());
        }
        if ("RESOLVED_UNCLAIMED".equalsIgnoreCase(position.getStatus())) {
            return claimableProceeds(position).subtract(originalCostBasis(position));
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal originalCostBasis(SmartWalletPositionEntity position) {
        if (position == null) {
            return BigDecimal.ZERO;
        }
        if (position.getOriginalCostBasisUsdc() != null && position.getOriginalCostBasisUsdc().compareTo(BigDecimal.ZERO) > 0) {
            return position.getOriginalCostBasisUsdc();
        }
        return safe(position.getCostBasisUsdc());
    }

    private BigDecimal realizedPnlForPosition(SmartWalletPositionEntity position) {
        if (position == null) {
            return BigDecimal.ZERO;
        }
        if (position.getRealizedPnlUsdc() != null) {
            return position.getRealizedPnlUsdc();
        }
        if ("RESOLVED_UNCLAIMED".equalsIgnoreCase(position.getStatus())) {
            return claimableProceeds(position).subtract(originalCostBasis(position));
        }
        return BigDecimal.ZERO;
    }

    private boolean isActivePositionStatus(String status) {
        return "OPEN".equalsIgnoreCase(status) || "PARTIALLY_CLOSED".equalsIgnoreCase(status);
    }

    private ConflictSummary calculateDailyConflictSummary(SmartWalletDailyReportEntity report) {
        if (report == null || report.getReportDate() == null) {
            return ConflictSummary.empty();
        }
        LocalDate reportDate = toLocalDate(report.getReportDate());
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .eq(StringUtils.hasText(report.getExecutionAddress()), SmartWalletPositionEntity::getExecutionAddress, report.getExecutionAddress()));
        populateMarketInfoForPositions(positions);
        return summarizeConflicts(positions, reportDate);
    }

    private ConflictSummary summarizeConflicts(List<SmartWalletPositionEntity> positions, LocalDate closedOnDate) {
        if (positions == null || positions.isEmpty()) {
            return ConflictSummary.empty();
        }
        Map<String, ConflictAccumulator> grouped = new LinkedHashMap<>();
        for (SmartWalletPositionEntity position : positions) {
            if (!StringUtils.hasText(position.getMarketId())) {
                continue;
            }
            if (closedOnDate != null) {
                if (position.getClosedAt() == null || !closedOnDate.equals(toLocalDate(position.getClosedAt()))) {
                    continue;
                }
            }
            grouped.computeIfAbsent(position.getMarketId(), key -> new ConflictAccumulator(position.getMarketId(), position.getQuestion()))
                    .add(position, realizedPnlForPosition(position));
        }
        int conflictingMarkets = 0;
        int conflictingPositions = 0;
        BigDecimal conflictNetPnlUsdc = BigDecimal.ZERO;
        for (ConflictAccumulator item : grouped.values()) {
            if (item.outcomes.size() < 2) {
                continue;
            }
            conflictingMarkets++;
            conflictingPositions += item.positionCount;
            conflictNetPnlUsdc = conflictNetPnlUsdc.add(item.realizedPnlUsdc).add(item.unrealizedPnlUsdc);
        }
        return new ConflictSummary(conflictingMarkets, conflictingPositions, conflictNetPnlUsdc);
    }

    private LocalDate toLocalDate(Date value) {
        if (value == null) {
            return null;
        }
        return value.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private String outcomeReferenceKey(SmartWalletCashLedgerEntity entry) {
        if (entry == null) {
            return "ledger:null";
        }
        if (StringUtils.hasText(entry.getReferenceType()) && entry.getReferenceId() != null) {
            return entry.getReferenceType() + ":" + entry.getReferenceId();
        }
        if (entry.getReferenceId() != null) {
            return "ref:" + entry.getReferenceId();
        }
        if (entry.getId() != null) {
            return "ledger:" + entry.getId();
        }
        return "occurred:" + entry.getOccurredAt();
    }

    private void applyPositionModeFilter(LambdaQueryWrapper<SmartWalletPositionEntity> wrapper, String mode) {
        String normalized = normalize(mode);
        if ("PAPER".equals(normalized)) {
            wrapper.eq(SmartWalletPositionEntity::getExecutionAddress, PAPER_BOOK_EXECUTION_ADDRESS);
        } else if ("LIVE".equals(normalized)) {
            wrapper.ne(SmartWalletPositionEntity::getExecutionAddress, PAPER_BOOK_EXECUTION_ADDRESS);
        }
    }

    private void applyTradeSettlementSqlFilter(LambdaQueryWrapper<SmartWalletPositionEntity> wrapper, String settlement) {
        String normalizedSettlement = normalize(settlement);
        if ("SETTLED".equals(normalizedSettlement)) {
            wrapper.in(SmartWalletPositionEntity::getStatus, terminalTradeStatuses());
        } else if ("UNSETTLED".equals(normalizedSettlement)) {
            wrapper.notIn(SmartWalletPositionEntity::getStatus, terminalTradeStatuses());
        } else if ("EARLY_EXIT".equals(normalizedSettlement)) {
            wrapper.eq(SmartWalletPositionEntity::getStatus, "CLOSED");
        }
    }

    private boolean matchesTradeFilters(SmartWalletTradeView view, String pnl, String settlement, String q) {
        if (view == null) {
            return false;
        }
        String normalizedPnl = normalize(pnl);
        BigDecimal totalPnl = safe(view.totalPnlUsdc());
        if ("PROFIT".equals(normalizedPnl) && totalPnl.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        if ("LOSS".equals(normalizedPnl) && totalPnl.compareTo(BigDecimal.ZERO) >= 0) {
            return false;
        }
        if ("ZERO".equals(normalizedPnl) && totalPnl.compareTo(BigDecimal.ZERO) != 0) {
            return false;
        }
        String normalizedSettlement = normalize(settlement);
        if ("SETTLED".equals(normalizedSettlement) && !isTerminalTradeStatus(view.status())) {
            return false;
        }
        if ("UNSETTLED".equals(normalizedSettlement) && isTerminalTradeStatus(view.status())) {
            return false;
        }
        if ("EARLY_EXIT".equals(normalizedSettlement) && !StringUtils.hasText(view.exitReason())) {
            return false;
        }
        if (!StringUtils.hasText(q)) {
            return true;
        }
        String needle = q.trim().toLowerCase(Locale.ROOT);
        return contains(view.question(), needle)
                || contains(view.marketId(), needle)
                || contains(view.outcome(), needle)
                || contains(view.status(), needle)
                || contains(view.executionAddress(), needle);
    }

    private boolean isTerminalTradeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return false;
        }
        return terminalTradeStatuses().stream().anyMatch(candidate -> candidate.equalsIgnoreCase(status.trim()));
    }

    private List<String> terminalTradeStatuses() {
        return List.of("CLOSED", "RESOLVED_UNCLAIMED", "CLAIMED");
    }

    private BigDecimal weightedAverage(BigDecimal notional, BigDecimal quantity) {
        BigDecimal safeQuantity = safe(quantity);
        if (safeQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return safe(notional).divide(safeQuantity, 8, java.math.RoundingMode.HALF_UP);
    }

    private String tradeRoiAnomalyReason(BigDecimal entryQuantity,
                                         BigDecimal entryCost,
                                         BigDecimal totalPnl,
                                         BigDecimal roiRate) {
        if (safe(entryQuantity).compareTo(BigDecimal.ZERO) <= 0 || safe(entryCost).compareTo(BigDecimal.ZERO) <= 0) {
            return "missing entry quantity/cost for ROI base";
        }
        BigDecimal entryAvgPrice = weightedAverage(entryCost, entryQuantity);
        if (entryAvgPrice.compareTo(BigDecimal.ZERO) <= 0 || entryAvgPrice.compareTo(BigDecimal.ONE) >= 0) {
            return null;
        }
        BigDecimal theoreticalMaxWinRoi = BigDecimal.ONE.subtract(entryAvgPrice)
                .divide(entryAvgPrice, 8, java.math.RoundingMode.HALF_UP);
        if (safe(roiRate).compareTo(theoreticalMaxWinRoi.add(new BigDecimal("0.05"))) > 0) {
            return "roi exceeds binary payout ceiling; theoreticalMax=" + theoreticalMaxWinRoi
                    + ", totalPnl=" + safe(totalPnl)
                    + ", entryAvgPrice=" + entryAvgPrice;
        }
        return null;
    }

    private String resolveTradeExitReason(List<SmartWalletOrderEntity> exitOrders) {
        if (exitOrders == null || exitOrders.isEmpty()) {
            return null;
        }
        for (int i = exitOrders.size() - 1; i >= 0; i--) {
            String reason = exitOrders.get(i).getExitReason();
            if (StringUtils.hasText(reason)) {
                return reason.trim();
            }
        }
        return null;
    }

    private SmartWalletMarketInfoEntity selectMarketInfo(List<SmartWalletMarketInfoEntity> rows, String outcome) {
        if (!StringUtils.hasText(outcome)) {
            return rows.get(0);
        }
        return rows.stream()
                .filter(item -> outcome.equalsIgnoreCase(item.getOutcome()))
                .findFirst()
                .orElse(rows.get(0));
    }

    private boolean withinWindow(SmartWalletTradeOrderEntity tradeOrder, long minOpenedAt) {
        if (minOpenedAt == Long.MIN_VALUE) {
            return true;
        }
        Date openedAt = firstDate(tradeOrder.getOpenedAt(), tradeOrder.getCreatedAt());
        return openedAt != null && openedAt.getTime() >= minOpenedAt;
    }

    private List<SmartWalletOrderPnlCategoryAttributionView.OrderPnlCategoryRow> toCategoryRows(Map<String, PnlOrderCounter> counters,
                                                                                                int limit) {
        return counters.values().stream()
                .filter(counter -> counter.totalOrderCount > 0)
                .sorted(Comparator.comparing((PnlOrderCounter counter) -> counter.totalOrderCount).reversed()
                        .thenComparing(counter -> counter.profitOrderCount, Comparator.reverseOrder())
                        .thenComparing(counter -> counter.label))
                .limit(limit)
                .map(counter -> new SmartWalletOrderPnlCategoryAttributionView.OrderPnlCategoryRow(
                        counter.key,
                        counter.label,
                        counter.walletAddress,
                        counter.category,
                        counter.profitOrderCount,
                        counter.lossOrderCount,
                        counter.totalOrderCount
                ))
                .toList();
    }

    private String normalizeCategory(SmartWalletTradeOrderEntity tradeOrder) {
        String category = firstText(tradeOrder.getNormalizedCategory(), tradeOrder.getSourceCategory(), "未分类");
        return category.trim().isEmpty() ? "未分类" : category.trim();
    }

    private String shortAddress(String address) {
        if (!StringUtils.hasText(address)) {
            return "UNKNOWN";
        }
        String trimmed = address.trim();
        return trimmed.length() > 14 ? trimmed.substring(0, 6) + "..." + trimmed.substring(trimmed.length() - 4) : trimmed;
    }

    private String firstText(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private Date firstDate(Date... values) {
        if (values == null) {
            return null;
        }
        for (Date value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private boolean isSettlementCandidate(SmartWalletPositionEntity position, long now) {
        if (position == null) {
            return false;
        }
        if ("RESOLVED_UNCLAIMED".equalsIgnoreCase(position.getStatus())
                || "SETTLEMENT_PENDING_ORACLE".equalsIgnoreCase(position.getStatus())) {
            return true;
        }
        Long marketEndAt = position.getMarketEndAt();
        return marketEndAt != null && marketEndAt > 0 && marketEndAt <= now;
    }

    private BigDecimal claimableProceeds(SmartWalletPositionEntity position) {
        if (position == null || !StringUtils.hasText(position.getResolvedOutcome())) {
            return null;
        }
        if (!position.getResolvedOutcome().equalsIgnoreCase(position.getOutcome())) {
            return BigDecimal.ZERO;
        }
        return safe(position.getQuantity());
    }

    private List<String> blockerCategories(SmartWalletFollowTaskEntity task) {
        if (task == null) {
            return List.of();
        }
        Set<String> categories = new LinkedHashSet<>();
        if (StringUtils.hasText(task.getSkipReasonTags())) {
            categories.addAll(parseCsv(task.getSkipReasonTags()));
        }
        if (categories.isEmpty() && StringUtils.hasText(task.getPrimarySkipReason())) {
            categories.add(task.getPrimarySkipReason());
        }
        if (categories.isEmpty() && StringUtils.hasText(task.getErrorMessage())) {
            categories.add(classifyReason(task.getErrorMessage()));
        }
        return categories.stream()
                .filter(StringUtils::hasText)
                .toList();
    }

    private String classifyReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            return "OTHER";
        }
        String text = reason.toLowerCase(Locale.ROOT);
        if (text.contains("trigger price")) {
            return "PRICE";
        }
        if (text.contains("replicability")
                || text.contains("observe-only")
                || text.contains("wallet is not qualified")
                || text.contains("[archetype]")
                || text.contains("[behavior]")) {
            return "PROFILE_BEHAVIOR";
        }
        if (text.contains("open position count")
                || text.contains("portfolio limit")
                || text.contains("total open position cost")
                || text.contains("portfolio budget")
                || text.contains("minimum order")
                || text.contains("capital is depleted")) {
            return "CAPACITY";
        }
        if (text.contains("signal is stale")) {
            return "STALE";
        }
        if (text.contains("trigger size")) {
            return "SIZE";
        }
        if (text.contains("market")
                && (text.contains("closed") || text.contains("resolved") || text.contains("accepting orders"))) {
            return "NON_TRADABLE";
        }
        return "OTHER";
    }

    private List<String> parseCsv(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    private List<String> splitReasons(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        return Arrays.stream(value.split("\\s*\\|\\s*"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    private String formatEpochMillis(Long epochMillis) {
        return SmartWalletReportMath.formatEpochMillis(epochMillis);
    }

    private int normalizeLimit(Integer value, int fallback) {
        if (value == null || value <= 0) {
            return fallback;
        }
        return Math.min(value, 500);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal orderFee(SmartWalletOrderEntity order) {
        if (order == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal splitFee = safe(order.getTradingFeeUsdc()).add(safe(order.getGasFeeUsdc()));
        return splitFee.compareTo(BigDecimal.ZERO) > 0 ? splitFee : safe(order.getFeeUsdc());
    }

    private static class MutableBlockerStat {
        private int taskCount;
        private int executableCount;
        private int blockedCount;
    }

    private static class PnlOrderCounter {
        private final String key;
        private final String label;
        private final String walletAddress;
        private final String category;
        private int profitOrderCount;
        private int lossOrderCount;
        private int totalOrderCount;

        private PnlOrderCounter(String key, String label, String walletAddress, String category) {
            this.key = key;
            this.label = label;
            this.walletAddress = walletAddress;
            this.category = category;
        }

        private void add(BigDecimal pnl) {
            BigDecimal safePnl = pnl == null ? BigDecimal.ZERO : pnl;
            if (safePnl.compareTo(BigDecimal.ZERO) > 0) {
                profitOrderCount++;
            } else if (safePnl.compareTo(BigDecimal.ZERO) < 0) {
                lossOrderCount++;
            }
            totalOrderCount++;
        }
    }

    private static class DailyOutcomeBreakdown {
        private final BigDecimal profitPnlUsdc;
        private final BigDecimal lossPnlUsdc;
        private final int winningPositions;
        private final int losingPositions;

        private DailyOutcomeBreakdown(BigDecimal profitPnlUsdc,
                                      BigDecimal lossPnlUsdc,
                                      int winningPositions,
                                      int losingPositions) {
            this.profitPnlUsdc = profitPnlUsdc == null ? BigDecimal.ZERO : profitPnlUsdc;
            this.lossPnlUsdc = lossPnlUsdc == null ? BigDecimal.ZERO : lossPnlUsdc;
            this.winningPositions = winningPositions;
            this.losingPositions = losingPositions;
        }

        private static DailyOutcomeBreakdown empty() {
            return new DailyOutcomeBreakdown(BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);
        }

        private static DailyOutcomeBreakdown fromStoredReport(SmartWalletDailyReportEntity report) {
            BigDecimal realized = report == null || report.getRealizedPnlUsdc() == null
                    ? BigDecimal.ZERO
                    : report.getRealizedPnlUsdc();
            return new DailyOutcomeBreakdown(
                    realized.max(BigDecimal.ZERO),
                    realized.compareTo(BigDecimal.ZERO) < 0 ? realized : BigDecimal.ZERO,
                    report == null || report.getWinningPositions() == null ? 0 : report.getWinningPositions(),
                    report == null || report.getLosingPositions() == null ? 0 : report.getLosingPositions()
            );
        }

        private boolean hasOutcomeEntries() {
            return winningPositions > 0 || losingPositions > 0
                    || profitPnlUsdc.compareTo(BigDecimal.ZERO) != 0
                    || lossPnlUsdc.compareTo(BigDecimal.ZERO) != 0;
        }

        private BigDecimal winRate() {
            int completed = winningPositions + losingPositions;
            return completed == 0
                    ? BigDecimal.ZERO
                    : BigDecimal.valueOf(winningPositions).divide(BigDecimal.valueOf(completed), 4, java.math.RoundingMode.HALF_UP);
        }
    }

    private static class TimelineBucketAccumulator {
        private BigDecimal profitPnl = BigDecimal.ZERO;
        private BigDecimal lossPnl = BigDecimal.ZERO;
        private BigDecimal realizedPnl = BigDecimal.ZERO;
        private BigDecimal fee = BigDecimal.ZERO;

        private void add(SmartWalletCashLedgerEntity entry, BigDecimal delta) {
            if (entry == null || delta == null) {
                return;
            }
            if ("REALIZED_PNL".equalsIgnoreCase(entry.getEntryType())) {
                realizedPnl = realizedPnl.add(delta);
                if (delta.compareTo(BigDecimal.ZERO) > 0) {
                    profitPnl = profitPnl.add(delta);
                } else if (delta.compareTo(BigDecimal.ZERO) < 0) {
                    lossPnl = lossPnl.add(delta);
                }
            } else if ("FEE".equalsIgnoreCase(entry.getEntryType()) || "NETWORK_FEE".equalsIgnoreCase(entry.getEntryType())) {
                fee = fee.add(delta.abs());
            }
        }

        private BigDecimal profitPnl() {
            return profitPnl;
        }

        private BigDecimal lossPnl() {
            return lossPnl;
        }

        private BigDecimal netPnl() {
            return realizedPnl.subtract(fee);
        }
    }

    private static class TradeOrderTimelineBucket {
        private BigDecimal profitPnl = BigDecimal.ZERO;
        private BigDecimal lossPnl = BigDecimal.ZERO;
        private BigDecimal realizedPnl = BigDecimal.ZERO;
        private BigDecimal unrealizedPnl = BigDecimal.ZERO;

        private void addRealized(BigDecimal pnl) {
            BigDecimal safePnl = pnl == null ? BigDecimal.ZERO : pnl;
            realizedPnl = realizedPnl.add(safePnl);
            if (safePnl.compareTo(BigDecimal.ZERO) > 0) {
                profitPnl = profitPnl.add(safePnl);
            } else if (safePnl.compareTo(BigDecimal.ZERO) < 0) {
                lossPnl = lossPnl.add(safePnl);
            }
        }

        private void addUnrealized(BigDecimal pnl) {
            unrealizedPnl = unrealizedPnl.add(pnl == null ? BigDecimal.ZERO : pnl);
        }

        private BigDecimal netPnl() {
            return realizedPnl.add(unrealizedPnl);
        }
    }

    private record TimelineLedgerEntry(SmartWalletCashLedgerEntity entity, LocalDateTime bucketAt, BigDecimal delta) {
    }

    private record TimelineBaseline(BigDecimal cumulativeLossPnlUsdc,
                                    BigDecimal cumulativeRealizedPnlUsdc,
                                    BigDecimal cumulativeFeeUsdc,
                                    BigDecimal cumulativeNetPnlUsdc) {
    }

    private static class ConflictSummary {
        private final int conflictingMarkets;
        private final int conflictingPositions;
        private final BigDecimal conflictNetPnlUsdc;

        private ConflictSummary(int conflictingMarkets, int conflictingPositions, BigDecimal conflictNetPnlUsdc) {
            this.conflictingMarkets = conflictingMarkets;
            this.conflictingPositions = conflictingPositions;
            this.conflictNetPnlUsdc = conflictNetPnlUsdc == null ? BigDecimal.ZERO : conflictNetPnlUsdc;
        }

        private static ConflictSummary empty() {
            return new ConflictSummary(0, 0, BigDecimal.ZERO);
        }
    }

    private class AttributionAccumulator {
        private final String key;
        private final String label;
        private int orderCount;
        private BigDecimal tradedVolumeUsdc = BigDecimal.ZERO;
        private BigDecimal realizedPnlUsdc = BigDecimal.ZERO;
        private BigDecimal pendingSettlementPnlUsdc = BigDecimal.ZERO;
        private BigDecimal feeUsdc = BigDecimal.ZERO;

        private AttributionAccumulator(String key, String label) {
            this.key = key;
            this.label = label;
        }

        private void addOrder(SmartWalletOrderEntity order) {
            if (order == null) {
                return;
            }
            orderCount++;
            tradedVolumeUsdc = tradedVolumeUsdc.add(safe(order.getNotionalUsdc()));
            feeUsdc = feeUsdc.add(orderFee(order));
        }

        private void addLedger(SmartWalletCashLedgerEntity entry) {
            if (entry == null || !StringUtils.hasText(entry.getEntryType())) {
                return;
            }
            if ("REALIZED_PNL".equalsIgnoreCase(entry.getEntryType())) {
                realizedPnlUsdc = realizedPnlUsdc.add(ledgerPnlDelta(entry));
            }
        }

        private void addExposure(SmartWalletPositionEntity position, BigDecimal exposurePnl) {
            if (position == null) {
                return;
            }
            pendingSettlementPnlUsdc = pendingSettlementPnlUsdc.add(safe(exposurePnl));
        }

        private BigDecimal netPnl() {
            return realizedPnlUsdc.subtract(feeUsdc).add(pendingSettlementPnlUsdc);
        }

        private AttributionRow toView() {
            return new AttributionRow(
                    key,
                    label,
                    orderCount,
                    tradedVolumeUsdc,
                    realizedPnlUsdc,
                    pendingSettlementPnlUsdc,
                    feeUsdc,
                    netPnl()
            );
        }
    }

    private static class ConflictAccumulator {
        private final String marketId;
        private final String question;
        private final Set<String> outcomes = new LinkedHashSet<>();
        private int positionCount;
        private int openPositionCount;
        private BigDecimal realizedPnlUsdc = BigDecimal.ZERO;
        private BigDecimal unrealizedPnlUsdc = BigDecimal.ZERO;
        private Date latestClosedAt;
        private Long marketEndAt;
        private String marketEndAtBjt;

        private ConflictAccumulator(String marketId, String question) {
            this.marketId = marketId;
            this.question = question;
        }

        private void add(SmartWalletPositionEntity position, BigDecimal realizedPnl) {
            positionCount++;
            if (StringUtils.hasText(position.getOutcome())) {
                outcomes.add(position.getOutcome());
            }
            if ("OPEN".equalsIgnoreCase(position.getStatus()) || "PARTIALLY_CLOSED".equalsIgnoreCase(position.getStatus())) {
                openPositionCount++;
            }
            realizedPnlUsdc = realizedPnlUsdc.add(realizedPnl == null ? BigDecimal.ZERO : realizedPnl);
            unrealizedPnlUsdc = unrealizedPnlUsdc.add(position.getUnrealizedPnlUsdc() == null ? BigDecimal.ZERO : position.getUnrealizedPnlUsdc());
            if (position.getClosedAt() != null && (latestClosedAt == null || position.getClosedAt().after(latestClosedAt))) {
                latestClosedAt = position.getClosedAt();
            }
            if (marketEndAt == null || marketEndAt <= 0) {
                marketEndAt = position.getMarketEndAt();
                marketEndAtBjt = position.getMarketEndAt() == null ? null : BJT_FORMATTER.format(Instant.ofEpochMilli(position.getMarketEndAt()));
            }
        }

        private ConflictMarketRow toView() {
            List<String> sortedOutcomes = new ArrayList<>(outcomes);
            sortedOutcomes.sort(Comparator.naturalOrder());
            return new ConflictMarketRow(
                    marketId,
                    question,
                    outcomes.size(),
                    positionCount,
                    openPositionCount,
                    sortedOutcomes,
                    realizedPnlUsdc,
                    unrealizedPnlUsdc,
                    realizedPnlUsdc.add(unrealizedPnlUsdc),
                    marketEndAt,
                    marketEndAtBjt,
                    latestClosedAt
            );
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Cash Ledger
    // ════════════════════════════════════════════════════════════════

    public List<SmartWalletCashLedgerView> cashLedger(int limit) {
        List<SmartWalletCashLedgerEntity> items = cashLedgerMapper.selectList(
                new LambdaQueryWrapper<SmartWalletCashLedgerEntity>()
                        .orderByDesc(SmartWalletCashLedgerEntity::getOccurredAt)
                        .last("limit " + Math.max(limit, 1)));
        List<SmartWalletCashLedgerView> result = new ArrayList<>();
        for (SmartWalletCashLedgerEntity item : items) {
            result.add(new SmartWalletCashLedgerView(item.getId(), item.getExecutionAddress(), item.getAsset(),
                    item.getEntryType(), item.getDirection(), item.getAmount(), item.getBalanceAfter(),
                    item.getReferenceType(), item.getReferenceId(), item.getMarketId(), item.getQuestion(),
                    null, item.getOccurredAt()));
        }
        return result;
    }

    // ════════════════════════════════════════════════════════════════
    // Position History (paged)
    // ════════════════════════════════════════════════════════════════

    public com.metaverse.coin.strategy.common.service.smartwallet.model.OffsetPageView<SmartWalletPositionView> positionHistory(
            boolean closedOnly, String mode, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 200));
        int safePage = Math.max(1, page);

        List<String> statuses = closedOnly
                ? List.of("CLOSED", "RESOLVED_UNCLAIMED", "CLAIMED")
                : null;
        LambdaQueryWrapper<SmartWalletPositionEntity> wrapper = new LambdaQueryWrapper<>();
        if (statuses != null) {
            wrapper.in(SmartWalletPositionEntity::getStatus, statuses);
        }
        wrapper.orderByDesc(SmartWalletPositionEntity::getId);
        List<SmartWalletPositionEntity> allEntities = positionMapper.selectList(wrapper);

        // Filter by mode and map to view
        List<SmartWalletPositionView> all = allEntities.stream()
                .filter(p -> matchesDashboardMode(p.getExecutionAddress(), mode))
                .map(this::toPositionView)
                .toList();

        int totalItems = all.size();
        int fromIndex = Math.min((safePage - 1) * safeSize, totalItems);
        int toIndex = Math.min(fromIndex + safeSize, totalItems);
        List<SmartWalletPositionView> pageItems = all.subList(fromIndex, toIndex);
        return com.metaverse.coin.strategy.common.service.smartwallet.model.OffsetPageView.of(pageItems, safePage, safeSize, totalItems);
    }

    /**
     * Export all positions without pagination cap (for CSV export).
     */
    public List<SmartWalletPositionView> allPositions(boolean closedOnly, String mode) {
        List<String> statuses = closedOnly
                ? List.of("CLOSED", "RESOLVED_UNCLAIMED", "CLAIMED")
                : null;
        LambdaQueryWrapper<SmartWalletPositionEntity> wrapper = new LambdaQueryWrapper<>();
        if (statuses != null) {
            wrapper.in(SmartWalletPositionEntity::getStatus, statuses);
        }
        wrapper.orderByDesc(SmartWalletPositionEntity::getId);
        return positionMapper.selectList(wrapper).stream()
                .filter(p -> matchesDashboardMode(p.getExecutionAddress(), mode))
                .map(this::toPositionView)
                .toList();
    }

    public List<FollowAddressDailyReportRow> followAddressDailyReport(LocalDate reportDate, Integer limit, String mode) {
        LocalDate targetDate = reportDate == null ? LocalDate.now(ZoneId.systemDefault()) : reportDate;
        Date start = Date.from(targetDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
        Date end = Date.from(targetDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant());
        boolean currentDay = targetDate.equals(LocalDate.now(ZoneId.systemDefault()));

        List<SmartWalletOrderEntity> allOrders = orderMapper.selectList(new LambdaQueryWrapper<SmartWalletOrderEntity>()
                .orderByDesc(SmartWalletOrderEntity::getPlacedAt)
                .orderByDesc(SmartWalletOrderEntity::getId));
        List<SmartWalletFillEntity> allFills = fillMapper.selectList(new LambdaQueryWrapper<SmartWalletFillEntity>()
                .orderByDesc(SmartWalletFillEntity::getFilledAt)
                .orderByDesc(SmartWalletFillEntity::getId));
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .orderByDesc(SmartWalletPositionEntity::getLastFilledAt)
                .orderByDesc(SmartWalletPositionEntity::getId));
        populateMarketInfoForOrders(allOrders);
        populateMarketInfoForFills(allFills);
        populateMarketInfoForPositions(positions);

        Map<String, String> positionWalletMap = buildPositionWalletMap(allOrders, allFills);
        Map<String, FollowAddressDailyAccumulator> grouped = new LinkedHashMap<>();

        for (SmartWalletOrderEntity order : allOrders) {
            if (!matchesDashboardMode(order.getExecutionAddress(), mode) || !isWithin(order.getPlacedAt(), start, end)) {
                continue;
            }
            grouped.computeIfAbsent(normalizeWalletAddress(order.getWalletAddress()), FollowAddressDailyAccumulator::new)
                    .addOrder(order);
        }

        for (SmartWalletFillEntity fill : allFills) {
            if (!matchesDashboardMode(fill.getExecutionAddress(), mode) || !isWithin(fill.getFilledAt(), start, end)) {
                continue;
            }
            grouped.computeIfAbsent(normalizeWalletAddress(fill.getWalletAddress()), FollowAddressDailyAccumulator::new)
                    .addFill(fill);
        }

        for (SmartWalletPositionEntity position : positions) {
            if (!matchesDashboardMode(position.getExecutionAddress(), mode)) {
                continue;
            }
            String walletAddress = resolvePositionWalletAddress(position, positionWalletMap);
            FollowAddressDailyAccumulator acc = grouped.computeIfAbsent(walletAddress, FollowAddressDailyAccumulator::new);
            if (position.getClosedAt() != null && targetDate.equals(toLocalDate(position.getClosedAt()))) {
                acc.addClosedPosition(position);
            }
            if (currentDay && isActivePositionStatus(position.getStatus())) {
                markToMarket(position);
                acc.addOpenPosition(position);
            }
        }

        return grouped.values().stream()
                .sorted(Comparator.comparing(FollowAddressDailyAccumulator::netPnl).reversed())
                .limit(Math.max(normalizeLimit(limit, 100), 1))
                .map(acc -> acc.toView(java.sql.Date.valueOf(targetDate)))
                .toList();
    }

    private Map<String, String> buildPositionWalletMap(List<SmartWalletOrderEntity> orders, List<SmartWalletFillEntity> fills) {
        Map<String, String> grouped = new LinkedHashMap<>();
        if (orders != null) {
            List<SmartWalletOrderEntity> ordered = new ArrayList<>(orders);
            ordered.sort(Comparator.comparing(SmartWalletOrderEntity::getPlacedAt,
                            Comparator.nullsLast(Date::compareTo))
                    .thenComparing(SmartWalletOrderEntity::getId, Comparator.nullsLast(Long::compareTo)));
            for (SmartWalletOrderEntity order : ordered) {
                if (order == null || !StringUtils.hasText(order.getWalletAddress())) {
                    continue;
                }
                String wallet = normalizeWalletAddress(order.getWalletAddress());
                grouped.put(positionWalletKey(order.getPositionId(), order.getExecutionAddress(), order.getMarketId(), order.getOutcome()), wallet);
                grouped.put(legacyPositionKey(order.getExecutionAddress(), order.getMarketId(), order.getOutcome()), wallet);
            }
        }
        if (fills != null) {
            List<SmartWalletFillEntity> ordered = new ArrayList<>(fills);
            ordered.sort(Comparator.comparing(SmartWalletFillEntity::getFilledAt,
                            Comparator.nullsLast(Date::compareTo))
                    .thenComparing(SmartWalletFillEntity::getId, Comparator.nullsLast(Long::compareTo)));
            for (SmartWalletFillEntity fill : ordered) {
                if (fill == null || !StringUtils.hasText(fill.getWalletAddress())) {
                    continue;
                }
                String wallet = normalizeWalletAddress(fill.getWalletAddress());
                grouped.put(positionWalletKey(fill.getPositionId(), fill.getExecutionAddress(), fill.getMarketId(), fill.getOutcome()), wallet);
                grouped.put(legacyPositionKey(fill.getExecutionAddress(), fill.getMarketId(), fill.getOutcome()), wallet);
            }
        }
        return grouped;
    }

    private String resolvePositionWalletAddress(SmartWalletPositionEntity position, Map<String, String> positionWalletMap) {
        if (position == null) {
            return "UNKNOWN";
        }
        String key = positionWalletKey(position.getId(), position.getExecutionAddress(), position.getMarketId(), position.getOutcome());
        String walletAddress = positionWalletMap.get(key);
        if (walletAddress != null) {
            return walletAddress;
        }
        return positionWalletMap.getOrDefault(
                legacyPositionKey(position.getExecutionAddress(), position.getMarketId(), position.getOutcome()),
                "UNKNOWN"
        );
    }

    private String positionWalletKey(Long positionId, String executionAddress, String marketId, String outcome) {
        if (positionId != null) {
            return "position:" + positionId;
        }
        return legacyPositionKey(executionAddress, marketId, outcome);
    }

    private String legacyPositionKey(String executionAddress, String marketId, String outcome) {
        return normalizeWalletAddress(executionAddress)
                + "|"
                + (marketId == null ? "" : marketId.trim())
                + "|"
                + (outcome == null ? "" : outcome.trim().toUpperCase(Locale.ROOT));
    }

    private String normalizeWalletAddress(String walletAddress) {
        return SmartWalletReportMath.normalizeWalletAddress(walletAddress);
    }

    private boolean isWithin(Date value, Date startInclusive, Date endExclusive) {
        if (value == null || startInclusive == null || endExclusive == null) {
            return false;
        }
        return !value.before(startInclusive) && value.before(endExclusive);
    }

    private void markToMarket(SmartWalletPositionEntity position) {
        if (position == null) {
            return;
        }
        if (position.getLastMarkPrice() == null && position.getAverageEntryPrice() != null) {
            position.setLastMarkPrice(position.getAverageEntryPrice());
        }
        if (position.getUnrealizedPnlUsdc() == null) {
            position.setUnrealizedPnlUsdc(BigDecimal.ZERO);
        }
    }

    private class FollowAddressDailyAccumulator {
        private final String walletAddress;
        private int totalOrders;
        private int filledOrders;
        private int openPositions;
        private int closedPositions;
        private int winningPositions;
        private int losingPositions;
        private int breakevenPositions;
        private final Set<String> distinctMarkets = new LinkedHashSet<>();
        private BigDecimal tradedVolumeUsdc = BigDecimal.ZERO;
        private BigDecimal grossExposureUsdc = BigDecimal.ZERO;
        private BigDecimal feeUsdc = BigDecimal.ZERO;
        private BigDecimal realizedPnlUsdc = BigDecimal.ZERO;
        private BigDecimal unrealizedPnlUsdc = BigDecimal.ZERO;
        private BigDecimal totalWinsUsdc = BigDecimal.ZERO;
        private BigDecimal totalLossesUsdc = BigDecimal.ZERO;
        private BigDecimal largestWinUsdc = BigDecimal.ZERO;
        private BigDecimal largestLossUsdc = BigDecimal.ZERO;

        private FollowAddressDailyAccumulator(String walletAddress) {
            this.walletAddress = walletAddress;
        }

        private void addOrder(SmartWalletOrderEntity order) {
            if (order == null) {
                return;
            }
            totalOrders++;
            if (StringUtils.hasText(order.getMarketId())) {
                distinctMarkets.add(order.getMarketId());
            }
        }

        private void addFill(SmartWalletFillEntity fill) {
            if (fill == null) {
                return;
            }
            filledOrders++;
            tradedVolumeUsdc = tradedVolumeUsdc.add(safe(fill.getNotionalUsdc()));
            feeUsdc = feeUsdc.add(safe(fill.getFeeUsdc()));
            if (StringUtils.hasText(fill.getMarketId())) {
                distinctMarkets.add(fill.getMarketId());
            }
        }

        private void addClosedPosition(SmartWalletPositionEntity position) {
            if (position == null) {
                return;
            }
            closedPositions++;
            BigDecimal realized = safe(position.getRealizedPnlUsdc()).setScale(8, RoundingMode.HALF_UP);
            realizedPnlUsdc = realizedPnlUsdc.add(realized);
            if (StringUtils.hasText(position.getMarketId())) {
                distinctMarkets.add(position.getMarketId());
            }
            if (realized.compareTo(BigDecimal.ZERO) > 0) {
                winningPositions++;
                totalWinsUsdc = totalWinsUsdc.add(realized);
                if (realized.compareTo(largestWinUsdc) > 0) {
                    largestWinUsdc = realized;
                }
            } else if (realized.compareTo(BigDecimal.ZERO) < 0) {
                losingPositions++;
                BigDecimal absoluteLoss = realized.abs();
                totalLossesUsdc = totalLossesUsdc.add(absoluteLoss);
                if (absoluteLoss.compareTo(largestLossUsdc.abs()) > 0) {
                    largestLossUsdc = realized;
                }
            } else {
                breakevenPositions++;
            }
        }

        private void addOpenPosition(SmartWalletPositionEntity position) {
            if (position == null) {
                return;
            }
            openPositions++;
            unrealizedPnlUsdc = unrealizedPnlUsdc.add(safe(position.getUnrealizedPnlUsdc()));
            grossExposureUsdc = grossExposureUsdc.add(safe(position.getCostBasisUsdc()));
            if (StringUtils.hasText(position.getMarketId())) {
                distinctMarkets.add(position.getMarketId());
            }
        }

        private BigDecimal netPnl() {
            return realizedPnlUsdc.add(unrealizedPnlUsdc).subtract(feeUsdc);
        }

        private FollowAddressDailyReportRow toView(Date reportDate) {
            BigDecimal winRate = SmartWalletReportMath.winRate(winningPositions, losingPositions);
            BigDecimal averageWin = SmartWalletReportMath.average(totalWinsUsdc, winningPositions);
            BigDecimal averageLoss = SmartWalletReportMath.average(totalLossesUsdc, losingPositions);
            return new FollowAddressDailyReportRow(
                    reportDate,
                    walletAddress,
                    totalOrders,
                    filledOrders,
                    openPositions,
                    closedPositions,
                    winningPositions,
                    losingPositions,
                    breakevenPositions,
                    distinctMarkets.size(),
                    tradedVolumeUsdc,
                    grossExposureUsdc,
                    feeUsdc,
                    realizedPnlUsdc,
                    unrealizedPnlUsdc,
                    netPnl(),
                    winRate,
                    averageWin,
                    averageLoss,
                    largestWinUsdc,
                    largestLossUsdc
            );
        }
    }
}
