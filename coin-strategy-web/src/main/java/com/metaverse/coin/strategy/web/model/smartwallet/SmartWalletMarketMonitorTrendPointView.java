package com.metaverse.coin.strategy.web.model.smartwallet;

import java.math.BigDecimal;
import java.util.Date;

public record SmartWalletMarketMonitorTrendPointView(
    Date date, Integer signalCount, Integer uniqueMarkets,
    Integer uniqueWallets, BigDecimal volumeUsdc) {}
