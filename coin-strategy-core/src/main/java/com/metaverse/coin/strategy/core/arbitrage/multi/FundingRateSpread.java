package com.metaverse.coin.strategy.core.arbitrage.multi;

import java.math.BigDecimal;

/** Candidate pair chosen by the highest common-window funding spread. */
public record FundingRateSpread(FundingRateQuote longLeg, FundingRateQuote shortLeg,
                                BigDecimal fundingSpread, BigDecimal priceSpread,
                                BigDecimal estimatedNetYield) {
    public FundingRateSpread {
        if (longLeg.exchange() == shortLeg.exchange()) throw new IllegalArgumentException("legs must use different exchanges");
    }

    /** Positive funding is paid by longs; negative funding is paid by shorts. */
    public boolean mixedFundingSigns() {
        return longLeg.fundingRate().signum() != shortLeg.fundingRate().signum();
    }

    public String directionRationale() {
        return mixedFundingSigns()
                ? "mixed-sign funding: long receives on negative leg and short receives on positive leg; require extra risk checks"
                : "long the lower common-window funding leg and short the higher common-window funding leg";
    }
}
