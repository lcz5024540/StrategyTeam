package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.job.smartwallet.model.ExecutionMode;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletExecutionResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletSignal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LoggingPolymarketExecutionGateway implements PolymarketExecutionGateway {

    private final ExecutionWalletContextLoader walletContextLoader;
    private final PolymarketOrderRequestFactory orderRequestFactory;

    @Override
    public SmartWalletExecutionResult execute(SmartWalletSignal signal, ExecutionMode mode) {
        if (mode == ExecutionMode.LIVE) {
            ExecutionWalletContextLoader.ExecutionWalletContext wallet = walletContextLoader.requireLiveContext();
            PolymarketOrderRequestFactory.PreparedPolymarketOrder order = orderRequestFactory.prepare(signal, wallet);
            return SmartWalletExecutionResult.builder()
                    .signalId(signal.getSignalId())
                    .mode(mode)
                    .success(false)
                    .message("Execution wallet is loaded, but live Polymarket order submission is still intentionally gated. Prepared order for market "
                            + order.getMarketId() + " using wallet " + wallet.getAddress() + ".")
                    .executionAddress(wallet.getAddress())
                    .build();
        }
        return SmartWalletExecutionResult.builder()
                .signalId(signal.getSignalId())
                .mode(ExecutionMode.PAPER)
                .success(true)
                .message("Paper order created from smart-wallet signal.")
                .externalOrderId("paper-" + signal.getSignalId())
                .build();
    }
}
