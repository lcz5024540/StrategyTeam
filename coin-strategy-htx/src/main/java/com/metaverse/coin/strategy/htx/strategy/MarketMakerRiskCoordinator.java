package com.metaverse.coin.strategy.htx.strategy;

import java.math.BigDecimal;

/** Spot grid risk gates: block only new risk while allowing reconciliation and inventory reduction. */
public class MarketMakerRiskCoordinator {

    public static final String PAUSED_RISK = "PAUSED_RISK";
    public static final String RECOVERING = "RECOVERING";
    public static final String HEDGE_DEGRADED = "HEDGE_DEGRADED";

    public RiskDecision evaluate(SpotBalanceSnapshot balances, BigDecimal requiredQuote,
                                 BigDecimal quoteReserve, BigDecimal currentBaseInventory,
                                 BigDecimal pendingBuyBase, BigDecimal maxBaseInventory) {
        if (balances == null || !balances.available()) {
            return RiskDecision.blocked(RECOVERING, "spot balance unavailable");
        }
        BigDecimal reserve = positive(quoteReserve);
        BigDecimal required = positive(requiredQuote);
        BigDecimal availableQuote = positive(balances.availableQuote());
        if (availableQuote.compareTo(required.add(reserve)) < 0) {
            return RiskDecision.blocked(PAUSED_RISK,
                    "quote reserve breached: available=" + availableQuote.toPlainString()
                            + ",required=" + required.toPlainString()
                            + ",reserve=" + reserve.toPlainString());
        }
        BigDecimal cap = positive(maxBaseInventory);
        if (cap.signum() > 0) {
            BigDecimal projectedBase = positive(currentBaseInventory).add(positive(pendingBuyBase));
            if (projectedBase.compareTo(cap) > 0) {
                return RiskDecision.blocked(PAUSED_RISK,
                        "base inventory cap breached: projected=" + projectedBase.toPlainString()
                                + ",cap=" + cap.toPlainString());
            }
        }
        return new RiskDecision(MovingGridEngine.RUNNING, true, null);
    }

    private static BigDecimal positive(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return value;
    }

    public record SpotBalanceSnapshot(boolean available, BigDecimal availableQuote, BigDecimal baseInventory) {
        public static SpotBalanceSnapshot unavailable() {
            return new SpotBalanceSnapshot(false, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    public record RiskDecision(String state, boolean allowNewRisk, String reason) {
        public static RiskDecision blocked(String state, String reason) {
            return new RiskDecision(state, false, reason);
        }
    }
}
