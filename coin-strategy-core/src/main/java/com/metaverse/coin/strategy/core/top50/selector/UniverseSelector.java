package com.metaverse.coin.strategy.core.top50.selector;

import java.util.List;

/**
 * Responsible for selecting the universe of tokens to trade.
 */
public interface UniverseSelector {

    /**
     * Get the current list of symbols in the universe (e.g. Top 50).
     * @return List of symbols (e.g. "BTCUSDT", "ETHUSDT")
     */
    List<String> getUniverse();

    /**
     * Refresh the universe based on latest data.
     */
    void refresh();
}
