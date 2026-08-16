package com.metaverse.coin.strategy.htx.strategy;

import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSpotFeeRate;
import com.metaverse.coin.strategy.htx.config.HtxMarketMakerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Reads HTX V2 account fee rates and keeps a conservative, short-lived cache per symbol. */
@Slf4j
@Component
@ConditionalOnBean(HtxApiService.class)
@RequiredArgsConstructor
public class HtxSpotFeeRateProvider {

    private static final long CACHE_MS = Duration.ofMinutes(5).toMillis();

    private final HtxApiService htxApiService;
    private final HtxMarketMakerProperties properties;
    private final Map<String, CachedRate> cache = new ConcurrentHashMap<>();

    public BigDecimal effectiveRate(String symbol) {
        CachedRate cached = cache.get(symbol);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt() > now) {
            return cached.rate();
        }

        BigDecimal configured = properties.getFeeRatePerSide();
        HtxRestResponse<List<HtxSpotFeeRate>> response = htxApiService.getSpotService().queryFeeRates(symbol);
        BigDecimal effective = configured;
        if (response != null && response.ok() && response.getData() != null) {
            effective = response.getData().stream()
                    .filter(rate -> rate != null && symbol.equalsIgnoreCase(rate.getSymbol()))
                    .findFirst()
                    .map(rate -> maxNonNegative(configured,
                            rate.getActualMakerRate(), rate.getActualTakerRate(),
                            rate.getMakerFeeRate(), rate.getTakerFeeRate()))
                    .orElse(configured);
        } else {
            log.warn("HTX V2 spot fee query failed symbol={} errCode={} errMsg={}; use configured conservative rate={}",
                    symbol,
                    response == null ? "null" : response.getErrCode(),
                    response == null ? "null" : response.getErrMsg(),
                    configured);
        }
        cache.put(symbol, new CachedRate(effective, now + CACHE_MS));
        return effective;
    }

    private static BigDecimal maxNonNegative(BigDecimal initial, BigDecimal... rates) {
        BigDecimal result = initial;
        for (BigDecimal rate : rates) {
            if (rate != null && rate.signum() >= 0 && rate.compareTo(result) > 0) {
                result = rate;
            }
        }
        return result;
    }

    private record CachedRate(BigDecimal rate, long expiresAt) {
    }
}
