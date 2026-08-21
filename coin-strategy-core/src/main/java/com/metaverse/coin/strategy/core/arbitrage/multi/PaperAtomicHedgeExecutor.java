package com.metaverse.coin.strategy.core.arbitrage.multi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Paper-only two-leg executor. The second leg always follows the first leg's actual fill. */
public class PaperAtomicHedgeExecutor {
    public record ExecutionEvent(String positionId, String opportunityId,
            PaperHedgePosition.Status status, Instant occurredAt, String detail) {
    }

    private final PaperPerpetualGateway gateway;
    private final Map<String, PaperHedgePosition> positions = new ConcurrentHashMap<>();
    private final Map<String, List<ExecutionEvent>> events = new ConcurrentHashMap<>();

    public PaperAtomicHedgeExecutor(PaperPerpetualGateway gateway) { this.gateway = gateway; }

    public PaperHedgePosition open(CrossExchangeOpportunity opportunity, BigDecimal notional,
            BigDecimal firstLegFillRatio, BigDecimal secondLegFillRatio, Instant now) {
        if (!opportunity.executable()) throw new IllegalArgumentException("opportunity is not executable");
        String id = UUID.randomUUID().toString();
        record(id, opportunity, PaperHedgePosition.Status.CREATED, now, null);
        boolean longFirst = opportunity.firstLegExchange() == opportunity.longQuote().exchange();
        BigDecimal firstPrice = longFirst ? opportunity.longQuote().markPrice()
                : opportunity.shortQuote().markPrice();
        BigDecimal plannedQty = notional.divide(firstPrice, 12, RoundingMode.DOWN);
        HedgeLeg longLeg = new HedgeLeg(opportunity.longQuote().exchange(), opportunity.symbol(), HedgeSide.LONG, plannedQty);
        HedgeLeg shortLeg = new HedgeLeg(opportunity.shortQuote().exchange(), opportunity.symbol(), HedgeSide.SHORT, plannedQty);
        HedgeLeg firstLeg = longFirst ? longLeg : shortLeg;
        record(id, opportunity, longFirst ? PaperHedgePosition.Status.OPENING_LONG
                : PaperHedgePosition.Status.OPENING_SHORT, now, null);
        String firstOrderId = id + (longFirst ? "-OPEN-L" : "-OPEN-S");
        PaperOrder firstOrder = gateway.fill(firstLeg, firstOrderId, firstPrice, firstLegFillRatio, now);
        if (firstOrder.filledQuantity().signum() == 0) {
            return finish(new PaperHedgePosition(id, opportunity, longFirst ? firstOrder : null,
                    longFirst ? null : firstOrder, BigDecimal.ZERO,
                    PaperHedgePosition.Status.ROLLED_BACK, null, now, "first leg rejected"));
        }

        HedgeLeg secondLeg = longFirst
                ? new HedgeLeg(shortLeg.exchange(), shortLeg.symbol(), shortLeg.side(), firstOrder.filledQuantity())
                : new HedgeLeg(longLeg.exchange(), longLeg.symbol(), longLeg.side(), firstOrder.filledQuantity());
        String secondOrderId = id + (longFirst ? "-OPEN-S" : "-OPEN-L");
        record(id, opportunity, longFirst ? PaperHedgePosition.Status.OPENING_SHORT
                : PaperHedgePosition.Status.OPENING_LONG, now, null);
        BigDecimal secondPrice = longFirst ? opportunity.shortQuote().markPrice()
                : opportunity.longQuote().markPrice();
        PaperOrder secondOrder = gateway.fill(secondLeg, secondOrderId, secondPrice,
                secondLegFillRatio, now);
        PaperOrder longOrder = longFirst ? firstOrder : secondOrder;
        PaperOrder shortOrder = longFirst ? secondOrder : firstOrder;
        if (secondOrder.filledQuantity().compareTo(firstOrder.filledQuantity()) < 0) {
            record(id, opportunity, PaperHedgePosition.Status.PARTIALLY_FILLED, now,
                    "second leg did not match first-leg fill");
            BigDecimal naked = firstOrder.filledQuantity().subtract(secondOrder.filledQuantity());
            HedgeSide compensationSide = firstLeg.side() == HedgeSide.LONG ? HedgeSide.SHORT : HedgeSide.LONG;
            HedgeLeg compensation = new HedgeLeg(firstLeg.exchange(), firstLeg.symbol(), compensationSide, naked);
            record(id, opportunity, PaperHedgePosition.Status.COMPENSATING, now, null);
            PaperOrder rollback = gateway.fill(compensation, id + (longFirst ? "-COMPENSATE-L" : "-COMPENSATE-S"),
                    firstPrice, BigDecimal.ONE, true, now);
            if (rollback.status() != PaperOrder.Status.FILLED) {
                return finish(new PaperHedgePosition(id, opportunity, longOrder, shortOrder, naked,
                        PaperHedgePosition.Status.MANUAL_INTERVENTION, null, now,
                        "second leg partial and excess compensation failed"));
            }
            BigDecimal matched = secondOrder.filledQuantity();
            if (matched.signum() > 0) {
                PaperOrder unwindLong = gateway.fill(
                        new HedgeLeg(longLeg.exchange(), longLeg.symbol(), HedgeSide.SHORT, matched),
                        id + "-ROLLBACK-L", opportunity.longQuote().markPrice(), BigDecimal.ONE, true, now);
                PaperOrder unwindShort = gateway.fill(
                        new HedgeLeg(shortLeg.exchange(), shortLeg.symbol(), HedgeSide.LONG, matched),
                        id + "-ROLLBACK-S", opportunity.shortQuote().markPrice(), BigDecimal.ONE, true, now);
                if (unwindLong.status() != PaperOrder.Status.FILLED
                        || unwindShort.status() != PaperOrder.Status.FILLED) {
                    return finish(new PaperHedgePosition(id, opportunity, longOrder, shortOrder, matched,
                            PaperHedgePosition.Status.MANUAL_INTERVENTION, null, now,
                            "partial hedge unwind failed"));
                }
            }
            return finish(new PaperHedgePosition(id, opportunity, longOrder, shortOrder, BigDecimal.ZERO,
                    PaperHedgePosition.Status.ROLLED_BACK, null, now,
                    "second leg partial; all filled exposure rolled back"));
        }
        return finish(new PaperHedgePosition(id, opportunity, longOrder, shortOrder, secondOrder.filledQuantity(),
                PaperHedgePosition.Status.OPENED, now, now, null));
    }

