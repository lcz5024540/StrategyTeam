package com.metaverse.coin.strategy.common.service.accounting;

import java.math.BigDecimal;

/**
 * Tier B (第十八批): 单时刻的容量快照, 供 {@link PositionAccountingPort} 实现 / 消费方互传.
 *
 * <p>不可变 POJO. 字段语义与 {@link PositionAccountingPort} 一致.</p>
 *
 * <p>跟单 {@code SmartWalletCapacitySummaryView} 字段更丰富 (含 maxHoursToMarketEnd /
 * duplicate flags 等跟单专属字段); 本快照只保留<b>跨策略通用</b>的子集.</p>
 */
public final class CapacitySnapshot implements PositionAccountingPort {

    private final BigDecimal availableCapitalUsdc;
    private final BigDecimal lockedCapitalUsdc;
    private final BigDecimal inFlightCapitalUsdc;
    private final int openPositionCount;
    private final Integer openPositionLimit;

    private CapacitySnapshot(BigDecimal available, BigDecimal locked, BigDecimal inFlight,
                             int openPositionCount, Integer openPositionLimit) {
        this.availableCapitalUsdc = available == null ? BigDecimal.ZERO : available;
        this.lockedCapitalUsdc = locked == null ? BigDecimal.ZERO : locked;
        this.inFlightCapitalUsdc = inFlight == null ? BigDecimal.ZERO : inFlight;
        this.openPositionCount = Math.max(0, openPositionCount);
        this.openPositionLimit = openPositionLimit;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override public BigDecimal availableCapitalUsdc() { return availableCapitalUsdc; }
    @Override public BigDecimal lockedCapitalUsdc()    { return lockedCapitalUsdc; }
    @Override public BigDecimal inFlightCapitalUsdc()  { return inFlightCapitalUsdc; }
    @Override public int openPositionCount()           { return openPositionCount; }
    @Override public Integer openPositionLimit()       { return openPositionLimit; }

    public static final class Builder {
        private BigDecimal available;
        private BigDecimal locked;
        private BigDecimal inFlight;
        private int openPositionCount;
        private Integer openPositionLimit;

        public Builder availableCapitalUsdc(BigDecimal v)   { this.available = v; return this; }
        public Builder lockedCapitalUsdc(BigDecimal v)      { this.locked = v; return this; }
        public Builder inFlightCapitalUsdc(BigDecimal v)    { this.inFlight = v; return this; }
        public Builder openPositionCount(int v)             { this.openPositionCount = v; return this; }
        public Builder openPositionLimit(Integer v)         { this.openPositionLimit = v; return this; }

        public CapacitySnapshot build() {
            return new CapacitySnapshot(available, locked, inFlight, openPositionCount, openPositionLimit);
        }
    }
}
