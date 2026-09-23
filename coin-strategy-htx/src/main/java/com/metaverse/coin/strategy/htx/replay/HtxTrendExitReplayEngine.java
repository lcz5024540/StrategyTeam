package com.metaverse.coin.strategy.htx.replay;

import com.metaverse.coin.strategy.htx.service.HtxTrendExitRules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Deterministic exit replay. It never calls an exchange and never silently assumes an intrabar path.
 * A bar that reaches both a protective stop and profit target uses the adverse stop-first outcome and
 * is marked {@code INTRABAR_AMBIGUOUS}; missing fee or required high-timeframe evidence is not comparable.
 */
public final class HtxTrendExitReplayEngine {

    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal TWO = new BigDecimal("2");
    private static final BigDecimal TRAILING_ATR_MULTIPLIER = new BigDecimal("2");

    public Outcome replay(Input input, List<Candle> rawCandles, Map<Long, Integer> oppositionByBar) {
        String missing = input.validationError();
        if (missing != null) {
            return Outcome.insufficient(missing);
        }
        List<Candle> candles = rawCandles == null ? List.of() : rawCandles.stream()
                .filter(candle -> candle != null && candle.barTime() > input.entryBarTime())
                .sorted(Comparator.comparingLong(Candle::barTime)).toList();
        if (candles.isEmpty()) {
            return Outcome.insufficient("entry period candles unavailable after entry bar");
        }

        BigDecimal entryFee = input.entryFee() != null && input.entryFee().signum() >= 0
                ? input.entryFee() : fee(input.entryPrice(), input.quantity(), input.feeRate());
        BigDecimal oneR = HtxTrendExitRules.rTargetPrice(input.side(), input.entryPrice(), input.stopPrice(), ONE);
        BigDecimal twoR = HtxTrendExitRules.rTargetPrice(input.side(), input.entryPrice(), input.stopPrice(), TWO);
        BigDecimal structure = input.structuralTarget();
        long remaining = input.contractVolume();
        int stage = 0;
        BigDecimal realizedGross = BigDecimal.ZERO;
        BigDecimal closeFees = BigDecimal.ZERO;
        BigDecimal highest = input.entryPrice();
        BigDecimal lowest = input.entryPrice();
        BigDecimal mfe = BigDecimal.ZERO;
        BigDecimal mae = BigDecimal.ZERO;
        boolean ambiguous = false;
        boolean missingTimeframeEvidence = false;

        for (Candle candle : candles) {
            highest = highest.max(candle.high());
            lowest = lowest.min(candle.low());
            mfe = max(mfe, favorablePnl(input, highest, input.contractVolume()));
            mae = min(mae, favorablePnl(input, lowest, input.contractVolume()));

            boolean stopHit = HtxTrendExitRules.crossedStop(input.side(), adversePrice(input.side(), candle), input.stopPrice());
            boolean oneRHit = HtxTrendExitRules.targetReached(input.side(), favorablePrice(input.side(), candle), oneR);
            boolean structureHit = HtxTrendExitRules.targetReached(input.side(), favorablePrice(input.side(), candle), structure);
            boolean twoRHit = HtxTrendExitRules.targetReached(input.side(), favorablePrice(input.side(), candle), twoR);
            if (stopHit && (oneRHit || structureHit || twoRHit)) {
                ambiguous = true;
                return closeAll(input, candle, remaining, realizedGross, closeFees, entryFee, mfe, mae,
                        input.stopPrice(), "MODEL_INVALIDATION", "INTRABAR_AMBIGUOUS: stop and target in one bar");
            }
            if (stopHit) {
                return closeAll(input, candle, remaining, realizedGross, closeFees, entryFee, mfe, mae,
                        input.stopPrice(), "MODEL_INVALIDATION", null);
            }
            BigDecimal activation = HtxTrendExitRules.rTargetPrice(input.side(), input.entryPrice(), input.stopPrice(), ONE);
            OptionalInt opposition = oppositionByBar == null ? OptionalInt.empty()
                    : oppositionByBar.containsKey(candle.barTime())
                    ? OptionalInt.of(oppositionByBar.get(candle.barTime())) : OptionalInt.empty();
            boolean runnerActivated = HtxTrendExitRules.targetReached(input.side(), favorablePrice(input.side(), candle), activation);
            BigDecimal trailing = runnerActivated ? trailingStop(input.side(), highest, lowest, input.atr()) : null;
            boolean trailingHit = runnerActivated && HtxTrendExitRules.crossedStop(input.side(), adversePrice(input.side(), candle), trailing);
            boolean trailingAllowed = runnerActivated && opposition.isPresent()
                    && HtxTrendExitRules.trailingExitAllowed(input.side(), input.entryPrice(), input.stopPrice(),
                    favorablePrice(input.side(), candle), ONE, true, opposition.getAsInt(), 2);
            if (runnerActivated && opposition.isEmpty()) {
                missingTimeframeEvidence = true;
            }
            if (trailingHit && trailingAllowed && (oneRHit || structureHit || twoRHit)) {
                return closeAll(input, candle, remaining, realizedGross, closeFees, entryFee, mfe, mae, trailing,
                        "ATR_TRAILING", "INTRABAR_AMBIGUOUS: trailing stop and target in one bar");
            }
            if (stage < 1 && (oneRHit || structureHit)) {
                BigDecimal target = firstReachedTarget(input.side(), oneR, oneRHit, structure, structureHit);
                long closeVolume = HtxTrendExitRules.partialCloseVolume(remaining, input.firstPartialRate());
                if (closeVolume > 0L) {
                    realizedGross = realizedGross.add(favorablePnl(input, target, closeVolume));
                    closeFees = closeFees.add(fee(target, quantity(input, closeVolume), input.feeRate()));
                    remaining -= closeVolume;
                    stage = 1;
                }
            }
            if (stage < 2 && twoRHit) {
                long closeVolume = HtxTrendExitRules.partialCloseVolume(remaining, input.secondPartialRate());
                if (closeVolume > 0L) {
                    realizedGross = realizedGross.add(favorablePnl(input, twoR, closeVolume));
                    closeFees = closeFees.add(fee(twoR, quantity(input, closeVolume), input.feeRate()));
                    remaining -= closeVolume;
                    stage = 2;
                }
            }
            if (trailingHit && trailingAllowed) {
                return closeAll(input, candle, remaining, realizedGross, closeFees, entryFee, mfe, mae,
                        trailing, "ATR_TRAILING", null);
            }
        }
        Candle last = candles.get(candles.size() - 1);
        BigDecimal gross = realizedGross.add(favorablePnl(input, last.close(), remaining));
        BigDecimal markCloseFee = closeFees.add(fee(last.close(), quantity(input, remaining), input.feeRate()));
        String integrity = missingTimeframeEvidence ? "INSUFFICIENT_EVIDENCE" : "COMPLETE";
        String message = missingTimeframeEvidence
                ? "high timeframe opposition unavailable for at least one runner evaluation"
                : "replay window ended with remaining runner marked to market";
        return new Outcome("MARK_TO_MARK", "REPLAY_WINDOW_END_MARK", integrity, null, last.barTime(), last.close(),
                remaining, mfe, mae, entryFee, markCloseFee, gross.subtract(entryFee).subtract(markCloseFee), message,
                stage, ambiguous);
    }

