package com.metaverse.coin.strategy.htx.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendFactorSnapshotEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendPaperExecutionEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendPaperExecutionEventEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendPaperExecutionMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendPaperExecutionEventMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Locale;

/** Accepts paper-only fills after validating the immutable outbox and price-plan boundaries. */
@Service
@RequiredArgsConstructor
public class HtxTrendPaperExecutionService {
    private static final String OUTCOME_FILLED = "FILLED";
    private static final String OUTCOME_PARTIALLY_FILLED = "PARTIALLY_FILLED";
    private static final String OUTCOME_REJECTED = "REJECTED";
    private static final String OUTCOME_EXPIRED = "EXPIRED";
    private static final String SIDE_LONG = "LONG";
    private static final String SIDE_SHORT = "SHORT";
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int RATIO_SCALE = 12;

    private final HtxTrendCombinationService combinationService;
    private final HtxTrendPaperExecutionMapper mapper;
    private final HtxTrendPaperExecutionEventMapper eventMapper;
    private final HtxTrendOrderStateMachine orderStateMachine = new HtxTrendOrderStateMachine();

    /**
     * Compatibility callback for the original one-shot paper executor. It now emits the equivalent
     * immutable lifecycle before it writes the existing fill summary, so old subscribers keep working
     * while BT-03 can distinguish a fill from an expired or rejected order.
     */
    @Transactional
    public Result report(Report request) {
        String requestError = validateRequest(request);
        if (requestError != null) {
            return Result.rejected(requestError);
        }
        if (mapper.findByExecutionKey(request.executionKey()) != null) {
            return Result.rejected("duplicate paper execution key");
        }
        HtxTrendFactorSnapshotEntity signal = combinationService.latestExternalSignal(request.contractCode(), request.period(),
                request.barTime(), request.policyVersion(), request.runId());
        if (signal == null) {
            return Result.rejected("ENTRY_READY outbox event not found");
        }
        HtxTrendFactorSnapshotEntity pricePlanSnapshot = combinationService.latestPricePlan(request.contractCode(), request.period(),
                request.barTime(), request.policyVersion(), request.runId());
        if (pricePlanSnapshot == null) {
            return Result.rejected("price plan not found for ENTRY_READY outbox event");
        }
        PricePlan plan = parsePlan(pricePlanSnapshot, request.runId());
        if (plan == null) {
            return Result.rejected("price plan is incomplete for paper execution");
        }
        String outcome = normalizeOutcome(request.outcome());
        if (isExpired(plan) && !OUTCOME_EXPIRED.equals(outcome)) {
            return Result.rejected("price plan expired; report EXPIRED instead");
        }
        String outcomeError = validateOutcome(request, outcome, plan);
        if (outcomeError != null) {
            return Result.rejected(outcomeError);
        }
        String lifecycleError = recordLegacyLifecycle(request, outcome, plan.plannedContracts());
        if (lifecycleError != null) {
            return Result.rejected(lifecycleError);
        }
        if (isFill(outcome)) {
            HtxTrendCombinationService.PaperExitPlanEvidence paperExitPlan = recomputeExitPlan(request, plan);
            if (paperExitPlan == null) {
                return Result.rejected("fill price violates initial stop boundary");
            }
            Long snapshotId = combinationService.recordPaperExitPlan(request.contractCode(), request.period(), request.barTime(),
                    request.policyVersion(), pricePlanSnapshot.getMarketRegime(), paperExitPlan);
            if (snapshotId == null) {
                return Result.rejected("paper exit plan persistence failed");
            }
        }
        HtxTrendPaperExecutionEntity entity = new HtxTrendPaperExecutionEntity();
        entity.setExecutionKey(request.executionKey());
        entity.setRunId(request.runId());
        entity.setContractCode(request.contractCode());
        entity.setOutcome(outcome);
        entity.setFillPrice(request.fillPrice());
        entity.setFilledContracts(request.filledContracts());
        entity.setReason(request.reason());
        try {
            if (mapper.insert(entity) != 1) {
                return Result.rejected("paper execution persistence failed");
            }
            return Result.accepted(entity.getId());
        } catch (RuntimeException ex) {
            return Result.rejected("duplicate or persistence failure");
        }
    }

