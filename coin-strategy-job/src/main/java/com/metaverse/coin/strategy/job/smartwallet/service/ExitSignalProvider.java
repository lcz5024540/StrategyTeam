package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;

import java.math.BigDecimal;

/**
 * Extension point for position exit signal logic.
 * Rule-based implementations (TimeAwareStopLossService) implement this interface.
 * Method-C ML models will provide alternative implementations that can be wired in
 * without modifying callers.
 */
public interface ExitSignalProvider {

    /**
     * Resolve the effective stop-loss threshold for a given position.
     * A threshold of 0.25 means "exit when ROI <= -25%".
     *
     * @param position position with marketInfo populated
     * @return threshold rate (positive number)
     */
    BigDecimal resolveStopLossThreshold(SmartWalletPositionEntity position);

    /**
     * Record the latest mark price for a position (used for trend analysis).
     *
     * @param positionId        position identifier
     * @param markPrice         current mark price
     * @param averageEntryPrice position's average entry price
     */
    void recordMarkPrice(Long positionId, BigDecimal markPrice, BigDecimal averageEntryPrice);

    /**
     * Returns true if the trend-based stop-loss should trigger for this position.
     *
     * @param positionId        position identifier
     * @param currentMarkPrice  latest mark price
     * @param averageEntryPrice position's average entry price
     */
    boolean isTrendStopLossTriggered(Long positionId, BigDecimal currentMarkPrice, BigDecimal averageEntryPrice);
}
