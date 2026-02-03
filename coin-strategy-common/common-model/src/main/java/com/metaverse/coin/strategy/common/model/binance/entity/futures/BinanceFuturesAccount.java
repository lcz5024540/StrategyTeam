package com.metaverse.coin.strategy.common.model.binance.entity.futures;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceFuturesAccount {
    private Integer feeTier;
    private Boolean canTrade;
    private Boolean canDeposit;
    private Boolean canWithdraw;
    private Long updateTime;
    private BigDecimal totalInitialMargin;
    private BigDecimal totalMaintMargin;
    private BigDecimal totalWalletBalance;
    private BigDecimal totalUnrealizedProfit;
    private BigDecimal totalMarginBalance;
    private BigDecimal totalPositionInitialMargin;
    private BigDecimal totalOpenOrderInitialMargin;
    private BigDecimal totalCrossWalletBalance;
    private BigDecimal totalCrossUnPnl;
    private BigDecimal availableBalance;
    private BigDecimal maxWithdrawAmount;
    private List<BinanceFuturesAsset> assets;
    private List<BinanceFuturesAccountPosition> positions;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BinanceFuturesAsset {
        private String asset;
        private BigDecimal walletBalance;
        private BigDecimal unrealizedProfit;
        private BigDecimal marginBalance;
        private BigDecimal maintMargin;
        private BigDecimal initialMargin;
        private BigDecimal positionInitialMargin;
        private BigDecimal openOrderInitialMargin;
        private BigDecimal maxWithdrawAmount;
        private BigDecimal crossWalletBalance;
        private BigDecimal crossUnPnl;
        private BigDecimal availableBalance;
        private Boolean marginAvailable;
        private Long updateTime;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BinanceFuturesAccountPosition {
        private String symbol;
        private BigDecimal initialMargin;
        private BigDecimal maintMargin;
        private BigDecimal unrealizedProfit;
        private BigDecimal positionInitialMargin;
        private BigDecimal openOrderInitialMargin;
        private Integer leverage;
        private Boolean isolated;
        private BigDecimal entryPrice;
        private BigDecimal maxNotional;
        private BigDecimal bidNotional;
        private BigDecimal askNotional;
        private String positionSide;
        private BigDecimal positionAmt;
        private Long updateTime;
    }
}
