package com.metaverse.coin.strategy.job.strategy.application;

import com.metaverse.coin.strategy.job.strategy.model.StrategyFlowLogEvent;
import com.metaverse.coin.strategy.job.strategy.model.StrategyLogEntryView;
import com.metaverse.coin.strategy.job.strategy.model.StrategyLogFlowView;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class FileStrategyFlowLogReader implements StrategyFlowLogReader {

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 500;

    private final StrategyLogQueryService logQueryService;

    public FileStrategyFlowLogReader(StrategyLogQueryService logQueryService) {
        this.logQueryService = logQueryService;
    }

    @Override
    public List<StrategyLogFlowView> queryFlows(String source,
                                                String strategy,
                                                String q,
                                                String wallet,
                                                String eventId,
                                                String followTaskId,
                                                String marketId,
                                                Integer limit) {
        String normalizedStrategy = strategy == null || strategy.isBlank() ? "POLYMARKET_COPY" : strategy;
        List<StrategyLogEntryView> rawEntries = logQueryService.query(source, "ALL", normalizedStrategy, null, MAX_LIMIT);
        List<StrategyFlowLogEvent> events = rawEntries.stream()
                .map(StrategyFlowLogSupport::toEvent)
                .filter(event -> event != null && matchesFilters(event, q, wallet, eventId, followTaskId, marketId))
                .sorted(Comparator.comparing(StrategyFlowLogEvent::timestamp))
                .toList();

        Map<String, List<StrategyFlowLogEvent>> grouped = new LinkedHashMap<>();
        for (StrategyFlowLogEvent event : events) {
            grouped.computeIfAbsent(event.flowId(), ignored -> new ArrayList<>()).add(event);
        }

        int safeLimit = clampLimit(limit);
        return grouped.values().stream()
                .map(this::toFlowView)
                .sorted(Comparator.comparing(StrategyLogFlowView::latestTimestamp,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .limit(safeLimit)
                .toList();
    }

    private boolean matchesFilters(StrategyFlowLogEvent event,
                                   String q,
                                   String wallet,
                                   String eventId,
                                   String followTaskId,
                                   String marketId) {
        if (hasText(wallet) && !containsIgnoreCase(event.wallet(), wallet)) {
            return false;
        }
        if (hasText(eventId) && !equalsIgnoreCase(event.eventId(), eventId)) {
            return false;
        }
        if (hasText(followTaskId) && !equalsIgnoreCase(event.followTaskId(), followTaskId)) {
            return false;
        }
        if (hasText(marketId) && !containsIgnoreCase(event.marketId(), marketId)) {
            return false;
        }
        if (!hasText(q)) {
            return true;
        }
        String haystack = String.join(" ",
                nullToEmpty(event.flowId()),
                nullToEmpty(event.flowStage()),
                nullToEmpty(event.action()),
                nullToEmpty(event.result()),
                nullToEmpty(event.eventId()),
                nullToEmpty(event.followTaskId()),
                nullToEmpty(event.wallet()),
                nullToEmpty(event.marketId()),
                nullToEmpty(event.outcome()),
                nullToEmpty(event.mode()),
                nullToEmpty(event.entryLane()),
                nullToEmpty(event.matchedStrategies()),
                nullToEmpty(event.firstRejectGate()),
                nullToEmpty(event.blockerCategories()),
                nullToEmpty(event.reasonSummary()),
                nullToEmpty(event.rawMessage())
        ).toLowerCase(Locale.ROOT);
        return haystack.contains(q.trim().toLowerCase(Locale.ROOT));
    }

    private StrategyLogFlowView toFlowView(List<StrategyFlowLogEvent> entries) {
        StrategyFlowLogEvent latest = entries.get(entries.size() - 1);
        String firstRejectGate = latest.firstRejectGate();
        String blockerCategories = latest.blockerCategories();
        for (StrategyFlowLogEvent event : entries) {
            if (!hasText(firstRejectGate) && hasText(event.firstRejectGate())) {
                firstRejectGate = event.firstRejectGate();
            }
            if (!hasText(blockerCategories) && hasText(event.blockerCategories())) {
                blockerCategories = event.blockerCategories();
            }
        }
        return new StrategyLogFlowView(
                latest.flowId(),
                latest.strategy(),
                latest.source(),
                latest.timestamp(),
                inferStatus(entries, latest, firstRejectGate),
                latest.flowStage(),
                firstNonBlank(latest.eventId(), findFirst(entries, StrategyFlowLogEvent::eventId)),
                firstNonBlank(latest.followTaskId(), findFirst(entries, StrategyFlowLogEvent::followTaskId)),
                firstNonBlank(latest.wallet(), findFirst(entries, StrategyFlowLogEvent::wallet)),
                firstNonBlank(latest.marketId(), findFirst(entries, StrategyFlowLogEvent::marketId)),
                firstNonBlank(latest.outcome(), findFirst(entries, StrategyFlowLogEvent::outcome)),
                firstNonBlank(latest.mode(), findFirst(entries, StrategyFlowLogEvent::mode)),
                firstNonBlank(latest.stakeUsdc(), findFirst(entries, StrategyFlowLogEvent::stakeUsdc)),
                firstNonBlank(latest.entryLane(), findFirst(entries, StrategyFlowLogEvent::entryLane)),
                firstNonBlank(latest.matchedStrategies(), findFirst(entries, StrategyFlowLogEvent::matchedStrategies)),
                firstNonBlank(latest.gateHighWaterMark(), findFirst(entries, StrategyFlowLogEvent::gateHighWaterMark)),
                firstRejectGate,
                blockerCategories,
                firstNonBlank(latest.reasonSummary(), latest.action() + " " + latest.result()),
                entries
        );
    }

    private String inferStatus(List<StrategyFlowLogEvent> entries, StrategyFlowLogEvent latest, String firstRejectGate) {
        String combined = (latest.result() + " " + latest.reasonSummary() + " " + firstRejectGate).toLowerCase(Locale.ROOT);
        if (combined.contains("失败") || combined.contains("异常") || combined.contains("failed")) {
            return "FAILED";
        }
        if (combined.contains("拒绝") || combined.contains("skipped") || combined.contains("block") || hasText(firstRejectGate)) {
            return "BLOCKED";
        }
        if ("S8_ORDER_EXECUTED".equalsIgnoreCase(latest.flowStage()) && combined.contains("成功")) {
            return "EXECUTED";
        }
        if (entries.stream().anyMatch(item -> "S6_TASK_CREATED".equalsIgnoreCase(item.flowStage())
                && "PENDING".equalsIgnoreCase(item.result()))) {
            return "PENDING";
        }
        return "ACTIVE";
    }

    private String findFirst(List<StrategyFlowLogEvent> entries, FieldGetter getter) {
        for (StrategyFlowLogEvent event : entries) {
            String value = getter.get(event);
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String firstNonBlank(String primary, String fallback) {
        return hasText(primary) ? primary : fallback;
    }

    private int clampLimit(Integer limit) {
        return limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
    }

    private boolean equalsIgnoreCase(String actual, String expected) {
        return actual != null && expected != null && actual.equalsIgnoreCase(expected.trim());
    }

    private boolean containsIgnoreCase(String actual, String expected) {
        return actual != null && expected != null
                && actual.toLowerCase(Locale.ROOT).contains(expected.trim().toLowerCase(Locale.ROOT));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private interface FieldGetter {
        String get(StrategyFlowLogEvent event);
    }
}