    /**
     * Event-oriented paper callback. It never creates an HTX order or position; callers may use it
     * to record SUBMIT/ACCEPT/FILL/CANCEL transitions before a terminal fill summary is reported.
     */
    @Transactional
    public LifecycleResult reportEvent(LifecycleEvent request) {
        if (request == null || blank(request.eventKey()) || blank(request.executionKey()) || blank(request.runId())
                || request.requestedContracts() <= 0L || request.cumulativeFilledContracts() < 0L
                || request.cumulativeFilledContracts() > request.requestedContracts() || request.eventType() == null) {
            return LifecycleResult.rejected("paper execution event identity is incomplete");
        }
        HtxTrendPaperExecutionEventEntity duplicate = eventMapper.findByEventKey(request.eventKey());
        if (duplicate != null) {
            return LifecycleResult.accepted(duplicate.getId(), state(duplicate.getStateTo()));
        }
        HtxTrendPaperExecutionEventEntity prior = eventMapper.findLatestByExecutionKey(request.executionKey());
        HtxTrendOrderStateMachine.State current = prior == null ? HtxTrendOrderStateMachine.State.INTENT
                : state(prior.getStateTo());
        if (current == null) {
            return LifecycleResult.rejected("stored paper execution state is invalid");
        }
        long priorFilled = prior == null || prior.getCumulativeFilledContracts() == null ? 0L
                : prior.getCumulativeFilledContracts();
        if (request.cumulativeFilledContracts() < priorFilled) {
            return LifecycleResult.rejected("cumulative filled contracts cannot decrease");
        }
        if (request.eventType() == HtxTrendOrderStateMachine.Event.FILL_UPDATE && !positive(request.fillPrice())) {
            return LifecycleResult.rejected("fill update requires a positive fill price");
        }
        HtxTrendOrderStateMachine.Transition transition = orderStateMachine.apply(current, request.eventType(),
                request.requestedContracts(), request.cumulativeFilledContracts());
        if (!transition.accepted()) {
            return LifecycleResult.rejected(transition.reason());
        }
        HtxTrendPaperExecutionEventEntity event = new HtxTrendPaperExecutionEventEntity();
        event.setEventKey(request.eventKey());
        event.setExecutionKey(request.executionKey());
        event.setRunId(request.runId());
        event.setStateFrom(transition.from().name());
        event.setStateTo(transition.to().name());
        event.setEventType(request.eventType().name());
        event.setRequestedContracts(request.requestedContracts());
        event.setCumulativeFilledContracts(request.cumulativeFilledContracts());
        event.setFillPrice(request.fillPrice());
        event.setReason(request.reason());
        event.setOccurredAt(request.occurredAt() == null ? LocalDateTime.now() : request.occurredAt());
        try {
            if (eventMapper.insert(event) != 1) {
                return LifecycleResult.rejected("paper execution event persistence failed");
            }
            return LifecycleResult.accepted(event.getId(), transition.to());
        } catch (RuntimeException ex) {
            return LifecycleResult.rejected("duplicate or persistence failure");
        }
    }

    private String recordLegacyLifecycle(Report request, String outcome, long requestedContracts) {
        String prefix = request.executionKey() + ":legacy:";
        LifecycleResult submitted = reportEvent(new LifecycleEvent(prefix + "submit", request.executionKey(), request.runId(),
                HtxTrendOrderStateMachine.Event.SUBMIT, requestedContracts, 0L, null, "legacy paper callback", null));
        if (!submitted.accepted()) return submitted.reason();
        LifecycleResult accepted = reportEvent(new LifecycleEvent(prefix + "accept", request.executionKey(), request.runId(),
                HtxTrendOrderStateMachine.Event.ACCEPT, requestedContracts, 0L, null, "legacy paper callback", null));
        if (!accepted.accepted()) return accepted.reason();
        HtxTrendOrderStateMachine.Event finalEvent = switch (outcome) {
            case OUTCOME_FILLED, OUTCOME_PARTIALLY_FILLED -> HtxTrendOrderStateMachine.Event.FILL_UPDATE;
            case OUTCOME_REJECTED -> HtxTrendOrderStateMachine.Event.REJECT;
            case OUTCOME_EXPIRED -> HtxTrendOrderStateMachine.Event.EXPIRE;
            default -> null;
        };
        if (finalEvent == null) return "unsupported paper outcome";
        long filled = isFill(outcome) ? request.filledContracts() : 0L;
        LifecycleResult terminal = reportEvent(new LifecycleEvent(prefix + "final:" + finalEvent.name(), request.executionKey(),
                request.runId(), finalEvent, requestedContracts, filled, request.fillPrice(), request.reason(), null));
        return terminal.accepted() ? null : terminal.reason();
    }

