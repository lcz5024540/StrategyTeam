package com.metaverse.coin.strategy.core.arbitrage.multi;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerpetualContractCatalogTest {

    @Test
    void oneExchangeFailureDoesNotDiscardOtherCatalogs() {
        ExchangeHealthRegistry health = new ExchangeHealthRegistry();
        PerpetualContractCatalog catalog = new PerpetualContractCatalog(
                List.of(gateway(Exchange.BINANCE, false), gateway(Exchange.OKX, true)), health);

        catalog.refresh(Instant.now());

        assertTrue(catalog.find(Exchange.BINANCE, "BTCUSDT").isPresent());
        assertTrue(catalog.find(Exchange.OKX, "BTCUSDT").isEmpty());
        assertEquals(1, health.snapshot().get(Exchange.OKX).failureCount());
    }

    private PerpetualExchangeGateway gateway(Exchange exchange, boolean fail) {
        return new PerpetualExchangeGateway() {
            @Override public Exchange exchange() { return exchange; }
            @Override public List<PerpetualContract> listContracts() {
                if (fail) throw new IllegalStateException("unavailable");
                return List.of(new PerpetualContract("BTCUSDT", "BTCUSDT", 3, 1,
                        new BigDecimal("5")));
            }
            @Override public List<String> supportedUsdtPerpetualSymbols() { return List.of("BTCUSDT"); }
            @Override public NormalizedFundingRateSnapshot fundingRate(String canonicalSymbol) {
                throw new UnsupportedOperationException();
            }
            @Override public NormalizedOrderBookSnapshot orderBook(String canonicalSymbol, int depth) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
