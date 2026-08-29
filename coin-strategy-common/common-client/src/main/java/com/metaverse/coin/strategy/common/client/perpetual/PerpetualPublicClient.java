package com.metaverse.coin.strategy.common.client.perpetual;

import com.fasterxml.jackson.databind.JsonNode;
import com.metaverse.coin.strategy.common.client.market.PublicJsonHttpClient;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Public-only USDT perpetual market-data client. It contains no signed trading operations. */
final class PerpetualPublicClient implements PerpetualMarketDataClient {

    private final Venue venue;
    private final URI baseUri;
    private final PublicJsonHttpClient http;
    private final Map<String, BigDecimal> contractMultipliers = new ConcurrentHashMap<>();

    PerpetualPublicClient(Venue venue) {
        this(venue, URI.create(defaultBaseUrl(venue)), new PublicJsonHttpClient());
    }

    PerpetualPublicClient(Venue venue, URI baseUri) {
        this(venue, baseUri, new PublicJsonHttpClient());
    }

    PerpetualPublicClient(Venue venue, URI baseUri, PublicJsonHttpClient http) {
        if (venue == null || baseUri == null || http == null) {
            throw new IllegalArgumentException("venue, baseUri and http are required");
        }
        this.venue = venue;
        this.baseUri = baseUri;
        this.http = http;
    }

    @Override
    public List<Contract> contracts() {
        return switch (venue) {
            case BINANCE -> binanceContracts();
            case OKX -> okxContracts();
            case HTX -> htxContracts();
            case BITGET -> bitgetContracts();
        };
    }

    @Override
    public Funding funding(String symbol) {
        Instant receivedAt = Instant.now();
        return switch (venue) {
            case BINANCE -> binanceFunding(symbol, receivedAt);
            case OKX -> okxFunding(symbol, receivedAt);
            case HTX -> htxFunding(symbol, receivedAt);
            case BITGET -> bitgetFunding(symbol, receivedAt);
        };
    }

    @Override
    public OrderBook orderBook(String symbol, int depth) {
        Instant receivedAt = Instant.now();
        return switch (venue) {
            case BINANCE -> binanceBook(symbol, depth, receivedAt);
            case OKX -> okxBook(symbol, depth, receivedAt);
            case HTX -> htxBook(symbol, depth, receivedAt);
            case BITGET -> bitgetBook(symbol, depth, receivedAt);
        };
    }

    private List<Contract> binanceContracts() {
        List<Contract> result = new ArrayList<>();
        for (JsonNode node : get("/fapi/v1/exchangeInfo").path("symbols")) {
            if (!"PERPETUAL".equals(node.path("contractType").asText())
                    || !"USDT".equals(node.path("quoteAsset").asText())
                    || !"TRADING".equals(node.path("status").asText())) {
                continue;
            }
            BigDecimal tick = filter(node, "PRICE_FILTER", "tickSize");
            BigDecimal step = filter(node, "LOT_SIZE", "stepSize");
            BigDecimal minimum = filter(node, "MIN_NOTIONAL", "notional");
            addContract(result, node.path("symbol").asText(), node.path("symbol").asText(),
                    step, tick, minimum);
        }
        return result;
    }

    private Funding binanceFunding(String symbol, Instant receivedAt) {
        JsonNode node = get("/fapi/v1/premiumIndex?symbol=" + symbol);
        return funding(symbol, decimal(node, "lastFundingRate"), 8,
                node.path("nextFundingTime").asLong(), decimal(node, "markPrice"),
                decimal(node, "indexPrice"), receivedAt);
    }

    private OrderBook binanceBook(String symbol, int depth, Instant receivedAt) {
        JsonNode node = get("/fapi/v1/depth?symbol=" + symbol + "&limit=" + clamp(depth, 5));
        return book(symbol, node.path("bids"), node.path("asks"), Integer.MAX_VALUE, receivedAt);
    }

