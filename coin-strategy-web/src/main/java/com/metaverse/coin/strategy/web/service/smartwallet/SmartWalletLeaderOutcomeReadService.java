package com.metaverse.coin.strategy.web.service.smartwallet;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletLeaderTradeOutcomeMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletMarketInfoMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletLeaderTradeOutcomeEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import com.metaverse.coin.strategy.web.model.smartwallet.LeaderTradeOutcomeCoverageView;
import com.metaverse.coin.strategy.web.model.smartwallet.LeaderTradeOutcomeSummaryView;
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
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Leader 真实闭环胜率/PnL 的 web 端只读视图。
 *
 * <p>从 job 端 {@code LeaderTradeOutcomeRebuildService.summarize/coverage} 拆出的只读部分，
 * 仅依赖 common-dao 的 mapper，不依赖 job 进程内状态。重建/校准等写路径仍保留在 job。</p>
 *
 * <p>与 job 端实现的语义保持一致：</p>
 * <ul>
 *   <li>窗口：ALL / 7D / 30D / 90D（基于 {@code first_trade_at} 截断）</li>
 *   <li>仅按 {@code source_confidence ≥ 0.55} 的 closed 行计入胜率/PnL</li>
 *   <li>covarage 的 followed/skipped 桶依赖 {@code smart_wallet_follow_task.market_info}，
 *       因此跨表填充 {@code marketInfo}</li>
 * </ul>
 */
@Service
public class SmartWalletLeaderOutcomeReadService {

    private static final String STATUS_CLOSED_WIN = "CLOSED_WIN";
    private static final String STATUS_CLOSED_LOSS = "CLOSED_LOSS";
    private static final String STATUS_CLOSED_BREAKEVEN = "CLOSED_BREAKEVEN";
    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_UNRESOLVED = "UNRESOLVED";
    private static final String STATUS_LOW_CONFIDENCE = "LOW_CONFIDENCE";
    private static final String STATUS_PAPER_SHADOW = "PAPER_SHADOW";

    private static final BigDecimal CONFIDENCE_THRESHOLD = new BigDecimal("0.5500");

    private final SmartWalletLeaderTradeOutcomeMapper leaderOutcomeMapper;
    private final SmartWalletFollowTaskMapper followTaskMapper;
    private final SmartWalletMarketInfoMapper marketInfoMapper;

    public SmartWalletLeaderOutcomeReadService(SmartWalletLeaderTradeOutcomeMapper leaderOutcomeMapper,
                                               SmartWalletFollowTaskMapper followTaskMapper,
                                               SmartWalletMarketInfoMapper marketInfoMapper) {
        this.leaderOutcomeMapper = leaderOutcomeMapper;
        this.followTaskMapper = followTaskMapper;
        this.marketInfoMapper = marketInfoMapper;
    }

    public List<LeaderTradeOutcomeSummaryView> summarize(String walletAddress, String window) {
        List<SmartWalletLeaderTradeOutcomeEntity> rows = listOutcomes(walletAddress, cutoffMillis(window));
        String normalizedWindow = normalizeWindow(window);
        Map<String, List<SmartWalletLeaderTradeOutcomeEntity>> byWallet = rows.stream()
                .collect(Collectors.groupingBy(SmartWalletLeaderTradeOutcomeEntity::getWalletAddress,
                        LinkedHashMap::new, Collectors.toList()));
        return byWallet.entrySet().stream()
                .map(entry -> toSummary(entry.getKey(), normalizedWindow, entry.getValue()))
                .sorted(Comparator.comparing(LeaderTradeOutcomeSummaryView::estimatedPnlUsdc,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public List<LeaderTradeOutcomeCoverageView> coverage(String walletAddress) {
        List<SmartWalletLeaderTradeOutcomeEntity> rows = listOutcomes(walletAddress, null);
        Map<String, List<SmartWalletLeaderTradeOutcomeEntity>> byWallet = rows.stream()
                .collect(Collectors.groupingBy(SmartWalletLeaderTradeOutcomeEntity::getWalletAddress,
                        LinkedHashMap::new, Collectors.toList()));
        return byWallet.entrySet().stream()
                .map(entry -> toCoverage(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(LeaderTradeOutcomeCoverageView::leaderPnlUsdc,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    // ─────────────────────────────── 读 DB ───────────────────────────────

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
                    wrapper.isNull(SmartWalletLeaderTradeOutcomeEntity::getFirstTradeAt)
                            .lt(SmartWalletLeaderTradeOutcomeEntity::getId, cursorId);
                } else {
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

    private Map<String, TaskBucket> loadTaskBuckets(String walletAddress) {
        if (!StringUtils.hasText(walletAddress)) {
            return Map.of();
        }
        List<SmartWalletFollowTaskEntity> tasks = followTaskMapper.selectList(
                new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                        .eq(SmartWalletFollowTaskEntity::getWalletAddress, walletAddress));
        populateMarketInfo(tasks);
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
                    || STATUS_PAPER_SHADOW.equalsIgnoreCase(task.getStatus())) {
                bucket.skipped = true;
            }
        }
        return result;
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
                .collect(Collectors.toMap(SmartWalletMarketInfoEntity::getId, Function.identity()));
        for (SmartWalletFollowTaskEntity task : tasks) {
            if (task.getMarketInfoId() != null) {
                task.setMarketInfo(marketInfoById.get(task.getMarketInfoId()));
            }
        }
    }

    // ─────────────────────────────── 视图聚合 ───────────────────────────────

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
                rate(trustedClosed.stream().filter(row -> Boolean.TRUE.equals(row.getIsWin())).count(),
                        trustedClosed.size()),
                followedWinRate,
                skippedWinRate,
                scale(leaderPnl, 4),
                scale(followed.pnl, 4),
                scale(skipped.pnl, 4),
                rate(followedTasks, eventCount),
                coverageRecommendation(trustedClosed.size(), followed.count, skipped.count,
                        followedWinRate, skippedWinRate, skipped.pnl)
        );
    }

    // ─────────────────────────────── 工具 ───────────────────────────────

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
        if (followedCount == 0
                || skippedWinRate.compareTo(followedWinRate.add(new BigDecimal("0.05"))) > 0
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
        return STATUS_CLOSED_WIN.equals(status)
                || STATUS_CLOSED_LOSS.equals(status)
                || STATUS_CLOSED_BREAKEVEN.equals(status);
    }

    private String tradeKey(String marketId, String outcome) {
        return (marketId == null ? "" : marketId).trim() + "|" + normalizeOutcome(outcome);
    }

    private String normalizeOutcome(String outcome) {
        return StringUtils.hasText(outcome) ? outcome.trim().toUpperCase(Locale.ROOT) : "";
    }

    private BigDecimal scale(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal rate(long numerator, long denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return safe(numerator).divide(denominator, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal sum(List<SmartWalletLeaderTradeOutcomeEntity> rows,
                           Function<SmartWalletLeaderTradeOutcomeEntity, BigDecimal> getter) {
        return rows.stream().map(getter).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ─────────────────────────────── 内部数据结构 ───────────────────────────────

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
            BigDecimal rowPnl = row.getEstimatedRealizedPnlUsdc();
            if (rowPnl != null) {
                pnl = pnl.add(rowPnl);
            }
        }

        private BigDecimal winRate() {
            if (count <= 0) {
                return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            }
            return BigDecimal.valueOf(wins)
                    .divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP);
        }
    }
}
