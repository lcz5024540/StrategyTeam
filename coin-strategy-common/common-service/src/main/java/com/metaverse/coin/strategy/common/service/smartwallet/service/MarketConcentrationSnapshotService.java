package com.metaverse.coin.strategy.common.service.smartwallet.service;
import com.metaverse.coin.strategy.common.service.smartwallet.model.ConcentrationSnapshot;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletMarketConcentrationSnapshotMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketConcentrationSnapshotEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketConcentrationSnapshotService {

    private static final String DEFAULT_OUTCOME = "ALL";

    private final SmartWalletMarketConcentrationSnapshotMapper snapshotMapper;
    private final SmartWalletProperties properties;
    private final ObjectMapper objectMapper;

    public Map<String, SmartWalletMarketConcentrationSnapshotEntity> batchGetLatestEntities(Collection<String> marketIds, String outcome) {
        List<String> normalizedIds = normalizeMarketIds(marketIds);
        if (normalizedIds.isEmpty()) {
            return Map.of();
        }
        String normalizedOutcome = normalizeOutcome(outcome);
        List<SmartWalletMarketConcentrationSnapshotEntity> rows = snapshotMapper.selectList(
                new LambdaQueryWrapper<SmartWalletMarketConcentrationSnapshotEntity>()
                        .in(SmartWalletMarketConcentrationSnapshotEntity::getMarketId, normalizedIds)
                        .eq(SmartWalletMarketConcentrationSnapshotEntity::getOutcome, normalizedOutcome)
        );
        Map<String, SmartWalletMarketConcentrationSnapshotEntity> result = new LinkedHashMap<>();
        for (SmartWalletMarketConcentrationSnapshotEntity row : rows) {
            if (row != null && StringUtils.hasText(row.getMarketId())) {
                result.put(row.getMarketId().trim(), row);
            }
        }
        return result;
    }

    public Map<String, ConcentrationSnapshot> batchGetUsableSnapshots(Collection<String> marketIds, String outcome) {
        return batchGetLatestSnapshots(marketIds, outcome).entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().stale())
                .collect(LinkedHashMap::new,
                        (map, entry) -> map.put(entry.getKey(), entry.getValue().snapshot()),
                        LinkedHashMap::putAll);
    }

    public Map<String, SnapshotView> batchGetLatestSnapshots(Collection<String> marketIds, String outcome) {
        Map<String, SmartWalletMarketConcentrationSnapshotEntity> rows = batchGetLatestEntities(marketIds, outcome);
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<String, SnapshotView> result = new LinkedHashMap<>();
        for (Map.Entry<String, SmartWalletMarketConcentrationSnapshotEntity> entry : rows.entrySet()) {
            SnapshotView view = latestSnapshotView(entry.getValue());
            if (view != null && view.snapshot().isDataAvailable()) {
                result.put(entry.getKey(), view);
            }
        }
        return result;
    }

    public void upsert(String marketId, String outcome, ConcentrationSnapshot snapshot) {
        if (!StringUtils.hasText(marketId) || snapshot == null || !snapshot.isDataAvailable()) {
            return;
        }
        String normalizedMarketId = marketId.trim();
        String normalizedOutcome = normalizeOutcome(outcome);
        SmartWalletMarketConcentrationSnapshotEntity entity = snapshotMapper.selectOne(
                new LambdaQueryWrapper<SmartWalletMarketConcentrationSnapshotEntity>()
                        .eq(SmartWalletMarketConcentrationSnapshotEntity::getMarketId, normalizedMarketId)
                        .eq(SmartWalletMarketConcentrationSnapshotEntity::getOutcome, normalizedOutcome)
                        .last("limit 1"));
        boolean insert = entity == null;
        if (entity == null) {
            entity = new SmartWalletMarketConcentrationSnapshotEntity()
                    .setMarketId(normalizedMarketId)
                    .setOutcome(normalizedOutcome);
        }
        entity.setObservedHolders(snapshot.observedHolders())
                .setTop1Pct(snapshot.top1Pct())
                .setTop3Pct(snapshot.top3Pct())
                .setTop5Pct(snapshot.top5Pct())
                .setHhi(snapshot.hhi())
                .setHighlyConcentrated(snapshot.isHighlyConcentrated())
                .setExtremelyConcentrated(snapshot.isExtremelyConcentrated())
                .setSnapshotAt(new Date(snapshot.capturedAt()))
                .setTopHolderAddressesJson(writeAddresses(snapshot.topHolderAddresses()));
        if (insert) {
            snapshotMapper.insert(entity);
        } else {
            snapshotMapper.updateById(entity);
        }
    }

    public List<String> topHolderAddresses(SmartWalletMarketConcentrationSnapshotEntity entity) {
        if (entity == null || !StringUtils.hasText(entity.getTopHolderAddressesJson())) {
            return List.of();
        }
        try {
            return objectMapper.readValue(entity.getTopHolderAddressesJson(), new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("Failed to parse concentration top holder addresses market={} outcome={}: {}",
                    entity.getMarketId(), entity.getOutcome(), e.getMessage());
            return List.of();
        }
    }

    private ConcentrationSnapshot toUsableSnapshot(SmartWalletMarketConcentrationSnapshotEntity entity) {
        SnapshotView view = latestSnapshotView(entity);
        if (view == null || view.stale()) {
            return null;
        }
        return view.snapshot();
    }

    private SnapshotView latestSnapshotView(SmartWalletMarketConcentrationSnapshotEntity entity) {
        ConcentrationSnapshot snapshot = toSnapshot(entity);
        if (snapshot == null || !snapshot.isDataAvailable()) {
            return null;
        }
        return new SnapshotView(
                snapshot,
                entity.getSnapshotAt(),
                isStale(entity)
        );
    }

    private ConcentrationSnapshot toSnapshot(SmartWalletMarketConcentrationSnapshotEntity entity) {
        if (entity == null
                || entity.getSnapshotAt() == null
                || entity.getObservedHolders() == null
                || entity.getObservedHolders() <= 0) {
            return null;
        }
        return new ConcentrationSnapshot(
                entity.getMarketId(),
                normalizeOutcome(entity.getOutcome()),
                entity.getObservedHolders(),
                entity.getTop1Pct(),
                entity.getTop3Pct(),
                entity.getTop5Pct(),
                entity.getHhi() == null ? 0 : entity.getHhi(),
                Boolean.TRUE.equals(entity.getExtremelyConcentrated()),
                Boolean.TRUE.equals(entity.getHighlyConcentrated()),
                topHolderAddresses(entity),
                entity.getSnapshotAt().getTime()
        );
    }

    private boolean isUsable(SmartWalletMarketConcentrationSnapshotEntity entity) {
        SnapshotView view = latestSnapshotView(entity);
        return view != null && !view.stale();
    }

    private boolean isStale(SmartWalletMarketConcentrationSnapshotEntity entity) {
        if (entity == null || entity.getSnapshotAt() == null) {
            return false;
        }
        Long maxSnapshotAgeMs = properties.getMarketMonitoring().getConcentrationSnapshot().getMaxSnapshotAgeMs();
        return maxSnapshotAgeMs == null
                || maxSnapshotAgeMs <= 0
                ? false
                : System.currentTimeMillis() - entity.getSnapshotAt().getTime() > maxSnapshotAgeMs;
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

    private String normalizeOutcome(String outcome) {
        return StringUtils.hasText(outcome)
                ? outcome.trim().toUpperCase(Locale.ROOT)
                : DEFAULT_OUTCOME;
    }

    private String writeAddresses(List<String> addresses) {
        try {
            return objectMapper.writeValueAsString(addresses == null ? Collections.emptyList() : addresses);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize concentration top holder addresses: {}", e.getMessage());
            return "[]";
        }
    }

    public record SnapshotView(
            ConcentrationSnapshot snapshot,
            Date snapshotAt,
            boolean stale
    ) {
    }
}
