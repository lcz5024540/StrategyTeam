package com.metaverse.coin.strategy.core.arbitrage.multi;

public record CrossExchangeHedgeExecution(String executionId, CrossExchangeHedgePlan plan,
                                           Status status, String failureReason) {
    public enum Status { OPENED, CLOSED, ROLLED_BACK, FAILED }
}
