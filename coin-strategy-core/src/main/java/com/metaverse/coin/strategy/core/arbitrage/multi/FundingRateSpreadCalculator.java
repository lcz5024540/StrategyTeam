package com.metaverse.coin.strategy.core.arbitrage.multi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.Map;

/** Pure decision helper shared by polling and websocket implementations. */
public final class FundingRateSpreadCalculator {
    private FundingRateSpreadCalculator() { }

    public static Optional<FundingRateSpread> best(Collection<FundingRateQuote> quotes,
                                                   BigDecimal roundTripFeeRate,
                                                   BigDecimal slippageRate,
                                                   BigDecimal minNetYieldRate) {
        if (quotes == null || quotes.size() < 2) return Optional.empty();
        return quotes.stream().flatMap(a -> quotes.stream().filter(b -> a != b)
                .map(b -> candidate(a, b, roundTripFeeRate, slippageRate)))
                .filter(c -> c.estimatedNetYield().compareTo(minNetYieldRate) >= 0)
                .max(Comparator.comparing(FundingRateSpread::estimatedNetYield));
    }

    public static Optional<FundingRateSpread> best(Collection<FundingRateQuote> quotes,
                                                   Map<Exchange, ExchangeCost> costs,
                                                   BigDecimal minNetYieldRate,
                                                   boolean allowMixedFundingSigns) {
        if (quotes == null || quotes.size() < 2) return Optional.empty();
        return quotes.stream().flatMap(a -> quotes.stream().filter(b -> a != b)
                .filter(b -> costs.containsKey(a.exchange()) && costs.containsKey(b.exchange()))
                .map(b -> candidate(a, b, costs.get(a.exchange()), costs.get(b.exchange()))))
                .filter(s -> s.estimatedNetYield().compareTo(minNetYieldRate) >= 0)
                .filter(s -> allowMixedFundingSigns || !s.mixedFundingSigns())
                .max(Comparator.comparing(FundingRateSpread::estimatedNetYield));
    }

    /** Same calculation, with an explicit safety gate for positive/negative mixed pairs. */
    public static Optional<FundingRateSpread> best(Collection<FundingRateQuote> quotes,
                                                   BigDecimal roundTripFeeRate,
                                                   BigDecimal slippageRate,
                                                   BigDecimal minNetYieldRate,
                                                   boolean allowMixedFundingSigns) {
        return best(quotes, roundTripFeeRate, slippageRate, minNetYieldRate)
                .filter(s -> allowMixedFundingSigns || !s.mixedFundingSigns());
    }

    public static FundingRateSpread candidate(FundingRateQuote a, FundingRateQuote b,
                                              BigDecimal fee, BigDecimal slippage) {
        FundingRateQuote low = a.fundingRate().compareTo(b.fundingRate()) <= 0 ? a : b;
        FundingRateQuote high = low == a ? b : a;
        BigDecimal spread = high.fundingRate().subtract(low.fundingRate());
        BigDecimal priceSpread = high.markPrice().subtract(low.markPrice())
                .divide(low.markPrice(), 12, RoundingMode.HALF_UP).abs();
        BigDecimal net = spread.subtract(fee == null ? BigDecimal.ZERO : fee)
                .subtract(slippage == null ? BigDecimal.ZERO : slippage);
        return new FundingRateSpread(low, high, spread, priceSpread, net);
    }

    public static FundingRateSpread candidate(FundingRateQuote a, FundingRateQuote b,
                                              ExchangeCost longCost, ExchangeCost shortCost) {
        return candidate(a, b, longCost.roundTripTakerCost().add(shortCost.roundTripTakerCost()), BigDecimal.ZERO);
    }
}
