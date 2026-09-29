package com.metaverse.coin.strategy.htx.research;

import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendMarketRegimeSnapshotEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendMarketRegimeSnapshotMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Set;

/**
 * First research-only market classifier. It writes immutable state evidence but is not read by entry,
 * sizing, or exit services until its labels prove useful in the unified backtest.
 */
@Service
@RequiredArgsConstructor
public class HtxTrendMarketRegimeService {

    private static final BigDecimal HIGH_VOLATILITY = new BigDecimal("0.80");
    private static final BigDecimal HIGH_CROWDING = new BigDecimal("0.80");
    private static final BigDecimal LONG_BREADTH = new BigDecimal("0.55");
    private static final BigDecimal SHORT_BREADTH = new BigDecimal("0.45");
    private static final Set<String> DIRECTIONS = Set.of("LONG", "SHORT", "FLAT");

    private final HtxTrendMarketRegimeSnapshotMapper snapshotMapper;

    public HtxTrendMarketRegimeSnapshotEntity classifyAndRecord(MarketRegimeInput input) {
        validate(input);
        HtxTrendMarketRegimeSnapshotEntity existing = snapshotMapper.findBySnapshotKey(input.snapshotKey());
        if (existing != null) {
            return existing;
        }
        HtxTrendMarketRegimeSnapshotEntity entity = new HtxTrendMarketRegimeSnapshotEntity();
        entity.setSnapshotKey(input.snapshotKey());
        entity.setDatasetKey(input.datasetKey());
        entity.setScope(input.scope());
        entity.setContractCode(input.contractCode());
        entity.setBarTime(input.barTime());
        entity.setStateCode(classify(input));
        entity.setDailyDirection(input.dailyDirection());
        entity.setFourHourDirection(input.fourHourDirection());
        entity.setOneHourDirection(input.oneHourDirection());
        entity.setBreadthRatio(input.breadthRatio());
        entity.setVolatilityScore(input.volatilityScore());
        entity.setCrowdingScore(input.crowdingScore());
        entity.setEvidenceJson(input.evidenceJson());
        entity.setDataIntegrity(input.dataIntegrity());
        snapshotMapper.insert(entity);
        return entity;
    }

    public String classify(MarketRegimeInput input) {
        if (!"COMPLETE".equals(input.dataIntegrity())) {
            return "UNCLASSIFIED";
        }
        if (atLeast(input.volatilityScore(), HIGH_VOLATILITY)) {
            return "VOLATILE_DISORDER";
        }
        if (atLeast(input.crowdingScore(), HIGH_CROWDING)) {
            return "CROWDED";
        }
        if (!input.dailyDirection().equals(input.fourHourDirection()) || "FLAT".equals(input.dailyDirection())) {
            return "RANGE";
        }
        if (!input.oneHourDirection().equals(input.dailyDirection())) {
            return "FLAT".equals(input.oneHourDirection()) ? "RANGE" : "PULLBACK";
        }
        if ("LONG".equals(input.dailyDirection()) && supportsLongBreadth(input.breadthRatio())) {
            return "TREND_UP";
        }
        if ("SHORT".equals(input.dailyDirection()) && supportsShortBreadth(input.breadthRatio())) {
            return "TREND_DOWN";
        }
        return "RANGE";
    }

    private static void validate(MarketRegimeInput input) {
        if (input == null || blank(input.snapshotKey()) || blank(input.datasetKey()) || blank(input.scope())
                || input.barTime() == null || input.barTime() <= 0 || blank(input.evidenceJson()) || blank(input.dataIntegrity())
                || !DIRECTIONS.contains(input.dailyDirection()) || !DIRECTIONS.contains(input.fourHourDirection())
                || !DIRECTIONS.contains(input.oneHourDirection())) {
            throw new IllegalArgumentException("market regime input is incomplete");
        }
        if ("CONTRACT".equals(input.scope()) && blank(input.contractCode())) {
            throw new IllegalArgumentException("contract regime snapshot requires contractCode");
        }
        if (!"MARKET".equals(input.scope()) && !"CONTRACT".equals(input.scope())) {
            throw new IllegalArgumentException("market regime scope must be MARKET or CONTRACT");
        }
    }

    private static boolean supportsLongBreadth(BigDecimal breadth) {
        return breadth != null && breadth.compareTo(LONG_BREADTH) >= 0;
    }

    private static boolean supportsShortBreadth(BigDecimal breadth) {
        return breadth != null && breadth.compareTo(SHORT_BREADTH) <= 0;
    }

    private static boolean atLeast(BigDecimal value, BigDecimal threshold) {
        return value != null && value.compareTo(threshold) >= 0;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record MarketRegimeInput(
            String snapshotKey,
            String datasetKey,
            String scope,
            String contractCode,
            Long barTime,
            String dailyDirection,
            String fourHourDirection,
            String oneHourDirection,
            BigDecimal breadthRatio,
            BigDecimal volatilityScore,
            BigDecimal crowdingScore,
            String evidenceJson,
            String dataIntegrity
    ) { }
}
