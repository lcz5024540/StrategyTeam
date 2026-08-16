package com.metaverse.coin.strategy.htx.strategy.contract;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Pure contract-grid pricing with explicit fee, funding, risk-buffer and profit floors. */
@Component
public class FeeAwareContractGridStrategy implements ContractGridStrategy {

    private static final BigDecimal ONE = BigDecimal.ONE;

    @Override
    public ExitQuote exitQuote(PositionCost longPosition,
                               PositionCost shortPosition,
                               BigDecimal closeFeeRate,
                               BigDecimal fundingBufferRate,
                               BigDecimal safetyBufferRate,
                               BigDecimal minNetProfitRate,
                               BigDecimal priceTick) {
        validateRate("closeFeeRate", closeFeeRate);
        validateRate("fundingBufferRate", fundingBufferRate);
        validateRate("safetyBufferRate", safetyBufferRate);
        validateRate("minNetProfitRate", minNetProfitRate);
        validateTick(priceTick);
        BigDecimal longClose = longPosition == null ? null : longClosePrice(longPosition, closeFeeRate,
                fundingBufferRate, safetyBufferRate, minNetProfitRate, priceTick);
        BigDecimal shortClose = shortPosition == null ? null : shortClosePrice(shortPosition, closeFeeRate,
                fundingBufferRate, safetyBufferRate, minNetProfitRate, priceTick);
        return new ExitQuote(longClose, shortClose);
    }

    @Override
    public boolean entrySpreadAllowed(BigDecimal bestBid, BigDecimal bestAsk, BigDecimal maxSpreadRate) {
        if (bestBid == null || bestAsk == null || bestBid.signum() <= 0
                || bestAsk.compareTo(bestBid) < 0 || maxSpreadRate == null || maxSpreadRate.signum() < 0) {
            return false;
        }
        BigDecimal spreadRate = bestAsk.subtract(bestBid).divide(bestBid, 18, RoundingMode.UP);
        return spreadRate.compareTo(maxSpreadRate) <= 0;
    }

    private static BigDecimal longClosePrice(PositionCost position,
                                             BigDecimal closeFeeRate,
                                             BigDecimal fundingBufferRate,
                                             BigDecimal safetyBufferRate,
                                             BigDecimal minNetProfitRate,
                                             BigDecimal tick) {
        PositionAmounts amounts = amounts(position, fundingBufferRate, safetyBufferRate, minNetProfitRate);
        BigDecimal denominator = amounts.baseQuantity().multiply(ONE.subtract(closeFeeRate));
        if (denominator.signum() <= 0) {
            throw new IllegalArgumentException("long close fee consumes all proceeds");
        }
        BigDecimal raw = amounts.entryNotional().add(amounts.openFee()).add(amounts.padding())
                .divide(denominator, 18, RoundingMode.CEILING);
        BigDecimal rounded = roundToTick(raw, tick, RoundingMode.CEILING);
        return rounded.compareTo(position.averageOpenPrice()) > 0
                ? rounded : position.averageOpenPrice().add(tick);
    }

    private static BigDecimal shortClosePrice(PositionCost position,
                                              BigDecimal closeFeeRate,
                                              BigDecimal fundingBufferRate,
                                              BigDecimal safetyBufferRate,
                                              BigDecimal minNetProfitRate,
                                              BigDecimal tick) {
        PositionAmounts amounts = amounts(position, fundingBufferRate, safetyBufferRate, minNetProfitRate);
        BigDecimal numerator = amounts.entryNotional().subtract(amounts.openFee()).subtract(amounts.padding());
        BigDecimal denominator = amounts.baseQuantity().multiply(ONE.add(closeFeeRate));
        if (numerator.signum() <= 0) {
            throw new IllegalArgumentException("short cost buffers exceed entry proceeds");
        }
        BigDecimal rounded = roundToTick(numerator.divide(denominator, 18, RoundingMode.FLOOR),
                tick, RoundingMode.FLOOR);
        return rounded.compareTo(position.averageOpenPrice()) < 0
                ? rounded : position.averageOpenPrice().subtract(tick);
    }

    private static PositionAmounts amounts(PositionCost position,
                                           BigDecimal fundingBufferRate,
                                           BigDecimal safetyBufferRate,
                                           BigDecimal minNetProfitRate) {
        if (position.averageOpenPrice() == null || position.averageOpenPrice().signum() <= 0
                || position.contractSize() == null || position.contractSize().signum() <= 0
                || position.volume() <= 0L) {
            throw new IllegalArgumentException("position price, contract size and volume must be positive");
        }
        BigDecimal baseQuantity = position.contractSize().multiply(BigDecimal.valueOf(position.volume()));
        BigDecimal entryNotional = baseQuantity.multiply(position.averageOpenPrice());
        BigDecimal openFee = nonNegative(position.actualOpenFee());
        BigDecimal paddingRate = fundingBufferRate.add(safetyBufferRate).add(minNetProfitRate);
        return new PositionAmounts(baseQuantity, entryNotional, openFee, entryNotional.multiply(paddingRate));
    }

    private static BigDecimal roundToTick(BigDecimal value, BigDecimal tick, RoundingMode mode) {
        return value.divide(tick, 0, mode).multiply(tick);
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value == null || value.signum() < 0 ? BigDecimal.ZERO : value;
    }

    private static void validateRate(String name, BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(ONE) >= 0) {
            throw new IllegalArgumentException(name + " must be in [0, 1)");
        }
    }

    private static void validateTick(BigDecimal tick) {
        if (tick == null || tick.signum() <= 0) {
            throw new IllegalArgumentException("priceTick must be positive");
        }
    }

    private record PositionAmounts(BigDecimal baseQuantity,
                                   BigDecimal entryNotional,
                                   BigDecimal openFee,
                                   BigDecimal padding) {
    }
}
