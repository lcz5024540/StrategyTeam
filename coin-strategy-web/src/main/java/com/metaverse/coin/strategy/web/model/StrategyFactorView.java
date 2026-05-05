package com.metaverse.coin.strategy.web.model;

import java.math.BigDecimal;
import java.util.Map;

public record StrategyFactorView(
        String code,
        String name,
        String category,
        String status,
        String direction,
        BigDecimal weight,
        String updatedAtBjt,
        BigDecimal value,
        String displayValue,
        BigDecimal score,
        String signal,
        BigDecimal ic,
        BigDecimal rankIc,
        BigDecimal ir,
        BigDecimal coverage,
        Integer sampleSize,
        Map<String, Object> metadata
) {
}
