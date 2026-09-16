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
    @Test void closeOrdersUseLatestMarketPrices() {
        var gateway = new PaperPerpetualGateway();
        var executor = new PaperAtomicHedgeExecutor(gateway);
        var opened = executor.open(opportunity(), new BigDecimal("100"), BigDecimal.ONE,
                BigDecimal.ONE, Instant.now());

        executor.close(opened.positionId(), new BigDecimal("95"),
                new BigDecimal("106"), Instant.now());

        assertEquals(0, new BigDecimal("95").compareTo(
                closeOrder(gateway, opened.positionId(), "L").averagePrice()));
        assertEquals(0, new BigDecimal("106").compareTo(
                closeOrder(gateway, opened.positionId(), "S").averagePrice()));
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
        assertNotNull(closeOrder(gateway, position.positionId(), "RECOVER"));
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
    @Test void failedCloseCanRetryWithANewIdempotencyKey() {
        Instant openedAt = Instant.parse("2026-08-21T00:00:00Z");
        var gateway = new PaperPerpetualGateway();
        var executor = new PaperAtomicHedgeExecutor(gateway);
        var position = executor.open(opportunity(), new BigDecimal("100"), BigDecimal.ONE,
                BigDecimal.ONE, openedAt);
        gateway.scriptNext("-CLOSE-L", PaperOrder.Status.REJECTED, BigDecimal.ZERO);
        gateway.scriptNext("-CLOSE-S", PaperOrder.Status.REJECTED, BigDecimal.ZERO);

        PaperHedgePosition firstAttempt = executor.close(position.positionId(), openedAt.plusSeconds(1));
        PaperHedgePosition secondAttempt = executor.close(position.positionId(), openedAt.plusSeconds(2));

        assertEquals(PaperHedgePosition.Status.OPENED, firstAttempt.status());
        assertEquals(PaperHedgePosition.Status.CLOSED, secondAttempt.status());
        assertEquals(4, gateway.orders().stream()
                .filter(order -> order.clientOrderId().contains("-CLOSE-"))
                .count());
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
    @Test void shortFirstStillSizesBothLegsFromLongVwap() {
        Instant time = Instant.now();
        NormalizedFundingRateSnapshot longQuote = new NormalizedFundingRateSnapshot(Exchange.BINANCE,
                "BTCUSDT", new BigDecimal("-0.001"), 8, time.plusSeconds(3600),
                new BigDecimal("100"), new BigDecimal("100"), time);
        NormalizedFundingRateSnapshot shortQuote = new NormalizedFundingRateSnapshot(Exchange.OKX,
                "BTCUSDT", new BigDecimal("-0.0001"), 8, time.plusSeconds(3600),
                new BigDecimal("110"), new BigDecimal("110"), time);
        CrossExchangeOpportunity opportunity = new CrossExchangeOpportunity("vwap", "BTCUSDT",
                longQuote, shortQuote, new BigDecimal("0.0001"), new BigDecimal("0.0001"),
                new BigDecimal("100"), new BigDecimal("110"), new BigDecimal("100"),
                Exchange.OKX, CrossExchangeOpportunity.Status.QUALIFIED, null, time);

        PaperHedgePosition position = new PaperAtomicHedgeExecutor(new PaperPerpetualGateway()).open(
                opportunity, new BigDecimal("100"), BigDecimal.ONE, BigDecimal.ONE, time);

        assertEquals(0, BigDecimal.ONE.compareTo(position.longOrder().requestedQuantity()));
        assertEquals(0, BigDecimal.ONE.compareTo(position.shortOrder().requestedQuantity()));
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
    private PaperOrder closeOrder(PaperPerpetualGateway gateway, String positionId, String leg) {
        return gateway.orders().stream()
                .filter(order -> order.clientOrderId().startsWith(positionId + "-ATTEMPT-"))
                .filter(order -> order.clientOrderId().contains("-CLOSE-"))
                .filter(order -> order.clientOrderId().endsWith("-" + leg))
                .findFirst().orElse(null);
    }
    private CrossExchangeOpportunity opportunity(){Instant n=Instant.now();var l=new NormalizedFundingRateSnapshot(Exchange.BINANCE,"BTCUSDT",new BigDecimal("-0.001"),8,n.plusSeconds(3600),new BigDecimal("100"),new BigDecimal("100"),n);var s=new NormalizedFundingRateSnapshot(Exchange.OKX,"BTCUSDT",new BigDecimal("-0.0001"),8,n.plusSeconds(3600),new BigDecimal("100"),new BigDecimal("100"),n);return new CrossExchangeOpportunity("x","BTCUSDT",l,s,new BigDecimal("0.0001"),new BigDecimal("0.0001"),new BigDecimal("100"),CrossExchangeOpportunity.Status.QUALIFIED,null,n);}
}
