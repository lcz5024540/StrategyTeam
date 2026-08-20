package com.metaverse.coin.strategy.core.arbitrage.multi;

import com.fasterxml.jackson.databind.JsonNode;
import com.metaverse.coin.strategy.common.client.market.PublicJsonHttpClient;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Public funding-rate adapter. Order methods deliberately fail until a signed private client is supplied. */
public abstract class ReadOnlyFundingRateGateway implements ExchangeFundingGateway {
    private final PublicJsonHttpClient httpClient = new PublicJsonHttpClient();
    protected abstract URI fundingUri(String symbol);
    protected URI markPriceUri(String symbol) { return fundingUri(symbol); }
    protected abstract BigDecimal fundingRate(JsonNode root);
    protected abstract BigDecimal markPrice(JsonNode root);
    protected JsonNode getJson(URI uri) {
        return httpClient.get(uri);
    }

    @Override public List<FundingRateQuote> fundingRates(List<String> symbols) {
        List<FundingRateQuote> result = new ArrayList<>();
        for (String symbol : symbols) {
            try {
                JsonNode root = getJson(fundingUri(symbol));
                JsonNode priceRoot = root;
                if (!markPriceUri(symbol).equals(fundingUri(symbol))) {
                    priceRoot = getJson(markPriceUri(symbol));
                }
                BigDecimal rate = fundingRate(root);
                BigDecimal price = markPrice(priceRoot);
                if (rate == null || price == null || price.signum() <= 0) throw new IllegalStateException("incomplete funding response");
                result.add(new FundingRateQuote(exchange(), symbol, rate, price, Instant.now()));
            } catch (RuntimeException e) {
                throw new IllegalStateException("funding query failed for " + exchange() + "/" + symbol, e);
            }
        }
        return result;
    }
    @Override public void openHedge(FundingRateSpread spread, String symbol, BigDecimal quantity) { throw unsupported(); }
    @Override public void closeHedge(FundingRateSpread spread, String symbol, BigDecimal quantity) { throw unsupported(); }
    @Override public void openLeg(HedgeLeg leg, String clientOrderId) { throw unsupported(); }
    @Override public void closeLeg(HedgeLeg leg, String clientOrderId) { throw unsupported(); }
    private UnsupportedOperationException unsupported() { return new UnsupportedOperationException("signed private order client is not configured for " + exchange()); }
}
