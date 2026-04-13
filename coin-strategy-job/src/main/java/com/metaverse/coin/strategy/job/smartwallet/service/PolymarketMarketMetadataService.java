package com.metaverse.coin.strategy.job.smartwallet.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class PolymarketMarketMetadataService {

    private final PolymarketApiClient polymarketApiClient;
    private final Map<String, PolymarketApiClient.MarketMetadata> cache = new ConcurrentHashMap<>();
    private final Map<String, PolymarketApiClient.MarketMetadata> localMetadata = new ConcurrentHashMap<>();

    public PolymarketApiClient.MarketMetadata getByConditionId(String conditionId) {
        return getByConditionId(conditionId, false, null);
    }

    public PolymarketApiClient.MarketMetadata getByConditionId(String conditionId, boolean refresh) {
        return getByConditionId(conditionId, refresh, null);
    }

    public PolymarketApiClient.MarketMetadata getByConditionId(String conditionId, boolean refresh, String questionHint) {
        if (!StringUtils.hasText(conditionId)) {
            throw new IllegalArgumentException("conditionId is required");
        }
        String normalized = conditionId.trim();
        PolymarketApiClient.MarketMetadata local = localMetadata.get(normalized);
        if (local != null) {
            return local;
        }
        PolymarketApiClient.MarketMetadata cached = cache.get(normalized);
        if (!refresh && cached != null) {
            return cached;
        }
        try {
            PolymarketApiClient.MarketMetadata metadata = polymarketApiClient.fetchMarketMetadata(normalized);
            cache.put(normalized, metadata);
            return metadata;
        } catch (RuntimeException ex) {
            PolymarketApiClient.MarketMetadata fallback = findByQuestionHint(normalized, questionHint);
            if (fallback != null) {
                cache.put(normalized, fallback);
                return fallback;
            }
            if (cached != null) {
                return cached;
            }
            throw ex;
        }
    }

    public PolymarketApiClient.MarketMetadata getBestEffortByConditionId(String conditionId,
                                                                         boolean refresh,
                                                                         String questionHint,
                                                                         List<String> outcomeHints) {
        try {
            return getByConditionId(conditionId, refresh, questionHint);
        } catch (RuntimeException ex) {
            return buildSyntheticMetadata(conditionId, questionHint, outcomeHints);
        }
    }

    public void registerLocalMetadata(String conditionId,
                                      String question,
                                      String category,
                                      List<String> outcomeHints) {
        if (!StringUtils.hasText(conditionId)) {
            return;
        }
        String normalized = conditionId.trim();
        PolymarketApiClient.MarketMetadata existing = localMetadata.get(normalized);
        List<String> outcomes = normalizeOutcomes(outcomeHints);
        if (outcomes.isEmpty() && existing != null && existing.getOutcomes() != null && !existing.getOutcomes().isEmpty()) {
            outcomes = new ArrayList<>(existing.getOutcomes());
        }
        if (outcomes.isEmpty()) {
            outcomes = List.of("YES", "NO");
        }
        List<String> outcomePrices = new ArrayList<>();
        for (int i = 0; i < outcomes.size(); i++) {
            outcomePrices.add("0.5");
        }
        PolymarketApiClient.MarketMetadata metadata = PolymarketApiClient.MarketMetadata.builder()
                .conditionId(normalized)
                .marketId(existing == null || !StringUtils.hasText(existing.getMarketId()) ? normalized : existing.getMarketId())
                .questionId(existing == null ? null : existing.getQuestionId())
                .question(firstText(question, existing == null ? null : existing.getQuestion(), normalized))
                .category(firstText(category, existing == null ? null : existing.getCategory()))
                .minimumTickSize(existing == null || existing.getMinimumTickSize() == null
                        ? new BigDecimal("0.01")
                        : existing.getMinimumTickSize())
                .negRisk(existing != null && existing.isNegRisk())
                .active(existing == null || existing.isActive())
                .closed(existing != null && existing.isClosed())
                .acceptingOrders(existing != null && existing.isAcceptingOrders())
                .umaResolutionStatus(existing == null ? null : existing.getUmaResolutionStatus())
                .resolvedOutcome(existing == null ? null : existing.getResolvedOutcome())
                .endAt(existing == null ? null : existing.getEndAt())
                .umaEndAt(existing == null ? null : existing.getUmaEndAt())
                .outcomes(outcomes)
                .outcomePrices(outcomePrices)
                .outcomeTokenIds(existing == null ? Map.of() : existing.getOutcomeTokenIds())
                .build();
        localMetadata.put(normalized, metadata);
        cache.put(normalized, metadata);
    }

    public void evict(String conditionId) {
        if (StringUtils.hasText(conditionId)) {
            String normalized = conditionId.trim();
            cache.remove(normalized);
            localMetadata.remove(normalized);
        }
    }

    public Map<String, Integer> clearAllCaches() {
        int cached = cache.size();
        int local = localMetadata.size();
        cache.clear();
        localMetadata.clear();
        return Map.of(
                "cache", cached,
                "localMetadata", local
        );
    }

    private PolymarketApiClient.MarketMetadata findByQuestionHint(String conditionId, String questionHint) {
        if (!StringUtils.hasText(questionHint)) {
            return null;
        }
        try {
            List<PolymarketApiClient.MarketMetadata> candidates = polymarketApiClient.searchMarkets(questionHint, 10, true);
            if (candidates.isEmpty()) {
                return null;
            }
            String normalizedConditionId = conditionId == null ? null : conditionId.trim();
            String normalizedQuestion = normalizeQuestion(questionHint);
            PolymarketApiClient.MarketMetadata exactCondition = candidates.stream()
                    .filter(item -> StringUtils.hasText(item.getConditionId()))
                    .filter(item -> item.getConditionId().trim().equalsIgnoreCase(normalizedConditionId))
                    .findFirst()
                    .orElse(null);
            if (exactCondition != null) {
                log.info("Resolved Polymarket metadata for conditionId={} via question search fallback.", normalizedConditionId);
                return exactCondition;
            }
            return candidates.stream()
                    .filter(item -> normalizeQuestion(item.getQuestion()).equals(normalizedQuestion))
                    .min(Comparator.comparing(item -> questionDistance(normalizedQuestion, normalizeQuestion(item.getQuestion()))))
                    .map(item -> {
                        log.warn("Using question-only metadata fallback for conditionId={} question={}", normalizedConditionId, questionHint);
                        return item;
                    })
                    .orElse(null);
        } catch (Exception fallbackEx) {
            log.warn("Question-search fallback failed for conditionId={} question={}: {}",
                    conditionId, questionHint, fallbackEx.getMessage());
            return null;
        }
    }

    private String normalizeQuestion(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ").toLowerCase();
    }

    private int questionDistance(String expected, String actual) {
        if (expected.equals(actual)) {
            return 0;
        }
        if (actual.contains(expected) || expected.contains(actual)) {
            return Math.abs(actual.length() - expected.length());
        }
        return Integer.MAX_VALUE / 2;
    }

    private PolymarketApiClient.MarketMetadata buildSyntheticMetadata(String conditionId,
                                                                      String questionHint,
                                                                      List<String> outcomeHints) {
        List<String> outcomes = normalizeOutcomes(outcomeHints);
        if (outcomes.isEmpty()) {
            outcomes = List.of("YES", "NO");
        }
        List<String> outcomePrices = new ArrayList<>();
        for (int i = 0; i < outcomes.size(); i++) {
            outcomePrices.add("0.5");
        }
        return PolymarketApiClient.MarketMetadata.builder()
                .conditionId(conditionId == null ? null : conditionId.trim())
                .marketId(conditionId == null ? null : conditionId.trim())
                .question(firstText(questionHint, conditionId))
                .minimumTickSize(new BigDecimal("0.01"))
                .active(false)
                .closed(false)
                .acceptingOrders(false)
                .umaResolutionStatus(null)
                .outcomes(outcomes)
                .outcomePrices(outcomePrices)
                .outcomeTokenIds(Map.of())
                .build();
    }

    private List<String> normalizeOutcomes(List<String> outcomeHints) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (outcomeHints != null) {
            for (String outcomeHint : outcomeHints) {
                if (!StringUtils.hasText(outcomeHint)) {
                    continue;
                }
                normalized.add(outcomeHint.trim().toUpperCase(Locale.ROOT));
            }
        }
        if (normalized.size() == 1) {
            String single = normalized.iterator().next();
            if ("YES".equals(single)) {
                normalized.add("NO");
            } else if ("NO".equals(single)) {
                normalized.add("YES");
            }
        }
        return new ArrayList<>(normalized);
    }

    private String firstText(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate)) {
                return candidate.trim();
            }
        }
        return null;
    }
}
