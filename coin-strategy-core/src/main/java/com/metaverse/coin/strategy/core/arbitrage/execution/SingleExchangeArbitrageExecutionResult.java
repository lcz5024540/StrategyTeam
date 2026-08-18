package com.metaverse.coin.strategy.core.arbitrage.execution;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SingleExchangeArbitrageOrder;
import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.List;

@Data
@Builder
public class SingleExchangeArbitrageExecutionResult {
    private boolean success;
    private String symbol;
    private String spotOrderId;
    private String futuresOrderId;
    private String quantity;
    private String message;
    @Builder.Default
    private List<SingleExchangeArbitrageOrder> orderRecords = Collections.emptyList();
    @Builder.Default
    private List<SingleExchangeSlippageAlert> slippageAlerts = Collections.emptyList();
    @Builder.Default
    private List<SingleExchangeNakedPositionAlert> nakedPositionAlerts = Collections.emptyList();

    public static SingleExchangeArbitrageExecutionResult success(String spotOrderId, String futuresOrderId) {
        return SingleExchangeArbitrageExecutionResult.builder()
                .success(true)
                .spotOrderId(spotOrderId)
                .futuresOrderId(futuresOrderId)
                .message("Success")
                .orderRecords(Collections.emptyList())
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
                .orderRecords(Collections.emptyList())
                .build();
    }

    public static SingleExchangeArbitrageExecutionResult failure(String message) {
        return SingleExchangeArbitrageExecutionResult.builder()
                .success(false)
                .message(message)
                .orderRecords(Collections.emptyList())
                .build();
    }
}
