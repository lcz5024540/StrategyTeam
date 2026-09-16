package com.metaverse.coin.strategy.core.arbitrage.multi;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;

/** Safe application facade: V1 exposes paper mode only and has no LIVE switch. */
public class CrossExchangePaperTradingService {
    private static final Duration MARKET_DATA_CIRCUIT_TTL = Duration.ofMinutes(30);

    private final PaperAtomicHedgeExecutor executor;
    private final CrossExchangeRiskService risk;
    private final CrossExchangeArbitragePersistenceService persistence;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean circuitOpen = new AtomicBoolean(false);
    private final Map<ScopedCircuitKey, Instant> scopedCircuitExpiresAt = new ConcurrentHashMap<>();

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
        if (persistence != null) { persistence.saveOpportunity(opportunity); persistence.saveRisk(position.positionId(),opportunity.opportunityId(),"PRE_OPEN",true,null); saveExecutionEvents(position.positionId()); persistence.savePosition(position); persistence.saveOrders(position.positionId(),executor.orders()); persistence.saveExecutionEvidence(position.positionId(),executor.drainExecutionEvidence(position.positionId())); }
        return position;
    }
    public PaperHedgePosition close(String positionId, Instant now) {
        return persistClose(positionId, executor.close(positionId, now));
    }

    public PaperHedgePosition close(String positionId, BigDecimal longClosePrice,
            BigDecimal shortClosePrice, Instant now) {
        return persistClose(positionId,
                executor.close(positionId, longClosePrice, shortClosePrice, now));
    }

    private PaperHedgePosition persistClose(String positionId, PaperHedgePosition position) {
        if (persistence != null) {
            saveExecutionEvents(positionId);
            persistence.savePosition(position);
            persistence.saveOrders(positionId, executor.orders());
            persistence.saveExecutionEvidence(positionId, executor.drainExecutionEvidence(positionId));
            persistence.saveRisk(positionId, position.opportunity().opportunityId(), "POST_CLOSE",
                    true, null);
        }
        return position;
    }
    public Collection<PaperHedgePosition> positions() { return executor.positions().values(); }
    public PaperHedgePosition restore(PaperHedgePosition position) { return executor.restore(position); }
    public boolean pause() { paused.set(true); return true; }
    public boolean resume() { paused.set(false); return true; }
    public boolean isPaused() { return paused.get(); }
    public boolean isCircuitOpen() { return circuitOpen.get(); }
    public boolean isCircuitOpen(CrossExchangeOpportunity opportunity, Instant now) {
        return isCircuitOpen() || isScopedCircuitOpen(opportunity, now);
    }
    public boolean isScopedCircuitOpen(CrossExchangeOpportunity opportunity, Instant now) {
        if (opportunity == null) {
            return false;
        }
        String symbol = opportunity.symbol();
        return isScopedCircuitOpen(opportunity.longQuote().exchange(), symbol, now)
                || isScopedCircuitOpen(opportunity.shortQuote().exchange(), symbol, now);
    }
    public boolean isScopedCircuitOpen(Exchange exchange, String symbol, Instant now) {
        clearExpiredScopedCircuits(now);
        Instant expiresAt = scopedCircuitExpiresAt.get(new ScopedCircuitKey(exchange, normalizeSymbol(symbol)));
        return expiresAt != null && expiresAt.isAfter(now);
    }
    public void openCircuit() { circuitOpen.set(true); }
    public void openScopedCircuit(Exchange exchange, String symbol, Instant now) {
        scopedCircuitExpiresAt.put(new ScopedCircuitKey(exchange, normalizeSymbol(symbol)),
                now.plus(MARKET_DATA_CIRCUIT_TTL));
    }
    public void resetCircuit() {
        circuitOpen.set(false);
        scopedCircuitExpiresAt.clear();
    }

    private void saveExecutionEvents(String positionId) {
        for (PaperAtomicHedgeExecutor.ExecutionEvent event : executor.drainEvents(positionId)) {
            boolean passed = event.status() != PaperHedgePosition.Status.MANUAL_INTERVENTION;
            String reason = event.detail() == null ? event.status().name()
                    : event.status().name() + ": " + event.detail();
            persistence.saveRisk(event.positionId(), event.opportunityId(),
                    "STATE_TRANSITION", passed, reason);
        }
    }

    private void clearExpiredScopedCircuits(Instant now) {
        scopedCircuitExpiresAt.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private record ScopedCircuitKey(Exchange exchange, String symbol) {
    }
}
