package com.metaverse.coin.strategy.core.arbitrage.spotperp;

import com.fasterxml.jackson.databind.JsonNode;
import com.metaverse.coin.strategy.common.client.market.PublicJsonHttpClient;
import com.metaverse.coin.strategy.common.client.perpetual.PerpetualMarketDataClient;
import com.metaverse.coin.strategy.core.arbitrage.multi.NormalizedOrderBookSnapshot;
import com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpInstrument.SpotPerpVenue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Public REST adapter: Binance spot+perp, HTX perp on {@code api.hbdm.vn}.
 * HTX spot L2 is proxied from the USDT-M index when Huobi/HTX spot hosts are unreachable.
 */
public final class PublicSpotPerpMarketSource {
    public record FetchError(SpotPerpInstrument instrument, String message) {
    }

    public record FetchResult(Instant at, List<SpotPerpSnapshot> snapshots, List<FetchError> errors,
            List<String> notes) {
        public FetchResult {
            snapshots = List.copyOf(snapshots);
            errors = List.copyOf(errors);
            notes = List.copyOf(notes);
        }
    }

    private final PublicJsonHttpClient http;
    private final Map<SpotPerpVenue, PerpetualMarketDataClient> perps;
    private final URI binanceSpotBase;
    private final Map<SpotPerpVenue, Map<String, PerpetualMarketDataClient.Contract>> contracts =
            new ConcurrentHashMap<>();

    public PublicSpotPerpMarketSource() {
        this(new PublicJsonHttpClient(),
                Map.of(SpotPerpVenue.BINANCE, PerpetualMarketDataClient.forVenue(PerpetualMarketDataClient.Venue.BINANCE),
                        SpotPerpVenue.HTX, PerpetualMarketDataClient.forVenue(
                                PerpetualMarketDataClient.Venue.HTX, "https://api.hbdm.vn")),
                URI.create("https://api.binance.com"));
    }

    PublicSpotPerpMarketSource(PublicJsonHttpClient http,
            Map<SpotPerpVenue, PerpetualMarketDataClient> perps,
            URI binanceSpotBase) {
        this.http = Objects.requireNonNull(http, "http");
        this.perps = Map.copyOf(perps);
        this.binanceSpotBase = Objects.requireNonNull(binanceSpotBase, "binanceSpotBase");
    }

    public FetchResult fetchUniverse() {
        Instant now = Instant.now();
        List<SpotPerpSnapshot> snapshots = new ArrayList<>();
        List<FetchError> errors = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        boolean htxSpotProxied = false;
        for (SpotPerpInstrument instrument : SpotPerpInstrument.universe()) {
            try {
                snapshots.add(fetch(instrument, now));
                if (instrument.venue() == SpotPerpVenue.HTX) {
                    htxSpotProxied = true;
                }
            } catch (RuntimeException exception) {
                errors.add(new FetchError(instrument, exception.getMessage() == null
                        ? exception.getClass().getSimpleName()
                        : exception.getMessage()));
            }
        }
        if (htxSpotProxied) {
            notes.add("HTX spot L2 is proxied from the USDT-M index (synthetic 1-tick book). "
                    + "api.huobi.pro/api.htx.com were unreachable or presented invalid TLS here. "
                    + "HTX perp public data uses https://api.hbdm.vn.");
        }
        return new FetchResult(now, snapshots, errors, notes);
    }

    public SpotPerpSnapshot fetch(SpotPerpInstrument instrument, Instant now) {
        PerpetualMarketDataClient perp = perps.get(instrument.venue());
        if (perp == null) {
            throw new IllegalStateException("no perpetual client for " + instrument.venue());
        }
        PerpetualMarketDataClient.Funding funding = perp.funding(instrument.canonicalSymbol());
        PerpetualMarketDataClient.OrderBook perpBook = perp.orderBook(instrument.canonicalSymbol(), 20);
        SpotBook spot = fetchSpot(instrument, funding);
        PerpetualMarketDataClient.Contract contract = contract(instrument, perp);
        BigDecimal quantityStep = quantityStep(instrument, contract, funding.markPrice());
        return new SpotPerpSnapshot(
                instrument,
                funding.rawRate(),
                funding.intervalHours(),
                funding.nextFundingTime(),
                funding.markPrice(),
                funding.indexPrice(),
                spot.bid,
                spot.ask,
                perpBook.bestBid(),
                perpBook.bestAsk(),
                spot.bids,
                spot.asks,
                SpotPerpSnapshot.levelsFrom(perpBook.bidLevels()),
                SpotPerpSnapshot.levelsFrom(perpBook.askLevels()),
                quantityStep,
                contract == null || contract.minimumNotional().signum() <= 0
                        ? new BigDecimal("5") : contract.minimumNotional(),
                now
        );
    }