    private static Outcome closeAll(Input input, Candle candle, long remaining, BigDecimal realizedGross,
                                    BigDecimal closeFees, BigDecimal entryFee, BigDecimal mfe, BigDecimal mae,
                                    BigDecimal exitPrice, String reason, String ambiguity) {
        BigDecimal gross = realizedGross.add(favorablePnl(input, exitPrice, remaining));
        BigDecimal totalCloseFee = closeFees.add(fee(exitPrice, quantity(input, remaining), input.feeRate()));
        return new Outcome("CLOSED", reason, "COMPLETE", ambiguity, candle.barTime(), exitPrice, 0L, mfe, mae,
                entryFee, totalCloseFee, gross.subtract(entryFee).subtract(totalCloseFee),
                ambiguity == null ? "all replay exit rules evaluated" : "conservative stop-first intrabar rule", 0, ambiguity != null);
    }

    private static BigDecimal trailingStop(String side, BigDecimal highest, BigDecimal lowest, BigDecimal atr) {
        return HtxTrendExitRules.LONG.equals(side) ? highest.subtract(atr.multiply(TRAILING_ATR_MULTIPLIER))
                : lowest.add(atr.multiply(TRAILING_ATR_MULTIPLIER));
    }

    private static BigDecimal firstReachedTarget(String side, BigDecimal first, boolean firstReached,
                                                  BigDecimal second, boolean secondReached) {
        if (!secondReached) {
            return first;
        }
        if (!firstReached) {
            return second;
        }
        return HtxTrendExitRules.LONG.equals(side) ? first.min(second) : first.max(second);
    }

