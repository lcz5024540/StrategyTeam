package com.metaverse.coin.strategy.job.funding.service;

import com.metaverse.coin.strategy.core.arbitrage.multi.Exchange;
import com.metaverse.coin.strategy.core.arbitrage.multi.NormalizedFundingRateSnapshot;
import com.metaverse.coin.strategy.core.arbitrage.multi.NormalizedOrderBookSnapshot;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads source-attributed capture CSV files into normalized replay frames. */
@Component
public class CrossExchangeMarketSnapshotReplayReader {

    public ReplayDataset read(Path input) {
        Map<FrameKey, MutableFrame> frames = new LinkedHashMap<>();
        List<CaptureFailure> failures = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(input, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.get(0).startsWith("schemaVersion,capturedAt,source,")) {
                throw new IllegalArgumentException("unsupported funding replay CSV header");
            }
            for (int lineNumber = 2; lineNumber <= lines.size(); lineNumber++) {
                String line = lines.get(lineNumber - 1);
                if (line.isBlank()) {
                    continue;
                }
                List<String> value = parseCsv(line);
                String schema = value.get(0);
                if (("1".equals(schema) && value.size() != 18)
                        || ("2".equals(schema) && value.size() != 20)
                        || (!"1".equals(schema) && !"2".equals(schema))
                        || !"PUBLIC_REST".equals(value.get(2))) {
                    throw new IllegalArgumentException("invalid replay row at line " + lineNumber);
                }
                int fundingReceivedAt = "1".equals(schema) ? 14 : 16;
                int bookReceivedAt = "1".equals(schema) ? 15 : 17;
                int status = "1".equals(schema) ? 16 : 18;
                int error = "1".equals(schema) ? 17 : 19;
                Instant capturedAt = Instant.parse(value.get(1));
                Exchange exchange = Exchange.parse(value.get(3));
                String symbol = value.get(4);
                if ("ERROR".equals(value.get(status))) {
                    failures.add(new CaptureFailure(capturedAt, exchange, symbol, value.get(error)));
                    continue;
                }
                if (!"OK".equals(value.get(status))) {
                    throw new IllegalArgumentException("unknown replay status at line " + lineNumber);
                }
                NormalizedFundingRateSnapshot funding = new NormalizedFundingRateSnapshot(
                        exchange, symbol, decimal(value, 5), Integer.parseInt(value.get(6)),
                        Instant.parse(value.get(7)), decimal(value, 8), decimal(value, 9),
                        Instant.parse(value.get(fundingReceivedAt)));
                NormalizedOrderBookSnapshot book = new NormalizedOrderBookSnapshot(
                        exchange, symbol, decimal(value, 10), decimal(value, 11),
                        decimal(value, 12), decimal(value, 13),
                        "1".equals(schema) ? List.of() : levels(value.get(14)),
                        "1".equals(schema) ? List.of() : levels(value.get(15)),
                        Instant.parse(value.get(bookReceivedAt)));
                MutableFrame frame = frames.computeIfAbsent(new FrameKey(capturedAt, symbol),
                        ignored -> new MutableFrame(capturedAt, symbol));
                frame.add(exchange, funding, book);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read funding replay snapshot " + input, exception);
        }
        return new ReplayDataset(frames.values().stream().map(MutableFrame::freeze).toList(),
                List.copyOf(failures));
    }

    private BigDecimal decimal(List<String> values, int index) {
        return new BigDecimal(values.get(index));
    }

    private List<NormalizedOrderBookSnapshot.Level> levels(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        List<NormalizedOrderBookSnapshot.Level> levels = new ArrayList<>();
        for (String encodedLevel : encoded.split(";", -1)) {
            String[] parts = encodedLevel.split(":", -1);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalArgumentException("invalid encoded order book level");
            }
            levels.add(new NormalizedOrderBookSnapshot.Level(new BigDecimal(parts[0]),
                    new BigDecimal(parts[1])));
        }
        return List.copyOf(levels);
    }

    private List<String> parseCsv(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if (current == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    value.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (current == ',' && !quoted) {
                values.add(value.toString());
                value.setLength(0);
            } else {
                value.append(current);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("unterminated quoted CSV field");
        }
        values.add(value.toString());
        return values;
    }

    private record FrameKey(Instant capturedAt, String symbol) { }

    private static final class MutableFrame {
        private final Instant capturedAt;
        private final String symbol;
        private final Map<Exchange, NormalizedFundingRateSnapshot> funding =
                new EnumMap<>(Exchange.class);
        private final Map<Exchange, NormalizedOrderBookSnapshot> books =
                new EnumMap<>(Exchange.class);

        private MutableFrame(Instant capturedAt, String symbol) {
            this.capturedAt = capturedAt;
            this.symbol = symbol;
        }

        private void add(Exchange exchange, NormalizedFundingRateSnapshot fundingSnapshot,
                NormalizedOrderBookSnapshot bookSnapshot) {
            if (funding.putIfAbsent(exchange, fundingSnapshot) != null
                    || books.putIfAbsent(exchange, bookSnapshot) != null) {
                throw new IllegalArgumentException("duplicate replay row for " + exchange + "/" + symbol
                        + " at " + capturedAt);
            }
        }

        private ReplayFrame freeze() {
            return new ReplayFrame(capturedAt, symbol, Map.copyOf(funding), Map.copyOf(books));
        }
    }

    public record ReplayFrame(Instant capturedAt, String symbol,
            Map<Exchange, NormalizedFundingRateSnapshot> funding,
            Map<Exchange, NormalizedOrderBookSnapshot> books) { }

    public record CaptureFailure(Instant capturedAt, Exchange exchange, String symbol,
            String error) { }

    public record ReplayDataset(List<ReplayFrame> frames, List<CaptureFailure> failures) { }
}