    private List<Contract> okxContracts() {
        Map<String, BigDecimal> prices = new HashMap<>();
        for (JsonNode ticker : get("/api/v5/market/tickers?instType=SWAP").path("data")) {
            prices.put(ticker.path("instId").asText(), decimal(ticker, "last"));
        }
        List<Contract> result = new ArrayList<>();
        for (JsonNode node : get("/api/v5/public/instruments?instType=SWAP").path("data")) {
            if (!"USDT".equals(node.path("settleCcy").asText())
                    || !"live".equals(node.path("state").asText())) {
                continue;
            }
            String exchangeSymbol = node.path("instId").asText();
            BigDecimal tick = decimal(node, "tickSz");
            BigDecimal lot = decimal(node, "lotSz");
            BigDecimal minimum = decimal(node, "minSz").multiply(decimal(node, "ctVal"))
                    .multiply(prices.getOrDefault(exchangeSymbol, BigDecimal.ZERO));
            String canonicalSymbol = node.path("ctValCcy").asText() + "USDT";
            addContract(result, canonicalSymbol, exchangeSymbol, lot, tick, minimum);
            rememberContractMultiplier(canonicalSymbol, decimal(node, "ctVal"));
        }
        return result;
    }

    private Funding okxFunding(String symbol, Instant receivedAt) {
        String instrument = okxSwap(symbol);
        JsonNode funding = get("/api/v5/public/funding-rate?instId=" + instrument)
                .path("data").path(0);
        JsonNode mark = get("/api/v5/public/mark-price?instType=SWAP&instId=" + instrument)
                .path("data").path(0);
        JsonNode index = get("/api/v5/market/index-tickers?instId=" + okxIndex(symbol))
                .path("data").path(0);
        long current = funding.path("fundingTime").asLong();
        long next = funding.path("nextFundingTime").asLong();
        int interval = (int) Math.max(1, Duration.ofMillis(Math.max(1, next - current)).toHours());
        return funding(symbol, decimal(funding, "fundingRate"), interval, next,
                decimal(mark, "markPx"), decimal(index, "idxPx"), receivedAt);
    }

    private OrderBook okxBook(String symbol, int depth, Instant receivedAt) {
        JsonNode node = get("/api/v5/market/books?instId=" + okxSwap(symbol)
                + "&sz=" + clamp(depth, 1)).path("data").path(0);
        return book(symbol, node.path("bids"), node.path("asks"), Integer.MAX_VALUE,
                contractMultiplier(symbol), receivedAt);
    }

    private List<Contract> htxContracts() {
        Map<String, BigDecimal> prices = new HashMap<>();
        for (JsonNode ticker : get("/linear-swap-ex/market/detail/batch_merged").path("ticks")) {
            prices.put(ticker.path("contract_code").asText(), decimal(ticker, "close"));
        }
        List<Contract> result = new ArrayList<>();
        for (JsonNode node : get("/linear-swap-api/v1/swap_contract_info").path("data")) {
            if (node.path("contract_status").asInt() != 1
                    || !"USDT".equals(node.path("settlement_asset").asText("USDT"))) {
                continue;
            }
            String exchangeSymbol = node.path("contract_code").asText();
            BigDecimal tick = decimal(node, "price_tick");
            BigDecimal contractSize = decimal(node, "contract_size");
            BigDecimal minimum = contractSize.multiply(
                    prices.getOrDefault(exchangeSymbol, BigDecimal.ZERO));
            if (tick.signum() > 0 && minimum.signum() > 0) {
                String canonicalSymbol = node.path("symbol").asText() + "USDT";
                result.add(new Contract(canonicalSymbol, exchangeSymbol,
                        0, scale(tick), minimum));
                rememberContractMultiplier(canonicalSymbol, contractSize);
            }
        }
        return result;
    }