    private static HtxTrendOrderStateMachine.State state(String value) {
        try {
            return value == null ? null : HtxTrendOrderStateMachine.State.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String validateRequest(Report request) {
        if (request == null || blank(request.executionKey()) || blank(request.runId()) || blank(request.contractCode())
                || blank(request.period()) || request.barTime() == null || request.barTime() <= 0L
                || blank(request.policyVersion()) || blank(request.outcome())) {
            return "paper execution identity is incomplete";
        }
        return null;
    }

    private static String normalizeOutcome(String outcome) {
        return outcome.trim().toUpperCase(Locale.ROOT);
    }

    private static String validateOutcome(Report request, String outcome, PricePlan plan) {
        if (!OUTCOME_FILLED.equals(outcome) && !OUTCOME_PARTIALLY_FILLED.equals(outcome)
                && !OUTCOME_REJECTED.equals(outcome) && !OUTCOME_EXPIRED.equals(outcome)) {
            return "unsupported paper outcome";
        }
        if (!isFill(outcome)) {
            return positive(request.fillPrice()) || positive(request.filledContracts())
                    ? "non-fill outcome must not include fill price or filled contracts" : null;
        }
        if (!positive(request.fillPrice()) || !positive(request.filledContracts())) {
            return "fill price and filled contracts are required for a filled outcome";
        }
        if (request.filledContracts() > plan.plannedContracts()) {
            return "filled contracts exceed the price plan volume";
        }
        if (OUTCOME_FILLED.equals(outcome) && request.filledContracts() != plan.plannedContracts()) {
            return "FILLED outcome must match the price plan volume";
        }
        if (OUTCOME_PARTIALLY_FILLED.equals(outcome) && request.filledContracts() >= plan.plannedContracts()) {
            return "PARTIALLY_FILLED outcome must be smaller than the price plan volume";
        }
        if (SIDE_LONG.equals(plan.side()) && request.fillPrice().compareTo(plan.maxAcceptablePrice()) > 0) {
            return "long fill exceeds max acceptable price";
        }
        if (SIDE_SHORT.equals(plan.side()) && request.fillPrice().compareTo(plan.maxAcceptablePrice()) < 0) {
            return "short fill is below max acceptable price";
        }
        return null;
    }

    private static HtxTrendCombinationService.PaperExitPlanEvidence recomputeExitPlan(Report request, PricePlan plan) {
        BigDecimal fillPrice = request.fillPrice();
        BigDecimal riskDistance = fillPrice.subtract(plan.initialStopPrice()).abs();
        boolean stopIsValid = SIDE_LONG.equals(plan.side())
                ? fillPrice.compareTo(plan.initialStopPrice()) > 0 : fillPrice.compareTo(plan.initialStopPrice()) < 0;
        if (!stopIsValid || riskDistance.signum() <= 0) {
            return null;
        }
        BigDecimal quantity = plan.contractSize().multiply(BigDecimal.valueOf(request.filledContracts()));
        BigDecimal plannedRisk = riskDistance.multiply(quantity);
        BigDecimal oneR = SIDE_LONG.equals(plan.side()) ? fillPrice.add(riskDistance) : fillPrice.subtract(riskDistance);
        BigDecimal twoR = SIDE_LONG.equals(plan.side()) ? fillPrice.add(riskDistance.multiply(BigDecimal.valueOf(2L)))
                : fillPrice.subtract(riskDistance.multiply(BigDecimal.valueOf(2L)));
        BigDecimal structureMove = SIDE_LONG.equals(plan.side()) ? plan.structureTargetPrice().subtract(fillPrice)
                : fillPrice.subtract(plan.structureTargetPrice());
        BigDecimal structureGross = structureMove.multiply(quantity);
        BigDecimal estimatedCosts = plan.estimatedRoundTripCostUsdt().multiply(BigDecimal.valueOf(request.filledContracts()))
                .divide(BigDecimal.valueOf(plan.plannedContracts()), RATIO_SCALE, RoundingMode.HALF_UP);
        BigDecimal netR = plannedRisk.signum() > 0 ? structureGross.subtract(estimatedCosts)
                .divide(plannedRisk, RATIO_SCALE, RoundingMode.HALF_UP) : null;
        return new HtxTrendCombinationService.PaperExitPlanEvidence(request.runId(), request.executionKey(), plan.side(),
                fillPrice, request.filledContracts(), plan.contractSize(), plan.invalidationPrice(), plan.initialStopPrice(),
                oneR, twoR, plan.structureTargetPrice(), riskDistance, plannedRisk, structureGross, estimatedCosts, netR);
    }

    private static PricePlan parsePlan(HtxTrendFactorSnapshotEntity snapshot, String expectedRunId) {
        try {
            JSONObject root = JSON.parseObject(snapshot.getFactorsJson());
            if (root == null || !"trend-price-plan-v1".equals(root.getString("schema"))
                    || !expectedRunId.equals(root.getString("runId"))) {
                return null;
            }
            JSONObject entry = root.getJSONObject("entry");
            JSONObject exit = root.getJSONObject("exit");
            JSONObject risk = root.getJSONObject("risk");
            if (entry == null || exit == null || risk == null) {
                return null;
            }
            String side = entry.getString("side");
            long plannedContracts = entry.getLongValue("plannedContracts");
            PricePlan plan = new PricePlan(side, entry.getBigDecimal("maxAcceptablePrice"), entry.getLong("validUntilEpoch"),
                    entry.getBigDecimal("contractSize"), plannedContracts, exit.getBigDecimal("invalidationPrice"),
                    exit.getBigDecimal("initialStopPrice"), exit.getBigDecimal("structureTargetPrice"),
                    risk.getBigDecimal("estimatedRoundTripCostUsdt"));
            return plan.complete() ? plan : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static boolean isExpired(PricePlan plan) {
        return Instant.now().getEpochSecond() > plan.validUntilEpoch();
    }

    private static boolean isFill(String outcome) {
        return OUTCOME_FILLED.equals(outcome) || OUTCOME_PARTIALLY_FILLED.equals(outcome);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static boolean positive(Long value) {
        return value != null && value > 0L;
    }

    public record Report(String executionKey, String runId, String contractCode, String period, Long barTime,
                         String policyVersion, String outcome, BigDecimal fillPrice, Long filledContracts, String reason) {
    }

    public record Result(boolean accepted, Long id, String reason) {
        static Result accepted(Long id) { return new Result(true, id, null); }
        static Result rejected(String reason) { return new Result(false, null, reason); }
    }

    public record LifecycleEvent(String eventKey, String executionKey, String runId,
                                 HtxTrendOrderStateMachine.Event eventType, long requestedContracts,
                                 long cumulativeFilledContracts, BigDecimal fillPrice, String reason,
                                 LocalDateTime occurredAt) {
    }

    public record LifecycleResult(boolean accepted, Long id, HtxTrendOrderStateMachine.State state, String reason) {
        static LifecycleResult accepted(Long id, HtxTrendOrderStateMachine.State state) {
            return new LifecycleResult(true, id, state, null);
        }
        static LifecycleResult rejected(String reason) { return new LifecycleResult(false, null, null, reason); }
    }

    private record PricePlan(String side, BigDecimal maxAcceptablePrice, Long validUntilEpoch, BigDecimal contractSize,
                             long plannedContracts, BigDecimal invalidationPrice, BigDecimal initialStopPrice,
                             BigDecimal structureTargetPrice, BigDecimal estimatedRoundTripCostUsdt) {
        boolean complete() {
            return (SIDE_LONG.equals(side) || SIDE_SHORT.equals(side)) && positive(maxAcceptablePrice)
                    && validUntilEpoch != null && validUntilEpoch > 0L && positive(contractSize)
                    && plannedContracts > 0L && positive(invalidationPrice) && positive(initialStopPrice)
                    && positive(structureTargetPrice) && estimatedRoundTripCostUsdt != null
                    && estimatedRoundTripCostUsdt.compareTo(ZERO) >= 0;
        }
    }
}
