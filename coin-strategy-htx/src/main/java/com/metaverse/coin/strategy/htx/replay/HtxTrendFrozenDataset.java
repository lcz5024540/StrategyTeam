package com.metaverse.coin.strategy.htx.replay;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapKline;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads only a complete, content-addressed public K-line dataset for offline replay.
 *
 * <p>This adapter has no Spring, database, account or HTTP dependency.  It deliberately
 * validates the manifest before returning candles, so a replay cannot silently use a
 * changed file, a partial collection, or a symbol outside the frozen universe.</p>
 */
public final class HtxTrendFrozenDataset {

    private static final String MANIFEST_SCHEMA = "htx-trend-research-dataset-manifest-v1";
    private final Path dataDirectory;
    private final String datasetKey;
    private final Map<InputKey, Input> inputs;

    private HtxTrendFrozenDataset(Path dataDirectory, String datasetKey, Map<InputKey, Input> inputs) {
        this.dataDirectory = dataDirectory;
        this.datasetKey = datasetKey;
        this.inputs = inputs;
    }

    public static HtxTrendFrozenDataset open(Path dataDirectory, Path manifestPath) throws IOException {
        if (dataDirectory == null || manifestPath == null) {
            throw new IllegalArgumentException("data directory and manifest path are required");
        }
        JSONObject manifest = JSON.parseObject(Files.readString(manifestPath));
        if (!MANIFEST_SCHEMA.equals(manifest.getString("schema"))) {
            throw new IllegalArgumentException("unsupported frozen dataset manifest schema");
        }
        if (!"COMPLETE".equals(manifest.getString("inputIntegrity"))) {
            throw new IllegalArgumentException("production replay requires a COMPLETE frozen dataset");
        }
        String datasetKey = manifest.getString("datasetKey");
        if (blank(datasetKey)) {
            throw new IllegalArgumentException("frozen dataset manifest has no datasetKey");
        }
        JSONArray rows = manifest.getJSONArray("inputs");
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("frozen dataset manifest has no inputs");
        }
        Map<InputKey, Input> inputs = new LinkedHashMap<>();
        for (int index = 0; index < rows.size(); index++) {
            JSONObject row = rows.getJSONObject(index);
            Input input = Input.from(row);
            Input previous = inputs.putIfAbsent(new InputKey(input.contractCode(), input.period()), input);
            if (previous != null) {
                throw new IllegalArgumentException("frozen dataset manifest repeats " + input.contractCode() + " " + input.period());
            }
        }
        return new HtxTrendFrozenDataset(dataDirectory.toAbsolutePath().normalize(), datasetKey, Map.copyOf(inputs));
    }

    public String datasetKey() {
        return datasetKey;
    }

    public List<String> contracts() {
        return inputs.keySet().stream().map(InputKey::contractCode).distinct().sorted().toList();
    }

    public List<String> periods(String contractCode) {
        return inputs.keySet().stream().filter(key -> key.contractCode().equals(contractCode))
                .map(InputKey::period).sorted().toList();
    }

    /**
     * Reads one manifest-declared input and verifies its digest and supplied coverage facts.
     * Callers receive ascending, de-duplicated candles suitable for the existing production calculators.
     */
    public List<HtxSwapKline> candles(String contractCode, String period) throws IOException {
        Input input = inputs.get(new InputKey(contractCode, period));
        if (input == null) {
            throw new IllegalArgumentException("contract/period is not declared by frozen dataset: " + contractCode + " " + period);
        }
        Path file = dataDirectory.resolve(input.fileName()).normalize();
        if (!file.startsWith(dataDirectory) || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("frozen dataset input file is unavailable: " + input.fileName());
        }
        if (!input.sha256().equalsIgnoreCase(sha256(file))) {
            throw new IllegalStateException("frozen dataset checksum mismatch: " + input.fileName());
        }
        JSONObject envelope = JSON.parseObject(Files.readString(file));
        if (!"ok".equals(envelope.getString("status"))) {
            throw new IllegalArgumentException("frozen K-line input is not an HTX ok envelope: " + input.fileName());
        }
        JSONArray data = envelope.getJSONArray("data");
        if (data == null) {
            throw new IllegalArgumentException("frozen K-line input has no data array: " + input.fileName());
        }
        Map<Long, HtxSwapKline> byTime = new LinkedHashMap<>();
        for (int index = 0; index < data.size(); index++) {
            JSONObject row = data.getJSONObject(index);
            HtxSwapKline candle = candle(row, input.fileName());
            if (byTime.putIfAbsent(candle.getId(), candle) != null) {
                throw new IllegalArgumentException("frozen K-line input repeats bar time: " + input.fileName() + " " + candle.getId());
            }
        }
        List<HtxSwapKline> candles = new ArrayList<>(byTime.values());
        candles.sort(Comparator.comparing(HtxSwapKline::getId));
        verifyCoverage(input, candles);
        return List.copyOf(candles);
    }

    private static HtxSwapKline candle(JSONObject row, String fileName) {
        Long id = row.getLong("id");
        BigDecimal open = row.getBigDecimal("open");
        BigDecimal high = row.getBigDecimal("high");
        BigDecimal low = row.getBigDecimal("low");
        BigDecimal close = row.getBigDecimal("close");
        if (id == null || !positive(open) || !positive(high) || !positive(low) || !positive(close)) {
            throw new IllegalArgumentException("frozen K-line input has invalid OHLC: " + fileName);
        }
        HtxSwapKline value = new HtxSwapKline();
        value.setId(id);
        value.setOpen(open);
        value.setHigh(high);
        value.setLow(low);
        value.setClose(close);
        value.setAmount(row.getBigDecimal("amount"));
        value.setVol(row.getBigDecimal("vol"));
        value.setTradeTurnover(row.getBigDecimal("trade_turnover"));
        value.setCount(row.getLong("count"));
        return value;
    }

    private static void verifyCoverage(Input input, List<HtxSwapKline> candles) {
        if (candles.size() != input.count()) {
            throw new IllegalArgumentException("frozen K-line count differs from manifest: " + input.fileName());
        }
        if (candles.isEmpty() || candles.get(0).getId() != input.firstBarTime()
                || candles.get(candles.size() - 1).getId() != input.lastBarTime()) {
            throw new IllegalArgumentException("frozen K-line range differs from manifest: " + input.fileName());
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] bytes = new byte[8192];
                for (int read; (read = input.read(bytes)) >= 0;) {
                    digest.update(bytes, 0, read);
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private record InputKey(String contractCode, String period) {
    }

    private record Input(String contractCode, String period, String fileName, String sha256, int count,
                         long firstBarTime, long lastBarTime) {
        private static Input from(JSONObject row) {
            String contractCode = row.getString("contract");
            String period = row.getString("period");
            String fileName = row.getString("file");
            String sha256 = row.getString("sha256");
            Integer count = row.getInteger("count");
            Long firstBarTime = row.getLong("firstBarTime");
            Long lastBarTime = row.getLong("lastBarTime");
            if (blank(contractCode) || blank(period) || blank(fileName) || blank(sha256) || count == null || count < 1
                    || firstBarTime == null || lastBarTime == null || !"COMPLETE".equals(row.getString("coverage"))) {
                throw new IllegalArgumentException("frozen dataset manifest contains an incomplete input declaration");
            }
            return new Input(contractCode, period, fileName, sha256, count, firstBarTime, lastBarTime);
        }
    }
}
