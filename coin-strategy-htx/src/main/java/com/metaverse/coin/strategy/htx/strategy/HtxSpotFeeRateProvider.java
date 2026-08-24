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

/** Reads HTX V2 account fee rates and keeps a short-lived cache per symbol. */
@Slf4j
@Component
@ConditionalOnBean(HtxApiService.class)
@RequiredArgsConstructor
public class HtxSpotFeeRateProvider {

    private static final long CACHE_MS = Duration.ofMinutes(5).toMillis();

    private final HtxApiService htxApiService;
    private final HtxMarketMakerProperties properties;
    private final Map<String, CachedRates> cache = new ConcurrentHashMap<>();

    public FeeRates rates(String symbol) {
        CachedRates cached = cache.get(symbol);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt() > now) {
            return cached.rates();
        }

        BigDecimal configured = properties.getFeeRatePerSide();
        HtxRestResponse<List<HtxSpotFeeRate>> response = htxApiService.getSpotService().queryFeeRates(symbol);
        FeeRates effective = new FeeRates(configured, configured);
        if (response != null && response.ok() && response.getData() != null) {
            effective = response.getData().stream()
                    .filter(rate -> rate != null && symbol.equalsIgnoreCase(rate.getSymbol()))
                    .findFirst()
                    .map(rate -> new FeeRates(
                            firstNonNegative(rate.getActualMakerRate(), rate.getMakerFeeRate(), configured),
                            firstNonNegative(rate.getActualTakerRate(), rate.getTakerFeeRate(), configured)))
                    .orElse(effective);
        } else {
            log.warn("HTX V2 spot fee query failed symbol={} errCode={} errMsg={}; use configured conservative rate={}",
                    symbol,
                    response == null ? "null" : response.getErrCode(),
                    response == null ? "null" : response.getErrMsg(),
                    configured);
        }
        log.info("HTX spot fee rates symbol={} maker={} taker={}",
                symbol, effective.maker(), effective.taker());
        cache.put(symbol, new CachedRates(effective, now + CACHE_MS));
        return effective;
    }

    public BigDecimal effectiveRate(String symbol) {
        FeeRates rates = rates(symbol);
        return rates.maker().max(rates.taker());
    }

    private static BigDecimal firstNonNegative(BigDecimal... rates) {
        for (BigDecimal rate : rates) {
            if (rate != null && rate.signum() >= 0) {
                return rate;
            }
        }
        throw new IllegalStateException("fee rate fallback must be non-negative");
    }

    public record FeeRates(BigDecimal maker, BigDecimal taker) {
    }

    private record CachedRates(FeeRates rates, long expiresAt) {
    }
}
