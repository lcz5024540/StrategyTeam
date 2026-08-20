package com.metaverse.coin.strategy.core.arbitrage.multi;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;

/** Safe application facade: V1 exposes paper mode only and has no LIVE switch. */
public class CrossExchangePaperTradingService {
    private final PaperAtomicHedgeExecutor executor;
    private final CrossExchangeRiskService risk;
    private final CrossExchangeArbitragePersistenceService persistence;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean circuitOpen = new AtomicBoolean(false);

    public CrossExchangePaperTradingService(PaperAtomicHedgeExecutor executor, CrossExchangeRiskService risk) {
        this(executor, risk, null);
    }
    public CrossExchangePaperTradingService(PaperAtomicHedgeExecutor executor, CrossExchangeRiskService risk,
                                             CrossExchangeArbitragePersistenceService persistence) {
        this.executor = executor; this.risk = risk; this.persistence = persistence;
    }

    public PaperHedgePosition open(CrossExchangeOpportunity opportunity, BigDecimal notional, Instant now) {
        var assessment = risk.approveOpen(opportunity, notional, executor.positions().values(), paused.get(), circuitOpen.get());
        if (!assessment.passed()) { if(persistence!=null)persistence.saveRisk(null,opportunity.opportunityId(),"PRE_OPEN",false,assessment.reason()); throw new IllegalStateException(assessment.reason()); }
        PaperHedgePosition position = executor.open(opportunity, notional, BigDecimal.ONE, BigDecimal.ONE, now);
        if (persistence != null) { persistence.saveOpportunity(opportunity); persistence.saveRisk(position.positionId(),opportunity.opportunityId(),"PRE_OPEN",true,null); saveExecutionEvents(position.positionId()); persistence.savePosition(position); persistence.saveOrders(position.positionId(),executor.orders()); }
        return position;
    }
    public PaperHedgePosition close(String positionId, Instant now) { PaperHedgePosition p=executor.close(positionId, now); if(persistence!=null){saveExecutionEvents(positionId);persistence.savePosition(p);persistence.saveOrders(positionId,executor.orders());persistence.saveRisk(positionId,p.opportunity().opportunityId(),"POST_CLOSE",true,null);} return p; }
    public Collection<PaperHedgePosition> positions() { return executor.positions().values(); }
    public PaperHedgePosition restore(PaperHedgePosition position) { return executor.restore(position); }
    public boolean pause() { paused.set(true); return true; }
    public boolean resume() { paused.set(false); return true; }
    public boolean isPaused() { return paused.get(); }
    public boolean isCircuitOpen() { return circuitOpen.get(); }
    public void openCircuit() { circuitOpen.set(true); }
    public void resetCircuit() { circuitOpen.set(false); }

    private void saveExecutionEvents(String positionId) {
        for (PaperAtomicHedgeExecutor.ExecutionEvent event : executor.drainEvents(positionId)) {
            boolean passed = event.status() != PaperHedgePosition.Status.MANUAL_INTERVENTION;
            String reason = event.detail() == null ? event.status().name()
                    : event.status().name() + ": " + event.detail();
            persistence.saveRisk(event.positionId(), event.opportunityId(),
                    "STATE_TRANSITION", passed, reason);
        }
    }
}
