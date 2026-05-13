package com.metaverse.coin.strategy.job.smartwallet.service;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletMarketInfoService;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
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
    private final SmartWalletMarketInfoService marketInfoService;
    private final SmartWalletProperties properties;
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
        PolymarketApiClient.MarketMetadata persisted = loadPersistedMetadata(normalized);
        if (!refresh && isAuthoritativeCacheEntry(cached)) {
            return cached;
        }
        if (!refresh && persisted != null && !isDbOnlyResolved(persisted)) {
            PolymarketApiClient.MarketMetadata merged = mergeMetadata(normalized, local, cached, persisted, null);
            cache.put(normalized, merged);
            return merged;
        }
        if (!refresh && local != null) {
            return local;
        }
        if (!refresh && cached != null) {
            return cached;
        }
        if (!refresh && !hotPathExternalRefreshEnabled()) {
            throw new IllegalStateException("Polymarket market metadata is not cached locally for conditionId=" + normalized);
        }
        PolymarketApiClient.MarketMetadata primary = null;
        PolymarketApiClient.MarketMetadata exactFallback = null;
        PolymarketApiClient.MarketMetadata activeEventFallback = null;
        PolymarketApiClient.MarketMetadata clobFallback = null;
        try {
            primary = withSource(polymarketApiClient.fetchMarketMetadata(normalized), "GAMMA_MARKETS");
            if (shouldProbeClobFallback(primary)) {
                clobFallback = findClobMarketByConditionId(normalized);
            }
            if (!StringUtils.hasText(primary == null ? null : primary.getSlug())) {
                activeEventFallback = findActiveEventByConditionId(normalized);
            }
            if (shouldProbeExactFallback(primary, questionHint)) {
                exactFallback = findExactByQuestionHint(normalized, questionHint);
            }
            PolymarketApiClient.MarketMetadata merged = mergeMetadata(normalized,
                    firstNonNull(local, persisted),
                    cached,
                    primary,
                    mergeMetadataOrNull(normalized, clobFallback,
                            mergeMetadataOrNull(normalized, activeEventFallback, exactFallback)));
            cache.put(normalized, merged);
            return merged;
        } catch (RuntimeException ex) {
            clobFallback = findClobMarketByConditionId(normalized);
            activeEventFallback = findActiveEventByConditionId(normalized);
            exactFallback = findExactByQuestionHint(normalized, questionHint);
            if (clobFallback != null || activeEventFallback != null || exactFallback != null || cached != null || local != null || persisted != null) {
                PolymarketApiClient.MarketMetadata merged = mergeMetadata(normalized,
                        firstNonNull(local, persisted),
                        cached,
                        primary,
                        mergeMetadataOrNull(normalized, clobFallback,
                                mergeMetadataOrNull(normalized, activeEventFallback, exactFallback)));
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
            PolymarketApiClient.MarketMetadata metadata = getByConditionId(conditionId, refresh, questionHint);
            if (!refresh && shouldRepairIncompleteMetadata(metadata, questionHint, outcomeHints)) {
                try {
                    PolymarketApiClient.MarketMetadata repaired = getByConditionId(conditionId, true, questionHint);
                    if (!isMetadataLessComplete(repaired, metadata)) {
                        return repaired;
                    }
                } catch (RuntimeException repairEx) {
                    log.debug("Best-effort metadata repair skipped for conditionId={} question={}: {}",
                            conditionId, questionHint, repairEx.getMessage());
                }
            }
            return metadata;
        } catch (RuntimeException ex) {
            return buildSyntheticMetadata(conditionId, questionHint, outcomeHints);
        }
    }

    public Map<String, PolymarketApiClient.MarketMetadata> refreshBatchByConditionIds(java.util.Collection<String> conditionIds,
                                                                                       int chunkSize) {
        if (conditionIds == null || conditionIds.isEmpty()) {
            return Map.of();
        }
        List<String> normalizedIds = conditionIds.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        if (normalizedIds.isEmpty()) {
            return Map.of();
        }
        Map<String, PolymarketApiClient.MarketMetadata> fetched =
                polymarketApiClient.fetchMarketMetadataBatch(normalizedIds, chunkSize);
        Map<String, PolymarketApiClient.MarketMetadata> result = new LinkedHashMap<>();
        for (String conditionId : normalizedIds) {
            PolymarketApiClient.MarketMetadata primary = fetched.get(conditionId);
            PolymarketApiClient.MarketMetadata clobFallback = shouldProbeClobFallback(primary)
                    ? findClobMarketByConditionId(conditionId)
                    : null;
            if (primary == null && clobFallback == null) {
                continue;
            }
            // 批量接口 /markets?condition_ids= 通常不返回 slug/events，
            // 当 slug 缺失且有 question 时，通过 /public-search 补全事件级 slug
            PolymarketApiClient.MarketMetadata searchFallback = null;
            PolymarketApiClient.MarketMetadata slugSource = primary != null ? primary : clobFallback;
            PolymarketApiClient.MarketMetadata cachedMetadata = cache.get(conditionId);
            boolean slugMissing = (slugSource == null || !StringUtils.hasText(slugSource.getSlug()))
                    && (cachedMetadata == null || !StringUtils.hasText(cachedMetadata.getSlug()));
            String questionHint = slugSource != null ? slugSource.getQuestion() : null;
            if (slugMissing && StringUtils.hasText(questionHint)) {
                searchFallback = findExactByQuestionHint(conditionId, questionHint);
            }
            PolymarketApiClient.MarketMetadata activeEventFallback = slugMissing
                    ? findActiveEventByConditionId(conditionId)
                    : null;
            PolymarketApiClient.MarketMetadata merged = mergeMetadata(
                    conditionId,
                    localMetadata.get(conditionId),
                    cache.get(conditionId),
                    withSource(primary, "GAMMA_MARKETS_BATCH"),
                    mergeMetadataOrNull(conditionId, clobFallback,
                            mergeMetadataOrNull(conditionId, activeEventFallback, searchFallback))
            );
            cache.put(conditionId, merged);
            result.put(conditionId, merged);
        }
        return result;
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

    public PolymarketApiClient.MarketMetadata findEventMarketBySlugAndConditionId(String eventSlug,
                                                                                  String conditionId) {
        if (!StringUtils.hasText(eventSlug) || !StringUtils.hasText(conditionId)) {
            return null;
        }
        String normalizedConditionId = conditionId.trim();
        return polymarketApiClient.fetchEventMarketsBySlug(eventSlug.trim()).stream()
                .filter(metadata -> metadata != null && StringUtils.hasText(metadata.getConditionId()))
                .filter(metadata -> normalizedConditionId.equalsIgnoreCase(metadata.getConditionId().trim()))
                .findFirst()
                .map(metadata -> withSource(metadata, "GAMMA_EVENT_SLUG_EXACT"))
                .orElse(null);
    }

    private boolean hotPathExternalRefreshEnabled() {
        SmartWalletProperties.MarketMonitoring.MetadataRefresh refresh =
                properties.getMarketMonitoring().getMetadataRefresh();
        return refresh != null && refresh.isHotPathExternalRefreshEnabled();
    }

    private PolymarketApiClient.MarketMetadata loadPersistedMetadata(String conditionId) {
        if (!StringUtils.hasText(conditionId)) {
            return null;
        }
        try {
            return buildPersistedMetadata(conditionId.trim(), marketInfoService.listByMarketId(conditionId.trim()));
        } catch (RuntimeException ex) {
            log.debug("Failed to load persisted Polymarket metadata for conditionId={}: {}", conditionId, ex.getMessage());
            return null;
        }
    }

    private PolymarketApiClient.MarketMetadata buildPersistedMetadata(String conditionId,
                                                                      List<SmartWalletMarketInfoEntity> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        SmartWalletMarketInfoEntity primary = rows.stream()
                .filter(row -> row != null && StringUtils.hasText(row.getMarketId()))
                .max(Comparator.comparing(SmartWalletMarketInfoEntity::getLastMetadataSyncAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(SmartWalletMarketInfoEntity::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(SmartWalletMarketInfoEntity::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
        if (primary == null) {
            return null;
        }
        Map<String, String> outcomeTokenIds = new LinkedHashMap<>();
        Map<String, Integer> tokenIdToIndexSet = new LinkedHashMap<>();
        List<String> outcomes = new ArrayList<>();
        for (SmartWalletMarketInfoEntity row : rows) {
            if (row == null || !StringUtils.hasText(row.getOutcome())) {
                continue;
            }
            String outcome = normalizeOutcomeValue(row.getOutcome());
            if (!outcomes.contains(outcome)) {
                outcomes.add(outcome);
            }
            if (StringUtils.hasText(row.getTokenId())) {
                outcomeTokenIds.putIfAbsent(outcome, row.getTokenId().trim());
                if (row.getIndexSet() != null) {
                    tokenIdToIndexSet.putIfAbsent(row.getTokenId().trim(), row.getIndexSet());
                }
            }
        }
        if (outcomes.isEmpty()) {
            outcomes = List.of("YES", "NO");
        }
        List<String> outcomePrices = new ArrayList<>();
        for (int i = 0; i < outcomes.size(); i++) {
            outcomePrices.add("0.5");
        }
        boolean resolved = StringUtils.hasText(primary.getResolvedOutcome())
                || "RESOLVED".equalsIgnoreCase(primary.getMarketLifecycle());
        boolean closed = Boolean.TRUE.equals(primary.getClosed())
                || resolved
                || "CLOSED_UNRESOLVED".equalsIgnoreCase(primary.getMarketLifecycle());
        boolean acceptingOrders = Boolean.TRUE.equals(primary.getAcceptingOrders())
                && !closed
                && !resolved;
        Long observedAt = firstDateMillis(primary.getLastMetadataSyncAt(), primary.getUpdatedAt(), primary.getCreatedAt());
        return PolymarketApiClient.MarketMetadata.builder()
                .conditionId(conditionId)
                .marketId(firstText(primary.getMarketId(), conditionId))
                .question(firstText(primary.getQuestion(), conditionId))
                .slug(primary.getMarketSlug())
                .category(firstText(primary.getNormalizedCategory(), primary.getSourceCategory()))
                .minimumTickSize(new BigDecimal("0.01"))
                .active(acceptingOrders)
                .closed(closed)
                .acceptingOrders(acceptingOrders)
                .resolvedOutcome(normalizeOutcomeValue(primary.getResolvedOutcome()))
                .winningTokenId(winningTokenIdFromRows(rows, primary.getResolvedOutcome()))
                .endAt(primary.getMarketEndAt())
                .resolutionSource("DB_MARKET_INFO")
                .resolutionStatus(resolved ? MarketResolutionResolver.STATUS_RESOLVED : null)
                .resolutionObservedAt(observedAt == null ? System.currentTimeMillis() : observedAt)
                .outcomes(outcomes)
                .outcomePrices(outcomePrices)
                .outcomeTokenIds(outcomeTokenIds)
                .tokenIdToIndexSet(tokenIdToIndexSet)
                .build();
    }

    private Long firstDateMillis(Date... dates) {
        if (dates == null) {
            return null;
        }
        for (Date date : dates) {
            if (date != null) {
                return date.getTime();
            }
        }
        return null;
    }

    private String winningTokenIdFromRows(List<SmartWalletMarketInfoEntity> rows, String resolvedOutcome) {
        String normalizedResolved = normalizeOutcomeValue(resolvedOutcome);
        if (!StringUtils.hasText(normalizedResolved) || rows == null) {
            return null;
        }
        return rows.stream()
                .filter(row -> row != null && StringUtils.hasText(row.getTokenId()))
                .filter(row -> normalizedResolved.equals(normalizeOutcomeValue(row.getOutcome())))
                .map(SmartWalletMarketInfoEntity::getTokenId)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .findFirst()
                .orElse(null);
    }

    public String resolveCanonicalConditionId(String marketIdentifier,
                                              String questionHint,
                                              List<String> outcomeHints) {
        if (!StringUtils.hasText(marketIdentifier)) {
            return marketIdentifier;
        }
        String normalizedIdentifier = marketIdentifier.trim();
        try {
            PolymarketApiClient.MarketMetadata direct = getByConditionId(normalizedIdentifier, false, questionHint);
            if (direct != null
                    && isAuthoritativeCacheEntry(direct)
                    && StringUtils.hasText(direct.getConditionId())) {
                return direct.getConditionId().trim();
            }
        } catch (RuntimeException ignored) {
            // Fall through to question/outcome based normalization.
        }
        if (!StringUtils.hasText(questionHint)) {
            return normalizedIdentifier;
        }
        String normalizedQuestion = normalizeQuestion(questionHint);
        List<String> normalizedOutcomeHints = normalizeOutcomeHints(outcomeHints);
        List<PolymarketApiClient.MarketMetadata> candidates = polymarketApiClient.searchMarkets(questionHint, 20, true).stream()
                .filter(candidate -> candidate != null && StringUtils.hasText(candidate.getConditionId()))
                .filter(candidate -> normalizedQuestion.equals(normalizeQuestion(candidate.getQuestion())))
                .filter(candidate -> matchesOutcomeHints(normalizedOutcomeHints, candidate.getOutcomes()))
                .toList();
        List<PolymarketApiClient.MarketMetadata> identifierMatches = candidates.stream()
                .filter(candidate -> matchesIdentifier(normalizedIdentifier, candidate))
                .toList();
        if (identifierMatches.size() == 1) {
            return identifierMatches.get(0).getConditionId().trim();
        }
        if (candidates.size() == 1) {
            return candidates.get(0).getConditionId().trim();
        }
        return normalizedIdentifier;
    }

    public String resolveOutcomeByTokenId(PolymarketApiClient.MarketMetadata metadata,
                                          String tokenId,
                                          String fallbackOutcome) {
        String resolved = findOutcomeByTokenId(metadata, tokenId);
        if (StringUtils.hasText(resolved)) {
            return resolved;
        }
        return normalizeOutcomeValue(fallbackOutcome);
    }

    public String resolveWinningTokenId(PolymarketApiClient.MarketMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        if (StringUtils.hasText(metadata.getWinningTokenId())) {
            return metadata.getWinningTokenId().trim();
        }
        String resolvedOutcome = normalizeOutcomeValue(metadata.getResolvedOutcome());
        if (!StringUtils.hasText(resolvedOutcome) || metadata.getOutcomeTokenIds() == null) {
            return null;
        }
        return metadata.getOutcomeTokenIds().entrySet().stream()
                .filter(entry -> resolvedOutcome.equals(normalizeOutcomeValue(entry.getKey())))
                .map(Map.Entry::getValue)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .findFirst()
                .orElse(null);
    }

    /**
     * Resolve the CTF indexSet for a given tokenId using the market metadata.
     * Returns null if the tokenId cannot be mapped.
     */
    public Integer resolveIndexSet(PolymarketApiClient.MarketMetadata metadata, String tokenId) {
        if (metadata == null || !StringUtils.hasText(tokenId)) {
            return null;
        }
        return metadata.resolveIndexSet(tokenId);
    }

    /**
     * Determine if a position's tokenId represents a winning outcome.
     * Uses direct tokenId comparison with winningTokenId for maximum reliability,
     * falling back to outcome label comparison.
     *
     * @return true if the position won, false if it lost, null if indeterminate
     */
    public Boolean isWinningPosition(PolymarketApiClient.MarketMetadata metadata,
                                     String positionTokenId,
                                     String positionOutcome) {
        if (metadata == null || !metadata.isResolved()) {
            return null;
        }
        // Path 1: direct tokenId comparison (most reliable)
        String winningTokenId = resolveWinningTokenId(metadata);
        if (StringUtils.hasText(positionTokenId) && StringUtils.hasText(winningTokenId)) {
            return positionTokenId.trim().equals(winningTokenId.trim());
        }
        // Path 2: outcome label comparison (fallback)
        String effectiveOutcome = resolveOutcomeByTokenId(metadata, positionTokenId, positionOutcome);
        if (!StringUtils.hasText(effectiveOutcome)) {
            return null;
        }
        String resolvedOutcome = normalizeOutcomeValue(metadata.getResolvedOutcome());
        if (!StringUtils.hasText(resolvedOutcome)) {
            return null;
        }
        return resolvedOutcome.equals(normalizeOutcomeValue(effectiveOutcome));
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
                .winningTokenId(existing == null ? null : existing.getWinningTokenId())
                .endAt(existing == null ? null : existing.getEndAt())
                .umaEndAt(existing == null ? null : existing.getUmaEndAt())
                .resolutionSource(existing == null ? "LOCAL" : existing.getResolutionSource())
                .resolutionStatus(existing == null ? null : existing.getResolutionStatus())
                .manualCheckReason(existing == null ? null : existing.getManualCheckReason())
                .resolutionObservedAt(existing == null ? System.currentTimeMillis() : existing.getResolutionObservedAt())
                .outcomes(outcomes)
                .outcomePrices(outcomePrices)
                .outcomeTokenIds(existing == null ? Map.of() : existing.getOutcomeTokenIds())
                .tokenIdToIndexSet(existing == null ? Map.of() : existing.getTokenIdToIndexSet())
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

    private PolymarketApiClient.MarketMetadata findActiveEventByConditionId(String conditionId) {
        if (!StringUtils.hasText(conditionId)) {
            return null;
        }
        try {
            PolymarketApiClient.MarketMetadata metadata =
                    polymarketApiClient.fetchActiveEventMarketMetadata(conditionId.trim(), 20);
            if (metadata != null && StringUtils.hasText(metadata.getConditionId())
                    && metadata.getConditionId().trim().equalsIgnoreCase(conditionId.trim())) {
                log.info("Resolved Polymarket metadata for conditionId={} via active events index.", conditionId.trim());
                return withSource(metadata, "GAMMA_EVENTS_ACTIVE_INDEX");
            }
            return null;
        } catch (RuntimeException ex) {
            log.warn("Active event metadata lookup failed for conditionId={}: {}", conditionId, ex.getMessage());
            return null;
        }
    }

    private PolymarketApiClient.MarketMetadata findClobMarketByConditionId(String conditionId) {
        if (!StringUtils.hasText(conditionId)) {
            return null;
        }
        try {
            PolymarketApiClient.MarketMetadata metadata =
                    withSource(polymarketApiClient.fetchClobMarketMetadata(conditionId.trim()), "CLOB_MARKET");
            if (metadata != null && StringUtils.hasText(metadata.getConditionId())
                    && metadata.getConditionId().trim().equalsIgnoreCase(conditionId.trim())) {
                return metadata;
            }
        } catch (RuntimeException ex) {
            log.debug("CLOB market metadata lookup failed for conditionId={}: {}", conditionId, ex.getMessage());
        }
        return null;
    }

    private PolymarketApiClient.MarketMetadata mergeMetadataOrNull(String conditionId,
                                                                   PolymarketApiClient.MarketMetadata primary,
                                                                   PolymarketApiClient.MarketMetadata fallback) {
        if (primary == null) {
            return fallback;
        }
        if (fallback == null) {
            return primary;
        }
        return mergeMetadata(conditionId, null, null, primary, fallback);
    }

    private boolean shouldProbeExactFallback(PolymarketApiClient.MarketMetadata primary,
                                             String questionHint) {
        if (primary == null || !StringUtils.hasText(questionHint)) {
            return false;
        }
        if (isIncompleteMetadata(primary, List.of())) {
            return true;
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

    private boolean shouldProbeClobFallback(PolymarketApiClient.MarketMetadata primary) {
        if (primary == null) {
            return true;
        }
        if (primary.isResolved()) {
            return false;
        }
        return primary.isClosed() || !primary.isAcceptingOrders();
    }

    private boolean shouldRepairIncompleteMetadata(PolymarketApiClient.MarketMetadata metadata,
                                                   String questionHint,
                                                   List<String> outcomeHints) {
        if (metadata == null || !StringUtils.hasText(metadata.getConditionId()) || !StringUtils.hasText(questionHint)) {
            return false;
        }
        if (polymarketApiClient.isMetadataEndpointCoolingDown()) {
            return false;
        }
        return isIncompleteMetadata(metadata, outcomeHints);
    }

    private boolean isIncompleteMetadata(PolymarketApiClient.MarketMetadata metadata, List<String> outcomeHints) {
        if (metadata == null) {
            return true;
        }
        if (!StringUtils.hasText(metadata.getSlug())) {
            return true;
        }
        if (metadata.getOutcomeTokenIds() == null || metadata.getOutcomeTokenIds().isEmpty()) {
            return true;
        }
        if (outcomeHints != null) {
            for (String outcomeHint : outcomeHints) {
                String normalizedHint = normalizeOutcomeValue(outcomeHint);
                if (!StringUtils.hasText(normalizedHint)) {
                    continue;
                }
                boolean mapped = metadata.getOutcomeTokenIds().entrySet().stream()
                        .anyMatch(entry -> normalizedHint.equals(normalizeOutcomeValue(entry.getKey()))
                                && StringUtils.hasText(entry.getValue()));
                if (!mapped) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isMetadataLessComplete(PolymarketApiClient.MarketMetadata candidate,
                                           PolymarketApiClient.MarketMetadata baseline) {
        return metadataCompletenessScore(candidate) < metadataCompletenessScore(baseline);
    }

    private int metadataCompletenessScore(PolymarketApiClient.MarketMetadata metadata) {
        if (metadata == null) {
            return -1;
        }
        int score = 0;
        if (StringUtils.hasText(metadata.getSlug())) {
            score += 4;
        }
        if (metadata.getOutcomeTokenIds() != null && !metadata.getOutcomeTokenIds().isEmpty()) {
            score += 4;
        }
        if (metadata.getEndAt() != null && metadata.getEndAt() > 0) {
            score += 2;
        }
        if (StringUtils.hasText(metadata.getQuestion())) {
            score += 1;
        }
        if (metadata.getOutcomes() != null && !metadata.getOutcomes().isEmpty()) {
            score += 1;
        }
        return score;
    }

    private PolymarketApiClient.MarketMetadata withSource(PolymarketApiClient.MarketMetadata metadata, String source) {
        if (metadata == null) {
            return null;
        }
        return metadata.toBuilder()
                .resolutionSource(firstText(metadata.getResolutionSource(), source))
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
                .slug(firstText(base.getSlug(),
                        fallback == null ? null : fallback.getSlug(),
                        cached == null ? null : cached.getSlug(),
                        local == null ? null : local.getSlug()))
                .category(firstText(base.getCategory(),
                        fallback == null ? null : fallback.getCategory(),
                        cached == null ? null : cached.getCategory(),
                        local == null ? null : local.getCategory()))
                .minimumTickSize(base.getMinimumTickSize() == null
                        ? firstNonNull(fallback == null ? null : fallback.getMinimumTickSize(),
                        cached == null ? null : cached.getMinimumTickSize(),
                        local == null ? null : local.getMinimumTickSize())
                        : base.getMinimumTickSize())
                .winningTokenId(firstText(base.getWinningTokenId(),
                        fallback == null ? null : fallback.getWinningTokenId(),
                        cached == null ? null : cached.getWinningTokenId(),
                        local == null ? null : local.getWinningTokenId()))
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
                        : base.getOutcomeTokenIds())
                .tokenIdToIndexSet(base.getTokenIdToIndexSet() == null || base.getTokenIdToIndexSet().isEmpty()
                        ? firstNonNullMap(fallback == null ? null : fallback.getTokenIdToIndexSet(),
                        cached == null ? null : cached.getTokenIdToIndexSet(),
                        local == null ? null : local.getTokenIdToIndexSet())
                        : base.getTokenIdToIndexSet());

        String mergedWinningTokenId = firstText(base.getWinningTokenId(),
                fallback == null ? null : fallback.getWinningTokenId(),
                cached == null ? null : cached.getWinningTokenId(),
                local == null ? null : local.getWinningTokenId());
        PolymarketApiClient.MarketMetadata trustedResolved = pickTrustedResolved(conditionId, local, cached, primary, fallback);
        if (trustedResolved != null) {
            builder.resolvedOutcome(trustedResolved.getResolvedOutcome())
                    .winningTokenId(firstText(resolveWinningTokenId(trustedResolved), mergedWinningTokenId))
                    .resolutionSource(firstText(trustedResolved.getResolutionSource(), base.getResolutionSource()))
                    .resolutionStatus(firstText(trustedResolved.getResolutionStatus(), base.getResolutionStatus()))
                    .manualCheckReason(firstText(trustedResolved.getManualCheckReason(), base.getManualCheckReason()))
                    .resolutionObservedAt(firstNonNull(trustedResolved.getResolutionObservedAt(), base.getResolutionObservedAt()))
                    .umaResolutionStatus(firstText(trustedResolved.getUmaResolutionStatus(), base.getUmaResolutionStatus()));
        } else {
            builder.resolvedOutcome(null)
                    .winningTokenId(null)
                    .resolutionSource(firstText(base.getResolutionSource(),
                            cached == null ? null : cached.getResolutionSource(),
                            local == null ? null : local.getResolutionSource()))
                    .resolutionStatus(firstText(base.getResolutionStatus(),
                            fallback == null ? null : fallback.getResolutionStatus(),
                            cached == null ? null : cached.getResolutionStatus(),
                            local == null ? null : local.getResolutionStatus()))
                    .manualCheckReason(firstText(base.getManualCheckReason(),
                            fallback == null ? null : fallback.getManualCheckReason(),
                            cached == null ? null : cached.getManualCheckReason(),
                            local == null ? null : local.getManualCheckReason()))
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
                && isTrustedResolutionSource(metadata)
                && StringUtils.hasText(metadata.getConditionId())
                && metadata.getConditionId().trim().equalsIgnoreCase(conditionId);
    }

    private boolean isTrustedResolutionSource(PolymarketApiClient.MarketMetadata metadata) {
        if (metadata == null) {
            return false;
        }
        String source = metadata.getResolutionSource();
        if (!StringUtils.hasText(source)) {
            return true;
        }
        String normalized = source.trim().toUpperCase(Locale.ROOT);
        return !"LOCAL".equals(normalized)
                && !"SYNTHETIC".equals(normalized)
                && !"DB_MARKET_INFO".equals(normalized);
    }

    private boolean isDbOnlyResolved(PolymarketApiClient.MarketMetadata metadata) {
        if (metadata == null || !metadata.isResolved()) {
            return false;
        }
        String source = metadata.getResolutionSource();
        return StringUtils.hasText(source) && "DB_MARKET_INFO".equalsIgnoreCase(source.trim());
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

    @SafeVarargs
    private <K, V> Map<K, V> firstNonNullMap(Map<K, V>... candidates) {
        if (candidates == null) {
            return Map.of();
        }
        for (Map<K, V> candidate : candidates) {
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
                .resolutionStatus(MarketResolutionResolver.STATUS_UNRESOLVED)
                .resolutionObservedAt(System.currentTimeMillis())
                .outcomes(outcomes)
                .outcomePrices(outcomePrices)
                .outcomeTokenIds(Map.of())
                .build();
    }

    private String findOutcomeByTokenId(PolymarketApiClient.MarketMetadata metadata, String tokenId) {
        if (metadata == null || !StringUtils.hasText(tokenId) || metadata.getOutcomeTokenIds() == null) {
            return null;
        }
        String normalizedTokenId = tokenId.trim();
        return metadata.getOutcomeTokenIds().entrySet().stream()
                .filter(entry -> StringUtils.hasText(entry.getValue()))
                .filter(entry -> normalizedTokenId.equals(entry.getValue().trim()))
                .map(Map.Entry::getKey)
                .map(this::normalizeOutcomeValue)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
    }

    private String normalizeOutcomeValue(String outcome) {
        if (!StringUtils.hasText(outcome)) {
            return null;
        }
        return outcome.trim().toUpperCase(Locale.ROOT);
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

    private boolean matchesIdentifier(String normalizedIdentifier,
                                      PolymarketApiClient.MarketMetadata candidate) {
        if (!StringUtils.hasText(normalizedIdentifier) || candidate == null) {
            return false;
        }
        return normalizedIdentifier.equalsIgnoreCase(candidate.getConditionId())
                || normalizedIdentifier.equalsIgnoreCase(candidate.getMarketId());
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
