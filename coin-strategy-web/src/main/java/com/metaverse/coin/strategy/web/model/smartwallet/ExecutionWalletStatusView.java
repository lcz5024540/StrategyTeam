package com.metaverse.coin.strategy.web.model.smartwallet;

import java.math.BigDecimal;
import java.util.List;

public record ExecutionWalletStatusView(
        boolean enabled,
        boolean configured,
        boolean liveReady,
        String chain,
        String address,
        String funderAddress,
        BigDecimal minOrderUsdc,
        BigDecimal maxOrderUsdc,
        BigDecimal priceSlippageRate,
        List<String> notes
) {
}
