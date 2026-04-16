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
        PolymarketApiClient.MarketMetadata cached = cache.get(normalized);
        if (!refresh && isAuthoritativeCacheEntry(cached)) {
            return cached;
        }
        PolymarketApiClient.MarketMetadata primary = null;
        PolymarketApiClient.MarketMetadata exactFallback = null;
        try {
            primary = withSource(polymarketApiClient.fetchMarketMetadata(normalized), "GAMMA_MARKETS");
            if (shouldProbeExactFallback(primary, questionHint)) {
                exactFallback = findExactByQuestionHint(normalized, questionHint);
            }
            PolymarketApiClient.MarketMetadata merged = mergeMetadata(normalized, local, cached, primary, exactFallback);
            cache.put(normalized, merged);
            return merged;
        } catch (RuntimeException ex) {
            exactFallback = findExactByQuestionHint(normalized, questionHint);
            if (exactFallback != null || cached != null || local != null) {
                PolymarketApiClient.MarketMetadata merged = mergeMetadata(normalized, local, cached, primary, exactFallback);
                cache.put(normalized, merged);
                return merged;
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

    public PolymarketApiClient.MarketMetadata findManualSettlementSignal(String conditionId,
                                                                         boolean refresh,
                                                                         String questionHint,
                                                                         List<String> outcomeHints) {
        if (!StringUtils.hasText(conditionId)) {
            return null;
        }
        PolymarketApiClient.MarketMetadata direct = getByConditionId(conditionId, refresh, questionHint);
        if (isAuthoritativeResolved(direct, conditionId)) {
            return direct;
        }
        if (!StringUtils.hasText(questionHint)) {
            return null;
        }
        String normalizedQuestion = normalizeQuestion(questionHint);
        List<String> normalizedOutcomeHints = normalizeOutcomeHints(outcomeHints);
        List<PolymarketApiClient.MarketMetadata> candidates = polymarketApiClient.searchMarkets(questionHint, 20, true).stream()
                .filter(candidate -> candidate != null && candidate.isClosed() && candidate.isResolved())
                .filter(candidate -> normalizedQuestion.equals(normalizeQuestion(candidate.getQuestion())))
                .filter(candidate -> matchesOutcomeHints(normalizedOutcomeHints, candidate.getOutcomes()))
                .toList();
        if (candidates.size() != 1) {
            return null;
        }
        return withSource(candidates.get(0), "MANUAL_SEARCH_FINAL_PRICE");
    }

    public void registerLocalMetadata(String conditionId,
                                      String question,
                                      String category,
                                      List<String> outcomeHints) {
        if (!StringUtils.hasText(conditionId)) {
            return;
        }
        String normalized = conditionId.trim();
        PolymarketApiClient.MarketMetadata existing = firstNonNull(localMetadata.get(normalized), cache.get(normalized));
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
                .resolutionSource(existing == null ? "LOCAL" : existing.getResolutionSource())
                .resolutionObservedAt(existing == null ? System.currentTimeMillis() : existing.getResolutionObservedAt())
                .outcomes(outcomes)
                .outcomePrices(outcomePrices)
                .outcomeTokenIds(existing == null ? Map.of() : existing.getOutcomeTokenIds())
                .build();
        localMetadata.put(normalized, metadata);
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

    private PolymarketApiClient.MarketMetadata findExactByQuestionHint(String conditionId, String questionHint) {
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
                return withSource(exactCondition, "GAMMA_SEARCH_EXACT");
            }
            PolymarketApiClient.MarketMetadata approximate = candidates.stream()
                    .filter(item -> normalizeQuestion(item.getQuestion()).equals(normalizedQuestion))
                    .min(Comparator.comparing(item -> questionDistance(normalizedQuestion, normalizeQuestion(item.getQuestion()))))
                    .orElse(null);
            if (approximate != null) {
                log.warn("Skip question-only metadata fallback for conditionId={} question={} because conditionId did not match exactly.",
                        normalizedConditionId, questionHint);
            }
            return null;
        } catch (Exception fallbackEx) {
            log.warn("Question-search fallback failed for conditionId={} question={}: {}",
                    conditionId, questionHint, fallbackEx.getMessage());
            return null;
        }
    }

    private boolean shouldProbeExactFallback(PolymarketApiClient.MarketMetadata primary,
                                             String questionHint) {
        if (primary == null || !StringUtils.hasText(questionHint)) {
            return false;
        }
        if (primary.isResolved()) {
            return false;
        }
        if (primary.isClosed()) {
            return true;
        }
        if (!primary.isAcceptingOrders()) {
            return true;
        }
        Long effectiveEndAt = primary.getEndAt();
        return effectiveEndAt != null && effectiveEndAt > 0 && effectiveEndAt <= System.currentTimeMillis();
    }

    private PolymarketApiClient.MarketMetadata withSource(PolymarketApiClient.MarketMetadata metadata, String source) {
        if (metadata == null) {
            return null;
        }
        return metadata.toBuilder()
                .resolutionSource(StringUtils.hasText(source) ? source.trim() : metadata.getResolutionSource())
                .resolutionObservedAt(System.currentTimeMillis())
                .build();
    }

    private PolymarketApiClient.MarketMetadata mergeMetadata(String conditionId,
                                                             PolymarketApiClient.MarketMetadata local,
                                                             PolymarketApiClient.MarketMetadata cached,
                                                             PolymarketApiClient.MarketMetadata primary,
                                                             PolymarketApiClient.MarketMetadata fallback) {
        PolymarketApiClient.MarketMetadata base = firstNonNull(primary, fallback, cached, local);
        if (base == null) {
            throw new IllegalStateException("Polymarket market metadata not found for conditionId=" + conditionId);
        }
        PolymarketApiClient.MarketMetadata.MarketMetadataBuilder builder = base.toBuilder()
                .conditionId(firstText(base.getConditionId(), conditionId, local == null ? null : local.getConditionId()))
                .marketId(firstText(base.getMarketId(),
                        fallback == null ? null : fallback.getMarketId(),
                        cached == null ? null : cached.getMarketId(),
                        local == null ? null : local.getMarketId()))
                .questionId(firstText(base.getQuestionId(),
                        fallback == null ? null : fallback.getQuestionId(),
                        cached == null ? null : cached.getQuestionId(),
                        local == null ? null : local.getQuestionId()))
                .question(firstText(base.getQuestion(),
                        fallback == null ? null : fallback.getQuestion(),
                        cached == null ? null : cached.getQuestion(),
                        local == null ? null : local.getQuestion()))
                .category(firstText(base.getCategory(),
                        fallback == null ? null : fallback.getCategory(),
                        cached == null ? null : cached.getCategory(),
                        local == null ? null : local.getCategory()))
                .minimumTickSize(base.getMinimumTickSize() == null
                        ? firstNonNull(fallback == null ? null : fallback.getMinimumTickSize(),
                        cached == null ? null : cached.getMinimumTickSize(),
                        local == null ? null : local.getMinimumTickSize())
                        : base.getMinimumTickSize())
                .endAt(firstNonNull(base.getEndAt(),
                        fallback == null ? null : fallback.getEndAt(),
                        cached == null ? null : cached.getEndAt(),
                        local == null ? null : local.getEndAt()))
                .umaEndAt(firstNonNull(base.getUmaEndAt(),
                        fallback == null ? null : fallback.getUmaEndAt(),
                        cached == null ? null : cached.getUmaEndAt(),
                        local == null ? null : local.getUmaEndAt()))
                .outcomes(base.getOutcomes() == null || base.getOutcomes().isEmpty()
                        ? firstNonNullList(fallback == null ? null : fallback.getOutcomes(),
                        cached == null ? null : cached.getOutcomes(),
                        local == null ? null : local.getOutcomes())
                        : base.getOutcomes())
                .outcomePrices(base.getOutcomePrices() == null || base.getOutcomePrices().isEmpty()
                        ? firstNonNullList(fallback == null ? null : fallback.getOutcomePrices(),
                        cached == null ? null : cached.getOutcomePrices(),
                        local == null ? null : local.getOutcomePrices())
                        : base.getOutcomePrices())
                .outcomeTokenIds(base.getOutcomeTokenIds() == null || base.getOutcomeTokenIds().isEmpty()
                        ? firstNonNullMap(fallback == null ? null : fallback.getOutcomeTokenIds(),
                        cached == null ? null : cached.getOutcomeTokenIds(),
                        local == null ? null : local.getOutcomeTokenIds())
                        : base.getOutcomeTokenIds());

        PolymarketApiClient.MarketMetadata trustedResolved = pickTrustedResolved(conditionId, local, cached, primary, fallback);
        if (trustedResolved != null) {
            builder.resolvedOutcome(trustedResolved.getResolvedOutcome())
                    .resolutionSource(firstText(trustedResolved.getResolutionSource(), base.getResolutionSource()))
                    .resolutionObservedAt(firstNonNull(trustedResolved.getResolutionObservedAt(), base.getResolutionObservedAt()))
                    .umaResolutionStatus(firstText(trustedResolved.getUmaResolutionStatus(), base.getUmaResolutionStatus()));
        } else {
            builder.resolvedOutcome(null)
                    .resolutionSource(firstText(base.getResolutionSource(),
                            cached == null ? null : cached.getResolutionSource(),
                            local == null ? null : local.getResolutionSource()))
                    .resolutionObservedAt(firstNonNull(base.getResolutionObservedAt(),
                            cached == null ? null : cached.getResolutionObservedAt(),
                            local == null ? null : local.getResolutionObservedAt()))
                    .umaResolutionStatus(firstText(base.getUmaResolutionStatus(),
                            fallback == null ? null : fallback.getUmaResolutionStatus(),
                            cached == null ? null : cached.getUmaResolutionStatus(),
                            local == null ? null : local.getUmaResolutionStatus()));
        }
        return builder.build();
    }

    private PolymarketApiClient.MarketMetadata pickTrustedResolved(String conditionId,
                                                                   PolymarketApiClient.MarketMetadata local,
                                                                   PolymarketApiClient.MarketMetadata cached,
                                                                   PolymarketApiClient.MarketMetadata primary,
                                                                   PolymarketApiClient.MarketMetadata fallback) {
        List<PolymarketApiClient.MarketMetadata> candidates = List.of(
                withNullable(local),
                withNullable(cached),
                withNullable(primary),
                withNullable(fallback)
        ).stream().flatMap(List::stream).toList();
        PolymarketApiClient.MarketMetadata firstResolved = null;
        for (PolymarketApiClient.MarketMetadata candidate : candidates) {
            if (!hasTrustedResolutionSignal(conditionId, candidate)) {
                continue;
            }
            if (firstResolved == null) {
                firstResolved = candidate;
                continue;
            }
            if (StringUtils.hasText(firstResolved.getResolvedOutcome())
                    && StringUtils.hasText(candidate.getResolvedOutcome())
                    && !firstResolved.getResolvedOutcome().equalsIgnoreCase(candidate.getResolvedOutcome())) {
                log.error("Conflicting resolved outcomes detected for conditionId={}: keep existing={} ignore={} sourceExisting={} sourceIncoming={}",
                        conditionId,
                        firstResolved.getResolvedOutcome(),
                        candidate.getResolvedOutcome(),
                        firstResolved.getResolutionSource(),
                        candidate.getResolutionSource());
                continue;
            }
            if (firstResolved.getResolutionObservedAt() == null
                    || (candidate.getResolutionObservedAt() != null
                    && candidate.getResolutionObservedAt() > firstResolved.getResolutionObservedAt())) {
                firstResolved = candidate;
            }
        }
        return firstResolved;
    }

    private boolean hasTrustedResolutionSignal(String conditionId, PolymarketApiClient.MarketMetadata metadata) {
        return metadata != null
                && metadata.isResolved()
                && StringUtils.hasText(metadata.getConditionId())
                && metadata.getConditionId().trim().equalsIgnoreCase(conditionId);
    }

    private List<PolymarketApiClient.MarketMetadata> withNullable(PolymarketApiClient.MarketMetadata metadata) {
        return metadata == null ? List.of() : List.of(metadata);
    }

    private boolean isAuthoritativeCacheEntry(PolymarketApiClient.MarketMetadata metadata) {
        if (metadata == null) {
            return false;
        }
        String source = metadata.getResolutionSource();
        return !StringUtils.hasText(source)
                || (!"LOCAL".equalsIgnoreCase(source.trim()) && !"SYNTHETIC".equalsIgnoreCase(source.trim()));
    }

    private boolean isAuthoritativeResolved(PolymarketApiClient.MarketMetadata metadata, String conditionId) {
        return hasTrustedResolutionSignal(conditionId, metadata) && isAuthoritativeCacheEntry(metadata);
    }

    private <T> T firstNonNull(T... candidates) {
        if (candidates == null) {
            return null;
        }
        for (T candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private List<String> firstNonNullList(List<String>... candidates) {
        if (candidates == null) {
            return List.of();
        }
        for (List<String> candidate : candidates) {
            if (candidate != null && !candidate.isEmpty()) {
                return new ArrayList<>(candidate);
            }
        }
        return List.of();
    }

    private Map<String, String> firstNonNullMap(Map<String, String>... candidates) {
        if (candidates == null) {
            return Map.of();
        }
        for (Map<String, String> candidate : candidates) {
            if (candidate != null && !candidate.isEmpty()) {
                return candidate;
            }
        }
        return Map.of();
    }

    private String normalizeQuestion(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ").toLowerCase();
    }

    private List<String> normalizeOutcomeHints(List<String> outcomeHints) {
        List<String> normalized = new ArrayList<>();
        if (outcomeHints == null) {
            return normalized;
        }
        for (String outcomeHint : outcomeHints) {
            if (!StringUtils.hasText(outcomeHint)) {
                continue;
            }
            normalized.add(outcomeHint.trim().toUpperCase(Locale.ROOT));
        }
        return normalized;
    }

    private boolean matchesOutcomeHints(List<String> normalizedOutcomeHints, List<String> outcomes) {
        if (normalizedOutcomeHints == null || normalizedOutcomeHints.isEmpty()) {
            return true;
        }
        if (outcomes == null || outcomes.isEmpty()) {
            return false;
        }
        List<String> normalizedOutcomes = outcomes.stream()
                .filter(StringUtils::hasText)
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .toList();
        return normalizedOutcomeHints.stream().allMatch(normalizedOutcomes::contains);
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
                .resolutionSource("SYNTHETIC")
                .resolutionObservedAt(System.currentTimeMillis())
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
