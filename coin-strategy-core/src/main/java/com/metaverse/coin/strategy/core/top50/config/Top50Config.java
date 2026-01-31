package com.metaverse.coin.strategy.core.top50.config;

import java.math.BigDecimal;

public class Top50Config {

    private int topN = 50;
    
    // Volatility Thresholds
    private BigDecimal volatilityThreshold = new BigDecimal("0.05"); // 5%
    
    // Order Flow Thresholds
    private BigDecimal imbalanceThreshold = new BigDecimal("1.5"); // Buy/Sell ratio > 1.5
    
    // Risk Management
    private BigDecimal stopLossPercentage = new BigDecimal("0.02"); // 2%
    private BigDecimal takeProfitPercentage = new BigDecimal("0.05"); // 5%
    private BigDecimal maxPositionSize = new BigDecimal("1000"); // USDT

    public int getTopN() {
        return topN;
    }

    public void setTopN(int topN) {
        this.topN = topN;
    }

    public BigDecimal getVolatilityThreshold() {
        return volatilityThreshold;
    }

    public void setVolatilityThreshold(BigDecimal volatilityThreshold) {
        this.volatilityThreshold = volatilityThreshold;
    }

    public BigDecimal getImbalanceThreshold() {
        return imbalanceThreshold;
    }

    public void setImbalanceThreshold(BigDecimal imbalanceThreshold) {
        this.imbalanceThreshold = imbalanceThreshold;
    }

    public BigDecimal getStopLossPercentage() {
        return stopLossPercentage;
    }

    public void setStopLossPercentage(BigDecimal stopLossPercentage) {
        this.stopLossPercentage = stopLossPercentage;
    }

    public BigDecimal getTakeProfitPercentage() {
        return takeProfitPercentage;
    }

    public void setTakeProfitPercentage(BigDecimal takeProfitPercentage) {
        this.takeProfitPercentage = takeProfitPercentage;
    }

    public BigDecimal getMaxPositionSize() {
        return maxPositionSize;
    }

    public void setMaxPositionSize(BigDecimal maxPositionSize) {
        this.maxPositionSize = maxPositionSize;
    }
}
