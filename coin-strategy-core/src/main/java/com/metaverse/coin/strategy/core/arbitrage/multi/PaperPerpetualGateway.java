package com.metaverse.coin.strategy.core.arbitrage.multi;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Deterministic paper gateway with idempotent client order IDs. */
public class PaperPerpetualGateway {
    public record FillInstruction(PaperOrder.Status status, BigDecimal fillRatio) {
        public FillInstruction {
            if (status == null || fillRatio == null) {
                throw new IllegalArgumentException("status and fillRatio are required");
            }
        }
    }

    private final Map<String, PaperOrder> orders = new ConcurrentHashMap<>();
    private final Map<String, FillInstruction> instructions = new ConcurrentHashMap<>();

    public void scriptNext(String clientOrderIdSuffix, PaperOrder.Status status, BigDecimal fillRatio) {
        instructions.put(clientOrderIdSuffix, new FillInstruction(status, fillRatio));
    }

    public PaperOrder fill(HedgeLeg leg, String clientOrderId, BigDecimal price, BigDecimal fillRatio, Instant now) {
        return fill(leg, clientOrderId, price, fillRatio, false, now);
    }

    public PaperOrder fill(HedgeLeg leg, String clientOrderId, BigDecimal price, BigDecimal fillRatio,
            boolean reduceOnly, Instant now) {
        return orders.computeIfAbsent(clientOrderId, ignored -> {
            Optional<Map.Entry<String, FillInstruction>> scripted = instructions.entrySet().stream()
                    .filter(entry -> clientOrderId.endsWith(entry.getKey()))
                    .findFirst();
            FillInstruction instruction = scripted.map(Map.Entry::getValue).orElse(null);
            scripted.ifPresent(entry -> instructions.remove(entry.getKey()));
            BigDecimal requestedRatio = instruction == null ? fillRatio : instruction.fillRatio();
            BigDecimal ratio = requestedRatio.max(BigDecimal.ZERO).min(BigDecimal.ONE);
            BigDecimal filled = leg.quantity().multiply(ratio);
            PaperOrder.Status status = instruction != null ? instruction.status()
                    : ratio.signum() == 0 ? PaperOrder.Status.REJECTED
                    : ratio.compareTo(BigDecimal.ONE) < 0 ? PaperOrder.Status.PARTIALLY_FILLED : PaperOrder.Status.FILLED;
            return new PaperOrder(clientOrderId, leg, reduceOnly, leg.quantity(), filled, price, status, now, null);
        });
    }

    public PaperOrder cancel(String clientOrderId, Instant now) {
        return orders.computeIfPresent(clientOrderId, (id, order) -> new PaperOrder(id, order.leg(), order.reduceOnly(), order.requestedQuantity(),
                order.filledQuantity(), order.averagePrice(), PaperOrder.Status.CANCELLED, now, "cancelled"));
    }

    public PaperOrder get(String clientOrderId) { return orders.get(clientOrderId); }
    public java.util.Collection<PaperOrder> orders() { return java.util.List.copyOf(orders.values()); }
}
