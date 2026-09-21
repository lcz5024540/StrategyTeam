package com.metaverse.coin.strategy.core.arbitrage.spotperp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Sizes a delta-neutral hedge (Scheme A default 10,000 USDT) to venue lot constraints. */
public final class SpotPerpSizer {
    public record Result(
            boolean accepted,
            String rejectionReason,
            BigDecimal quantity,
            BigDecimal targetNotional,
            BigDecimal spotNotional,
            BigDecimal perpNotional
    ) {
        public static Result reject(String reason) {
            return new Result(false, reason, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    private final SpotPerpRules rules;

    public SpotPerpSizer(SpotPerpRules rules) {
        this.rules = Objects.requireNonNull(rules, "rules");
    }

    public Result size(SpotPerpSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        BigDecimal step = snapshot.quantityStep();
        BigDecimal spotAsk = snapshot.spotAsk();
        BigDecimal target = rules.notionalUsdt();
        BigDecimal raw = target.divide(spotAsk, 16, RoundingMode.DOWN);
        BigDecimal quantity = roundDownToStep(raw, step);
        if (quantity.signum() <= 0) {
            BigDecimal oneLot = step;
            BigDecimal oneLotNotional = oneLot.multiply(spotAsk);
            BigDecimal maxNotional = target.multiply(rules.maxLotOvershoot());
            if (oneLotNotional.compareTo(maxNotional) <= 0
                    && oneLotNotional.compareTo(snapshot.minimumNotional()) >= 0) {
                quantity = oneLot;
            } else {
                return Result.reject("SIZED_NOTIONAL_TOO_SMALL_FOR_LOT");
            }
        }
        BigDecimal spotNotional = quantity.multiply(spotAsk);
        BigDecimal maxNotional = target.multiply(rules.maxLotOvershoot());
        if (spotNotional.compareTo(maxNotional) > 0) {
            quantity = roundDownToStep(maxNotional.divide(spotAsk, 16, RoundingMode.DOWN), step);
            spotNotional = quantity.multiply(spotAsk);
        }
        if (quantity.signum() <= 0 || spotNotional.compareTo(snapshot.minimumNotional()) < 0) {
            return Result.reject("SIZED_NOTIONAL_BELOW_EXCHANGE_MINIMUM");
        }
        BigDecimal perpNotional = quantity.multiply(snapshot.perpBid());
        return new Result(true, null, quantity, target, spotNotional, perpNotional);
    }

    static BigDecimal roundDownToStep(BigDecimal quantity, BigDecimal step) {
        if (quantity.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal steps = quantity.divide(step, 0, RoundingMode.DOWN);
        return steps.multiply(step);
    }
}