    private SpotBook fetchSpot(SpotPerpInstrument instrument, PerpetualMarketDataClient.Funding funding) {
        return switch (instrument.venue()) {
            case BINANCE -> binanceSpot(instrument);
            case HTX -> syntheticSpotFromIndex(funding.indexPrice());
        };
    }

    private SpotBook binanceSpot(SpotPerpInstrument instrument) {
        JsonNode node = http.get(binanceSpotBase.resolve(
                "/api/v3/depth?symbol=" + instrument.spotSymbol() + "&limit=20"));
        return book(node.path("bids"), node.path("asks"), BigDecimal.ONE);
    }

    private SpotBook syntheticSpotFromIndex(BigDecimal index) {
        if (index == null || index.signum() <= 0) {
            throw new IllegalStateException("HTX index price missing; cannot proxy spot");
        }
        BigDecimal tick = index.movePointLeft(5).max(new BigDecimal("0.01"));
        BigDecimal bid = index.subtract(tick);
        BigDecimal ask = index.add(tick);
        BigDecimal depth = new BigDecimal("1000000");
        return new SpotBook(
                bid,
                ask,
                SpotPerpSnapshot.syntheticBook(bid, depth, 5, tick, true),
                SpotPerpSnapshot.syntheticBook(ask, depth, 5, tick, false)
        );
    }

    private SpotBook book(JsonNode bids, JsonNode asks, BigDecimal multiplier) {
        List<NormalizedOrderBookSnapshot.Level> bidLevels = levels(bids, multiplier);
        List<NormalizedOrderBookSnapshot.Level> askLevels = levels(asks, multiplier);
        if (bidLevels.isEmpty() || askLevels.isEmpty()) {
            throw new IllegalStateException("incomplete spot book");
        }
        return new SpotBook(bidLevels.get(0).price(), askLevels.get(0).price(), bidLevels, askLevels);
    }

    private List<NormalizedOrderBookSnapshot.Level> levels(JsonNode raw, BigDecimal multiplier) {
        List<NormalizedOrderBookSnapshot.Level> levels = new ArrayList<>();
        int count = 0;
        for (JsonNode ignored : raw) {
            if (count >= 20) {
                break;
            }
            BigDecimal price = decimal(raw.path(count).path(0));
            BigDecimal quantity = decimal(raw.path(count).path(1));
            if (price.signum() <= 0 || quantity.signum() <= 0) {
                throw new IllegalStateException("invalid spot book level");
            }
            levels.add(new NormalizedOrderBookSnapshot.Level(
                    price, price.multiply(quantity).multiply(multiplier)));
            count++;
        }
        return List.copyOf(levels);
    }

    private PerpetualMarketDataClient.Contract contract(SpotPerpInstrument instrument,
            PerpetualMarketDataClient client) {
        Map<String, PerpetualMarketDataClient.Contract> bySymbol = contracts.computeIfAbsent(
                instrument.venue(), venue -> {
                    Map<String, PerpetualMarketDataClient.Contract> loaded = new ConcurrentHashMap<>();
                    for (PerpetualMarketDataClient.Contract item : client.contracts()) {
                        loaded.put(item.canonicalSymbol(), item);
                    }
                    return loaded;
                });
        return bySymbol.get(instrument.canonicalSymbol());
    }

    private BigDecimal quantityStep(SpotPerpInstrument instrument,
            PerpetualMarketDataClient.Contract contract, BigDecimal mark) {
        if (instrument.venue() == SpotPerpVenue.HTX && contract != null && mark.signum() > 0
                && contract.minimumNotional().signum() > 0) {
            return contract.minimumNotional().divide(mark, 8, RoundingMode.HALF_UP).max(new BigDecimal("0.0001"));
        }
        int scale = contract == null ? 5 : contract.quantityScale();
        if (scale <= 0) {
            return new BigDecimal("0.0001");
        }
        return BigDecimal.ONE.scaleByPowerOfTen(-scale);
    }

    private static BigDecimal decimal(JsonNode node) {
        String value = node.asText();
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
    }

    private record SpotBook(
            BigDecimal bid,
            BigDecimal ask,
            List<NormalizedOrderBookSnapshot.Level> bids,
            List<NormalizedOrderBookSnapshot.Level> asks
    ) {
    }
}
