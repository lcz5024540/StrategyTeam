package com.metaverse.coin.strategy.job.smartwallet.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class PolymarketMarketMetadataService {

    private final PolymarketApiClient polymarketApiClient;
    private final Map<String, PolymarketApiClient.MarketMetadata> cache = new ConcurrentHashMap<>();

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

    public void evict(String conditionId) {
        if (StringUtils.hasText(conditionId)) {
            cache.remove(conditionId.trim());
        }
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
}