    public PaperHedgePosition close(String positionId, Instant now) {
        PaperHedgePosition current = positions.get(positionId);
        if (current == null) throw new IllegalStateException("position is not open");
        return close(positionId, current.opportunity().longQuote().markPrice(),
                current.opportunity().shortQuote().markPrice(), now);
    }

    public PaperHedgePosition close(String positionId, BigDecimal longClosePrice,
            BigDecimal shortClosePrice, Instant now) {
        PaperHedgePosition current = positions.get(positionId);
        if (current == null || current.status() != PaperHedgePosition.Status.OPENED) throw new IllegalStateException("position is not open");
        if (longClosePrice == null || shortClosePrice == null
                || longClosePrice.signum() <= 0 || shortClosePrice.signum() <= 0) {
            throw new IllegalArgumentException("positive close prices are required");
        }
        record(positionId, current.opportunity(), PaperHedgePosition.Status.CLOSING, now, null);
        long attemptId = Math.max(now.toEpochMilli(), current.updatedAt().toEpochMilli() + 1L);
        BigDecimal quantity = current.hedgedQuantity();
        HedgeLeg closeLong = new HedgeLeg(current.opportunity().longQuote().exchange(),
                current.opportunity().symbol(), HedgeSide.SHORT, quantity);
        HedgeLeg closeShort = new HedgeLeg(current.opportunity().shortQuote().exchange(),
                current.opportunity().symbol(), HedgeSide.LONG, quantity);
        PaperOrder longCloseOrder = gateway.fill(closeLong,
                closeOrderId(positionId, attemptId, "L"), longClosePrice, BigDecimal.ONE, true, now);
        PaperOrder shortCloseOrder = gateway.fill(closeShort,
                closeOrderId(positionId, attemptId, "S"), shortClosePrice, BigDecimal.ONE, true, now);
        boolean longClosed = longCloseOrder.status() == PaperOrder.Status.FILLED;
        boolean shortClosed = shortCloseOrder.status() == PaperOrder.Status.FILLED;
        if (!longClosed || !shortClosed) {
            return recoverFailedClose(current, longClosed, shortClosed, longClosePrice,
                    shortClosePrice, attemptId, now);
        }
        return finish(new PaperHedgePosition(positionId, current.opportunity(), current.longOrder(), current.shortOrder(), quantity,
                PaperHedgePosition.Status.CLOSED, current.openedAt(), now, null));
    }

