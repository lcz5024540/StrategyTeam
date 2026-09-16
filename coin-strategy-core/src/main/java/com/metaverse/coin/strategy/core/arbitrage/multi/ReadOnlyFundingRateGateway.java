package com.metaverse.coin.strategy.core.arbitrage.multi;

import com.metaverse.coin.strategy.common.client.perpetual.PerpetualMarketDataClient;
import org.springframework.core.env.Environment;

import java.math.BigDecimal;
import java.util.List;

/** Maps public connector data to core models; signed private operations remain unavailable. */
public abstract class ReadOnlyFundingRateGateway
        implements PerpetualExchangeGateway, ExchangeFundingGateway {
    private final Exchange exchange;
    private final PerpetualMarketDataClient client;

    protected ReadOnlyFundingRateGateway(Exchange exchange, PerpetualMarketDataClient client) {
        this.exchange = exchange;
        this.client = client;
    }

    protected static PerpetualMarketDataClient configuredClient(Exchange exchange,
            PerpetualMarketDataClient.Venue venue, Environment environment) {
        String key = "arbitrage.multi-exchange.base-urls."
                + exchange.name().toLowerCase(java.util.Locale.ROOT);
        String configured = environment.getProperty(key);
        return configured == null || configured.isBlank()
                ? PerpetualMarketDataClient.forVenue(venue)
                : PerpetualMarketDataClient.forVenue(venue, configured.trim());
    }

    @Override
    public Exchange exchange() {
        return exchange;
    }

    @Override
    public List<String> supportedUsdtPerpetualSymbols() {
        return client.contracts().stream().map(PerpetualMarketDataClient.Contract::canonicalSymbol)
                .toList();
    }

    @Override
    public List<PerpetualContract> listContracts() {
        return client.contracts().stream().map(contract -> new PerpetualContract(
                contract.canonicalSymbol(), contract.exchangeSymbol(), contract.quantityScale(),
                contract.priceScale(), contract.minimumNotional())).toList();
    }

    @Override
    public NormalizedFundingRateSnapshot fundingRate(String symbol) {
        PerpetualMarketDataClient.Funding funding = client.funding(symbol);
        return new NormalizedFundingRateSnapshot(exchange, funding.canonicalSymbol(),
                funding.rawRate(), funding.intervalHours(), funding.nextFundingTime(),
                funding.markPrice(), funding.indexPrice(), funding.receivedAt());
    }

    @Override
    public NormalizedOrderBookSnapshot orderBook(String symbol, int depth) {
        PerpetualMarketDataClient.OrderBook book = client.orderBook(symbol, depth);
        return new NormalizedOrderBookSnapshot(exchange, book.canonicalSymbol(), book.bestBid(),
                book.bestAsk(), book.bidNotional(), book.askNotional(),
                book.bidLevels().stream().map(level -> new NormalizedOrderBookSnapshot.Level(
                        level.price(), level.notional())).toList(),
                book.askLevels().stream().map(level -> new NormalizedOrderBookSnapshot.Level(
                        level.price(), level.notional())).toList(),
                book.receivedAt());
    }

    @Override
    public List<FundingRateQuote> fundingRates(List<String> symbols) {
        return symbols.stream().map(this::fundingRate).map(snapshot -> new FundingRateQuote(
                exchange, snapshot.canonicalSymbol(), snapshot.rawFundingRate(),
                snapshot.fundingIntervalHours(), snapshot.markPrice(), snapshot.nextFundingTime())).toList();
    }

    @Override
    public void openHedge(FundingRateSpread spread, String symbol, BigDecimal quantity) {
        throw unsupported();
    }

    @Override
    public void closeHedge(FundingRateSpread spread, String symbol, BigDecimal quantity) {
        throw unsupported();
    }

    @Override
    public void openLeg(HedgeLeg leg, String clientOrderId) {
        throw unsupported();
    }

    @Override
    public void closeLeg(HedgeLeg leg, String clientOrderId) {
        throw unsupported();
    }

    private UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException(
                "signed private order client is not configured for " + exchange);
    }
}
