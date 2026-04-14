package com.metaverse.coin.strategy.core.arbitrage.execution;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SingleExchangeArbitrageExecutionResult {
    private boolean success;
    private String symbol;
    private String spotOrderId;
    private String futuresOrderId;
    private String quantity;
    private String message;
    
    public static SingleExchangeArbitrageExecutionResult success(String spotOrderId, String futuresOrderId) {
        return SingleExchangeArbitrageExecutionResult.builder()
                .success(true)
                .spotOrderId(spotOrderId)
                .futuresOrderId(futuresOrderId)
                .message("Success")
                .build();
    }

    public static SingleExchangeArbitrageExecutionResult success(String symbol, String quantity, String spotOrderId, String futuresOrderId) {
        return SingleExchangeArbitrageExecutionResult.builder()
                .success(true)
                .symbol(symbol)
                .quantity(quantity)
                .spotOrderId(spotOrderId)
                .futuresOrderId(futuresOrderId)
                .message("Success")
                .build();
    }

    public static SingleExchangeArbitrageExecutionResult failure(String message) {
        return SingleExchangeArbitrageExecutionResult.builder()
                .success(false)
                .message(message)
                .build();
    }
}
