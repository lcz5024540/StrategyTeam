package com.metaverse.coin.strategy.htx.replay;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.metaverse.coin.strategy.htx.config.HtxTrendEnsembleShadowProperties;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;

/** Loads the immutable, non-secret calculation settings used by one offline frozen-data replay. */
public final class HtxTrendFrozenReplayConfiguration {

    private static final String SCHEMA = "htx-trend-replay-configuration-v1";
    private static final Set<String> SOURCE_KINDS = Set.of("CODE_DEFAULTS", "DEPLOYMENT_ENV_SNAPSHOT");

    private HtxTrendFrozenReplayConfiguration() {
    }

    public static Configuration open(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("frozen replay configuration file is required");
        }
        JSONObject root = JSON.parseObject(Files.readString(path));
        if (root == null || !SCHEMA.equals(root.getString("schema")) || !"COMPLETE".equals(root.getString("inputIntegrity"))) {
            throw new IllegalArgumentException("frozen replay configuration must declare a COMPLETE supported schema");
        }
        String configurationKey = required(root.getString("configurationKey"), "configurationKey");
        String sourceKind = required(root.getString("sourceKind"), "sourceKind");
        if (!SOURCE_KINDS.contains(sourceKind)) {
            throw new IllegalArgumentException("unsupported frozen replay configuration source kind: " + sourceKind);
        }
        String evidence = required(root.getString("configurationEvidence"), "configurationEvidence");
        String sourceReference = required(root.getString("sourceReference"), "sourceReference");
        if ("CODE_DEFAULTS".equals(sourceKind) && !evidence.startsWith("INSUFFICIENT_EVIDENCE:")) {
            throw new IllegalArgumentException("code-default configuration must retain its historical-evidence limitation");
        }
        if ("DEPLOYMENT_ENV_SNAPSHOT".equals(sourceKind) && !evidence.startsWith("FROZEN_DEPLOYMENT_CONFIGURATION:")) {
            throw new IllegalArgumentException("deployment configuration must identify its immutable evidence source");
        }
        JSONObject ensemble = root.getJSONObject("ensemble");
        if (ensemble == null) {
            throw new IllegalArgumentException("frozen replay configuration has no ensemble section");
        }
        HtxTrendEnsembleShadowProperties properties = properties(ensemble);
        int boxWindowBars = positiveInt(root, "boxWindowBars");
        return new Configuration(configurationKey, sourceKind, sourceReference, evidence, sha256(path), properties, boxWindowBars);
    }

    private static HtxTrendEnsembleShadowProperties properties(JSONObject input) {
        HtxTrendEnsembleShadowProperties properties = new HtxTrendEnsembleShadowProperties();
        properties.setKlineSize(positiveInt(input, "klineSize"));
        properties.setCandleCloseGraceSeconds(nonNegativeLong(input, "candleCloseGraceSeconds"));
        properties.setFastEmaPeriod(positiveInt(input, "fastEmaPeriod"));
        properties.setMediumEmaPeriod(positiveInt(input, "mediumEmaPeriod"));
        properties.setSlowEmaPeriod(positiveInt(input, "slowEmaPeriod"));
        properties.setBreakoutLookback(positiveInt(input, "breakoutLookback"));
        properties.setMomentumLookback(positiveInt(input, "momentumLookback"));
        properties.setAtrPeriod(positiveInt(input, "atrPeriod"));
        properties.setAdxPeriod(positiveInt(input, "adxPeriod"));
        properties.setVolumeLookback(positiveInt(input, "volumeLookback"));
        properties.setMinAtrPct(positiveDecimal(input, "minAtrPct"));
        properties.setMinAdx(positiveDecimal(input, "minAdx"));
        properties.setMinMomentumPct(positiveDecimal(input, "minMomentumPct"));
        properties.setMinVolumeRatio(positiveDecimal(input, "minVolumeRatio"));
        properties.setEntryScoreThreshold(positiveDecimal(input, "entryScoreThreshold"));
        properties.setMinScoreGap(positiveDecimal(input, "minScoreGap"));
        properties.setModelVersion(required(input.getString("modelVersion"), "ensemble.modelVersion"));
        return properties;
    }

    private static int positiveInt(JSONObject input, String field) {
        Integer value = input.getInteger(field);
        if (value == null || value < 1) {
            throw new IllegalArgumentException("frozen replay configuration requires positive " + field);
        }
        return value;
    }

    private static long nonNegativeLong(JSONObject input, String field) {
        Long value = input.getLong(field);
        if (value == null || value < 0L) {
            throw new IllegalArgumentException("frozen replay configuration requires non-negative " + field);
        }
        return value;
    }

    private static BigDecimal positiveDecimal(JSONObject input, String field) {
        BigDecimal value = input.getBigDecimal(field);
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException("frozen replay configuration requires positive " + field);
        }
        return value;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("frozen replay configuration requires " + field);
        }
        return value;
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

    public record Configuration(String configurationKey, String sourceKind, String sourceReference,
                                String configurationEvidence, String sha256,
                                HtxTrendEnsembleShadowProperties properties, int boxWindowBars) {
    }
}
