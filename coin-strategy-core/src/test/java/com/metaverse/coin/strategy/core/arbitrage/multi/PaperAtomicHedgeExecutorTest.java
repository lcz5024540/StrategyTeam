package com.metaverse.coin.strategy.core.arbitrage.multi;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class PaperAtomicHedgeExecutorTest {
    @Test void secondLegUsesFirstLegActualFill() {
        var executor = new PaperAtomicHedgeExecutor(new PaperPerpetualGateway());
        var position = executor.open(opportunity(), new BigDecimal("100"), new BigDecimal("0.5"), BigDecimal.ONE, Instant.now());
        assertEquals(0, new BigDecimal("0.5").compareTo(position.hedgedQuantity()));
        assertEquals(position.longOrder().filledQuantity(), position.shortOrder().requestedQuantity());
        assertEquals(PaperHedgePosition.Status.OPENED, position.status());
    }
    @Test void compensatesWhenSecondLegPartiallyFills() {
        var gateway = new PaperPerpetualGateway();
        var executor = new PaperAtomicHedgeExecutor(gateway);
        var position = executor.open(opportunity(), new BigDecimal("100"), BigDecimal.ONE, new BigDecimal("0.4"), Instant.now());
        assertEquals(PaperHedgePosition.Status.ROLLED_BACK, position.status());
        assertEquals(0, BigDecimal.ZERO.compareTo(position.hedgedQuantity()));
        assertTrue(gateway.orders().stream().anyMatch(order -> order.clientOrderId().endsWith("-ROLLBACK-L")));
        assertTrue(gateway.orders().stream().anyMatch(order -> order.clientOrderId().endsWith("-ROLLBACK-S")));
        assertTrue(gateway.orders().stream().filter(order -> order.clientOrderId().endsWith("-COMPENSATE-L"))
                .allMatch(PaperOrder::reduceOnly));
    }
    @Test void closeOrdersAreReduceOnly() {
        var gateway = new PaperPerpetualGateway();
        var executor = new PaperAtomicHedgeExecutor(gateway);
        var opened = executor.open(opportunity(), new BigDecimal("100"), BigDecimal.ONE, BigDecimal.ONE, Instant.now());
        executor.close(opened.positionId(), Instant.now());
        assertTrue(gateway.orders().stream().filter(order -> order.clientOrderId().contains("-CLOSE-"))
                .allMatch(PaperOrder::reduceOnly));
        assertTrue(gateway.orders().stream().filter(order -> order.clientOrderId().contains("-OPEN-"))
                .noneMatch(PaperOrder::reduceOnly));
    }
    @Test void timeoutOnSecondLegAndFailedRollbackRequiresManualIntervention() {
        var gateway = new PaperPerpetualGateway();
        gateway.scriptNext("-OPEN-S", PaperOrder.Status.TIMED_OUT, BigDecimal.ZERO);
        gateway.scriptNext("-COMPENSATE-L", PaperOrder.Status.REJECTED, BigDecimal.ZERO);
        var executor = new PaperAtomicHedgeExecutor(gateway);

        var position = executor.open(opportunity(), new BigDecimal("100"), BigDecimal.ONE,
                BigDecimal.ONE, Instant.now());

        assertEquals(PaperHedgePosition.Status.MANUAL_INTERVENTION, position.status());
        assertTrue(position.hedgedQuantity().signum() > 0);
    }
    @Test void singleCloseFailureReopensSuccessfulLeg() {
        var gateway = new PaperPerpetualGateway();
        var executor = new PaperAtomicHedgeExecutor(gateway);
        var position = executor.open(opportunity(), new BigDecimal("100"), BigDecimal.ONE,
                BigDecimal.ONE, Instant.now());
        gateway.scriptNext("-CLOSE-S", PaperOrder.Status.REJECTED, BigDecimal.ZERO);

        var recovered = executor.close(position.positionId(), Instant.now());

        assertEquals(PaperHedgePosition.Status.OPENED, recovered.status());
        assertNotNull(gateway.get(position.positionId() + "-CLOSE-RECOVER"));
    }
    @Test void failedCloseRecoveryRequiresManualIntervention() {
        var gateway = new PaperPerpetualGateway();
        var executor = new PaperAtomicHedgeExecutor(gateway);
        var position = executor.open(opportunity(), new BigDecimal("100"), BigDecimal.ONE,
                BigDecimal.ONE, Instant.now());
        gateway.scriptNext("-CLOSE-S", PaperOrder.Status.REJECTED, BigDecimal.ZERO);
        gateway.scriptNext("-CLOSE-RECOVER", PaperOrder.Status.REJECTED, BigDecimal.ZERO);

        var recovered = executor.close(position.positionId(), Instant.now());

        assertEquals(PaperHedgePosition.Status.MANUAL_INTERVENTION, recovered.status());
    }
    @Test void shortLegCanExecuteFirstAndLongLegUsesActualFill() {
        var gateway = new PaperPerpetualGateway();
        var executor = new PaperAtomicHedgeExecutor(gateway);
        CrossExchangeOpportunity base = opportunity();
        CrossExchangeOpportunity shortFirst = new CrossExchangeOpportunity(base.opportunityId(),
                base.symbol(), base.longQuote(), base.shortQuote(), base.hourlyFundingSpread(),
                base.estimatedNetYield(), base.executableNotional(),
                base.shortQuote().exchange(), base.status(), base.rejectionReason(),
                base.discoveredAt());

        var position = executor.open(shortFirst, new BigDecimal("100"),
                new BigDecimal("0.5"), BigDecimal.ONE, Instant.now());

        assertEquals(position.shortOrder().filledQuantity(), position.longOrder().requestedQuantity());
        assertTrue(position.shortOrder().clientOrderId().endsWith("-OPEN-S"));
        assertEquals(PaperHedgePosition.Status.OPENED, position.status());
    }
    @Test void recordsEveryOpeningStateTransitionForAudit() {
        var executor = new PaperAtomicHedgeExecutor(new PaperPerpetualGateway());
        var position = executor.open(opportunity(), new BigDecimal("100"), BigDecimal.ONE,
                BigDecimal.ONE, Instant.now());

        assertEquals(java.util.List.of(PaperHedgePosition.Status.CREATED,
                        PaperHedgePosition.Status.OPENING_LONG,
                        PaperHedgePosition.Status.OPENING_SHORT,
                        PaperHedgePosition.Status.OPENED),
                executor.drainEvents(position.positionId()).stream()
                        .map(PaperAtomicHedgeExecutor.ExecutionEvent::status).toList());
        assertTrue(executor.drainEvents(position.positionId()).isEmpty());
    }
    private CrossExchangeOpportunity opportunity(){Instant n=Instant.now();var l=new NormalizedFundingRateSnapshot(Exchange.BINANCE,"BTCUSDT",new BigDecimal("-0.001"),8,n.plusSeconds(3600),new BigDecimal("100"),new BigDecimal("100"),n);var s=new NormalizedFundingRateSnapshot(Exchange.OKX,"BTCUSDT",new BigDecimal("-0.0001"),8,n.plusSeconds(3600),new BigDecimal("100"),new BigDecimal("100"),n);return new CrossExchangeOpportunity("x","BTCUSDT",l,s,new BigDecimal("0.0001"),new BigDecimal("0.0001"),new BigDecimal("100"),CrossExchangeOpportunity.Status.QUALIFIED,null,n);}
}
