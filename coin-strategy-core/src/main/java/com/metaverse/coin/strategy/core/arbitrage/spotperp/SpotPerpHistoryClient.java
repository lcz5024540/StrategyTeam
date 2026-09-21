package com.metaverse.coin.strategy.core.arbitrage.spotperp;

import com.fasterxml.jackson.databind.JsonNode;
import com.metaverse.coin.strategy.common.client.market.PublicJsonHttpClient;
import com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpInstrument.SpotPerpVenue;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Fetches public historical funding prints. Depth history is not available from these endpoints. */
public final class SpotPerpHistoryClient {
    public record FundingPrint(
            SpotPerpInstrument instrument,
            Instant fundingTime,
            BigDecimal fundingRate,
            BigDecimal markPrice,
            BigDecimal indexOrPremium,
            String source
    ) {
        public Instant nextFundingTime(Duration interval) {
            return fundingTime.plus(interval);
        }
    }

    private final PublicJsonHttpClient http;
    private final URI binanceFutures;
    private final URI htxLinear;

    public SpotPerpHistoryClient() {
        this(new PublicJsonHttpClient(),
                URI.create("https://fapi.binance.com"),
                URI.create("https://api.hbdm.vn"));
    }

    SpotPerpHistoryClient(PublicJsonHttpClient http, URI binanceFutures, URI htxLinear) {
        this.http = Objects.requireNonNull(http);
        this.binanceFutures = Objects.requireNonNull(binanceFutures);
        this.htxLinear = Objects.requireNonNull(htxLinear);
    }

    public List<FundingPrint> fetch(SpotPerpInstrument instrument, Instant from, Instant to) {
        List<FundingPrint> prints = switch (instrument.venue()) {
            case BINANCE -> fetchBinance(instrument, from, to);
            case HTX -> fetchHtx(instrument, from, to);
        };
        prints.sort(Comparator.comparing(FundingPrint::fundingTime));
        return List.copyOf(prints);
    }

    public List<FundingPrint> fetchUniverse(Instant from, Instant to, List<String> errors) {
        List<FundingPrint> all = new ArrayList<>();
        for (SpotPerpInstrument instrument : SpotPerpInstrument.universe()) {
            try {
                all.addAll(fetch(instrument, from, to));
            } catch (RuntimeException exception) {
                errors.add(instrument.key() + ": " + exception.getMessage());
            }
        }
        all.sort(Comparator.comparing(FundingPrint::fundingTime)
                .thenComparing(print -> print.instrument().key()));
        return List.copyOf(all);
    }

    private List<FundingPrint> fetchBinance(SpotPerpInstrument instrument, Instant from, Instant to) {
        List<FundingPrint> prints = new ArrayList<>();
        long cursor = from.toEpochMilli();
        long end = to.toEpochMilli();
        for (int page = 0; page < 20 && cursor < end; page++) {
            String path = "/fapi/v1/fundingRate?symbol=" + instrument.canonicalSymbol()
                    + "&startTime=" + cursor + "&endTime=" + end + "&limit=1000";
            JsonNode array = http.get(binanceFutures.resolve(path));
            if (!array.isArray() || array.isEmpty()) {
                break;
            }
            long last = cursor;
            for (JsonNode node : array) {
                Instant time = Instant.ofEpochMilli(node.path("fundingTime").asLong());
                if (time.isBefore(from) || time.isAfter(to)) {
                    continue;
                }
                prints.add(new FundingPrint(
                        instrument,
                        time,
                        decimal(node, "fundingRate"),
                        decimal(node, "markPrice"),
                        decimal(node, "markPrice"),
                        "binance.fapi.v1.fundingRate"));
                last = Math.max(last, time.toEpochMilli());
            }
            if (array.size() < 1000) {
                break;
            }
            cursor = last + 1;
        }
        return prints;
    }

    private List<FundingPrint> fetchHtx(SpotPerpInstrument instrument, Instant from, Instant to) {
        List<FundingPrint> prints = new ArrayList<>();
        for (int page = 1; page <= 12; page++) {
            String path = "/linear-swap-api/v1/swap_historical_funding_rate?contract_code="
                    + instrument.perpExchangeSymbol() + "&page_index=" + page + "&page_size=50";
            JsonNode root = http.get(htxLinear.resolve(path));
            JsonNode pageNode = root.path("data");
            JsonNode rows = pageNode.path("data");
            if (!rows.isArray() || rows.isEmpty()) {
                break;
            }
            boolean olderThanWindow = false;
            for (JsonNode node : rows) {
                long millis = parseHtxTime(node.path("funding_time"));
                Instant time = Instant.ofEpochMilli(millis);
                if (time.isAfter(to)) {
                    continue;
                }
                if (time.isBefore(from)) {
                    olderThanWindow = true;
                    continue;
                }
                BigDecimal rate = firstPresent(node, "realized_rate", "funding_rate");
                BigDecimal mark = decimal(node, "mark_price");
                if (mark.signum() <= 0) {
                    mark = BigDecimal.ONE;
                }
                BigDecimal premium = decimal(node, "avg_premium_index");
                prints.add(new FundingPrint(
                        instrument,
                        time,
                        rate,
                        mark,
                        premium,
                        "htx.linear-swap.swap_historical_funding_rate"));
            }
            int totalPage = pageNode.path("total_page").asInt(page);
            if (page >= totalPage || olderThanWindow) {
                break;
            }
        }
        return prints;
    }

    private static long parseHtxTime(JsonNode node) {
        if (node.isNumber()) {
            return node.asLong();
        }
        String text = node.asText();
        if (text == null || text.isBlank()) {
            return 0L;
        }
        return Long.parseLong(text);
    }

    private static BigDecimal firstPresent(JsonNode node, String first, String second) {
        JsonNode value = node.path(first);
        if (!value.isMissingNode() && !value.isNull() && !value.asText().isBlank()
                && !"null".equalsIgnoreCase(value.asText())) {
            return new BigDecimal(value.asText());
        }
        return decimal(node, second);
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        String value = node.path(field).asText();
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
    }
}
