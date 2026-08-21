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
                if (value.size() != 18 || !"1".equals(value.get(0))
                        || !"PUBLIC_REST".equals(value.get(2))) {
                    throw new IllegalArgumentException("invalid replay row at line " + lineNumber);
                }
                Instant capturedAt = Instant.parse(value.get(1));
                Exchange exchange = Exchange.parse(value.get(3));
                String symbol = value.get(4);
                if ("ERROR".equals(value.get(16))) {
                    failures.add(new CaptureFailure(capturedAt, exchange, symbol, value.get(17)));
                    continue;
                }
                if (!"OK".equals(value.get(16))) {
                    throw new IllegalArgumentException("unknown replay status at line " + lineNumber);
                }
                NormalizedFundingRateSnapshot funding = new NormalizedFundingRateSnapshot(
                        exchange, symbol, decimal(value, 5), Integer.parseInt(value.get(6)),
                        Instant.parse(value.get(7)), decimal(value, 8), decimal(value, 9),
                        Instant.parse(value.get(14)));
                NormalizedOrderBookSnapshot book = new NormalizedOrderBookSnapshot(
                        exchange, symbol, decimal(value, 10), decimal(value, 11),
                        decimal(value, 12), decimal(value, 13), Instant.parse(value.get(15)));
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
