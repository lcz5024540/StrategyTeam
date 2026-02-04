package com.metaverse.coin.strategy.core.arbitrage.execution;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ArbitrageExecutionResult {
    private boolean success;
    private String spotOrderId;
    private String futuresOrderId;
    private String message;
    
    public static ArbitrageExecutionResult success(String spotOrderId, String futuresOrderId) {
        return ArbitrageExecutionResult.builder()
                .success(true)
                .spotOrderId(spotOrderId)
                .futuresOrderId(futuresOrderId)
                .message("Success")
                .build();
    }

    public static ArbitrageExecutionResult failure(String message) {
        return ArbitrageExecutionResult.builder()
                .success(false)
                .message(message)
                .build();
    }
}
