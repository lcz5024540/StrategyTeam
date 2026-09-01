package com.metaverse.coin.strategy.htx.strategy;

import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrderRequest;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure aggregate delta hedge planner for the moving spot grid.
 * It does not submit orders; callers must persist intent and send through a dedicated hedge owner.
 */
public class DeltaHedgeManager {

    private static final String MARGIN_MODE = "isolated";
    private static final String POSITION_SIDE_SHORT = "short";
    private static final String OPEN_SHORT_SIDE = "sell";
    private static final String CLOSE_SHORT_SIDE = "buy";
    private static final String MARKET_TYPE = "market";
    private static final String IOC = "ioc";

    public HedgeDecision plan(HedgeInput input) {
        if (input == null || !input.enabled()) {
            return HedgeDecision.none(MovingGridEngine.RUNNING, BigDecimal.ZERO, "hedge disabled");
        }
        BigDecimal contractSize = positive(input.contractSize());
        if (contractSize.signum() <= 0) {
            return HedgeDecision.none(MarketMakerRiskCoordinator.HEDGE_DEGRADED,
                    BigDecimal.ZERO, "contract size unavailable");
        }
        BigDecimal currentShort = contractBase(input.currentShortContracts(), contractSize);
        if (!input.fundingAllowed() || !input.basisAllowed() || !input.marginSafe()) {
            if (currentShort.signum() > 0) {
                return closeShort(input, contractSize, currentShort, BigDecimal.ZERO,
                        "hedge data or margin gate blocked; reduce existing short");
            }
            return HedgeDecision.none(MarketMakerRiskCoordinator.HEDGE_DEGRADED,
                    BigDecimal.ZERO, "hedge data or margin gate blocked");
        }

        BigDecimal target = targetShortBase(input.spotBaseInventory(), input.hedgeRatio(),
                input.maxShortBase(), contractSize);
        int cmp = currentShort.compareTo(target);
        if (cmp < 0) {
            return openShort(input, contractSize, target.subtract(currentShort), target);
        }
        if (cmp > 0) {
            return closeShort(input, contractSize, currentShort.subtract(target), target,
                    "reduce excess short");
        }
        return HedgeDecision.none(MovingGridEngine.RUNNING, target, "hedge already aligned");
    }

    private HedgeDecision openShort(HedgeInput input, BigDecimal contractSize,
                                    BigDecimal deltaBase, BigDecimal targetBase) {
        long contracts = contracts(deltaBase, contractSize);
        if (contracts <= 0) {
            return HedgeDecision.none(MovingGridEngine.RUNNING, targetBase, "delta below contract step");
        }
        return new HedgeDecision(Action.OPEN_SHORT, MovingGridEngine.RUNNING, targetBase,
                order(input.contractCode(), input.clientOrderIdPrefix(), input.epoch(), "OPEN",
                        contracts, OPEN_SHORT_SIDE, false), null);
    }

    private HedgeDecision closeShort(HedgeInput input, BigDecimal contractSize,
                                     BigDecimal deltaBase, BigDecimal targetBase, String reason) {
        long contracts = contracts(deltaBase, contractSize);
        if (contracts <= 0) {
            return HedgeDecision.none(MovingGridEngine.RUNNING, targetBase, "delta below contract step");
        }
        return new HedgeDecision(Action.CLOSE_SHORT, MovingGridEngine.RUNNING, targetBase,
                order(input.contractCode(), input.clientOrderIdPrefix(), input.epoch(), "CLOSE",
                        contracts, CLOSE_SHORT_SIDE, true), reason);
    }

    private static HtxSwapOrderRequest order(String contractCode, String prefix, long epoch,
                                             String action, long contracts, String side,
                                             boolean reduceOnly) {
        return HtxSwapOrderRequest.builder()
                .contractCode(contractCode)
                .marginMode(MARGIN_MODE)
                .positionSide(POSITION_SIDE_SHORT)
                .side(side)
                .type(MARKET_TYPE)
                .clientOrderId(prefix + "-" + action + "-" + epoch)
                .volume(Long.toString(contracts))
                .reduceOnly(reduceOnly)
                .timeInForce(IOC)
                .build();
    }

    private static BigDecimal targetShortBase(BigDecimal inventory, BigDecimal ratio,
                                              BigDecimal maxShortBase, BigDecimal contractSize) {
        BigDecimal raw = positive(inventory).multiply(positive(ratio));
        BigDecimal cap = positive(maxShortBase);
        if (cap.signum() > 0 && raw.compareTo(cap) > 0) {
            raw = cap;
        }
        long contracts = contracts(raw, contractSize);
        return contractSize.multiply(BigDecimal.valueOf(contracts));
    }

    private static BigDecimal contractBase(long contracts, BigDecimal contractSize) {
        if (contracts <= 0 || contractSize == null) {
            return BigDecimal.ZERO;
        }
        return contractSize.multiply(BigDecimal.valueOf(contracts));
    }

    private static long contracts(BigDecimal baseAmount, BigDecimal contractSize) {
        if (baseAmount == null || contractSize == null || contractSize.signum() <= 0) {
            return 0L;
        }
        return baseAmount.divide(contractSize, 0, RoundingMode.DOWN).longValue();
    }

    private static BigDecimal positive(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return value;
    }

    public enum Action {
        NONE,
        OPEN_SHORT,
        CLOSE_SHORT
    }

    public record HedgeInput(boolean enabled, String symbol, String contractCode, BigDecimal spotBaseInventory,
                             long currentShortContracts, BigDecimal contractSize, BigDecimal hedgeRatio,
                             BigDecimal maxShortBase, boolean fundingAllowed, boolean basisAllowed,
                             boolean marginSafe, String clientOrderIdPrefix, long epoch) {
    }

    public record HedgeDecision(Action action, String state, BigDecimal targetShortBase,
                                HtxSwapOrderRequest orderRequest, String reason) {
        public static HedgeDecision none(String state, BigDecimal targetShortBase, String reason) {
            return new HedgeDecision(Action.NONE, state, targetShortBase, null, reason);
        }
    }
}
