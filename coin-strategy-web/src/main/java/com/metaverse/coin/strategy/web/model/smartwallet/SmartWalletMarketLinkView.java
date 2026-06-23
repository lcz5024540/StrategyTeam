package com.metaverse.coin.strategy.web.model.smartwallet;

import java.util.List;
import java.util.Map;

public record SmartWalletMarketLinkView(
        String conditionId,
        String question,
        String outcome,
        String marketSlug,
        String marketUrl,
        String externalMarketUrl,
        String internalMarketUrl,
        String linkStatus,
        String metadataSource,
        String category,
        Long marketEndAt,
        Boolean active,
        Boolean closed,
        Boolean acceptingOrders,
        String resolutionStatus,
        String resolvedOutcome,
        String winningTokenId,
        List<String> outcomes,
        Map<String, String> outcomeTokenIds
) {
}