    private PaperHedgePosition recoverFailedClose(PaperHedgePosition current, boolean longClosed,
            boolean shortClosed, BigDecimal longClosePrice, BigDecimal shortClosePrice,
            long attemptId, Instant now) {
        if (!longClosed && !shortClosed) {
            return finish(new PaperHedgePosition(current.positionId(), current.opportunity(), current.longOrder(),
                    current.shortOrder(), current.hedgedQuantity(), PaperHedgePosition.Status.OPENED,
                    current.openedAt(), now, "both close legs failed; position remains hedged"));
        }
        HedgeLeg recoveryLeg = longClosed
                ? new HedgeLeg(current.opportunity().longQuote().exchange(), current.opportunity().symbol(),
                        HedgeSide.LONG, current.hedgedQuantity())
                : new HedgeLeg(current.opportunity().shortQuote().exchange(), current.opportunity().symbol(),
                        HedgeSide.SHORT, current.hedgedQuantity());
        BigDecimal recoveryPrice = longClosed ? longClosePrice : shortClosePrice;
        PaperOrder recovery = gateway.fill(recoveryLeg,
                closeOrderId(current.positionId(), attemptId, "RECOVER"),
                recoveryPrice, BigDecimal.ONE, false, now);
        PaperHedgePosition.Status status = recovery.status() == PaperOrder.Status.FILLED
                ? PaperHedgePosition.Status.OPENED : PaperHedgePosition.Status.MANUAL_INTERVENTION;
        return finish(new PaperHedgePosition(current.positionId(), current.opportunity(), current.longOrder(),
                current.shortOrder(), current.hedgedQuantity(), status, current.openedAt(), now,
                status == PaperHedgePosition.Status.OPENED
                        ? "single close leg failed; successful leg reopened"
                        : "single close leg failed and recovery failed"));
    }

    public Map<String, PaperHedgePosition> positions() { return Map.copyOf(positions); }

    private String closeOrderId(String positionId, long attemptId, String leg) {
        return positionId + "-ATTEMPT-" + attemptId + "-CLOSE-" + leg;
    }
    public PaperHedgePosition restore(PaperHedgePosition position) {
        if (position == null || position.status() != PaperHedgePosition.Status.OPENED) {
            throw new IllegalArgumentException("only an opened paper position can be restored");
        }
        return positions.computeIfAbsent(position.positionId(), ignored -> position);
    }
    public java.util.Collection<PaperOrder> orders() { return gateway.orders(); }
    public List<ExecutionEvent> drainEvents(String positionId) {
        List<ExecutionEvent> recorded = events.remove(positionId);
        return recorded == null ? List.of() : List.copyOf(recorded);
    }

    private PaperHedgePosition finish(PaperHedgePosition position) {
        positions.put(position.positionId(), position);
        record(position.positionId(), position.opportunity(), position.status(),
                position.updatedAt(), position.message());
        return position;
    }

    private void record(String positionId, CrossExchangeOpportunity opportunity,
            PaperHedgePosition.Status status, Instant occurredAt, String detail) {
        events.computeIfAbsent(positionId, ignored -> java.util.Collections.synchronizedList(new ArrayList<>()))
                .add(new ExecutionEvent(positionId, opportunity.opportunityId(), status, occurredAt, detail));
    }
}
