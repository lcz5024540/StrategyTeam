package com.metaverse.coin.strategy.job.smartwallet.service;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletMarketInfoService;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletLeaderTradeOutcomeMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletProfileMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeOrderMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletLeaderTradeOutcomeEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeOrderEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.LeaderTradeOutcomeCoverageView;
import com.metaverse.coin.strategy.job.smartwallet.model.LeaderTradeOutcomeRebuildResult;
import com.metaverse.coin.strategy.job.smartwallet.model.LeaderTradeOutcomeSummaryView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class LeaderTradeOutcomeRebuildService {

    public static final String STATUS_CLOSED_WIN = "CLOSED_WIN";
    public static final String STATUS_CLOSED_LOSS = "CLOSED_LOSS";
    public static final String STATUS_CLOSED_BREAKEVEN = "CLOSED_BREAKEVEN";
    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_UNRESOLVED = "UNRESOLVED";
    public static final String STATUS_LOW_CONFIDENCE = "LOW_CONFIDENCE";
    private static final String DATA_SOURCE = "POLYMARKET_USER_TRADES";
    private static final BigDecimal CONFIDENCE_THRESHOLD = new BigDecimal("0.5500");

    private final SmartWalletLeaderTradeOutcomeMapper leaderOutcomeMapper;
    private final SmartWalletProfileMapper profileMapper;
    private final SmartWalletTradeOrderMapper tradeOrderMapper;
    private final SmartWalletFollowTaskMapper followTaskMapper;
    private final SmartWalletMarketInfoService marketInfoService;
    private final PolymarketApiClient polymarketApiClient;
    private final PolymarketMarketMetadataService marketMetadataService;

    /**
     * 重建 leader 钱包的成交闭环视图。
     *
     * <p><b>2026-05-27 ray@cowork（P1-8 修复）：</b> 此方法<strong>不再使用 {@code @Transactional}</strong>。
     * 原先把整个 wallet 循环（含 Polymarket HTTP 调用 + 每个 outcome 的 upsert）包在单个事务里，
     * 一旦任何 wallet 的 HTTP 请求慢，整段事务持锁不放，下游开仓链路碰到
     * {@code smart_wallet_leader_trade_outcome} / {@code smart_wallet_market_info} 就排队，
     * 最终大量 {@code Lock wait timeout}（07:48 起 25 次 ERROR + 4h 零开仓）。
     *
     * <p>现在依赖 JDBC autocommit + 单语句 {@code INSERT ... ON DUPLICATE KEY UPDATE}
     * （见 {@link SmartWalletLeaderTradeOutcomeMapper#upsert}）保证单行原子写入，
     * 持锁窗口压到毫秒级，HTTP I/O 自然落在事务之外。
     */
    public LeaderTradeOutcomeRebuildResult rebuild(String walletAddress, Integer limit) {
        List<String> wallets = resolveWallets(walletAddress, limit);
        int fetchedTrades = 0;
        int rebuiltOutcomes = 0;
        int lowConfidenceOutcomes = 0;
        int failedWallets = 0;
        for (String wallet : wallets) {
            try {
                List<PolymarketApiClient.UserTrade> trades = polymarketApiClient.fetchAllUserTrades(wallet);
                fetchedTrades += trades.size();
                List<SmartWalletLeaderTradeOutcomeEntity> outcomes = rebuildWallet(wallet, trades);
                rebuiltOutcomes += outcomes.size();
                lowConfidenceOutcomes += (int) outcomes.stream()
                        .filter(row -> STATUS_LOW_CONFIDENCE.equals(row.getOutcomeStatus()))
                        .count();
            } catch (Exception ex) {
                failedWallets++;
                log.warn("Failed to rebuild leader trade outcomes wallet={} reason={}", wallet, rootMessage(ex), ex);
            }
        }
        return new LeaderTradeOutcomeRebuildResult(
                wallets.size(),
                wallets.size() - failedWallets,
                fetchedTrades,
                rebuiltOutcomes,
                lowConfidenceOutcomes,
                failedWallets
        );
    }

    List<SmartWalletLeaderTradeOutcomeEntity> rebuildWallet(String walletAddress,
                                                            List<PolymarketApiClient.UserTrade> trades) {
        if (!StringUtils.hasText(walletAddress) || trades == null || trades.isEmpty()) {
            return List.of();
        }
        Map<String, TradeAccumulator> grouped = new LinkedHashMap<>();
        for (PolymarketApiClient.UserTrade trade : trades) {
            if (trade == null || !StringUtils.hasText(trade.getMarketId()) || !StringUtils.hasText(trade.getOutcome())) {
                continue;
            }
            String key = tradeKey(trade.getMarketId(), trade.getOutcome());
            grouped.computeIfAbsent(key, ignored -> new TradeAccumulator(walletAddress, trade.getMarketId(), trade.getOutcome()))
                    .add(trade);
        }
        List<SmartWalletLeaderTradeOutcomeEntity> built = new ArrayList<>();
        boolean probablyTruncated = trades.size() >= PolymarketApiClient.MAX_USER_TRADE_HISTORY_OFFSET;
        for (TradeAccumulator accumulator : grouped.values()) {
            if (!accumulator.shouldEmit()) {
                continue;
            }
            // 单 outcome 失败（含锁等待）不应连累同一 wallet 的其它 outcome；
            // upsert 是单语句 + autocommit，即便回滚也只丢这一行，无需手工重试事务。
            try {
                SmartWalletLeaderTradeOutcomeEntity entity = buildOutcome(accumulator, probablyTruncated);
                upsert(entity);
                built.add(entity);
            } catch (PessimisticLockingFailureException lockEx) {
                // 覆盖 CannotAcquireLockException（Lock wait timeout）、DeadlockLoserDataAccessException 等子类
                log.warn("Lock contention skipping outcome wallet={} market={} outcome={} reason={}",
                        walletAddress, accumulator.marketId, accumulator.outcome, rootMessage(lockEx));
            } catch (Exception ex) {
                log.warn("Failed to persist outcome wallet={} market={} outcome={} reason={}",
                        walletAddress, accumulator.marketId, accumulator.outcome, rootMessage(ex), ex);
            }
        }
        return built;
    }

/**
 * 按钱包地址汇总指定窗口的交易结果，按预估盈亏降序排序
 * @param walletAddress 钱包地址
 * @param window 时间窗口标识
 * @return 汇总视图列表
 */
    public List<LeaderTradeOutcomeSummaryView> summarize(String walletAddress, String window) {
        List<SmartWalletLeaderTradeOutcomeEntity> rows = listOutcomes(walletAddress, cutoffMillis(window));
        Map<String, List<SmartWalletLeaderTradeOutcomeEntity>> byWallet = rows.stream()
                .collect(Collectors.groupingBy(SmartWalletLeaderTradeOutcomeEntity::getWalletAddress, LinkedHashMap::new, Collectors.toList()));
        return byWallet.entrySet().stream()
                .map(entry -> toSummary(entry.getKey(), normalizeWindow(window), entry.getValue()))
                .sorted(Comparator.comparing(LeaderTradeOutcomeSummaryView::estimatedPnlUsdc,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public List<LeaderTradeOutcomeCoverageView> coverage(String walletAddress) {
        List<SmartWalletLeaderTradeOutcomeEntity> rows = listOutcomes(walletAddress, null);
        Map<String, List<SmartWalletLeaderTradeOutcomeEntity>> byWallet = rows.stream()
                .collect(Collectors.groupingBy(SmartWalletLeaderTradeOutcomeEntity::getWalletAddress, LinkedHashMap::new, Collectors.toList()));
        return byWallet.entrySet().stream()
                .map(entry -> toCoverage(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(LeaderTradeOutcomeCoverageView::leaderPnlUsdc,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private SmartWalletLeaderTradeOutcomeEntity buildOutcome(TradeAccumulator accumulator, boolean probablyTruncated) {
        PolymarketApiClient.MarketMetadata metadata = resolveMetadata(accumulator.marketId);
        String normalizedOutcome = normalizeOutcome(accumulator.outcome);
        String tokenId = resolveTokenId(metadata, normalizedOutcome);
        SmartWalletMarketInfoEntity marketInfo = marketInfoService.getOrCreate(
                accumulator.marketId,
                normalizedOutcome,
                firstNonBlank(accumulator.question, metadata == null ? null : metadata.getQuestion()),
                tokenId,
                metadata == null ? null : metadata.resolveIndexSet(tokenId),
                metadata == null ? null : metadata.getEndAt()
        );

        BigDecimal netShares = accumulator.buyShares.subtract(accumulator.sellShares).max(BigDecimal.ZERO);
        BigDecimal avgBuyPrice = accumulator.buyShares.compareTo(BigDecimal.ZERO) <= 0
                ? BigDecimal.ZERO
                : accumulator.buyCostUsdc.divide(accumulator.buyShares, 8, RoundingMode.HALF_UP);
        ResolutionState resolution = resolveState(metadata, normalizedOutcome, netShares, accumulator);
        BigDecimal settlementValue = resolution.settlementPrice == null
                ? BigDecimal.ZERO
                : netShares.multiply(resolution.settlementPrice);
        BigDecimal pnl = accumulator.sellProceedsUsdc.add(settlementValue).subtract(accumulator.buyCostUsdc);
        BigDecimal roi = accumulator.buyCostUsdc.compareTo(BigDecimal.ZERO) <= 0
                ? BigDecimal.ZERO
                : pnl.divide(accumulator.buyCostUsdc, 8, RoundingMode.HALF_UP);
        BigDecimal confidence = sourceConfidence(accumulator, metadata, probablyTruncated);
        String outcomeStatus = confidence.compareTo(CONFIDENCE_THRESHOLD) < 0
                ? STATUS_LOW_CONFIDENCE
                : outcomeStatus(resolution.status, pnl);
        Boolean isWin = isClosed(outcomeStatus) ? pnl.compareTo(BigDecimal.ZERO) > 0 : null;

        Date now = new Date();
        return new SmartWalletLeaderTradeOutcomeEntity()
                .setWalletAddress(accumulator.walletAddress)
                .setMarketId(accumulator.marketId)
                .setMarketInfoId(marketInfo == null ? null : marketInfo.getId())
                .setQuestion(firstNonBlank(accumulator.question, metadata == null ? null : metadata.getQuestion()))
                .setOutcome(normalizedOutcome)
                .setTokenId(tokenId)
                .setFirstTradeAt(asDate(accumulator.firstTradeAt))
                .setLastTradeAt(asDate(accumulator.lastTradeAt))
                .setBuyCount(accumulator.buyCount)
                .setSellCount(accumulator.sellCount)
                .setBuyShares(scale(accumulator.buyShares, 8))
                .setSellShares(scale(accumulator.sellShares, 8))
                .setNetShares(scale(netShares, 8))
                .setAvgBuyPrice(scale(avgBuyPrice, 8))
                .setBuyCostUsdc(scale(accumulator.buyCostUsdc, 8))
                .setSellProceedsUsdc(scale(accumulator.sellProceedsUsdc, 8))
                .setResolvedOutcome(resolution.resolvedOutcome)
                .setSettlementPrice(resolution.settlementPrice)
                .setEstimatedSettlementValueUsdc(scale(settlementValue, 8))
                .setEstimatedRealizedPnlUsdc(scale(pnl, 8))
                .setEstimatedRoiRate(scale(roi, 8))
                .setIsWin(isWin)
                .setOutcomeStatus(outcomeStatus)
                .setSourceConfidence(scale(confidence, 4))
                .setDataSource(DATA_SOURCE)
                .setCreatedAt(now)
                .setUpdatedAt(now);
    }

    private void upsert(SmartWalletLeaderTradeOutcomeEntity entity) {
        // 2026-05-27 ray@cowork（P1-8 修复）：
        // 旧逻辑 selectOne → insert / updateById 在 MySQL REPEATABLE READ 下，
        // SELECT 会走 (wallet_address, market_id, outcome) 唯一索引并占用 next-key 锁，
        // 紧跟的 INSERT 又申请插入意向锁；同 wallet 并发 rebuild 或与开仓链路交叉时极易撞锁。
        // 改为单语句 ON DUPLICATE KEY UPDATE，由 InnoDB 一次性获取/释放锁，窗口压到毫秒级。
        // created_at 保留首次值；wallet/market/outcome 是唯一键，无需也不能更新。
        leaderOutcomeMapper.upsert(entity);
    }

    private List<String> resolveWallets(String walletAddress, Integer limit) {
        if (StringUtils.hasText(walletAddress)) {
            return List.of(walletAddress.trim());
        }
        int safeLimit = limit == null ? 50 : Math.max(1, Math.min(limit, 500));
        Set<String> wallets = profileMapper.selectList(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                        .and(q -> q.eq(SmartWalletProfileEntity::getWatchEnabled, true)
                                .or().eq(SmartWalletProfileEntity::getQualified, true)
                                .or().eq(SmartWalletProfileEntity::getMonitorTier, "HOT"))
                        .orderByDesc(SmartWalletProfileEntity::getWalletScore)
                        .last("limit " + safeLimit))
                .stream()
                .map(SmartWalletProfileEntity::getWalletAddress)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        tradeOrderMapper.selectList(new LambdaQueryWrapper<SmartWalletTradeOrderEntity>()
                        .isNotNull(SmartWalletTradeOrderEntity::getWalletAddress)
                        .last("limit " + safeLimit))
                .stream()
                .map(SmartWalletTradeOrderEntity::getWalletAddress)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .forEach(wallets::add);
        return wallets.stream().limit(safeLimit).toList();
    }

    private List<SmartWalletLeaderTradeOutcomeEntity> listOutcomes(String walletAddress, Long cutoffMillis) {
        final int pageSize = 1000;
        List<SmartWalletLeaderTradeOutcomeEntity> all = new ArrayList<>();
        Date lastTs = null;
        Long lastId = null;
        boolean enteredNullPartition = false;
        while (true) {
            LambdaQueryWrapper<SmartWalletLeaderTradeOutcomeEntity> wrapper = new LambdaQueryWrapper<>();
            if (StringUtils.hasText(walletAddress)) {
                wrapper.eq(SmartWalletLeaderTradeOutcomeEntity::getWalletAddress, walletAddress.trim());
            }
            if (cutoffMillis != null) {
                wrapper.ge(SmartWalletLeaderTradeOutcomeEntity::getFirstTradeAt, new Date(cutoffMillis));
            }
            if (lastId != null) {
                final Date cursorTs = lastTs;
                final Long cursorId = lastId;
                if (enteredNullPartition) {
                    // 已进入 firstTradeAt IS NULL 分区，仅按 id 继续推进
                    wrapper.isNull(SmartWalletLeaderTradeOutcomeEntity::getFirstTradeAt)
                            .lt(SmartWalletLeaderTradeOutcomeEntity::getId, cursorId);
                } else {
                    // 复合游标: (firstTradeAt < cursorTs) OR (firstTradeAt = cursorTs AND id < cursorId) OR firstTradeAt IS NULL
                    wrapper.and(w -> w
                            .lt(SmartWalletLeaderTradeOutcomeEntity::getFirstTradeAt, cursorTs)
                            .or(sub -> sub.eq(SmartWalletLeaderTradeOutcomeEntity::getFirstTradeAt, cursorTs)
                                    .lt(SmartWalletLeaderTradeOutcomeEntity::getId, cursorId))
                            .or(sub -> sub.isNull(SmartWalletLeaderTradeOutcomeEntity::getFirstTradeAt)));
                }
            }
            wrapper.orderByDesc(SmartWalletLeaderTradeOutcomeEntity::getFirstTradeAt)
                    .orderByDesc(SmartWalletLeaderTradeOutcomeEntity::getId)
                    .last("limit " + pageSize);
            List<SmartWalletLeaderTradeOutcomeEntity> chunk = leaderOutcomeMapper.selectList(wrapper);
            if (chunk == null || chunk.isEmpty()) {
                break;
            }
            all.addAll(chunk);
            SmartWalletLeaderTradeOutcomeEntity tail = chunk.get(chunk.size() - 1);
            lastTs = tail == null ? null : tail.getFirstTradeAt();
            lastId = tail == null ? null : tail.getId();
            if (lastTs == null) {
                enteredNullPartition = true;
            }
            if (chunk.size() < pageSize) {
                break;
            }
        }
        return all;
    }

    private LeaderTradeOutcomeSummaryView toSummary(String walletAddress,
                                                    String window,
                                                    List<SmartWalletLeaderTradeOutcomeEntity> rows) {
        List<SmartWalletLeaderTradeOutcomeEntity> trustedClosed = rows.stream()
                .filter(row -> isClosed(row.getOutcomeStatus()))
                .filter(row -> safe(row.getSourceConfidence()).compareTo(CONFIDENCE_THRESHOLD) >= 0)
                .toList();
        long wins = trustedClosed.stream().filter(row -> Boolean.TRUE.equals(row.getIsWin())).count();
        long losses = trustedClosed.stream().filter(row -> STATUS_CLOSED_LOSS.equals(row.getOutcomeStatus())).count();
        long breakeven = trustedClosed.stream().filter(row -> STATUS_CLOSED_BREAKEVEN.equals(row.getOutcomeStatus())).count();
        BigDecimal cost = sum(rows, SmartWalletLeaderTradeOutcomeEntity::getBuyCostUsdc);
        BigDecimal pnl = sum(trustedClosed, SmartWalletLeaderTradeOutcomeEntity::getEstimatedRealizedPnlUsdc);
        return new LeaderTradeOutcomeSummaryView(
                walletAddress,
                window,
                rows.size(),
                trustedClosed.size(),
                wins,
                losses,
                breakeven,
                rows.stream().filter(row -> STATUS_OPEN.equals(row.getOutcomeStatus())).count(),
                rows.stream().filter(row -> STATUS_UNRESOLVED.equals(row.getOutcomeStatus())).count(),
                rows.stream().filter(row -> STATUS_LOW_CONFIDENCE.equals(row.getOutcomeStatus())).count(),
                rate(wins, trustedClosed.size()),
                scale(cost, 4),
                scale(pnl, 4),
                ratio(pnl, cost),
                summaryRecommendation(trustedClosed.size(), rate(wins, trustedClosed.size()), pnl)
        );
    }

    private LeaderTradeOutcomeCoverageView toCoverage(String walletAddress,
                                                      List<SmartWalletLeaderTradeOutcomeEntity> rows) {
        List<SmartWalletLeaderTradeOutcomeEntity> trustedClosed = rows.stream()
                .filter(row -> isClosed(row.getOutcomeStatus()))
                .filter(row -> safe(row.getSourceConfidence()).compareTo(CONFIDENCE_THRESHOLD) >= 0)
                .toList();
        Map<String, TaskBucket> taskBuckets = loadTaskBuckets(walletAddress);
        CoverageStats followed = new CoverageStats();
        CoverageStats skipped = new CoverageStats();
        long notMatched = 0;
        for (SmartWalletLeaderTradeOutcomeEntity row : trustedClosed) {
            TaskBucket bucket = taskBuckets.get(tradeKey(row.getMarketId(), row.getOutcome()));
            if (bucket == null || (!bucket.followed && !bucket.skipped)) {
                notMatched++;
                continue;
            }
            if (bucket.followed) {
                followed.add(row);
            } else {
                skipped.add(row);
            }
        }
        long eventCount = taskBuckets.values().stream().mapToLong(bucket -> bucket.totalTasks).sum();
        long followedTasks = taskBuckets.values().stream().mapToLong(bucket -> bucket.followedTasks).sum();
        BigDecimal leaderPnl = sum(trustedClosed, SmartWalletLeaderTradeOutcomeEntity::getEstimatedRealizedPnlUsdc);
        BigDecimal skippedWinRate = skipped.winRate();
        BigDecimal followedWinRate = followed.winRate();
        return new LeaderTradeOutcomeCoverageView(
                walletAddress,
                rows.size(),
                trustedClosed.size(),
                followed.count,
                skipped.count,
                notMatched,
                rate(trustedClosed.stream().filter(row -> Boolean.TRUE.equals(row.getIsWin())).count(), trustedClosed.size()),
                followedWinRate,
                skippedWinRate,
                scale(leaderPnl, 4),
                scale(followed.pnl, 4),
                scale(skipped.pnl, 4),
                rate(followedTasks, eventCount),
                coverageRecommendation(trustedClosed.size(), followed.count, skipped.count, followedWinRate, skippedWinRate, skipped.pnl)
        );
    }

    private Map<String, TaskBucket> loadTaskBuckets(String walletAddress) {
        List<SmartWalletFollowTaskEntity> tasks = followTaskMapper.selectList(new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                .eq(SmartWalletFollowTaskEntity::getWalletAddress, walletAddress));
        marketInfoService.populateFollowTasks(tasks);
        Map<String, TaskBucket> result = new LinkedHashMap<>();
        for (SmartWalletFollowTaskEntity task : tasks) {
            String marketId = task.getMarketId();
            String outcome = task.getOutcome();
            if (!StringUtils.hasText(marketId) || !StringUtils.hasText(outcome)) {
                continue;
            }
            TaskBucket bucket = result.computeIfAbsent(tradeKey(marketId, outcome), ignored -> new TaskBucket());
            bucket.totalTasks++;
            if ("FOLLOWED".equalsIgnoreCase(task.getStatus())) {
                bucket.followed = true;
                bucket.followedTasks++;
            } else if ("SKIPPED".equalsIgnoreCase(task.getStatus())
                    || SmartWalletFollowTaskService.STATUS_PAPER_SHADOW.equalsIgnoreCase(task.getStatus())) {
                bucket.skipped = true;
            }
        }
        return result;
    }

    private PolymarketApiClient.MarketMetadata resolveMetadata(String marketId) {
        try {
            return marketMetadataService.getBestEffortByConditionId(marketId, false, null, List.of());
        } catch (Exception ex) {
            log.debug("Failed to resolve metadata for leader outcome market={} reason={}", marketId, rootMessage(ex));
            return null;
        }
    }

    private ResolutionState resolveState(PolymarketApiClient.MarketMetadata metadata,
                                         String outcome,
                                         BigDecimal netShares,
                                         TradeAccumulator accumulator) {
        if (isFullyRealized(accumulator, netShares)) {
            return new ResolutionState("CLOSED", metadata == null ? null : normalizeOutcome(metadata.getResolvedOutcome()), null);
        }
        if (metadata == null) {
            return new ResolutionState(STATUS_UNRESOLVED, null, null);
        }
        if (metadata.isResolved() && StringUtils.hasText(metadata.getResolvedOutcome())) {
            String resolved = normalizeOutcome(metadata.getResolvedOutcome());
            BigDecimal settlementPrice = outcome.equals(resolved) ? BigDecimal.ONE : BigDecimal.ZERO;
            return new ResolutionState("CLOSED", resolved, settlementPrice);
        }
        if (metadata.isWaitingFinalPrice() || metadata.needsManualCheck()) {
            return new ResolutionState(STATUS_UNRESOLVED, normalizeOutcome(metadata.getResolvedOutcome()), null);
        }
        return new ResolutionState(netShares.compareTo(BigDecimal.ZERO) > 0 ? STATUS_OPEN : STATUS_UNRESOLVED,
                normalizeOutcome(metadata.getResolvedOutcome()), null);
    }

    private boolean isFullyRealized(TradeAccumulator accumulator, BigDecimal netShares) {
        return accumulator != null
                && accumulator.buyShares.compareTo(BigDecimal.ZERO) > 0
                && accumulator.sellShares.compareTo(BigDecimal.ZERO) > 0
                && netShares.compareTo(BigDecimal.ZERO) <= 0;
    }

    private String outcomeStatus(String resolutionStatus, BigDecimal pnl) {
        if (!"CLOSED".equals(resolutionStatus)) {
            return resolutionStatus;
        }
        if (pnl.compareTo(BigDecimal.ZERO) > 0) {
            return STATUS_CLOSED_WIN;
        }
        if (pnl.compareTo(BigDecimal.ZERO) < 0) {
            return STATUS_CLOSED_LOSS;
        }
        return STATUS_CLOSED_BREAKEVEN;
    }

    private BigDecimal sourceConfidence(TradeAccumulator accumulator,
                                        PolymarketApiClient.MarketMetadata metadata,
                                        boolean probablyTruncated) {
        BigDecimal confidence = new BigDecimal("0.35");
        if (accumulator.buyCount > 0 && accumulator.buyCostUsdc.compareTo(BigDecimal.ZERO) > 0) {
            confidence = confidence.add(new BigDecimal("0.25"));
        }
        if (accumulator.firstTradeAt != null && accumulator.lastTradeAt != null) {
            confidence = confidence.add(new BigDecimal("0.10"));
        }
        if (metadata != null && (metadata.isResolved() || metadata.getEndAt() != null)) {
            confidence = confidence.add(new BigDecimal("0.20"));
        }
        if (metadata != null && StringUtils.hasText(resolveTokenId(metadata, accumulator.outcome))) {
            confidence = confidence.add(new BigDecimal("0.10"));
        }
        if (probablyTruncated) {
            confidence = confidence.subtract(new BigDecimal("0.30"));
        }
        if (accumulator.missingRequiredFields) {
            confidence = confidence.subtract(new BigDecimal("0.35"));
        }
        return confidence.max(BigDecimal.ZERO).min(BigDecimal.ONE);
    }

    private String resolveTokenId(PolymarketApiClient.MarketMetadata metadata, String outcome) {
        if (metadata == null || metadata.getOutcomeTokenIds() == null) {
            return null;
        }
        return metadata.getOutcomeTokenIds().get(normalizeOutcome(outcome));
    }

    private String summaryRecommendation(long closedCount, BigDecimal winRate, BigDecimal pnl) {
        if (closedCount < 20) {
            return "INSUFFICIENT_DATA";
        }
        if (winRate.compareTo(new BigDecimal("0.55")) >= 0 && pnl.compareTo(BigDecimal.ZERO) > 0) {
            return "EXPAND_PAPER_100_PERCENT";
        }
        return "KEEP_FILTER_FIRST";
    }

    private String coverageRecommendation(long closedCount,
                                          long followedCount,
                                          long skippedCount,
                                          BigDecimal followedWinRate,
                                          BigDecimal skippedWinRate,
                                          BigDecimal skippedPnl) {
        if (closedCount < 20 || skippedCount < 10) {
            return "INSUFFICIENT_DATA";
        }
        if (followedCount == 0 || skippedWinRate.compareTo(followedWinRate.add(new BigDecimal("0.05"))) > 0
                || skippedPnl.compareTo(BigDecimal.ZERO) > 0) {
            return "EXPAND_PAPER_100_PERCENT";
        }
        return "KEEP_FILTER_FIRST";
    }

    private Long cutoffMillis(String window) {
        String normalized = normalizeWindow(window);
        long now = System.currentTimeMillis();
        return switch (normalized) {
            case "7D" -> now - 7L * 24L * 3600_000L;
            case "30D" -> now - 30L * 24L * 3600_000L;
            case "90D" -> now - 90L * 24L * 3600_000L;
            default -> null;
        };
    }

    private String normalizeWindow(String window) {
        return StringUtils.hasText(window) ? window.trim().toUpperCase(Locale.ROOT) : "ALL";
    }

    private boolean isClosed(String status) {
        return STATUS_CLOSED_WIN.equals(status) || STATUS_CLOSED_LOSS.equals(status) || STATUS_CLOSED_BREAKEVEN.equals(status);
    }

    private String tradeKey(String marketId, String outcome) {
        return defaultString(marketId).trim() + "|" + normalizeOutcome(outcome);
    }

    private String normalizeOutcome(String outcome) {
        return StringUtils.hasText(outcome) ? outcome.trim().toUpperCase(Locale.ROOT) : "";
    }

    private Date asDate(Long epochMillis) {
        return epochMillis == null ? null : new Date(epochMillis);
    }

    private BigDecimal scale(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String firstNonBlank(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    private BigDecimal rate(long numerator, long denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return safe(numerator).divide(denominator, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal sum(List<SmartWalletLeaderTradeOutcomeEntity> rows,
                           java.util.function.Function<SmartWalletLeaderTradeOutcomeEntity, BigDecimal> getter) {
        return rows.stream().map(getter).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor != null && cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return cursor == null || cursor.getMessage() == null ? String.valueOf(error) : cursor.getMessage();
    }

    private static class TradeAccumulator {
        private final String walletAddress;
        private final String marketId;
        private final String outcome;
        private String question;
        private int buyCount;
        private int sellCount;
        private BigDecimal buyShares = BigDecimal.ZERO;
        private BigDecimal sellShares = BigDecimal.ZERO;
        private BigDecimal buyCostUsdc = BigDecimal.ZERO;
        private BigDecimal sellProceedsUsdc = BigDecimal.ZERO;
        private Long firstTradeAt;
        private Long lastTradeAt;
        private boolean missingRequiredFields;

        private TradeAccumulator(String walletAddress, String marketId, String outcome) {
            this.walletAddress = walletAddress;
            this.marketId = marketId;
            this.outcome = outcome;
        }

        private void add(PolymarketApiClient.UserTrade trade) {
            if (StringUtils.hasText(trade.getQuestion()) && !StringUtils.hasText(question)) {
                question = trade.getQuestion();
            }
            if (trade.getTradedAt() == null || trade.getPrice() == null || trade.getSizeShares() == null) {
                missingRequiredFields = true;
                return;
            }
            firstTradeAt = firstTradeAt == null ? trade.getTradedAt() : Math.min(firstTradeAt, trade.getTradedAt());
            lastTradeAt = lastTradeAt == null ? trade.getTradedAt() : Math.max(lastTradeAt, trade.getTradedAt());
            BigDecimal shares = trade.getSizeShares();
            BigDecimal notional = shares.multiply(trade.getPrice());
            if ("SELL".equalsIgnoreCase(trade.getSide())) {
                sellCount++;
                sellShares = sellShares.add(shares);
                sellProceedsUsdc = sellProceedsUsdc.add(notional);
            } else {
                buyCount++;
                buyShares = buyShares.add(shares);
                buyCostUsdc = buyCostUsdc.add(notional);
            }
        }

        private boolean shouldEmit() {
            return buyShares.subtract(sellShares).compareTo(BigDecimal.ZERO) > 0
                    || (buyCount > 0 && sellCount > 0);
        }
    }

    private record ResolutionState(String status, String resolvedOutcome, BigDecimal settlementPrice) {
    }

    private static class TaskBucket {
        private boolean followed;
        private boolean skipped;
        private long totalTasks;
        private long followedTasks;
    }

    private static class CoverageStats {
        private long count;
        private long wins;
        private BigDecimal pnl = BigDecimal.ZERO;

        private void add(SmartWalletLeaderTradeOutcomeEntity row) {
            count++;
            if (Boolean.TRUE.equals(row.getIsWin())) {
                wins++;
            }
            pnl = pnl.add(row.getEstimatedRealizedPnlUsdc() == null ? BigDecimal.ZERO : row.getEstimatedRealizedPnlUsdc());
        }

        private BigDecimal winRate() {
            if (count <= 0) {
                return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            }
            return BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP);
        }
    }
}
