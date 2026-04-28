package com.metaverse.coin.strategy.web.model;

import java.util.List;

public record StrategyIdentityView(
        String code,
        String name,
        String category,
        String status,
        String description,
        List<String> supportedModes,
        List<String> capabilities,
        String modeBehavior
) {
}
