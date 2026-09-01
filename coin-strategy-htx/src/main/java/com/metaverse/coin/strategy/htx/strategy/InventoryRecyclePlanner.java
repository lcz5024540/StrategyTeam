package com.metaverse.coin.strategy.htx.strategy;

import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrderRequest;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Pure planner for paired spot-inventory recycle: confirmed spot sell fill drives reduce-only short close. */
public class InventoryRecyclePlanner {

    private final DeltaHedgeManager hedgeManager = new DeltaHedgeManager();

    public RecycleDecision afterSpotSellFill(RecycleInput input) {
        if (input == null || input.spotSoldBase() == null || input.spotSoldBase().signum() <= 0) {
            return RecycleDecision.degraded("spot sell fill missing");
        }
        BigDecimal closeBase = input.spotSoldBase().min(input.availableShortBase());
        long contracts = contracts(closeBase, input.contractSize());
        if (contracts <= 0) {
            return RecycleDecision.none("spot fill below contract step");
        }
        DeltaHedgeManager.HedgeInput hedgeInput = new DeltaHedgeManager.HedgeInput(
                true, input.symbol(), input.contractCode(), BigDecimal.ZERO, contracts, input.contractSize(),
                BigDecimal.ZERO, BigDecimal.ZERO, true, true, true, input.clientOrderIdPrefix(), input.epoch());
        HtxSwapOrderRequest request = hedgeManager.plan(hedgeInput).orderRequest();
        return new RecycleDecision(MovingGridEngine.RUNNING, contracts, request, null);
    }

    private static long contracts(BigDecimal baseAmount, BigDecimal contractSize) {
        if (baseAmount == null || contractSize == null || contractSize.signum() <= 0) {
            return 0L;
        }
        return baseAmount.divide(contractSize, 0, RoundingMode.DOWN).longValue();
    }

    public record RecycleInput(String symbol, String contractCode, BigDecimal spotSoldBase,
                               BigDecimal availableShortBase, BigDecimal contractSize,
                               String clientOrderIdPrefix, long epoch) {
    }

    public record RecycleDecision(String state, long closeContracts,
                                  HtxSwapOrderRequest closeShortRequest, String reason) {
        public static RecycleDecision degraded(String reason) {
            return new RecycleDecision(MarketMakerRiskCoordinator.HEDGE_DEGRADED, 0L, null, reason);
        }

        public static RecycleDecision none(String reason) {
            return new RecycleDecision(MovingGridEngine.RUNNING, 0L, null, reason);
        }
    }
}