    private Funding htxFunding(String symbol, Instant receivedAt) {
        String contract = htxContract(symbol);
        JsonNode funding = get("/linear-swap-api/v1/swap_funding_rate?contract_code=" + contract)
                .path("data");
        JsonNode mark = get("/linear-swap-ex/market/detail/merged?contract_code=" + contract)
                .path("tick");
        JsonNode index = get("/linear-swap-api/v1/swap_index?contract_code=" + contract)
                .path("data").path(0);
        long next = funding.path("next_funding_time")
                .asLong(funding.path("funding_time").asLong());
        return funding(symbol, decimal(funding, "funding_rate"), 8, next,
                decimal(mark, "close"), decimal(index, "index_price"), receivedAt);
    }

    private OrderBook htxBook(String symbol, int depth, Instant receivedAt) {
        JsonNode node = get("/linear-swap-ex/market/depth?contract_code=" + htxContract(symbol)
                + "&type=step0").path("tick");
        return book(symbol, node.path("bids"), node.path("asks"), Math.max(1, depth),
                contractMultiplier(symbol), receivedAt);
    }

    private List<Contract> bitgetContracts() {
        List<Contract> result = new ArrayList<>();
        for (JsonNode node : get("/api/v2/mix/market/contracts?productType=USDT-FUTURES")
                .path("data")) {
            if (!"normal".equalsIgnoreCase(node.path("symbolStatus").asText("normal"))) {
                continue;
            }
            BigDecimal minimum = decimal(node, "minTradeUSDT");
            int quantityScale = node.path("volumePlace").asInt(-1);
            int priceScale = node.path("pricePlace").asInt(-1);
            if (minimum.signum() > 0 && quantityScale >= 0 && priceScale >= 0) {
                result.add(new Contract(node.path("symbol").asText(), node.path("symbol").asText(),
                        quantityScale, priceScale, minimum));
            }
        }
        return result;
    }

    private Funding bitgetFunding(String symbol, Instant receivedAt) {
        String query = "?symbol=" + symbol + "&productType=USDT-FUTURES";
        JsonNode funding = get("/api/v2/mix/market/current-fund-rate" + query)
                .path("data").path(0);
        JsonNode ticker = get("/api/v2/mix/market/ticker" + query).path("data").path(0);
        long next = funding.path("nextUpdate")
                .asLong(funding.path("nextFundingTime").asLong());
        if (next <= 0) {
            throw new IllegalStateException("Bitget next funding time is missing");
        }
        return funding(symbol, decimal(funding, "fundingRate"),
                Math.max(1, funding.path("fundingRateInterval").asInt(8)), next,
                decimal(ticker, "markPrice"), decimal(ticker, "indexPrice"), receivedAt);
    }

    private OrderBook bitgetBook(String symbol, int depth, Instant receivedAt) {
        JsonNode node = get("/api/v2/mix/market/merge-depth?symbol=" + symbol
                + "&productType=USDT-FUTURES&precision=scale0&limit=" + clamp(depth, 5))
                .path("data");
        return book(symbol, node.path("bids"), node.path("asks"), Integer.MAX_VALUE, receivedAt);
    }

    private Funding funding(String symbol, BigDecimal rate, int interval, long next,
            BigDecimal mark, BigDecimal index, Instant receivedAt) {
        if (rate == null || mark == null || index == null || next <= 0
                || mark.signum() <= 0 || index.signum() <= 0) {
            throw new IllegalStateException("incomplete funding response for " + venue + "/" + symbol);
        }
        return new Funding(symbol, rate, interval, Instant.ofEpochMilli(next), mark, index, receivedAt);
    }

    private OrderBook book(String symbol, JsonNode bids, JsonNode asks, int maximumLevels,
            Instant receivedAt) {
        return book(symbol, bids, asks, maximumLevels, BigDecimal.ONE, receivedAt);
    }

    private OrderBook book(String symbol, JsonNode bids, JsonNode asks, int maximumLevels,
            BigDecimal contractMultiplier, Instant receivedAt) {
        BigDecimal bestBid = levelValue(bids, 0, 0);
        BigDecimal bestAsk = levelValue(asks, 0, 0);
        if (bestBid.signum() <= 0 || bestAsk.signum() <= 0) {
            throw new IllegalStateException("incomplete order book for " + venue + "/" + symbol);
        }
        List<BookLevel> bidLevels = levels(bids, maximumLevels, contractMultiplier);
        List<BookLevel> askLevels = levels(asks, maximumLevels, contractMultiplier);
        return new OrderBook(symbol, bestBid, bestAsk, notional(bidLevels), notional(askLevels),
                bidLevels, askLevels, receivedAt);
    }

