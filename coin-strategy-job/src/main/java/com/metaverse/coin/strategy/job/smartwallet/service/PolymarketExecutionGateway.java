package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.job.smartwallet.model.ExecutionMode;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletExecutionResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletSignal;

public interface PolymarketExecutionGateway {

    SmartWalletExecutionResult execute(SmartWalletSignal signal, ExecutionMode mode);
}
