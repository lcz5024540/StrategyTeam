package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.job.smartwallet.model.ExecutionMode;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletExecutionResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletSignal;
import org.springframework.stereotype.Component;

@Component
public class LoggingPolymarketExecutionGateway implements PolymarketExecutionGateway {

    @Override
    public SmartWalletExecutionResult execute(SmartWalletSignal signal, ExecutionMode mode) {
        boolean success = mode != ExecutionMode.LIVE;
        String orderId = "paper-" + signal.getSignalId();
        String message = mode == ExecutionMode.LIVE
                ? "LIVE execution adapter is not wired yet."
                : "Paper order created from smart-wallet signal.";
        return SmartWalletExecutionResult.builder()
                .signalId(signal.getSignalId())
                .mode(mode)
                .success(success)
                .message(message)
                .externalOrderId(success ? orderId : null)
                .build();
    }
}
