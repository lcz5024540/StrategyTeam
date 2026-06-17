package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletMarketLinkView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class SmartWalletMarketLinkService {

    public static final String STATUS_EXTERNAL_READY = "EXTERNAL_READY";
    public static final String STATUS_INTERNAL_FALLBACK = "INTERNAL_FALLBACK";

    private static final Pattern SPORTS_MARKET_SLUG_PATTERN = Pattern.compile(
            "^(([a-z0-9]+)-[a-z0-9]+-[a-z0-9]+-\\d{4}-\\d{2}-\\d{2})(?:-.+)?$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Set<String> SPORTS_URL_ROOTS = Set.of(
            "mlb", "nba", "nfl", "nhl", "wnba", "epl", "ucl"
    );

    private final PolymarketMarketMetadataService marketMetadataService;
    private final SmartWalletMarketStateService marketStateService;
    private final Map<String, CachedLink> cache = new ConcurrentHashMap<>();

    public SmartWalletMarketLinkView resolve(String conditionId, String question, String outcome) {
        String normalizedConditionId = StringUtils.hasText(conditionId) ? conditionId.trim() : null;
        String internalUrl = buildInternalMarketUrl(normalizedConditionId);
        PolymarketApiClient.MarketMetadata metadata = null;
        if (StringUtils.hasText(normalizedConditionId)) {
            CachedLink cached = cache.get(normalizedConditionId.toLowerCase(Locale.ROOT));
            if (cached != null && cached.expiresAtMs() > System.currentTimeMillis()) {
                return cached.view();
            }
            List<String> outcomeHints = StringUtils.hasText(outcome) ? List.of(outcome) : List.of();
            try {
                marketStateService.refreshMarket(normalizedConditionId, question, outcomeHints, true);
            } catch (RuntimeException ignored) {
                // Best-effort link lookup must not break order rendering.
            }
            metadata = marketMetadataService.getBestEffortByConditionId(
                    normalizedConditionId,
                    false,
                    question,
                    outcomeHints
            );
        }
        String slug = metadata == null ? null : metadata.getSlug();
        String externalUrl = buildExternalMarketUrl(slug);
        String linkStatus = StringUtils.hasText(externalUrl) ? STATUS_EXTERNAL_READY : STATUS_INTERNAL_FALLBACK;
        SmartWalletMarketLinkView view = SmartWalletMarketLinkView.builder()
                .conditionId(normalizedConditionId)
                .question(firstText(metadata == null ? null : metadata.getQuestion(), question))
                .outcome(outcome)
                .marketSlug(slug)
                .marketUrl(StringUtils.hasText(externalUrl) ? externalUrl : internalUrl)
                .externalMarketUrl(externalUrl)
                .internalMarketUrl(internalUrl)
                .linkStatus(linkStatus)
                .metadataSource(metadata == null ? null : metadata.getResolutionSource())
                .category(metadata == null ? null : metadata.getCategory())
                .marketEndAt(metadata == null ? null : metadata.getEndAt())
                .active(metadata == null ? null : metadata.isActive())
                .closed(metadata == null ? null : metadata.isClosed())
                .acceptingOrders(metadata == null ? null : metadata.isAcceptingOrders())
                .resolutionStatus(metadata == null ? null : metadata.getResolutionStatus())
                .resolvedOutcome(metadata == null ? null : metadata.getResolvedOutcome())
                .winningTokenId(metadata == null ? null : metadata.getWinningTokenId())
                .outcomes(metadata == null ? List.of() : metadata.getOutcomes())
                .outcomeTokenIds(metadata == null ? java.util.Map.of() : metadata.getOutcomeTokenIds())
                .build();
        if (StringUtils.hasText(normalizedConditionId)) {
            cache.put(normalizedConditionId.toLowerCase(Locale.ROOT),
                    new CachedLink(view, System.currentTimeMillis() + 300_000L));
        }
        return view;
    }

    /**
     * Cache-only resolve: returns a cached link if available, otherwise builds
     * a minimal internal-only link without any HTTP calls.  This is safe to call
     * in hot-path loops (e.g. page queries) where latency matters.
     */
    public SmartWalletMarketLinkView resolveCacheOnly(String conditionId, String question, String outcome) {
        String normalizedConditionId = StringUtils.hasText(conditionId) ? conditionId.trim() : null;
        if (StringUtils.hasText(normalizedConditionId)) {
            CachedLink cached = cache.get(normalizedConditionId.toLowerCase(Locale.ROOT));
            if (cached != null && cached.expiresAtMs() > System.currentTimeMillis()) {
                return cached.view();
            }
        }
        // Cache miss — return a lightweight internal-only link (no HTTP).
        String internalUrl = buildInternalMarketUrl(normalizedConditionId);
        return SmartWalletMarketLinkView.builder()
                .conditionId(normalizedConditionId)
                .question(question)
                .outcome(outcome)
                .marketUrl(internalUrl)
                .internalMarketUrl(internalUrl)
                .linkStatus(STATUS_INTERNAL_FALLBACK)
                .metadataSource("CACHE_MISS")
                .build();
    }

    public SmartWalletMarketLinkView fromStoredSlug(String conditionId,
                                                    String question,
                                                    String outcome,
                                                    String marketSlug) {
        String externalUrl = buildExternalMarketUrl(marketSlug);
        String internalUrl = buildInternalMarketUrl(conditionId);
        return SmartWalletMarketLinkView.builder()
                .conditionId(conditionId)
                .question(question)
                .outcome(outcome)
                .marketSlug(marketSlug)
                .marketUrl(StringUtils.hasText(externalUrl) ? externalUrl : internalUrl)
                .externalMarketUrl(externalUrl)
                .internalMarketUrl(internalUrl)
                .linkStatus(StringUtils.hasText(externalUrl) ? STATUS_EXTERNAL_READY : STATUS_INTERNAL_FALLBACK)
                .metadataSource("DB")
                .build();
    }

    public String buildExternalMarketUrl(String marketSlug) {
        if (!StringUtils.hasText(marketSlug)) {
            return null;
        }
        String slug = marketSlug.trim();
        Matcher sportsMarket = SPORTS_MARKET_SLUG_PATTERN.matcher(slug);
        if (sportsMarket.matches()) {
            String root = sportsMarket.group(2).toLowerCase(Locale.ROOT);
            String eventSlug = sportsMarket.group(1).toLowerCase(Locale.ROOT);
            if (SPORTS_URL_ROOTS.contains(root)) {
                return "https://polymarket.com/sports/" + root + "/" + eventSlug;
            }
            return "https://polymarket.com/event/" + eventSlug;
        }
        return "https://polymarket.com/event/" + slug;
    }

    public String buildInternalMarketUrl(String conditionId) {
        if (!StringUtils.hasText(conditionId)) {
            return null;
        }
        return "/dashboard/polymarket/markets/"
                + URLEncoder.encode(conditionId.trim(), StandardCharsets.UTF_8);
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

    private record CachedLink(SmartWalletMarketLinkView view, long expiresAtMs) {}
}
