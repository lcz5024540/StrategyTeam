package com.metaverse.coin.strategy.core.signal;

import com.metaverse.coin.strategy.core.domain.TradingSignal;

import java.util.List;

public interface StrategySignalService<T> {

    List<TradingSignal> generateSignals(T input);
}
