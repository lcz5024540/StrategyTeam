package com.metaverse.coin.strategy.core.arbitrage.spotperp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** In-memory paper position. PnL target is accumulated funding, not basis MTM. */
public final class SpotPerpPosition {
    public enum Status { OPENED, CLOSED, ROLLED_BACK }

    public record Fill(
            String clientOrderId,
            String leg,
            String side,
            String orderType,
            BigDecimal quantity,
            BigDecimal vwap,
            BigDecimal notional,
            BigDecimal impactBps,
            Instant at
    ) {
    }

    public record FundingSettlement(
            Instant fundingTime,
            BigDecimal fundingRate,
            BigDecimal markPrice,
            BigDecimal amount
    ) {
    }

    private final String positionId;
    private final SpotPerpInstrument instrument;
    private final int leverage;
    private final BigDecimal quantity;
    private final BigDecimal spotEntry;
    private final BigDecimal perpEntry;
    private final BigDecimal entryBasis;
    private final BigDecimal entryFundingRate;
    private final Instant openedAt;
    private Instant nextFundingTime;
    private Status status;
    private Instant closedAt;
    private String closeReason;
    private BigDecimal spotExit;
    private BigDecimal perpExit;
    private BigDecimal fundingFeesEarned;
    private BigDecimal estimatedOpenFee;
    private BigDecimal estimatedCloseFee;
    private final List<Fill> fills;
    private final List<FundingSettlement> settlements;

    private SpotPerpPosition(String positionId, SpotPerpInstrument instrument, int leverage,
            BigDecimal quantity, BigDecimal spotEntry, BigDecimal perpEntry, BigDecimal entryBasis,
            BigDecimal entryFundingRate, Instant openedAt, Instant nextFundingTime,
            BigDecimal estimatedOpenFee, List<Fill> fills) {
        this.positionId = Objects.requireNonNull(positionId);
        this.instrument = Objects.requireNonNull(instrument);
        this.leverage = leverage;
        this.quantity = Objects.requireNonNull(quantity);
        this.spotEntry = Objects.requireNonNull(spotEntry);
        this.perpEntry = Objects.requireNonNull(perpEntry);
        this.entryBasis = Objects.requireNonNull(entryBasis);
        this.entryFundingRate = Objects.requireNonNull(entryFundingRate);
        this.openedAt = Objects.requireNonNull(openedAt);
        this.nextFundingTime = Objects.requireNonNull(nextFundingTime);
        this.status = Status.OPENED;
        this.fundingFeesEarned = BigDecimal.ZERO;
        this.estimatedOpenFee = estimatedOpenFee == null ? BigDecimal.ZERO : estimatedOpenFee;
        this.estimatedCloseFee = BigDecimal.ZERO;
        this.fills = new ArrayList<>(fills);
        this.settlements = new ArrayList<>();
    }

    static SpotPerpPosition opened(SpotPerpInstrument instrument, int leverage, BigDecimal quantity,
            BigDecimal spotEntry, BigDecimal perpEntry, BigDecimal entryBasis,
            BigDecimal entryFundingRate, Instant openedAt, Instant nextFundingTime,
            BigDecimal estimatedOpenFee, List<Fill> fills) {
        return new SpotPerpPosition(
                "SP-" + instrument.venue() + "-" + instrument.canonicalSymbol() + "-"
                        + UUID.randomUUID().toString().replace("-", ""),
                instrument, leverage, quantity, spotEntry, perpEntry, entryBasis, entryFundingRate,
                openedAt, nextFundingTime, estimatedOpenFee, fills);
    }

    public String positionId() {
        return positionId;
    }

    public SpotPerpInstrument instrument() {
        return instrument;
    }

    public int leverage() {
        return leverage;
    }

    public BigDecimal quantity() {
        return quantity;
    }

    public BigDecimal spotEntry() {
        return spotEntry;
    }

    public BigDecimal perpEntry() {
        return perpEntry;
    }

    public BigDecimal entryBasis() {
        return entryBasis;
    }

    public BigDecimal entryFundingRate() {
        return entryFundingRate;
    }

    public Instant openedAt() {
        return openedAt;
    }

    public Instant nextFundingTime() {
        return nextFundingTime;
    }

    public Status status() {
        return status;
    }

    public Instant closedAt() {
        return closedAt;
    }

    public String closeReason() {
        return closeReason;
    }

    public BigDecimal spotExit() {
        return spotExit;
    }

    public BigDecimal perpExit() {
        return perpExit;
    }

    public BigDecimal fundingFeesEarned() {
        return fundingFeesEarned;
    }

    public BigDecimal estimatedFees() {
        return estimatedOpenFee.add(estimatedCloseFee);
    }

    public BigDecimal fundingPnlNetOfFees() {
        return fundingFeesEarned.subtract(estimatedFees());
    }

    public BigDecimal spotNotional() {
        return quantity.multiply(spotEntry);
    }

    public BigDecimal requiredMargin() {
        return quantity.multiply(perpEntry)
                .divide(BigDecimal.valueOf(Math.max(1, leverage)), 8, RoundingMode.HALF_UP);
    }

    /**
     * Remaining distance to isolated-short liquidation: (liqPrice - mark) / mark.
     * Short liqPrice ≈ entry * (1 + 1/leverage - MMR). Dummy marks are rejected by callers.
     */
    public BigDecimal liquidationDistance(BigDecimal mark, BigDecimal maintenanceMarginRate) {
        Objects.requireNonNull(mark, "mark");
        BigDecimal mmr = maintenanceMarginRate == null ? BigDecimal.ZERO : maintenanceMarginRate;
        BigDecimal buffer = BigDecimal.ONE
                .divide(BigDecimal.valueOf(Math.max(1, leverage)), 18, RoundingMode.HALF_UP)
                .subtract(mmr);
        BigDecimal liqPrice = perpEntry.multiply(BigDecimal.ONE.add(buffer));
        return liqPrice.subtract(mark).divide(mark, 18, RoundingMode.HALF_UP);
    }

    public List<Fill> fills() {
        return List.copyOf(fills);
    }

    public List<FundingSettlement> settlements() {
        return List.copyOf(settlements);
    }

    BigDecimal accrueFunding(Instant fundingTime, BigDecimal rate, BigDecimal mark) {
        BigDecimal amount = quantity.multiply(mark).multiply(rate).setScale(8, RoundingMode.HALF_UP);
        settlements.add(new FundingSettlement(fundingTime, rate, mark, amount));
        fundingFeesEarned = fundingFeesEarned.add(amount);
        return amount;
    }

    void advanceFundingClock(Instant next) {
        this.nextFundingTime = next;
    }

    void close(Instant at, String reason, BigDecimal spotExitPrice, BigDecimal perpExitPrice,
            BigDecimal estimatedCloseFeeAmount, List<Fill> closeFills) {
        this.status = Status.CLOSED;
        this.closedAt = at;
        this.closeReason = reason;
        this.spotExit = spotExitPrice;
        this.perpExit = perpExitPrice;
        this.estimatedCloseFee = estimatedCloseFeeAmount == null ? BigDecimal.ZERO : estimatedCloseFeeAmount;
        this.fills.addAll(closeFills);
    }

    void markRolledBack(Instant at, String reason, List<Fill> compensationFills) {
        this.status = Status.ROLLED_BACK;
        this.closedAt = at;
        this.closeReason = reason;
        this.fills.addAll(compensationFills);
    }
}
