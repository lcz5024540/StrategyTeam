package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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
        logIncompleteActiveMarketRefresh(normalizedMarketId, snapshot, rowsToPersist, effectiveQuestion);
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
            logIncompleteActiveMarketRefresh(marketId, snapshot, rowsToPersist, effectiveQuestion);
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
            logIncompleteActiveMarketRefresh(marketId, snapshot, rowsToPersist, effectiveQuestion);
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
        MarketStateSnapshot snapshot = snapshotFromRows(rows);
        if (isActiveIncomplete(snapshot) || (snapshot != null && snapshot.isActive() && rowsMissingToken(rows))) {
            return null;
        }
        return snapshot;
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

    private boolean isActiveIncomplete(MarketStateSnapshot snapshot) {
        return snapshot != null && snapshot.isActive() && !missingMarketInfoFields(snapshot).isEmpty();
    }

    private boolean rowsMissingToken(List<SmartWalletMarketInfoEntity> rows) {
        return rows != null && rows.stream()
                .filter(row -> row != null && StringUtils.hasText(row.getOutcome()))
                .anyMatch(row -> !StringUtils.hasText(row.getTokenId()));
    }

    private List<String> missingMarketInfoFields(MarketStateSnapshot snapshot) {
        if (snapshot == null) {
            return List.of("snapshot");
        }
        List<String> missing = new ArrayList<>();
        if (!StringUtils.hasText(snapshot.marketSlug())) {
            missing.add("marketSlug");
        }
        if (snapshot.marketEndAt() == null || snapshot.marketEndAt() <= 0) {
            missing.add("marketEndAt");
        }
        return missing;
    }

    private void logIncompleteActiveMarketRefresh(String marketId,
                                                  MarketStateSnapshot snapshot,
                                                  List<SmartWalletMarketInfoEntity> rows,
                                                  String questionHint) {
        if (snapshot == null || !snapshot.isActive()) {
            return;
        }
        List<String> missing = missingMarketInfoFields(snapshot);
        boolean tokenMissing = rows == null || rowsMissingToken(rows);
        if (tokenMissing) {
            missing = new ArrayList<>(missing);
            missing.add("tokenId");
        }
        if (missing.isEmpty()) {
            return;
        }
        log.warn("[ACTIVE_MARKET_METADATA_INCOMPLETE] marketId={} question={} outcome={} active={} refreshResult={} missingFields={}",
                marketId,
                firstNonBlank(snapshot.question(), questionHint),
                firstOutcome(rows),
                snapshot.isActive(),
                snapshot.lastMetadataSyncAt() == null ? "NO_REFRESH" : "REFRESHED_INCOMPLETE",
                missing);
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
        String question = firstNonBlank(metadata == null ? null : metadata.getQuestion(), firstQuestion(existingRows), questionFallback, marketId);
        String slug = firstNonBlank(metadata == null ? null : metadata.getSlug(), firstMarketSlug(existingRows));
        // Include slug in classification — slugs often contain useful keywords (e.g., "will-xrp-hit-3")
        String classificationText = question + (StringUtils.hasText(slug) ? " " + slug.replace('-', ' ') : "");
        String newCategory = resolveNormalizedCategory(sourceCategory, classificationText);
        // When API fails (metadata == null) and new classification falls back to OTHER,
        // preserve the existing non-OTHER category — don't downgrade on API 404.
        String existingCategory = firstNormalizedCategory(existingRows);
        if (CATEGORY_OTHER.equals(newCategory)
                && StringUtils.hasText(existingCategory)
                && !CATEGORY_OTHER.equals(existingCategory)) {
            newCategory = existingCategory;
        }
        return MarketStateSnapshot.builder()
                .marketId(marketId)
                .question(question)
                .marketSlug(firstNonBlank(metadata == null ? null : metadata.getSlug(), firstMarketSlug(existingRows)))
                .marketEndAt(marketEndAt)
                .sourceCategory(sourceCategory)
                .normalizedCategory(newCategory)
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
            if (row != null) {
                rowsByOutcome.put(normalizeValue(outcome), row);
            }
        }
        return new ArrayList<>(rowsByOutcome.values());
    }

    private void persistSnapshot(List<SmartWalletMarketInfoEntity> rows, MarketStateSnapshot snapshot) {
        if (rows == null || rows.isEmpty() || snapshot == null) {
            return;
        }
        for (SmartWalletMarketInfoEntity row : rows) {
            if (row == null) {
                continue;
            }
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

    /**
     * Re-run the categorization logic on all existing market_info rows and update
     * any that are currently OTHER (or any that differ from the new classification).
     * Returns the number of rows whose category was updated.
     */
    public int recategorizeAll() {
        List<SmartWalletMarketInfoEntity> allRows = marketInfoService.listAll(20_000);
        int updated = 0;
        for (SmartWalletMarketInfoEntity row : allRows) {
            String slug = row.getMarketSlug();
            String classificationText = (row.getQuestion() == null ? "" : row.getQuestion())
                    + (StringUtils.hasText(slug) ? " " + slug.replace('-', ' ') : "");
            String newCategory = resolveNormalizedCategory(row.getSourceCategory(), classificationText);
            if (!newCategory.equals(row.getNormalizedCategory())) {
                row.setNormalizedCategory(newCategory);
                marketInfoService.update(row);
                updated++;
            }
        }
        return updated;
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
        return LIFECYCLE_ACTIVE;
    }

    private String resolveNormalizedCategory(String sourceCategory, String question) {
        // Step 1: Direct mapping from Polymarket sourceCategory (most reliable signal)
        String directMapping = mapSourceCategoryDirect(sourceCategory);
        if (directMapping != null) {
            return directMapping;
        }

        // Step 2: Keyword-based classification from combined text
        String combined = normalizeForMatching(sourceCategory + " " + question);

        if (containsAny(combined,
                // Crypto tokens & projects (comprehensive)
                "crypto", "cryptocurrency", "bitcoin", "ethereum", "solana",
                "btc", "eth", "sol", "xrp", "ripple", "doge", "dogecoin",
                "cardano", "bnb", "binance", "avax", "avalanche",
                "matic", "polygon", "chainlink", "polkadot",
                "sui", "aptos", "arb", "arbitrum", "optimism",
                "cosmos", "uniswap", "aave",
                "ltc", "litecoin", "bch", "filecoin", "hbar", "hedera",
                "xlm", "stellar", "algorand", "icp", "vechain",
                "fantom", "sandbox", "axie",
                "shib", "pepe", "floki", "bonk", "meme coin",
                "toncoin", "trx", "tron", "eos",
                "sei", "injective", "jupiter", "render", "rndr",
                "kaspa", "celestia", "stacks",
                "pendle", "pyth", "jto", "worldcoin",
                "blockchain", "defi", "nft", "stablecoin",
                "usdt", "usdc", "coinbase", "kraken",
                "mining", "halving", "altcoin", "memecoin",
                // Price-related patterns strongly indicating crypto
                "above $", "below $", "reach $", "hit $", "break $",
                "$100k", "$50k", "$10k", "$1k")) {
            return CATEGORY_CRYPTO;
        }
        if (containsAny(combined,
                "politic", "election", "president", "senate", "congress",
                "parliament", "governor", "mayor", "prime minister",
                "referendum", "vote", "democrat", "republican", "gop",
                "trump", "biden", "party", "ballot", "primary",
                "cabinet", "legislation", "bill pass", "veto",
                "supreme court", "executive order", "impeach")) {
            return CATEGORY_POLITICS;
        }
        if (containsAny(combined,
                "sports", "nba", "nfl", "mlb", "nhl", "mls",
                "soccer", "football", "baseball", "basketball", "tennis",
                "golf", "ufc", "mma", "boxing", "fifa", "f1", "formula 1",
                "champions league", "premier league", "la liga", "serie a",
                "bundesliga", "world cup", "super bowl", "playoffs",
                "game ", "match", "vs.", "vs ", " v ",
                // Common NBA/NFL/MLB teams & terms
                "lakers", "celtics", "warriors", "76ers", "sixers",
                "bucks", "nets", "knicks", "bulls", "heat", "nuggets",
                "chiefs", "eagles", "cowboys", "49ers", "ravens",
                "yankees", "dodgers", "braves", "astros", "mets",
                "mvp", "championship", "finals", "series",
                "o/u", "over/under", "spread", "point",
                // Soccer
                "real madrid", "barcelona", "manchester", "liverpool",
                "arsenal", "chelsea", "bayern", "psg", "juventus")) {
            return CATEGORY_SPORTS;
        }
        if (containsAny(combined,
                "weather", "rain", "snow", "temperature", "hurricane",
                "storm", "sunny", "wind", "climate", "tornado",
                "flood", "drought", "heatwave", "wildfire")) {
            return CATEGORY_WEATHER;
        }
        if (containsAny(combined,
                "macro", "cpi", "inflation", "gdp", "fed", "fomc",
                "recession", "unemployment", "interest rate", "treasury",
                "oil", "gold", "silver", "commodity", "forex",
                "bond", "yield", "tariff", "trade war", "sanctions",
                "central bank", "ecb", "boj", "rate cut", "rate hike",
                "jobs report", "nonfarm", "ppi", "pce")) {
            return CATEGORY_MACRO;
        }
        if (containsAny(combined,
                "business", "company", "earnings", "ipo", "merger",
                "acquisition", "ceo", "revenue", "stock", "shares",
                "tesla", "apple", "nvidia", "amazon", "google", "meta",
                "microsoft", "openai", "spacex", "tiktok",
                "market cap", "quarterly", "profit", "layoff",
                "ipo", "spac", "sec", "antitrust", "valuation")) {
            return CATEGORY_BUSINESS;
        }
        if (containsAny(combined,
                "entertainment", "movie", "film", "oscar", "grammy",
                "emmy", "music", "tv", "television", "celebrity",
                "box office", "streaming", "netflix", "disney",
                "album", "concert", "award", "billboard",
                "kardashian", "taylor swift", "drake")) {
            return CATEGORY_ENTERTAINMENT;
        }
        if (containsAny(combined,
                "news", "current event", "breaking", "world",
                "war", "conflict", "headline", "crisis",
                "earthquake", "eruption", "terrorist", "peace",
                "ceasefire", "treaty", "refugee", "nato", "un ")) {
            return CATEGORY_CURRENT_EVENTS;
        }
        return CATEGORY_OTHER;
    }

    /**
     * Directly map Polymarket's sourceCategory to our normalized category.
     * Polymarket uses tags like "crypto", "sports", "politics", "pop-culture", etc.
     */
    private String mapSourceCategoryDirect(String sourceCategory) {
        if (!StringUtils.hasText(sourceCategory)) {
            return null;
        }
        String lower = sourceCategory.trim().toLowerCase(Locale.ROOT);
        // Polymarket category values: crypto, sports, politics, pop-culture, science, business, etc.
        if (lower.contains("crypto") || lower.contains("coin") || lower.contains("defi")
                || lower.contains("blockchain") || lower.contains("web3")) {
            return CATEGORY_CRYPTO;
        }
        if (lower.contains("politic") || lower.contains("election") || lower.contains("government")) {
            return CATEGORY_POLITICS;
        }
        if (lower.contains("sport") || lower.contains("nba") || lower.contains("nfl")
                || lower.contains("mlb") || lower.contains("soccer") || lower.contains("football")
                || lower.contains("basketball") || lower.contains("baseball") || lower.contains("tennis")
                || lower.contains("mma") || lower.contains("boxing") || lower.contains("f1")) {
            return CATEGORY_SPORTS;
        }
        if (lower.contains("entertainment") || lower.contains("pop-culture") || lower.contains("pop culture")
                || lower.contains("music") || lower.contains("movie") || lower.contains("celebrity")
                || lower.contains("tv")) {
            return CATEGORY_ENTERTAINMENT;
        }
        if (lower.contains("business") || lower.contains("finance") || lower.contains("stock")
                || lower.contains("tech")) {
            return CATEGORY_BUSINESS;
        }
        if (lower.contains("econ") || lower.contains("macro") || lower.contains("fed")
                || lower.contains("inflation") || lower.contains("commodit")) {
            return CATEGORY_MACRO;
        }
        if (lower.contains("weather") || lower.contains("climate")) {
            return CATEGORY_WEATHER;
        }
        if (lower.contains("world") || lower.contains("news") || lower.contains("conflict")
                || lower.contains("geopolit") || lower.contains("war")) {
            return CATEGORY_CURRENT_EVENTS;
        }
        // Not recognized — fall through to keyword matching
        return null;
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
            if (row == null) {
                continue;
            }
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
            if (row == null) {
                continue;
            }
            if (StringUtils.hasText(row.getMarketSlug())) {
                return row.getMarketSlug().trim();
            }
        }
        return null;
    }

    private String firstOutcome(List<SmartWalletMarketInfoEntity> rows) {
        if (rows == null) {
            return null;
        }
        for (SmartWalletMarketInfoEntity row : rows) {
            if (row != null && StringUtils.hasText(row.getOutcome())) {
                return row.getOutcome().trim();
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
            if (row == null) {
                continue;
            }
            if (StringUtils.hasText(row.getSourceCategory())) {
                return row.getSourceCategory().trim();
            }
        }
        return null;
    }

    private String firstNormalizedCategory(List<SmartWalletMarketInfoEntity> rows) {
        if (rows == null) {
            return null;
        }
        for (SmartWalletMarketInfoEntity row : rows) {
            if (row == null) {
                continue;
            }
            if (StringUtils.hasText(row.getNormalizedCategory())) {
                return row.getNormalizedCategory().trim();
            }
        }
        return null;
    }

    private Long firstMarketEndAt(List<SmartWalletMarketInfoEntity> rows) {
        if (rows == null) {
            return null;
        }
        for (SmartWalletMarketInfoEntity row : rows) {
            if (row == null) {
                continue;
            }
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
                if (row == null) {
                    continue;
                }
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
