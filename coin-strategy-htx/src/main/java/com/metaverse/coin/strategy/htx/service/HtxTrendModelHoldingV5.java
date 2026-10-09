package com.metaverse.coin.strategy.htx.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import static com.metaverse.coin.strategy.htx.service.HtxTrendExitContractV5.*;

/** Pure adapter from a frozen model contract and one eligible close to E-S evidence. */
public final class HtxTrendModelHoldingV5 {
    private static final Set<String> SUPPORTED = Set.of("FIB_PULLBACK_CONTINUATION", "DONCHIAN_BREAKOUT",
            "EMA_VWAP_MOMENTUM_CONTINUATION");
    private HtxTrendModelHoldingV5() { }
    public record Evidence(ModelState state, List<String> evidence_groups, List<String> reason_codes) { }
    public static Evidence evaluate(EntryThesis contract, BigDecimal close, boolean sourceComplete) {
        if (!SUPPORTED.contains(contract.model_id())) return new Evidence(ModelState.UNKNOWN,List.of(),List.of("UNSUPPORTED_MODEL"));
        if (!sourceComplete || close == null || close.signum() <= 0) return new Evidence(ModelState.UNKNOWN,List.of(),List.of("MODEL_SOURCE_INCOMPLETE"));
        if (contract.failure_zone()==null || contract.failure_zone().compareTo(contract.immutable_initial_stop())==0)
            return new Evidence(ModelState.UNKNOWN,List.of(),List.of("SOFT_FAILURE_ZONE_DISABLED"));
        boolean failed=contract.side()==Side.LONG?close.compareTo(contract.failure_zone())<=0:close.compareTo(contract.failure_zone())>=0;
        return failed?new Evidence(ModelState.INVALID,List.of("E-S"),List.of(contract.model_id()+"_FAILURE_ZONE_CROSSED"))
                :new Evidence(ModelState.VALID,List.of(),List.of(contract.model_id()+"_THESIS_VALID"));
    }
}