    private static BigDecimal favorablePnl(Input input, BigDecimal price, long contracts) {
        BigDecimal quantity = quantity(input, contracts);
        return HtxTrendExitRules.LONG.equals(input.side())
                ? price.subtract(input.entryPrice()).multiply(quantity)
                : input.entryPrice().subtract(price).multiply(quantity);
    }

    private static BigDecimal quantity(Input input, long contracts) {
        return input.contractSize().multiply(BigDecimal.valueOf(contracts));
    }

    private static BigDecimal fee(BigDecimal price, BigDecimal quantity, BigDecimal rate) {
        return price.multiply(quantity).multiply(rate);
    }

    private static BigDecimal favorablePrice(String side, Candle candle) {
        return HtxTrendExitRules.LONG.equals(side) ? candle.high() : candle.low();
    }

    private static BigDecimal adversePrice(String side, Candle candle) {
        return HtxTrendExitRules.LONG.equals(side) ? candle.low() : candle.high();
    }

    private static BigDecimal max(BigDecimal left, BigDecimal right) {
        return left.compareTo(right) >= 0 ? left : right;
    }

    private static BigDecimal min(BigDecimal left, BigDecimal right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    public record Input(String side, long entryBarTime, BigDecimal entryPrice, BigDecimal stopPrice,
                        BigDecimal structuralTarget, BigDecimal atr, BigDecimal contractSize, long contractVolume,
                        BigDecimal entryFee, BigDecimal feeRate, BigDecimal firstPartialRate,
                        BigDecimal secondPartialRate) {
        String validationError() {
            if (!HtxTrendExitRules.LONG.equals(side) && !HtxTrendExitRules.SHORT.equals(side)) return "unsupported side";
            if (!HtxTrendExitRules.positive(entryPrice) || !HtxTrendExitRules.positive(stopPrice)) return "entry or stop price unavailable";
            if (!HtxTrendExitRules.positive(atr)) return "entry ATR unavailable";
            if (!HtxTrendExitRules.positive(contractSize) || contractVolume <= 0L) return "filled contract quantity unavailable";
            if ((entryFee == null || entryFee.signum() < 0) && !HtxTrendExitRules.positive(feeRate)) return "account fee rate unavailable";
            if (!HtxTrendExitRules.positive(feeRate)) return "close fee rate unavailable";
            return null;
        }

        BigDecimal quantity() {
            return contractSize.multiply(BigDecimal.valueOf(contractVolume));
        }
    }

    public record Candle(long barTime, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {
        public Candle {
            if (!HtxTrendExitRules.positive(open) || !HtxTrendExitRules.positive(high)
                    || !HtxTrendExitRules.positive(low) || !HtxTrendExitRules.positive(close)) {
                throw new IllegalArgumentException("replay candle must contain positive OHLC prices");
            }
        }
    }

    public record Outcome(String outcomeState, String exitReason, String dataIntegrity, String ambiguityReason,
                          Long exitBarTime, BigDecimal exitPrice, long remainingContracts, BigDecimal mfe,
                          BigDecimal mae, BigDecimal entryFee, BigDecimal closeFee, BigDecimal netProfit,
                          String message, int partialExitStage, boolean intrabarAmbiguous) {
        static Outcome insufficient(String message) {
            return new Outcome("UNAVAILABLE", "INSUFFICIENT_EVIDENCE", "INSUFFICIENT_EVIDENCE", null,
                    null, null, 0L, null, null, null, null, null, message, 0, false);
        }
    }
}
