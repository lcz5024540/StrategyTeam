package com.metaverse.coin.strategy.job.strategy.application;

import com.metaverse.coin.strategy.job.strategy.model.StrategyLogEntryView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class StrategyLogQueryService {

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 500;
    private static final int DEFAULT_MAX_SCAN_LINES = 5_000;
    private static final long DEFAULT_MAX_READ_BYTES = 2L * 1024L * 1024L;
    private static final Map<String, List<String>> SOURCE_FILES = Map.of(
            "app", List.of("job-current.log", "job-startup.log"),
            "error", List.of("job-error-current.log"),
            "startup", List.of("job-startup.log")
    );
    private static final Pattern LOG_LINE = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+" +
                    "(TRACE|DEBUG|INFO|WARN|ERROR)\\s+\\[([^]]*)]\\s+(.+?)\\s+-\\s?(.*)$"
    );
    private static final Pattern PIPE_LOG_LINE = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})(?:\\[\\d+])?\\s*\\|\\s*" +
                    "(TRACE|DEBUG|INFO|WARN|ERROR)\\s*\\|\\s*([^|]+?)\\s*\\|\\s*(.+?)\\s+-\\s?(.*)$"
    );
    private static final Pattern ENC_SECRET = Pattern.compile("ENC\\([^)]*\\)");
    private static final Pattern ASSIGNMENT_SECRET = Pattern.compile(
            "(?i)\\b(api[-_ ]?key|api[-_ ]?secret|secret[-_ ]?key|private[-_ ]?key|passphrase|password|token)\\b\\s*[:=]\\s*([^\\s,;]+)"
    );
    private static final Pattern BEARER_SECRET = Pattern.compile("(?i)\\bBearer\\s+[-._~+/A-Za-z0-9=]+");

    private final Path logDirectory;
    private final int maxScanLines;
    private final long maxReadBytes;

    @Autowired
    public StrategyLogQueryService(@Value("${log.dir:${LOG_DIR:/opt/coinstrategy/smart-wallet/logs}}") String logDirectory) {
        this(Path.of(logDirectory), DEFAULT_MAX_SCAN_LINES, DEFAULT_MAX_READ_BYTES);
    }

    StrategyLogQueryService(Path logDirectory, int maxScanLines, long maxReadBytes) {
        this.logDirectory = logDirectory;
        this.maxScanLines = Math.max(100, maxScanLines);
        this.maxReadBytes = Math.max(16L * 1024L, maxReadBytes);
    }

    public List<StrategyLogEntryView> query(String source, String level, String strategy, String q, Integer limit) {
        String normalizedSource = normalizeSource(source);
        List<StrategyLogEntryView> entries = new ArrayList<>();
        for (Path logFile : resolveSources(normalizedSource)) {
            if (!Files.isRegularFile(logFile) || !Files.isReadable(logFile)) {
                continue;
            }
            try {
                entries.addAll(parse(normalizedSource, readTailLines(logFile)));
            } catch (IOException ignored) {
                // A stale or rotating log file should not hide readable fallback logs.
            }
        }

        String normalizedLevel = normalizeLevel(level);
        String normalizedStrategy = normalizeStrategy(strategy);
        String normalizedQuery = normalizeQuery(q);
        int safeLimit = clampLimit(limit);

        return entries.stream()
                .filter(entry -> matchesLevel(entry, normalizedLevel))
                .filter(entry -> matchesStrategy(entry, normalizedStrategy))
                .filter(entry -> matchesQuery(entry, normalizedQuery))
                .sorted(Comparator.comparing(StrategyLogEntryView::timestamp).reversed())
                .limit(safeLimit)
                .toList();
    }

    private String normalizeSource(String source) {
        String normalized = source == null || source.isBlank()
                ? "app"
                : source.trim().toLowerCase(Locale.ROOT);
        if (!SOURCE_FILES.containsKey(normalized)) {
            throw new IllegalArgumentException("Unsupported log source: " + source);
        }
        return normalized;
    }

    private List<Path> resolveSources(String source) {
        Path root = logDirectory.toAbsolutePath().normalize();
        List<Path> targets = new ArrayList<>();
        for (String fileName : SOURCE_FILES.get(source)) {
            Path target = root.resolve(fileName).normalize();
            if (!target.startsWith(root)) {
                throw new IllegalArgumentException("Unsupported log source: " + source);
            }
            targets.add(target);
        }
        return targets;
    }

    private List<String> readTailLines(Path file) throws IOException {
        long size = Files.size(file);
        long start = Math.max(0L, size - maxReadBytes);
        int bytesToRead = (int) (size - start);
        ByteBuffer buffer = ByteBuffer.allocate(bytesToRead);
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            channel.position(start);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // Read until EOF or buffer is full.
            }
        }
        buffer.flip();
        String text = StandardCharsets.UTF_8.decode(buffer).toString();
        if (start > 0) {
            int firstLineBreak = text.indexOf('\n');
            text = firstLineBreak >= 0 ? text.substring(firstLineBreak + 1) : "";
        }
        String[] split = text.split("\\R");
        int fromIndex = Math.max(0, split.length - maxScanLines);
        List<String> lines = new ArrayList<>(split.length - fromIndex);
        for (int i = fromIndex; i < split.length; i++) {
            if (!split[i].isEmpty()) {
                lines.add(split[i]);
            }
        }
        return lines;
    }

    private List<StrategyLogEntryView> parse(String source, List<String> lines) {
        List<ParsedLog> parsedLogs = new ArrayList<>();
        ParsedLog current = null;

        for (String line : lines) {
            ParsedLog parsed = parseLogLine(line);
            if (parsed != null) {
                if (current != null) {
                    parsedLogs.add(current);
                }
                current = parsed;
            } else if (current != null) {
                current.message().append('\n').append(line);
            }
        }
        if (current != null) {
            parsedLogs.add(current);
        }

        return parsedLogs.stream()
                .map(item -> {
                    String message = redact(item.message().toString());
                    String strategy = inferStrategy(item);
                    return StrategyFlowLogSupport.enrichEntry(item, strategy, source, message);
                })
                .toList();
    }

    private ParsedLog parseLogLine(String line) {
        Matcher matcher = LOG_LINE.matcher(line);
        if (!matcher.matches()) {
            matcher = PIPE_LOG_LINE.matcher(line);
        }
        if (!matcher.matches()) {
            return null;
        }
        return new ParsedLog(
                matcher.group(1).trim(),
                matcher.group(2).trim(),
                matcher.group(3).trim(),
                matcher.group(4).trim(),
                new StringBuilder(matcher.group(5))
        );
    }

    private String inferStrategy(ParsedLog item) {
        String combined = (item.logger() + " " + item.message()).toLowerCase(Locale.ROOT);
        if (combined.contains("funding_arb")
                || combined.contains("fundingarb")
                || combined.contains("funding rate")
                || combined.contains("singleexchangearbitrage")
                || combined.contains("arbitrage")) {
            return "FUNDING_ARB";
        }
        if (combined.contains("polymarket_copy")
                || combined.contains("polymarket")
                || combined.contains("smartwallet")
                || combined.contains("smart-wallet")) {
            return "POLYMARKET_COPY";
        }
        return "SYSTEM";
    }

    private String redact(String message) {
        String redacted = ENC_SECRET.matcher(message).replaceAll("ENC(***)");
        redacted = BEARER_SECRET.matcher(redacted).replaceAll("Bearer ***");
        return ASSIGNMENT_SECRET.matcher(redacted).replaceAll("$1=***");
    }

    private String normalizeLevel(String level) {
        String normalized = level == null || level.isBlank()
                ? "ALL"
                : level.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "TRACE", "DEBUG", "INFO", "WARN", "ERROR" -> normalized;
            default -> "ALL";
        };
    }

    private String normalizeStrategy(String strategy) {
        String normalized = strategy == null || strategy.isBlank()
                ? "ALL"
                : strategy.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "POLYMARKET_COPY", "FUNDING_ARB", "SYSTEM" -> normalized;
            default -> "ALL";
        };
    }

    private String normalizeQuery(String q) {
        return q == null || q.isBlank() ? "" : q.trim().toLowerCase(Locale.ROOT);
    }

    private int clampLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(MAX_LIMIT, limit));
    }

    private boolean matchesLevel(StrategyLogEntryView entry, String level) {
        return "ALL".equals(level) || level.equalsIgnoreCase(entry.level());
    }

    private boolean matchesStrategy(StrategyLogEntryView entry, String strategy) {
        return "ALL".equals(strategy) || strategy.equalsIgnoreCase(entry.strategy());
    }

    private boolean matchesQuery(StrategyLogEntryView entry, String query) {
        if (query.isEmpty()) {
            return true;
        }
        String haystack = String.join(" ",
                entry.timestamp(),
                entry.level(),
                entry.thread(),
                entry.logger(),
                entry.strategy(),
                entry.message(),
                nullToEmpty(entry.action()),
                nullToEmpty(entry.result()),
                nullToEmpty(entry.flowStage()),
                nullToEmpty(entry.eventId()),
                nullToEmpty(entry.followTaskId()),
                nullToEmpty(entry.wallet()),
                nullToEmpty(entry.marketId()),
                nullToEmpty(entry.outcome()),
                nullToEmpty(entry.firstRejectGate()),
                nullToEmpty(entry.blockerCategories())
        ).toLowerCase(Locale.ROOT);
        return haystack.contains(query);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    record ParsedLog(
            String timestamp,
            String level,
            String thread,
            String logger,
            StringBuilder message
    ) {
    }
}
