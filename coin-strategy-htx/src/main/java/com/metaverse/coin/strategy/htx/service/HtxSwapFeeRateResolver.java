package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapFeeRate;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxUsdtSwapService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Resolves account-specific HTX swap fee rates with a conservative fallback. */
@Slf4j
class HtxSwapFeeRateResolver {

    private static final long DEFAULT_CACHE_MS = Duration.ofMinutes(10).toMillis();

    private final HtxUsdtSwapService swap;
    private final Supplier<BigDecimal> fallbackSupplier;
    private final Supplier<Long> cacheMsSupplier;
    private final String accountLabel;
    private final Map<String, CachedRates> cache = new ConcurrentHashMap<>();

    HtxSwapFeeRateResolver(HtxUsdtSwapService swap, Supplier<BigDecimal> fallbackSupplier,
                           Supplier<Long> cacheMsSupplier, String accountLabel) {
        this.swap = swap;
        this.fallbackSupplier = fallbackSupplier;
        this.cacheMsSupplier = cacheMsSupplier;
        this.accountLabel = StringUtils.hasText(accountLabel) ? accountLabel : "primary";
    }

    BigDecimal takerRate(String contractCode) {
        return rates(contractCode).taker();
    }

    FeeRates rates(String contractCode) {
        String normalized = normalize(contractCode);
        BigDecimal fallback = nonNegative(fallbackSupplier == null ? null : fallbackSupplier.get());
        if (swap == null || !StringUtils.hasText(normalized)) {
            return FeeRates.fallback(fallback);
        }
        long now = System.currentTimeMillis();
        CachedRates cached = cache.get(normalized);
        if (cached != null && cached.expiresAtMs() > now) {
            return cached.rates();
        }
        FeeRates resolved = queryRates(normalized, fallback);
        cache.put(normalized, new CachedRates(resolved, now + cacheMs()));
        return resolved;
    }

    private FeeRates queryRates(String contractCode, BigDecimal fallback) {
        HtxRestResponse<List<HtxSwapFeeRate>> response = swap.tradingFee(contractCode);
        if (response != null && response.ok() && response.payload() != null) {
            FeeRates rates = response.payload().stream()
                    .filter(rate -> rate != null)
                    .filter(rate -> !StringUtils.hasText(rate.getContractCode())
                            || contractCode.equalsIgnoreCase(rate.getContractCode()))
                    .findFirst()
                    .map(rate -> toFeeRates(rate, fallback))
                    .orElse(FeeRates.fallback(fallback));
            log.info("HTX swap fee rates account={} contract={} openMaker={} openTaker={} closeMaker={} closeTaker={} effectiveTaker={}",
                    accountLabel, contractCode, rates.openMaker(), rates.openTaker(), rates.closeMaker(),
                    rates.closeTaker(), rates.taker());
            return rates;
        }
        log.warn("HTX swap fee query failed account={} contract={} errCode={} errMsg={}; use fallback taker={}",
                accountLabel, contractCode,
                response == null ? "null" : response.getErrCode(),
                response == null ? "null" : response.getErrMsg(), fallback);
        return FeeRates.fallback(fallback);
    }

    private FeeRates toFeeRates(HtxSwapFeeRate rate, BigDecimal fallback) {
        BigDecimal openMaker = firstNonNegative(rate.getOpenMakerFee(), fallback);
        BigDecimal openTaker = firstNonNegative(rate.getOpenTakerFee(), fallback);
        BigDecimal closeMaker = firstNonNegative(rate.getCloseMakerFee(), openMaker, fallback);
        BigDecimal closeTaker = firstNonNegative(rate.getCloseTakerFee(), openTaker, fallback);
        BigDecimal taker = openTaker.max(closeTaker);
        return new FeeRates(openMaker, openTaker, closeMaker, closeTaker, taker, false);
    }

    private long cacheMs() {
        Long configured = cacheMsSupplier == null ? null : cacheMsSupplier.get();
        return configured == null || configured <= 0L ? DEFAULT_CACHE_MS : configured;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value == null || value.signum() < 0 ? BigDecimal.ZERO : value;
    }

    private static BigDecimal firstNonNegative(BigDecimal... values) {
        for (BigDecimal value : values) {
            if (value != null && value.signum() >= 0) {
                return value;
            }
        }
        return BigDecimal.ZERO;
    }

    record FeeRates(BigDecimal openMaker, BigDecimal openTaker, BigDecimal closeMaker,
                    BigDecimal closeTaker, BigDecimal taker, boolean fallback) {
        static FeeRates fallback(BigDecimal fallback) {
            BigDecimal value = nonNegative(fallback);
            return new FeeRates(value, value, value, value, value, true);
        }
    }

    private record CachedRates(FeeRates rates, long expiresAtMs) {
    }
}
