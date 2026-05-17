package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletCashLedgerMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFillMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletMarketInfoMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeOrderMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletCashLedgerEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFillEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeOrderEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletMarketLinkView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletOrderPnlCategoryAttributionView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletTradeOrderView;
import com.metaverse.coin.strategy.job.strategy.model.CursorPageView;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SmartWalletTradeOrderService {

    private static final String ORDER_TYPE_EXIT = "EXIT";
    private static final int CATEGORY_ATTRIBUTION_QUERY_LIMIT = 20_000;

    private final SmartWalletTradeOrderMapper tradeOrderMapper;
    private final SmartWalletPositionMapper positionMapper;
    private final SmartWalletOrderMapper orderMapper;
    private final SmartWalletFillMapper fillMapper;
    private final SmartWalletCashLedgerMapper cashLedgerMapper;
    private final SmartWalletMarketInfoMapper marketInfoMapper;
    private final SmartWalletMarketLinkService marketLinkService;
    private final SmartWalletTimeFormatter timeFormatter;
    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void ensureTradeOrderTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `smart_wallet_trade_order` (
                  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                  `position_id` BIGINT UNSIGNED NOT NULL,
                  `market_info_id` BIGINT UNSIGNED DEFAULT NULL,
                  `execution_address` VARCHAR(128) NOT NULL,
                  `wallet_address` VARCHAR(128) DEFAULT NULL,
                  `source_follow_task_id` BIGINT UNSIGNED DEFAULT NULL,
                  `entry_quantity` DECIMAL(20,8) NOT NULL DEFAULT 0,
                  `entry_avg_price` DECIMAL(20,8) NOT NULL DEFAULT 0,
                  `entry_cost_usdc` DECIMAL(20,8) NOT NULL DEFAULT 0,
                  `entry_fee_usdc` DECIMAL(20,8) NOT NULL DEFAULT 0,
                  `opened_at` TIMESTAMP NULL,
                  `exit_quantity` DECIMAL(20,8) NOT NULL DEFAULT 0,
                  `exit_avg_price` DECIMAL(20,8) NOT NULL DEFAULT 0,
                  `exit_proceeds_usdc` DECIMAL(20,8) NOT NULL DEFAULT 0,
                  `exit_realized_pnl_usdc` DECIMAL(20,8) NOT NULL DEFAULT 0,
                  `exit_fee_usdc` DECIMAL(20,8) NOT NULL DEFAULT 0,
                  `last_exit_at` TIMESTAMP NULL,
                  `remaining_quantity` DECIMAL(20,8) NOT NULL DEFAULT 0,
                  `remaining_cost_basis_usdc` DECIMAL(20,8) NOT NULL DEFAULT 0,
                  `last_mark_price` DECIMAL(20,8) NOT NULL DEFAULT 0,
                  `unrealized_pnl_usdc` DECIMAL(20,8) NOT NULL DEFAULT 0,
                  `settlement_price` DECIMAL(20,8) DEFAULT NULL,
                  `settlement_proceeds_usdc` DECIMAL(20,8) NOT NULL DEFAULT 0,
                  `settlement_pnl_usdc` DECIMAL(20,8) NOT NULL DEFAULT 0,
                  `settled_at` TIMESTAMP NULL,
                  `total_realized_pnl_usdc` DECIMAL(20,8) NOT NULL DEFAULT 0,
                  `total_pnl_usdc` DECIMAL(20,8) NOT NULL DEFAULT 0,
                  `roi_rate` DECIMAL(20,8) NOT NULL DEFAULT 0,
                  `status` VARCHAR(32) NOT NULL DEFAULT 'OPEN',
                  `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  PRIMARY KEY (`id`),
                  UNIQUE KEY `uk_trade_order_position` (`position_id`),
                  KEY `idx_trade_order_market` (`market_info_id`, `status`),
                  KEY `idx_trade_order_execution` (`execution_address`, `status`, `opened_at`),
                  KEY `idx_trade_order_exec_id` (`execution_address`, `id` DESC),
                  KEY `idx_trade_order_status` (`status`, `updated_at`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        // 存量表可能缺少 idx_trade_order_exec_id，补建
        try {
            jdbcTemplate.execute("""
                    ALTER TABLE `smart_wallet_trade_order`
                    ADD INDEX IF NOT EXISTS `idx_trade_order_exec_id` (`execution_address`, `id` DESC)
                    """);
        } catch (Exception ignored) {
            // 索引已存在或不支持 IF NOT EXISTS，忽略
        }
    }

    public SmartWalletTradeOrderEntity upsertFromPosition(Long positionId) {
        if (positionId == null) {
            return null;
        }
        SmartWalletPositionEntity position = positionMapper.selectById(positionId);
        if (position == null) {
            return null;
        }
        SmartWalletMarketInfoEntity marketInfo = position.getMarketInfoId() == null ? null : marketInfoMapper.selectById(position.getMarketInfoId());
        position.setMarketInfo(marketInfo);

        List<SmartWalletOrderEntity> orders = orderMapper.selectList(new LambdaQueryWrapper<SmartWalletOrderEntity>()
                .eq(SmartWalletOrderEntity::getPositionId, positionId)
                .orderByAsc(SmartWalletOrderEntity::getPlacedAt)
                .orderByAsc(SmartWalletOrderEntity::getId));
        List<SmartWalletFillEntity> fills = fillMapper.selectList(new LambdaQueryWrapper<SmartWalletFillEntity>()
                .eq(SmartWalletFillEntity::getPositionId, positionId)
                .orderByAsc(SmartWalletFillEntity::getFilledAt)
                .orderByAsc(SmartWalletFillEntity::getId));
        List<SmartWalletCashLedgerEntity> ledgers = cashLedgerMapper.selectList(new LambdaQueryWrapper<SmartWalletCashLedgerEntity>()
                .eq(SmartWalletCashLedgerEntity::getReferenceType, "POSITION")
                .eq(SmartWalletCashLedgerEntity::getReferenceId, positionId)
                .orderByAsc(SmartWalletCashLedgerEntity::getOccurredAt)
                .orderByAsc(SmartWalletCashLedgerEntity::getId));

        SmartWalletTradeOrderEntity entity = buildEntity(position, marketInfo, orders, fills, ledgers);
        SmartWalletTradeOrderEntity existing = tradeOrderMapper.selectOne(new LambdaQueryWrapper<SmartWalletTradeOrderEntity>()
                .eq(SmartWalletTradeOrderEntity::getPositionId, positionId)
                .last("limit 1"));
        Date now = new Date();
        if (existing == null) {
            entity.setCreatedAt(now).setUpdatedAt(now);
            tradeOrderMapper.insert(entity);
            return entity;
        }
        entity.setId(existing.getId())
                .setCreatedAt(existing.getCreatedAt())
                .setUpdatedAt(now);
        tradeOrderMapper.updateById(entity);
        return entity;
    }

    public int rebuildByPositionIds(List<Long> positionIds) {
        if (positionIds == null || positionIds.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Long positionId : positionIds.stream().filter(Objects::nonNull).distinct().toList()) {
            if (upsertFromPosition(positionId) != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * Detect trade orders stuck in OPEN while their positions are already terminal
     * (CLOSED / RESOLVED_UNCLAIMED / CLAIMED), and rebuild them.
     */
    public int repairStaleTradeOrders() {
        List<Long> stalePositionIds = tradeOrderMapper.findStaleOpenPositionIds();
        if (stalePositionIds.isEmpty()) {
            return 0;
        }
        return rebuildByPositionIds(stalePositionIds);
    }

    public int rebuildAll(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 10_000));
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .orderByDesc(SmartWalletPositionEntity::getId)
                .last("limit " + safeLimit));
        return rebuildByPositionIds(positions.stream().map(SmartWalletPositionEntity::getId).toList());
    }

    public CursorPageView<SmartWalletTradeOrderView> listTradeOrdersCursor(Integer limit,
                                                                           Long lastId,
                                                                           String mode,
                                                                           String pnl,
                                                                           String settlement,
                                                                           String status,
                                                                           String q) {
        int safeLimit = Math.max(1, Math.min(limit == null ? 50 : limit, 200));
        backfillIfTradeOrderTableEmpty(lastId);
        return queryTradeOrdersCursor(safeLimit, lastId, mode, pnl, settlement, status, q);
    }

    public SmartWalletOrderPnlCategoryAttributionView orderPnlCategoryAttribution(String mode, Integer limit) {
        int safeLimit = Math.max(1, Math.min(limit == null ? 12 : limit, 30));
        backfillIfTradeOrderTableEmpty(null);
        LambdaQueryWrapper<SmartWalletTradeOrderEntity> wrapper = new LambdaQueryWrapper<SmartWalletTradeOrderEntity>()
                .orderByDesc(SmartWalletTradeOrderEntity::getId)
                .last("limit " + CATEGORY_ATTRIBUTION_QUERY_LIMIT);
        if ("PAPER".equalsIgnoreCase(mode)) {
            wrapper.eq(SmartWalletTradeOrderEntity::getExecutionAddress, "paper-book");
        } else if ("LIVE".equalsIgnoreCase(mode)) {
            wrapper.ne(SmartWalletTradeOrderEntity::getExecutionAddress, "paper-book");
        }
        List<SmartWalletTradeOrderEntity> tradeOrders = tradeOrderMapper.selectList(wrapper);
        populateMarketInfo(tradeOrders);
        List<WindowSpec> windowSpecs = List.of(
                new WindowSpec("30d", "30 天", 30),
                new WindowSpec("7d", "7 天", 7),
                new WindowSpec("all", "全部", null)
        );
        List<SmartWalletOrderPnlCategoryAttributionView.WindowBucket> windows = windowSpecs.stream()
                .map(window -> buildCategoryWindow(window, tradeOrders, safeLimit))
                .toList();
        return SmartWalletOrderPnlCategoryAttributionView.builder()
                .windows(windows)
                .build();
    }

    private void backfillIfTradeOrderTableEmpty(Long lastId) {
        if (lastId != null && lastId > 0) {
            return;
        }
        Long tradeOrderCount = tradeOrderMapper.selectCount(new LambdaQueryWrapper<SmartWalletTradeOrderEntity>());
        if (tradeOrderCount != null && tradeOrderCount > 0) {
            return;
        }
        Long positionCount = positionMapper.selectCount(new LambdaQueryWrapper<SmartWalletPositionEntity>());
        if (positionCount == null || positionCount <= 0) {
            return;
        }
        rebuildAll(Math.toIntExact(Math.min(positionCount, 10_000L)));
    }

    private void populateMarketInfo(List<SmartWalletTradeOrderEntity> tradeOrders) {
        if (tradeOrders == null || tradeOrders.isEmpty()) {
            return;
        }
        Set<Long> marketInfoIds = tradeOrders.stream()
                .map(SmartWalletTradeOrderEntity::getMarketInfoId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (marketInfoIds.isEmpty()) {
            return;
        }
        Map<Long, SmartWalletMarketInfoEntity> marketInfoById = marketInfoMapper.selectBatchIds(marketInfoIds).stream()
                .collect(Collectors.toMap(SmartWalletMarketInfoEntity::getId, Function.identity(), (left, right) -> left));
        tradeOrders.forEach(tradeOrder -> tradeOrder.setMarketInfo(marketInfoById.get(tradeOrder.getMarketInfoId())));
    }

    private SmartWalletOrderPnlCategoryAttributionView.WindowBucket buildCategoryWindow(WindowSpec window,
                                                                                       List<SmartWalletTradeOrderEntity> tradeOrders,
                                                                                       int limit) {
        long minOpenedAt = window.days == null
                ? Long.MIN_VALUE
                : System.currentTimeMillis() - window.days * 24L * 60L * 60L * 1000L;
        Map<String, PnlOrderCounter> marketCounters = new LinkedHashMap<>();
        Map<String, PnlOrderCounter> walletCounters = new LinkedHashMap<>();
        for (SmartWalletTradeOrderEntity tradeOrder : tradeOrders) {
            if (!withinWindow(tradeOrder, minOpenedAt) || safe(tradeOrder.getTotalPnlUsdc()).compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            String category = normalizeCategory(tradeOrder);
            marketCounters.computeIfAbsent(category, key -> new PnlOrderCounter(key, category, null, category))
                    .add(tradeOrder.getTotalPnlUsdc());

            String walletAddress = firstText(tradeOrder.getWalletAddress(), tradeOrder.getExecutionAddress(), "UNKNOWN");
            String walletCategoryKey = walletAddress + "|" + category;
            String walletCategoryLabel = shortAddress(walletAddress) + " / " + category;
            walletCounters.computeIfAbsent(walletCategoryKey,
                            key -> new PnlOrderCounter(key, walletCategoryLabel, walletAddress, category))
                    .add(tradeOrder.getTotalPnlUsdc());
        }
        return SmartWalletOrderPnlCategoryAttributionView.WindowBucket.builder()
                .window(window.key)
                .label(window.label)
                .marketCategories(toCategoryRows(marketCounters, limit))
                .walletCategories(toCategoryRows(walletCounters, limit))
                .build();
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
                .filter(counter -> counter.totalOrderCount() > 0)
                .sorted(Comparator.comparing(PnlOrderCounter::totalOrderCount).reversed()
                        .thenComparing(PnlOrderCounter::profitOrderCount, Comparator.reverseOrder())
                        .thenComparing(PnlOrderCounter::label))
                .limit(limit)
                .map(PnlOrderCounter::toView)
                .toList();
    }

    private String normalizeCategory(SmartWalletTradeOrderEntity tradeOrder) {
        String category = firstText(tradeOrder.getNormalizedCategory(), tradeOrder.getSourceCategory(), "未分类");
        return category.trim().isEmpty() ? "未分类" : category.trim();
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

    private String shortAddress(String address) {
        if (!StringUtils.hasText(address)) {
            return "UNKNOWN";
        }
        String trimmed = address.trim();
        return trimmed.length() > 14 ? trimmed.substring(0, 6) + "..." + trimmed.substring(trimmed.length() - 4) : trimmed;
    }

    private CursorPageView<SmartWalletTradeOrderView> queryTradeOrdersCursor(int safeLimit,
                                                                             Long lastId,
                                                                             String mode,
                                                                             String pnl,
                                                                             String settlement,
                                                                             String status,
                                                                             String q) {
        LambdaQueryWrapper<SmartWalletTradeOrderEntity> wrapper = new LambdaQueryWrapper<SmartWalletTradeOrderEntity>()
                .lt(lastId != null && lastId > 0, SmartWalletTradeOrderEntity::getId, lastId)
                .eq(StringUtils.hasText(status), SmartWalletTradeOrderEntity::getStatus, normalize(status))
                .orderByDesc(SmartWalletTradeOrderEntity::getId)
                .last("limit " + (safeLimit * 4));
        if ("PAPER".equalsIgnoreCase(mode)) {
            wrapper.eq(SmartWalletTradeOrderEntity::getExecutionAddress, "paper-book");
        } else if ("LIVE".equalsIgnoreCase(mode)) {
            wrapper.ne(SmartWalletTradeOrderEntity::getExecutionAddress, "paper-book");
        }
        List<SmartWalletTradeOrderEntity> entities = tradeOrderMapper.selectList(wrapper);
        // Batch-load market info to avoid N+1
        populateMarketInfo(entities);
        // Batch-load orders and fills for all position IDs
        Set<Long> positionIds = entities.stream()
                .map(SmartWalletTradeOrderEntity::getPositionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, List<SmartWalletOrderEntity>> ordersByPosition = batchLoadOrders(positionIds);
        Map<Long, List<SmartWalletFillEntity>> fillsByPosition = batchLoadFills(positionIds);
        List<SmartWalletTradeOrderView> filtered = entities.stream()
                .map(entity -> toViewBatch(entity,
                        ordersByPosition.getOrDefault(entity.getPositionId(), List.of()),
                        fillsByPosition.getOrDefault(entity.getPositionId(), List.of())))
                .filter(view -> matchesPnl(view, pnl))
                .filter(view -> matchesSettlement(view, settlement))
                .filter(view -> matchesQuery(view, q))
                .limit(safeLimit + 1L)
                .toList();
        boolean hasMore = filtered.size() > safeLimit;
        List<SmartWalletTradeOrderView> items = hasMore ? filtered.subList(0, safeLimit) : filtered;
        String nextCursor = hasMore && !items.isEmpty() ? String.valueOf(items.get(items.size() - 1).getId()) : null;
        return CursorPageView.of(items, nextCursor);
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

    private SmartWalletTradeOrderView toViewBatch(SmartWalletTradeOrderEntity entity,
                                                  List<SmartWalletOrderEntity> orders,
                                                  List<SmartWalletFillEntity> fills) {
        // Fast path: build URLs from stored slug — zero HTTP calls.
        // resolve() triggers per-entity API calls and caused >30s page timeouts.
        SmartWalletMarketLinkView link;
        if (StringUtils.hasText(entity.getMarketSlug())) {
            link = marketLinkService.fromStoredSlug(entity.getMarketId(), entity.getQuestion(), entity.getOutcome(), entity.getMarketSlug());
        } else if (StringUtils.hasText(entity.getMarketId())) {
            link = marketLinkService.resolveCacheOnly(entity.getMarketId(), entity.getQuestion(), entity.getOutcome());
        } else {
            link = null;
        }
        return SmartWalletTradeOrderView.builder()
                .id(entity.getId())
                .positionId(entity.getPositionId())
                .marketInfoId(entity.getMarketInfoId())
                .executionAddress(entity.getExecutionAddress())
                .walletAddress(entity.getWalletAddress())
                .sourceFollowTaskId(entity.getSourceFollowTaskId())
                .marketId(entity.getMarketId())
                .marketSlug(entity.getMarketSlug())
                .marketUrl(link == null ? null : link.getMarketUrl())
                .externalMarketUrl(link == null ? null : link.getExternalMarketUrl())
                .internalMarketUrl(link == null ? null : link.getInternalMarketUrl())
                .marketLinkStatus(link == null ? null : link.getLinkStatus())
                .tokenId(entity.getTokenId())
                .question(entity.getQuestion())
                .outcome(entity.getOutcome())
                .marketEndAt(entity.getMarketEndAt())
                .marketEndAtBjt(timeFormatter.formatEpochMillisToBjt(entity.getMarketEndAt()))
                .marketLifecycle(entity.getMarketLifecycle())
                .activeMarket(isActiveMarket(entity))
                .closed(Boolean.TRUE.equals(entity.getClosed()))
                .acceptingOrders(entity.getAcceptingOrders() == null || entity.getAcceptingOrders())
                .normalizedCategory(entity.getNormalizedCategory())
                .sourceCategory(entity.getSourceCategory())
                .entryQuantity(entity.getEntryQuantity())
                .entryAvgPrice(entity.getEntryAvgPrice())
                .entryCostUsdc(entity.getEntryCostUsdc())
                .entryFeeUsdc(entity.getEntryFeeUsdc())
                .openedAt(entity.getOpenedAt())
                .exitQuantity(entity.getExitQuantity())
                .exitAvgPrice(entity.getExitAvgPrice())
                .exitProceedsUsdc(entity.getExitProceedsUsdc())
                .exitRealizedPnlUsdc(entity.getExitRealizedPnlUsdc())
                .exitFeeUsdc(entity.getExitFeeUsdc())
                .lastExitAt(entity.getLastExitAt())
                .remainingQuantity(entity.getRemainingQuantity())
                .remainingCostBasisUsdc(entity.getRemainingCostBasisUsdc())
                .lastMarkPrice(entity.getLastMarkPrice())
                .unrealizedPnlUsdc(entity.getUnrealizedPnlUsdc())
                .settlementPrice(entity.getSettlementPrice())
                .settlementProceedsUsdc(entity.getSettlementProceedsUsdc())
                .settlementPnlUsdc(entity.getSettlementPnlUsdc())
                .settledAt(entity.getSettledAt())
                .totalRealizedPnlUsdc(entity.getTotalRealizedPnlUsdc())
                .totalPnlUsdc(entity.getTotalPnlUsdc())
                .roiRate(entity.getRoiRate())
                .status(entity.getStatus())
                .entryOrderIds(orders.stream().filter(order -> !isExitOrder(order)).map(SmartWalletOrderEntity::getId).toList())
                .exitOrderIds(orders.stream().filter(this::isExitOrder).map(SmartWalletOrderEntity::getId).toList())
                .fillIds(fills.stream().map(SmartWalletFillEntity::getId).toList())
                .build();
    }

    private SmartWalletTradeOrderEntity buildEntity(SmartWalletPositionEntity position,
                                                    SmartWalletMarketInfoEntity marketInfo,
                                                    List<SmartWalletOrderEntity> orders,
                                                    List<SmartWalletFillEntity> fills,
                                                    List<SmartWalletCashLedgerEntity> ledgers) {
        List<SmartWalletFillEntity> entryFills = fills.stream()
                .filter(fill -> fill != null && !"SELL".equalsIgnoreCase(fill.getSide()))
                .toList();
        List<SmartWalletFillEntity> exitFills = fills.stream()
                .filter(fill -> fill != null && "SELL".equalsIgnoreCase(fill.getSide()))
                .toList();
        List<SmartWalletOrderEntity> entryOrders = orders.stream()
                .filter(order -> order != null && isFilledOrder(order) && !isExitOrder(order))
                .toList();
        List<SmartWalletOrderEntity> exitOrders = orders.stream()
                .filter(order -> order != null && isFilledOrder(order) && isExitOrder(order))
                .toList();
        BigDecimal entryQuantity = entryFills.isEmpty()
                ? sum(entryOrders.stream().map(SmartWalletOrderEntity::getSize).toList())
                : sum(entryFills.stream().map(SmartWalletFillEntity::getSize).toList());
        BigDecimal entryCost = entryFills.isEmpty()
                ? sum(entryOrders.stream().map(SmartWalletOrderEntity::getNotionalUsdc).toList())
                : sum(entryFills.stream().map(SmartWalletFillEntity::getNotionalUsdc).toList());
        BigDecimal entryFee = entryFills.isEmpty()
                ? sum(entryOrders.stream().map(SmartWalletOrderEntity::getFeeUsdc).toList())
                : sum(entryFills.stream().map(SmartWalletFillEntity::getFeeUsdc).toList());
        BigDecimal exitQuantity = exitFills.isEmpty()
                ? sum(exitOrders.stream().map(SmartWalletOrderEntity::getSize).toList())
                : sum(exitFills.stream().map(SmartWalletFillEntity::getSize).toList());
        BigDecimal exitProceeds = exitFills.isEmpty()
                ? sum(exitOrders.stream().map(SmartWalletOrderEntity::getNotionalUsdc).toList())
                : sum(exitFills.stream().map(SmartWalletFillEntity::getNotionalUsdc).toList());
        BigDecimal exitFee = exitFills.isEmpty()
                ? sum(exitOrders.stream().map(SmartWalletOrderEntity::getFeeUsdc).toList())
                : sum(exitFills.stream().map(SmartWalletFillEntity::getFeeUsdc).toList());
        BigDecimal exitRealized = exitFills.isEmpty()
                ? sum(exitOrders.stream().map(SmartWalletOrderEntity::getRealizedPnlUsdc).toList())
                : sum(exitFills.stream().map(SmartWalletFillEntity::getRealizedPnlUsdc).toList());
        BigDecimal totalRealized = safe(position.getRealizedPnlUsdc());
        BigDecimal unrealized = safe(position.getUnrealizedPnlUsdc());
        BigDecimal totalPnl = totalRealized.add(unrealized).setScale(8, RoundingMode.HALF_UP);
        BigDecimal settlementProceeds = sumLedger(ledgers, "SETTLEMENT_PROCEEDS");
        BigDecimal settlementPnl = totalRealized.subtract(exitRealized).setScale(8, RoundingMode.HALF_UP);
        // Position is settled if status is RESOLVED_UNCLAIMED/CLAIMED,
        // or if CLOSED with no exit fills (closed through market resolution, not user selling)
        boolean settled = isSettled(position.getStatus())
                || ("CLOSED".equalsIgnoreCase(position.getStatus()) && exitFills.isEmpty());
        BigDecimal settlementPrice = settled && safe(position.getLastMarkPrice()).compareTo(BigDecimal.ZERO) >= 0
                ? safe(position.getLastMarkPrice())
                : null;
        BigDecimal roiBase = entryCost.compareTo(BigDecimal.ZERO) > 0 ? entryCost : safe(position.getOriginalCostBasisUsdc());
        BigDecimal roiRate = roiBase.compareTo(BigDecimal.ZERO) > 0
                ? totalPnl.divide(roiBase, 8, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        SmartWalletOrderEntity firstOrder = orders.stream().filter(Objects::nonNull).findFirst().orElse(null);
        Date openedAt = firstDate(position.getOpenedAt(), firstFillAt(entryFills), firstOrderDate(entryOrders), firstOrder == null ? null : firstOrder.getPlacedAt());
        Date lastExitAt = firstDate(lastFillAt(exitFills), lastOrderDate(exitOrders));
        return new SmartWalletTradeOrderEntity()
                .setPositionId(position.getId())
                .setMarketInfoId(position.getMarketInfoId())
                .setMarketInfo(marketInfo)
                .setExecutionAddress(position.getExecutionAddress())
                .setWalletAddress(firstOrder == null ? null : firstOrder.getWalletAddress())
                .setSourceFollowTaskId(firstOrder == null ? null : firstOrder.getFollowTaskId())
                // W1-T3: 聚合订单 trace_id 取首笔 CLOB 单的 trace_id
                .setTraceId(firstOrder == null ? null : firstOrder.getTraceId())
                .setEntryQuantity(entryQuantity)
                .setEntryAvgPrice(weightedAverage(entryCost, entryQuantity))
                .setEntryCostUsdc(entryCost)
                .setEntryFeeUsdc(entryFee)
                .setOpenedAt(openedAt)
                .setExitQuantity(exitQuantity)
                .setExitAvgPrice(weightedAverage(exitProceeds, exitQuantity))
                .setExitProceedsUsdc(exitProceeds)
                .setExitRealizedPnlUsdc(exitRealized)
                .setExitFeeUsdc(exitFee)
                .setLastExitAt(lastExitAt)
                .setRemainingQuantity(safe(position.getQuantity()))
                .setRemainingCostBasisUsdc(safe(position.getCostBasisUsdc()))
                .setLastMarkPrice(safe(position.getLastMarkPrice()))
                .setUnrealizedPnlUsdc(unrealized)
                .setSettlementPrice(settlementPrice)
                .setSettlementProceedsUsdc(settlementProceeds)
                .setSettlementPnlUsdc(settled ? settlementPnl : BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP))
                .setSettledAt(settled ? position.getClosedAt() : null)
                .setTotalRealizedPnlUsdc(totalRealized)
                .setTotalPnlUsdc(totalPnl)
                .setRoiRate(roiRate)
                .setStatus(resolveTradeOrderStatus(position, exitQuantity));
    }

    private SmartWalletTradeOrderView toView(SmartWalletTradeOrderEntity entity) {
        SmartWalletMarketInfoEntity marketInfo = entity.getMarketInfoId() == null ? null : marketInfoMapper.selectById(entity.getMarketInfoId());
        entity.setMarketInfo(marketInfo);
        // Same fast-path as toViewBatch: avoid per-entity HTTP calls.
        SmartWalletMarketLinkView link;
        if (StringUtils.hasText(entity.getMarketSlug())) {
            link = marketLinkService.fromStoredSlug(entity.getMarketId(), entity.getQuestion(), entity.getOutcome(), entity.getMarketSlug());
        } else if (StringUtils.hasText(entity.getMarketId())) {
            link = marketLinkService.resolveCacheOnly(entity.getMarketId(), entity.getQuestion(), entity.getOutcome());
        } else {
            link = null;
        }
        List<SmartWalletOrderEntity> orders = orderMapper.selectList(new LambdaQueryWrapper<SmartWalletOrderEntity>()
                .eq(SmartWalletOrderEntity::getPositionId, entity.getPositionId())
                .orderByAsc(SmartWalletOrderEntity::getPlacedAt)
                .orderByAsc(SmartWalletOrderEntity::getId));
        List<SmartWalletFillEntity> fills = fillMapper.selectList(new LambdaQueryWrapper<SmartWalletFillEntity>()
                .eq(SmartWalletFillEntity::getPositionId, entity.getPositionId())
                .orderByAsc(SmartWalletFillEntity::getFilledAt)
                .orderByAsc(SmartWalletFillEntity::getId));
        return SmartWalletTradeOrderView.builder()
                .id(entity.getId())
                .positionId(entity.getPositionId())
                .marketInfoId(entity.getMarketInfoId())
                .executionAddress(entity.getExecutionAddress())
                .walletAddress(entity.getWalletAddress())
                .sourceFollowTaskId(entity.getSourceFollowTaskId())
                .marketId(entity.getMarketId())
                .marketSlug(entity.getMarketSlug())
                .marketUrl(link == null ? null : link.getMarketUrl())
                .externalMarketUrl(link == null ? null : link.getExternalMarketUrl())
                .internalMarketUrl(link == null ? null : link.getInternalMarketUrl())
                .marketLinkStatus(link == null ? null : link.getLinkStatus())
                .tokenId(entity.getTokenId())
                .question(entity.getQuestion())
                .outcome(entity.getOutcome())
                .marketEndAt(entity.getMarketEndAt())
                .marketEndAtBjt(timeFormatter.formatEpochMillisToBjt(entity.getMarketEndAt()))
                .marketLifecycle(entity.getMarketLifecycle())
                .activeMarket(isActiveMarket(entity))
                .closed(Boolean.TRUE.equals(entity.getClosed()))
                .acceptingOrders(entity.getAcceptingOrders() == null || entity.getAcceptingOrders())
                .normalizedCategory(entity.getNormalizedCategory())
                .sourceCategory(entity.getSourceCategory())
                .entryQuantity(entity.getEntryQuantity())
                .entryAvgPrice(entity.getEntryAvgPrice())
                .entryCostUsdc(entity.getEntryCostUsdc())
                .entryFeeUsdc(entity.getEntryFeeUsdc())
                .openedAt(entity.getOpenedAt())
                .exitQuantity(entity.getExitQuantity())
                .exitAvgPrice(entity.getExitAvgPrice())
                .exitProceedsUsdc(entity.getExitProceedsUsdc())
                .exitRealizedPnlUsdc(entity.getExitRealizedPnlUsdc())
                .exitFeeUsdc(entity.getExitFeeUsdc())
                .lastExitAt(entity.getLastExitAt())
                .remainingQuantity(entity.getRemainingQuantity())
                .remainingCostBasisUsdc(entity.getRemainingCostBasisUsdc())
                .lastMarkPrice(entity.getLastMarkPrice())
                .unrealizedPnlUsdc(entity.getUnrealizedPnlUsdc())
                .settlementPrice(entity.getSettlementPrice())
                .settlementProceedsUsdc(entity.getSettlementProceedsUsdc())
                .settlementPnlUsdc(entity.getSettlementPnlUsdc())
                .settledAt(entity.getSettledAt())
                .totalRealizedPnlUsdc(entity.getTotalRealizedPnlUsdc())
                .totalPnlUsdc(entity.getTotalPnlUsdc())
                .roiRate(entity.getRoiRate())
                .status(entity.getStatus())
                .entryOrderIds(orders.stream().filter(order -> !isExitOrder(order)).map(SmartWalletOrderEntity::getId).toList())
                .exitOrderIds(orders.stream().filter(this::isExitOrder).map(SmartWalletOrderEntity::getId).toList())
                .fillIds(fills.stream().map(SmartWalletFillEntity::getId).toList())
                .build();
    }

    private boolean isFilledOrder(SmartWalletOrderEntity order) {
        return order != null && "FILLED".equalsIgnoreCase(order.getStatus());
    }

    private boolean isExitOrder(SmartWalletOrderEntity order) {
        if (order == null) return false;
        return ORDER_TYPE_EXIT.equalsIgnoreCase(order.getOrderType()) || "SELL".equalsIgnoreCase(order.getSide());
    }

    private String resolveTradeOrderStatus(SmartWalletPositionEntity position, BigDecimal exitQuantity) {
        String status = normalize(position.getStatus());
        if ("OPEN".equals(status) && safe(exitQuantity).compareTo(BigDecimal.ZERO) > 0) {
            return "PARTIALLY_EXITED";
        }
        if ("RESOLVED_UNCLAIMED".equals(status) || "CLAIMED".equals(status)) {
            return "SETTLED";
        }
        if ("CLOSED".equals(status)) {
            // No exit fills → closed through market resolution (settlement), not user selling
            if (safe(exitQuantity).compareTo(BigDecimal.ZERO) <= 0) {
                return "SETTLED";
            }
            return "EXITED";
        }
        return status;
    }

    private boolean matchesPnl(SmartWalletTradeOrderView view, String pnl) {
        String normalized = normalize(pnl);
        BigDecimal value = safe(view.getTotalPnlUsdc());
        if ("PROFIT".equals(normalized)) return value.compareTo(BigDecimal.ZERO) > 0;
        if ("LOSS".equals(normalized)) return value.compareTo(BigDecimal.ZERO) < 0;
        if ("ZERO".equals(normalized)) return value.compareTo(BigDecimal.ZERO) == 0;
        return true;
    }

    private boolean matchesSettlement(SmartWalletTradeOrderView view, String settlement) {
        String normalized = normalize(settlement);
        if ("SETTLED".equals(normalized)) return isSettled(view.getStatus());
        if ("UNSETTLED".equals(normalized)) return !isSettled(view.getStatus());
        if ("EARLY_EXIT".equals(normalized)) return safe(view.getExitQuantity()).compareTo(BigDecimal.ZERO) > 0;
        return true;
    }

    private boolean matchesQuery(SmartWalletTradeOrderView view, String q) {
        if (!StringUtils.hasText(q)) return true;
        String needle = q.trim().toLowerCase(Locale.ROOT);
        return contains(view.getQuestion(), needle)
                || contains(view.getOutcome(), needle)
                || contains(view.getMarketId(), needle)
                || contains(view.getStatus(), needle);
    }

    private boolean isActiveMarket(SmartWalletTradeOrderEntity entity) {
        if (!"ACTIVE".equalsIgnoreCase(entity.getMarketLifecycle())) return false;
        if (Boolean.TRUE.equals(entity.getClosed())) return false;
        if (entity.getAcceptingOrders() != null && !entity.getAcceptingOrders()) return false;
        return true;
    }

    private boolean contains(String value, String needle) {
        return StringUtils.hasText(value) && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private boolean isSettled(String status) {
        String normalized = normalize(status);
        return "RESOLVED_UNCLAIMED".equals(normalized) || "CLAIMED".equals(normalized)
                || "SETTLED".equals(normalized);
    }

    private BigDecimal sumLedger(List<SmartWalletCashLedgerEntity> ledgers, String entryType) {
        BigDecimal total = BigDecimal.ZERO;
        for (SmartWalletCashLedgerEntity ledger : ledgers) {
            if (ledger != null && entryType.equalsIgnoreCase(ledger.getEntryType())) {
                total = total.add(safe(ledger.getAmount()));
            }
        }
        return total.setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal sum(List<BigDecimal> values) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            total = total.add(safe(value));
        }
        return total.setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP) : value.setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal weightedAverage(BigDecimal notional, BigDecimal quantity) {
        BigDecimal safeQuantity = safe(quantity);
        if (safeQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        }
        return safe(notional).divide(safeQuantity, 8, RoundingMode.HALF_UP);
    }

    private Date firstDate(Date... dates) {
        for (Date date : dates) {
            if (date != null) return date;
        }
        return null;
    }

    private Date firstFillAt(List<SmartWalletFillEntity> fills) {
        return fills.stream().map(SmartWalletFillEntity::getFilledAt).filter(Objects::nonNull).min(Date::compareTo).orElse(null);
    }

    private Date lastFillAt(List<SmartWalletFillEntity> fills) {
        return fills.stream().map(SmartWalletFillEntity::getFilledAt).filter(Objects::nonNull).max(Date::compareTo).orElse(null);
    }

    private Date firstOrderDate(List<SmartWalletOrderEntity> orders) {
        return orders.stream()
                .map(order -> firstDate(order.getFilledAt(), order.getPlacedAt()))
                .filter(Objects::nonNull)
                .min(Date::compareTo)
                .orElse(null);
    }

    private Date lastOrderDate(List<SmartWalletOrderEntity> orders) {
        return orders.stream()
                .map(order -> firstDate(order.getFilledAt(), order.getPlacedAt()))
                .filter(Objects::nonNull)
                .max(Date::compareTo)
                .orElse(null);
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "ALL";
    }

    private record WindowSpec(String key, String label, Integer days) {
    }

    private static class PnlOrderCounter {
        private final String key;
        private final String label;
        private final String walletAddress;
        private final String category;
        private int profitOrderCount;
        private int lossOrderCount;

        private PnlOrderCounter(String key, String label, String walletAddress, String category) {
            this.key = key;
            this.label = label;
            this.walletAddress = walletAddress;
            this.category = category;
        }

        private void add(BigDecimal pnl) {
            if (pnl == null) {
                return;
            }
            int compared = pnl.compareTo(BigDecimal.ZERO);
            if (compared > 0) {
                profitOrderCount++;
            } else if (compared < 0) {
                lossOrderCount++;
            }
        }

        private int profitOrderCount() {
            return profitOrderCount;
        }

        private int totalOrderCount() {
            return profitOrderCount + lossOrderCount;
        }

        private String label() {
            return label;
        }

        private SmartWalletOrderPnlCategoryAttributionView.OrderPnlCategoryRow toView() {
            return SmartWalletOrderPnlCategoryAttributionView.OrderPnlCategoryRow.builder()
                    .key(key)
                    .label(label)
                    .walletAddress(walletAddress)
                    .category(category)
                    .profitOrderCount(profitOrderCount)
                    .lossOrderCount(lossOrderCount)
                    .totalOrderCount(totalOrderCount())
                    .build();
        }
    }
}
