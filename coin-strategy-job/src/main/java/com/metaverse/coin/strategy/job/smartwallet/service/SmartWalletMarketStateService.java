package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SmartWalletMarketStateService {

    public static final String LIFECYCLE_ACTIVE = "ACTIVE";
    public static final String LIFECYCLE_CLOSED_UNRESOLVED = "CLOSED_UNRESOLVED";
    public static final String LIFECYCLE_RESOLVED = "RESOLVED";

    public static final String CATEGORY_POLITICS = "POLITICS";
    public static final String CATEGORY_WEATHER = "WEATHER";
    public static final String CATEGORY_SPORTS = "SPORTS";
    public static final String CATEGORY_CRYPTO = "CRYPTO";
    public static final String CATEGORY_MACRO = "MACRO";
    public static final String CATEGORY_BUSINESS = "BUSINESS";
    public static final String CATEGORY_ENTERTAINMENT = "ENTERTAINMENT";
    public static final String CATEGORY_CURRENT_EVENTS = "CURRENT_EVENTS";
    public static final String CATEGORY_OTHER = "OTHER";

    private static final long DEFAULT_METADATA_STALE_MS = 60_000L;

    private final SmartWalletMarketInfoService marketInfoService;
    private final PolymarketMarketMetadataService marketMetadataService;

    public MarketStateSnapshot refreshMarket(String marketId,
                                            String questionHint,
                                            List<String> outcomeHints) {
        return refreshMarket(marketId, questionHint, outcomeHints, false);
    }

    public MarketStateSnapshot refreshMarket(String marketId,
                                            String questionHint,
                                            List<String> outcomeHints,
                                            boolean forceRefresh) {
        if (!StringUtils.hasText(marketId)) {
            return MarketStateSnapshot.unknown(null);
        }
        String normalizedMarketId = marketId.trim();
        List<SmartWalletMarketInfoEntity> existingRows = marketInfoService.listByMarketId(normalizedMarketId);
        if (!forceRefresh) {
            MarketStateSnapshot cached = snapshotFromFreshRows(existingRows);
            if (cached != null) {
                return cached;
            }
        }

        String effectiveQuestion = firstNonBlank(questionHint, firstQuestion(existingRows), normalizedMarketId);
        List<String> effectiveOutcomes = normalizeOutcomes(outcomeHints, existingRows);
        PolymarketApiClient.MarketMetadata metadata = marketMetadataService.getBestEffortByConditionId(
                normalizedMarketId,
                forceRefresh,
                effectiveQuestion,
                effectiveOutcomes
        );

        if (isSynthetic(metadata) && !existingRows.isEmpty()) {
            return snapshotFromRows(existingRows);
        }

        List<SmartWalletMarketInfoEntity> rowsToPersist = ensureRows(normalizedMarketId, metadata, existingRows, effectiveQuestion);
        MarketStateSnapshot snapshot = buildSnapshot(normalizedMarketId, metadata, rowsToPersist, effectiveQuestion);
        persistSnapshot(rowsToPersist, snapshot);
        return snapshot;
    }

    public Map<String, MarketStateSnapshot> refreshMarkets(Collection<String> marketIds, boolean forceRefresh) {
        if (marketIds == null || marketIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<SmartWalletMarketInfoEntity>> rowsByMarketId = marketInfoService.batchListByMarketIds(marketIds);
        Map<String, MarketStateSnapshot> result = new LinkedHashMap<>();
        for (String marketId : marketIds.stream().filter(StringUtils::hasText).map(String::trim).distinct().toList()) {
            List<SmartWalletMarketInfoEntity> existingRows = rowsByMarketId.getOrDefault(marketId, List.of());
            if (!forceRefresh) {
                MarketStateSnapshot cached = snapshotFromFreshRows(existingRows);
                if (cached != null) {
                    result.put(marketId, cached);
                    continue;
                }
            }
            String effectiveQuestion = firstNonBlank(firstQuestion(existingRows), marketId);
            List<String> effectiveOutcomes = normalizeOutcomes(null, existingRows);
            PolymarketApiClient.MarketMetadata metadata = marketMetadataService.getBestEffortByConditionId(
                    marketId,
                    forceRefresh,
                    effectiveQuestion,
                    effectiveOutcomes
            );
            if (isSynthetic(metadata) && !existingRows.isEmpty()) {
                result.put(marketId, snapshotFromRows(existingRows));
                continue;
            }
            List<SmartWalletMarketInfoEntity> rowsToPersist = ensureRows(marketId, metadata, existingRows, effectiveQuestion);
            MarketStateSnapshot snapshot = buildSnapshot(marketId, metadata, rowsToPersist, effectiveQuestion);
            persistSnapshot(rowsToPersist, snapshot);
            result.put(marketId, snapshot);
        }
        return result;
    }

    public MarketStateSnapshot loadPersistedMarket(String marketId) {
        if (!StringUtils.hasText(marketId)) {
            return MarketStateSnapshot.unknown(null);
        }
        Map<String, MarketStateSnapshot> snapshots = loadPersistedMarkets(List.of(marketId.trim()));
        return snapshots.getOrDefault(marketId.trim(), MarketStateSnapshot.unknown(marketId.trim()));
    }

    public Map<String, MarketStateSnapshot> loadPersistedMarkets(Collection<String> marketIds) {
        List<String> normalizedIds = normalizeMarketIds(marketIds);
        if (normalizedIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<SmartWalletMarketInfoEntity>> rowsByMarketId = marketInfoService.batchListByMarketIds(normalizedIds);
        Map<String, MarketStateSnapshot> result = new LinkedHashMap<>();
        for (String marketId : normalizedIds) {
            MarketStateSnapshot snapshot = snapshotFromRows(rowsByMarketId.get(marketId));
            if (snapshot != null) {
                result.put(marketId, snapshot);
            }
        }
        return result;
    }

    public Map<String, MarketStateSnapshot> refreshPersistedMarkets(Collection<String> marketIds, int gammaChunkSize) {
        List<String> normalizedIds = normalizeMarketIds(marketIds);
        if (normalizedIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<SmartWalletMarketInfoEntity>> rowsByMarketId = marketInfoService.batchListByMarketIds(normalizedIds);
        Map<String, PolymarketApiClient.MarketMetadata> metadataByMarketId =
                marketMetadataService.refreshBatchByConditionIds(normalizedIds, gammaChunkSize);
        Map<String, MarketStateSnapshot> result = new LinkedHashMap<>();
        for (String marketId : normalizedIds) {
            List<SmartWalletMarketInfoEntity> existingRows = rowsByMarketId.getOrDefault(marketId, List.of());
            PolymarketApiClient.MarketMetadata metadata = metadataByMarketId.get(marketId);
            if (isSynthetic(metadata) && !existingRows.isEmpty()) {
                result.put(marketId, snapshotFromRows(existingRows));
                continue;
            }
            if (metadata == null) {
                MarketStateSnapshot persisted = snapshotFromRows(existingRows);
                if (persisted != null) {
                    result.put(marketId, persisted);
                }
                continue;
            }
            String effectiveQuestion = firstNonBlank(metadata.getQuestion(), firstQuestion(existingRows), marketId);
            List<SmartWalletMarketInfoEntity> rowsToPersist = ensureRows(marketId, metadata, existingRows, effectiveQuestion);
            MarketStateSnapshot snapshot = buildSnapshot(marketId, metadata, rowsToPersist, effectiveQuestion);
            persistSnapshot(rowsToPersist, snapshot);
            result.put(marketId, snapshot);
        }
        return result;
    }

    public boolean isActiveLifecycle(String lifecycle) {
        return LIFECYCLE_ACTIVE.equalsIgnoreCase(lifecycle);
    }

    private MarketStateSnapshot snapshotFromFreshRows(List<SmartWalletMarketInfoEntity> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        Date lastSyncAt = rows.stream()
                .map(SmartWalletMarketInfoEntity::getLastMetadataSyncAt)
                .filter(java.util.Objects::nonNull)
                .max(Date::compareTo)
                .orElse(null);
        if (lastSyncAt == null || System.currentTimeMillis() - lastSyncAt.getTime() > DEFAULT_METADATA_STALE_MS) {
            return null;
        }
        return snapshotFromRows(rows);
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
                .filter(java.util.Objects::nonNull)
                .max(Date::compareTo)
                .orElse(primary.getLastMetadataSyncAt());
        return MarketStateSnapshot.builder()
                .marketId(primary.getMarketId())
                .question(firstNonBlank(primary.getQuestion(), primary.getMarketId()))
                .marketSlug(primary.getMarketSlug())
                .marketEndAt(primary.getMarketEndAt())
                .sourceCategory(primary.getSourceCategory())
                .normalizedCategory(firstNonBlank(primary.getNormalizedCategory(), CATEGORY_OTHER))
                .marketLifecycle(lifecycle)
                .closed(closed)
                .acceptingOrders(acceptingOrders)
                .resolvedOutcome(resolvedOutcome)
                .lastMetadataSyncAt(lastSyncAt)
                .build();
    }

    private String resolvePersistedLifecycle(SmartWalletMarketInfoEntity primary,
                                             String resolvedOutcome,
                                             boolean closed,
                                             boolean acceptingOrders) {
        String persistedLifecycle = firstNonBlank(
                primary == null ? null : primary.getMarketLifecycle(),
                LIFECYCLE_ACTIVE
        );
        if (primary == null) {
            return persistedLifecycle;
        }
        if (StringUtils.hasText(resolvedOutcome) || LIFECYCLE_RESOLVED.equalsIgnoreCase(persistedLifecycle)) {
            return LIFECYCLE_RESOLVED;
        }
        if (closed) {
            return LIFECYCLE_CLOSED_UNRESOLVED;
        }
        if (!acceptingOrders) {
            return LIFECYCLE_CLOSED_UNRESOLVED;
        }
        Long marketEndAt = primary.getMarketEndAt();
        if (marketEndAt != null && marketEndAt > 0 && marketEndAt <= System.currentTimeMillis()) {
            return LIFECYCLE_CLOSED_UNRESOLVED;
        }
        return persistedLifecycle;
    }

    private MarketStateSnapshot buildSnapshot(String marketId,
                                             PolymarketApiClient.MarketMetadata metadata,
                                             List<SmartWalletMarketInfoEntity> existingRows,
                                             String questionFallback) {
        String resolvedOutcome = normalizeValue(metadata == null ? null : metadata.getResolvedOutcome());
        Long marketEndAt = firstNonNull(
                metadata == null ? null : metadata.getEndAt(),
                firstMarketEndAt(existingRows)
        );
        String sourceCategory = firstNonBlank(
                metadata == null ? null : metadata.getCategory(),
                firstSourceCategory(existingRows)
        );
        boolean closed = metadata != null && metadata.isClosed();
        boolean acceptingOrders = metadata == null || metadata.isAcceptingOrders();
        if (metadata == null && !existingRows.isEmpty()) {
            closed = Boolean.TRUE.equals(existingRows.get(0).getClosed());
            acceptingOrders = existingRows.get(0).getAcceptingOrders() == null || existingRows.get(0).getAcceptingOrders();
        }
        String lifecycle = resolveLifecycle(resolvedOutcome, closed, acceptingOrders, marketEndAt);
        return MarketStateSnapshot.builder()
                .marketId(marketId)
                .question(firstNonBlank(metadata == null ? null : metadata.getQuestion(), firstQuestion(existingRows), questionFallback, marketId))
                .marketSlug(firstNonBlank(metadata == null ? null : metadata.getSlug(), firstMarketSlug(existingRows)))
                .marketEndAt(marketEndAt)
                .sourceCategory(sourceCategory)
                .normalizedCategory(resolveNormalizedCategory(sourceCategory, firstNonBlank(
                        metadata == null ? null : metadata.getQuestion(),
                        firstQuestion(existingRows),
                        questionFallback
                )))
                .marketLifecycle(lifecycle)
                .closed(closed)
                .acceptingOrders(acceptingOrders)
                .resolvedOutcome(resolvedOutcome)
                .lastMetadataSyncAt(new Date())
                .build();
    }

    private List<SmartWalletMarketInfoEntity> ensureRows(String marketId,
                                                         PolymarketApiClient.MarketMetadata metadata,
                                                         List<SmartWalletMarketInfoEntity> existingRows,
                                                         String questionFallback) {
        List<String> effectiveOutcomes = normalizeMetadataOutcomes(metadata, existingRows);
        if (effectiveOutcomes.isEmpty()) {
            return existingRows == null ? List.of() : new ArrayList<>(existingRows);
        }
        Map<String, SmartWalletMarketInfoEntity> rowsByOutcome = new LinkedHashMap<>();
        if (existingRows != null) {
            for (SmartWalletMarketInfoEntity row : existingRows) {
                if (row != null && StringUtils.hasText(row.getOutcome())) {
                    rowsByOutcome.put(normalizeValue(row.getOutcome()), row);
                }
            }
        }
        for (String outcome : effectiveOutcomes) {
            String tokenId = resolveTokenId(metadata, outcome);
            Integer indexSet = metadata == null ? null : metadata.resolveIndexSet(tokenId);
            SmartWalletMarketInfoEntity row = marketInfoService.getOrCreate(
                    marketId,
                    outcome,
                    firstNonBlank(metadata == null ? null : metadata.getQuestion(), questionFallback, marketId),
                    tokenId,
                    indexSet,
                    metadata == null ? null : metadata.getEndAt()
            );
            rowsByOutcome.put(normalizeValue(outcome), row);
        }
        return new ArrayList<>(rowsByOutcome.values());
    }

    private void persistSnapshot(List<SmartWalletMarketInfoEntity> rows, MarketStateSnapshot snapshot) {
        if (rows == null || rows.isEmpty() || snapshot == null) {
            return;
        }
        for (SmartWalletMarketInfoEntity row : rows) {
            boolean changed = false;
            if (StringUtils.hasText(snapshot.question()) && !snapshot.question().equals(row.getQuestion())) {
                row.setQuestion(snapshot.question());
                changed = true;
            }
            if (StringUtils.hasText(snapshot.marketSlug()) && !snapshot.marketSlug().equals(row.getMarketSlug())) {
                row.setMarketSlug(snapshot.marketSlug());
                changed = true;
            }
            if (!java.util.Objects.equals(snapshot.marketEndAt(), row.getMarketEndAt())) {
                row.setMarketEndAt(snapshot.marketEndAt());
                changed = true;
            }
            if (!java.util.Objects.equals(snapshot.sourceCategory(), row.getSourceCategory())) {
                row.setSourceCategory(snapshot.sourceCategory());
                changed = true;
            }
            if (!java.util.Objects.equals(snapshot.normalizedCategory(), row.getNormalizedCategory())) {
                row.setNormalizedCategory(snapshot.normalizedCategory());
                changed = true;
            }
            if (!java.util.Objects.equals(snapshot.marketLifecycle(), row.getMarketLifecycle())) {
                row.setMarketLifecycle(snapshot.marketLifecycle());
                changed = true;
            }
            if (!java.util.Objects.equals(snapshot.closed(), row.getClosed())) {
                row.setClosed(snapshot.closed());
                changed = true;
            }
            if (!java.util.Objects.equals(snapshot.acceptingOrders(), row.getAcceptingOrders())) {
                row.setAcceptingOrders(snapshot.acceptingOrders());
                changed = true;
            }
            if (!java.util.Objects.equals(snapshot.resolvedOutcome(), row.getResolvedOutcome())) {
                row.setResolvedOutcome(snapshot.resolvedOutcome());
                changed = true;
            }
            if (changed || row.getLastMetadataSyncAt() == null) {
                row.setLastMetadataSyncAt(snapshot.lastMetadataSyncAt());
                marketInfoService.update(row);
            }
        }
    }

    private String resolveLifecycle(String resolvedOutcome,
                                    boolean closed,
                                    boolean acceptingOrders,
                                    Long marketEndAt) {
        if (StringUtils.hasText(resolvedOutcome)) {
            return LIFECYCLE_RESOLVED;
        }
        if (closed) {
            return LIFECYCLE_CLOSED_UNRESOLVED;
        }
        if (!acceptingOrders) {
            return LIFECYCLE_CLOSED_UNRESOLVED;
        }
        if (marketEndAt != null && marketEndAt > 0 && marketEndAt <= System.currentTimeMillis()) {
            return LIFECYCLE_CLOSED_UNRESOLVED;
        }
        return LIFECYCLE_ACTIVE;
    }

    private String resolveNormalizedCategory(String sourceCategory, String question) {
        String combined = normalizeForMatching(sourceCategory + " " + question);
        if (containsAny(combined, "politic", "election", "president", "senate", "congress", "parliament", "governor", "mayor", "prime minister", "referendum", "vote")) {
            return CATEGORY_POLITICS;
        }
        if (containsAny(combined, "weather", "rain", "snow", "temperature", "hurricane", "storm", "sunny", "wind", "climate")) {
            return CATEGORY_WEATHER;
        }
        if (containsAny(combined, "sports", "nba", "nfl", "mlb", "nhl", "soccer", "football", "baseball", "basketball", "tennis", "golf", "ufc", "fifa", "champions league", "match")) {
            return CATEGORY_SPORTS;
        }
        if (containsAny(combined, "crypto", "bitcoin", "ethereum", "solana", "token", "blockchain", "defi", "nft", "btc", "eth")) {
            return CATEGORY_CRYPTO;
        }
        if (containsAny(combined, "macro", "cpi", "inflation", "gdp", "fed", "fomc", "recession", "unemployment", "interest rate", "treasury", "oil", "gold")) {
            return CATEGORY_MACRO;
        }
        if (containsAny(combined, "business", "company", "earnings", "ipo", "merger", "acquisition", "ceo", "revenue", "stock", "tesla", "apple", "nvidia", "amazon")) {
            return CATEGORY_BUSINESS;
        }
        if (containsAny(combined, "entertainment", "movie", "film", "oscar", "grammy", "music", "tv", "television", "celebrity", "box office")) {
            return CATEGORY_ENTERTAINMENT;
        }
        if (containsAny(combined, "news", "current event", "breaking", "world", "war", "conflict", "headline")) {
            return CATEGORY_CURRENT_EVENTS;
        }
        return CATEGORY_OTHER;
    }

    private boolean containsAny(String haystack, String... needles) {
        if (!StringUtils.hasText(haystack) || needles == null) {
            return false;
        }
        for (String needle : needles) {
            if (StringUtils.hasText(needle) && haystack.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String normalizeForMatching(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String firstQuestion(List<SmartWalletMarketInfoEntity> rows) {
        if (rows == null) {
            return null;
        }
        for (SmartWalletMarketInfoEntity row : rows) {
            if (StringUtils.hasText(row.getQuestion())) {
                return row.getQuestion().trim();
            }
        }
        return null;
    }

    private String firstMarketSlug(List<SmartWalletMarketInfoEntity> rows) {
        if (rows == null) {
            return null;
        }
        for (SmartWalletMarketInfoEntity row : rows) {
            if (StringUtils.hasText(row.getMarketSlug())) {
                return row.getMarketSlug().trim();
            }
        }
        return null;
    }

    private SmartWalletMarketInfoEntity primaryRow(List<SmartWalletMarketInfoEntity> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        return rows.stream()
                .sorted(java.util.Comparator.comparing(SmartWalletMarketInfoEntity::getUpdatedAt,
                                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()))
                        .thenComparing(SmartWalletMarketInfoEntity::getId,
                                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .findFirst()
                .orElse(rows.get(0));
    }

    private String firstSourceCategory(List<SmartWalletMarketInfoEntity> rows) {
        if (rows == null) {
            return null;
        }
        for (SmartWalletMarketInfoEntity row : rows) {
            if (StringUtils.hasText(row.getSourceCategory())) {
                return row.getSourceCategory().trim();
            }
        }
        return null;
    }

    private Long firstMarketEndAt(List<SmartWalletMarketInfoEntity> rows) {
        if (rows == null) {
            return null;
        }
        for (SmartWalletMarketInfoEntity row : rows) {
            if (row.getMarketEndAt() != null && row.getMarketEndAt() > 0) {
                return row.getMarketEndAt();
            }
        }
        return null;
    }

    private List<String> normalizeOutcomes(List<String> providedOutcomes, List<SmartWalletMarketInfoEntity> rows) {
        Set<String> normalized = new LinkedHashSet<>();
        if (providedOutcomes != null) {
            for (String outcome : providedOutcomes) {
                if (StringUtils.hasText(outcome)) {
                    normalized.add(outcome.trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        if (rows != null) {
            for (SmartWalletMarketInfoEntity row : rows) {
                if (StringUtils.hasText(row.getOutcome())) {
                    normalized.add(row.getOutcome().trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        return new ArrayList<>(normalized);
    }

    private List<String> normalizeMetadataOutcomes(PolymarketApiClient.MarketMetadata metadata,
                                                   List<SmartWalletMarketInfoEntity> rows) {
        Set<String> normalized = new LinkedHashSet<>();
        if (metadata != null && metadata.getOutcomes() != null) {
            for (String outcome : metadata.getOutcomes()) {
                String normalizedOutcome = normalizeValue(outcome);
                if (StringUtils.hasText(normalizedOutcome)) {
                    normalized.add(normalizedOutcome);
                }
            }
        }
        if (metadata != null && metadata.getOutcomeTokenIds() != null) {
            for (String outcome : metadata.getOutcomeTokenIds().keySet()) {
                String normalizedOutcome = normalizeValue(outcome);
                if (StringUtils.hasText(normalizedOutcome)) {
                    normalized.add(normalizedOutcome);
                }
            }
        }
        normalized.addAll(normalizeOutcomes(null, rows));
        return new ArrayList<>(normalized);
    }

    private String resolveTokenId(PolymarketApiClient.MarketMetadata metadata, String outcome) {
        if (metadata == null || metadata.getOutcomeTokenIds() == null || !StringUtils.hasText(outcome)) {
            return null;
        }
        String normalizedOutcome = normalizeValue(outcome);
        for (Map.Entry<String, String> entry : metadata.getOutcomeTokenIds().entrySet()) {
            if (normalizedOutcome.equals(normalizeValue(entry.getKey())) && StringUtils.hasText(entry.getValue())) {
                return entry.getValue().trim();
            }
        }
        return null;
    }

    private List<String> normalizeMarketIds(Collection<String> marketIds) {
        if (marketIds == null || marketIds.isEmpty()) {
            return List.of();
        }
        return marketIds.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private boolean isSynthetic(PolymarketApiClient.MarketMetadata metadata) {
        return metadata != null
                && StringUtils.hasText(metadata.getResolutionSource())
                && "SYNTHETIC".equalsIgnoreCase(metadata.getResolutionSource().trim());
    }

    private String normalizeValue(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
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

    private String firstNonBlank(String... candidates) {
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

    @Builder
    public record MarketStateSnapshot(
            String marketId,
            String question,
            String marketSlug,
            Long marketEndAt,
            String sourceCategory,
            String normalizedCategory,
            String marketLifecycle,
            boolean closed,
            boolean acceptingOrders,
            String resolvedOutcome,
            Date lastMetadataSyncAt
    ) {
        public static MarketStateSnapshot unknown(String marketId) {
            return MarketStateSnapshot.builder()
                    .marketId(marketId)
                    .normalizedCategory(CATEGORY_OTHER)
                    .marketLifecycle(LIFECYCLE_ACTIVE)
                    .acceptingOrders(true)
                    .build();
        }

        public boolean isActive() {
            return LIFECYCLE_ACTIVE.equalsIgnoreCase(marketLifecycle);
        }

        public String blockingReasonCode() {
            if (LIFECYCLE_RESOLVED.equalsIgnoreCase(marketLifecycle)) {
                return "MARKET_RESOLVED";
            }
            if (!acceptingOrders) {
                return "MARKET_NOT_ACCEPTING_ORDERS";
            }
            return "MARKET_CLOSED";
        }

        public String blockingReasonMessage() {
            return switch (blockingReasonCode()) {
                case "MARKET_RESOLVED" -> "[MARKET_RESOLVED] Market is already resolved.";
                case "MARKET_NOT_ACCEPTING_ORDERS" -> "[MARKET_NOT_ACCEPTING_ORDERS] Market is no longer accepting orders.";
                default -> "[MARKET_CLOSED] Market is already closed.";
            };
        }
    }
}