    private void addContract(List<Contract> result, String canonical, String exchangeSymbol,
            BigDecimal quantityStep, BigDecimal priceStep, BigDecimal minimum) {
        if (quantityStep.signum() > 0 && priceStep.signum() > 0 && minimum.signum() > 0) {
            result.add(new Contract(canonical, exchangeSymbol, scale(quantityStep),
                    scale(priceStep), minimum));
        }
    }

    private JsonNode get(String pathAndQuery) {
        try {
            return http.get(baseUri.resolve(pathAndQuery));
        } catch (PublicJsonHttpClient.RateLimitException exception) {
            throw new RateLimitException(exception);
        }
    }

    private BigDecimal filter(JsonNode symbol, String type, String field) {
        for (JsonNode filter : symbol.path("filters")) {
            if (type.equals(filter.path("filterType").asText())) {
                return decimal(filter, field);
            }
        }
        return BigDecimal.ZERO;
    }

    private List<BookLevel> levels(JsonNode rawLevels, int maximumLevels,
            BigDecimal contractMultiplier) {
        List<BookLevel> levels = new ArrayList<>();
        int count = 0;
        for (JsonNode ignored : rawLevels) {
            if (count >= maximumLevels) {
                break;
            }
            BigDecimal price = levelValue(rawLevels, count, 0);
            BigDecimal quantity = levelValue(rawLevels, count, 1);
            if (price.signum() <= 0 || quantity.signum() <= 0) {
                throw new IllegalStateException("invalid order book level for " + venue);
            }
            levels.add(new BookLevel(price, price.multiply(quantity).multiply(contractMultiplier)));
            count++;
        }
        return List.copyOf(levels);
    }

    private BigDecimal notional(List<BookLevel> levels) {
        BigDecimal result = BigDecimal.ZERO;
        for (BookLevel level : levels) {
            result = result.add(level.notional());
        }
        return result;
    }

    private void rememberContractMultiplier(String symbol, BigDecimal multiplier) {
        if (multiplier.signum() > 0) {
            contractMultipliers.put(symbol, multiplier);
        }
    }

    private BigDecimal contractMultiplier(String symbol) {
        BigDecimal multiplier = contractMultipliers.get(symbol);
        if (multiplier != null) {
            return multiplier;
        }
        contracts();
        multiplier = contractMultipliers.get(symbol);
        if (multiplier == null) {
            throw new IllegalStateException("contract multiplier is missing for " + venue + "/" + symbol);
        }
        return multiplier;
    }

    private BigDecimal levelValue(JsonNode levels, int level, int field) {
        String value = levels.path(level).path(field).asText();
        return value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
    }

    private BigDecimal decimal(JsonNode node, String field) {
        String value = node.path(field).asText();
        return value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
    }

    private int scale(BigDecimal step) {
        return Math.max(0, step.stripTrailingZeros().scale());
    }

    private int clamp(int depth, int minimum) {
        return Math.min(100, Math.max(minimum, depth));
    }

    private String okxSwap(String symbol) {
        return symbol.substring(0, symbol.length() - 4) + "-USDT-SWAP";
    }

    private String okxIndex(String symbol) {
        return symbol.substring(0, symbol.length() - 4) + "-USDT";
    }

    private String htxContract(String symbol) {
        return symbol.substring(0, symbol.length() - 4) + "-USDT";
    }

    private static String defaultBaseUrl(Venue venue) {
        return switch (venue) {
            case BINANCE -> "https://fapi.binance.com";
            case OKX -> "https://www.okx.com";
            case HTX -> "https://api.hbdm.com";
            case BITGET -> "https://api.bitget.com";
        };
    }
}
