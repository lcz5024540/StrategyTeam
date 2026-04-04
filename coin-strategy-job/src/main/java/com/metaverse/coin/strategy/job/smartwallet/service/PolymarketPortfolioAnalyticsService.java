package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.PortfolioAttributionItem;
import com.metaverse.coin.strategy.job.smartwallet.model.PortfolioPositionView;
import com.metaverse.coin.strategy.job.smartwallet.model.PortfolioSummaryView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PolymarketPortfolioAnalyticsService {

    private final SmartWalletFollowTaskMapper followTaskMapper;

    public PortfolioSummaryView summary() {
        return summary(null, 5000);
    }

    public PortfolioSummaryView summary(Integer windowSeconds, Integer limit) {
        List<SmartWalletFollowTaskEntity> tasks = loadRecentTasks(windowSeconds, limit);
        Map<String, Long> statusCounts = countByStatus(tasks);
        List<SmartWalletFollowTaskEntity> followed = tasks.stream()
                .filter(task -> "FOLLOWED".equalsIgnoreCase(task.getStatus()))
                .toList();
        BigDecimal deployed = sumStake(followed);
        return PortfolioSummaryView.builder()
                .totalTasks(tasks.size())
                .followedTasks(statusCounts.getOrDefault("FOLLOWED", 0L))
                .skippedTasks(statusCounts.getOrDefault("SKIPPED", 0L))
                .failedTasks(statusCounts.getOrDefault("FAILED", 0L))
                .pendingTasks(statusCounts.getOrDefault("PENDING", 0L))
                .activePositions(listPositions(windowSeconds, limit, 0).size())
                .totalDeployedUsdc(deployed)
                .averageStakeUsdc(averageStake(followed, deployed))
                .statusCounts(statusCounts)
                .notes(List.of(
                        "Portfolio summary is derived from follow-task executions.",
                        "Active positions currently represent open exposure inferred from followed tasks."
                ))
                .build();
    }

    public List<PortfolioPositionView> listPositions() {
        return listPositions(null, 2000, 0);
    }

    public List<PortfolioPositionView> listPositions(Integer windowSeconds, Integer limit, Integer offset) {
        List<SmartWalletFollowTaskEntity> followed = loadRecentTasks(windowSeconds, limit).stream()
                .filter(task -> "FOLLOWED".equalsIgnoreCase(task.getStatus()))
                .toList();
        Map<String, PositionAccumulator> grouped = new LinkedHashMap<>();
        for (SmartWalletFollowTaskEntity task : followed) {
            String key = String.join("|", safeText(task.getMarketId()), safeText(task.getOutcome()), safeText(task.getSide()));
            grouped.computeIfAbsent(key, ignored -> new PositionAccumulator(task.getMarketId(), task.getQuestion(), task.getOutcome(), task.getSide()))
                    .add(task);
        }
        int skip = Math.max(0, offset == null ? 0 : offset);
        int take = normalizeOutputLimit(limit, 200);
        return grouped.values().stream()
                .map(PositionAccumulator::toView)
                .sorted(Comparator.comparing(PortfolioPositionView::getTotalStakeUsdc).reversed())
                .skip(skip)
                .limit(take)
                .toList();
    }

    public List<PortfolioAttributionItem> walletAttribution(int limit) {
        return walletAttribution(null, 5000, limit);
    }

    public List<PortfolioAttributionItem> walletAttribution(Integer windowSeconds, Integer taskLimit, int limit) {
        return attributionBy(loadRecentTasks(windowSeconds, taskLimit).stream()
                        .filter(task -> "FOLLOWED".equalsIgnoreCase(task.getStatus()))
                        .toList(),
                SmartWalletFollowTaskEntity::getWalletAddress,
                SmartWalletFollowTaskEntity::getWalletAddress,
                limit);
    }

    public List<PortfolioAttributionItem> marketAttribution(int limit) {
        return marketAttribution(null, 5000, limit);
    }

    public List<PortfolioAttributionItem> marketAttribution(Integer windowSeconds, Integer taskLimit, int limit) {
        return attributionBy(loadRecentTasks(windowSeconds, taskLimit).stream()
                        .filter(task -> "FOLLOWED".equalsIgnoreCase(task.getStatus()))
                        .toList(),
                SmartWalletFollowTaskEntity::getMarketId,
                task -> task.getQuestion() == null ? task.getMarketId() : task.getQuestion(),
                limit);
    }

    private List<PortfolioAttributionItem> attributionBy(List<SmartWalletFollowTaskEntity> tasks,
                                                          Function<SmartWalletFollowTaskEntity, String> keyFn,
                                                          Function<SmartWalletFollowTaskEntity, String> labelFn,
                                                          int limit) {
        BigDecimal deployed = sumStake(tasks);
        Map<String, List<SmartWalletFollowTaskEntity>> grouped = tasks.stream()
                .collect(Collectors.groupingBy(task -> safeText(keyFn.apply(task)), LinkedHashMap::new, Collectors.toList()));
        return grouped.entrySet().stream()
                .map(entry -> {
                    List<SmartWalletFollowTaskEntity> group = entry.getValue();
                    BigDecimal totalStake = sumStake(group);
                    return PortfolioAttributionItem.builder()
                            .key(entry.getKey())
                            .label(group.isEmpty() ? entry.getKey() : labelFn.apply(group.get(0)))
                            .executionCount(group.size())
                            .totalStakeUsdc(totalStake)
                            .averageStakeUsdc(averageStake(group, totalStake))
                            .shareOfDeployedCapital(ratio(totalStake, deployed))
                            .build();
                })
                .sorted(Comparator.comparing(PortfolioAttributionItem::getTotalStakeUsdc).reversed())
                .limit(normalizeOutputLimit(limit, 100))
                .toList();
    }

    private List<SmartWalletFollowTaskEntity> loadRecentTasks(Integer windowSeconds, Integer limit) {
        LambdaQueryWrapper<SmartWalletFollowTaskEntity> query = new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                .orderByDesc(SmartWalletFollowTaskEntity::getCreatedAt);
        if (windowSeconds != null && windowSeconds > 0) {
            Date from = new Date(System.currentTimeMillis() - windowSeconds * 1000L);
            query.ge(SmartWalletFollowTaskEntity::getCreatedAt, from);
        }
        query.last("limit " + normalizeOutputLimit(limit, 5000));
        return followTaskMapper.selectList(query);
    }

    private int normalizeOutputLimit(Integer limit, int defaultValue) {
        if (limit == null || limit <= 0) {
            return defaultValue;
        }
        return Math.min(limit, 10000);
    }

    private Map<String, Long> countByStatus(List<SmartWalletFollowTaskEntity> tasks) {
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        for (String status : List.of("PENDING", "FOLLOWED", "SKIPPED", "FAILED")) {
            statusCounts.put(status, tasks.stream().filter(task -> status.equalsIgnoreCase(task.getStatus())).count());
        }
        return statusCounts;
    }

    private BigDecimal sumStake(List<SmartWalletFollowTaskEntity> tasks) {
        return tasks.stream()
                .map(task -> task.getRecommendedStakeUsdc() == null ? BigDecimal.ZERO : task.getRecommendedStakeUsdc())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal averageStake(List<SmartWalletFollowTaskEntity> tasks, BigDecimal totalStake) {
        if (tasks.isEmpty()) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return totalStake.divide(BigDecimal.valueOf(tasks.size()), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return numerator.divide(denominator, 4, RoundingMode.HALF_UP);
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private static class PositionAccumulator {
        private final String marketId;
        private final String question;
        private final String outcome;
        private final String side;
        private final List<String> sourceWallets = new ArrayList<>();
        private BigDecimal totalStakeUsdc = BigDecimal.ZERO;
        private BigDecimal totalTriggerPrice = BigDecimal.ZERO;
        private int priceCount = 0;
        private int executionCount = 0;
        private Date firstExecutedAt;
        private Date latestExecutedAt;

        private PositionAccumulator(String marketId, String question, String outcome, String side) {
            this.marketId = marketId;
            this.question = question;
            this.outcome = outcome;
            this.side = side;
        }

        private void add(SmartWalletFollowTaskEntity task) {
            executionCount++;
            if (task.getWalletAddress() != null && !sourceWallets.contains(task.getWalletAddress())) {
                sourceWallets.add(task.getWalletAddress());
            }
            totalStakeUsdc = totalStakeUsdc.add(task.getRecommendedStakeUsdc() == null ? BigDecimal.ZERO : task.getRecommendedStakeUsdc());
            if (task.getTriggerPrice() != null) {
                totalTriggerPrice = totalTriggerPrice.add(task.getTriggerPrice());
                priceCount++;
            }
            if (firstExecutedAt == null || before(task.getExecutedAt(), firstExecutedAt)) {
                firstExecutedAt = task.getExecutedAt();
            }
            if (latestExecutedAt == null || after(task.getExecutedAt(), latestExecutedAt)) {
                latestExecutedAt = task.getExecutedAt();
            }
        }

        private PortfolioPositionView toView() {
            BigDecimal averagePrice = priceCount == 0
                    ? BigDecimal.ZERO
                    : totalTriggerPrice.divide(BigDecimal.valueOf(priceCount), 4, RoundingMode.HALF_UP);
            return PortfolioPositionView.builder()
                    .marketId(marketId)
                    .question(question)
                    .outcome(outcome)
                    .side(side)
                    .lifecycle("OPEN")
                    .executionCount(executionCount)
                    .totalStakeUsdc(totalStakeUsdc.setScale(2, RoundingMode.HALF_UP))
                    .averageTriggerPrice(averagePrice)
                    .firstExecutedAt(firstExecutedAt)
                    .latestExecutedAt(latestExecutedAt)
                    .sourceWallets(sourceWallets)
                    .build();
        }

        private boolean before(Date a, Date b) {
            return a != null && b != null && a.before(b);
        }

        private boolean after(Date a, Date b) {
            return a != null && b != null && a.after(b);
        }
    }
}
