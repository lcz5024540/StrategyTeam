package com.metaverse.coin.strategy.job.strategy.application;

public final class StrategyFlowLogFormatter {

    private StrategyFlowLogFormatter() {
    }

    public static String flow(Object... keyValues) {
        StringBuilder builder = new StringBuilder("[POLY_FLOW]");
        if (keyValues == null) {
            return builder.toString();
        }
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object key = keyValues[i];
            if (key == null) {
                continue;
            }
            builder.append(' ')
                    .append(key)
                    .append('=')
                    .append(formatValue(keyValues[i + 1]));
        }
        return builder.toString();
    }

    private static String formatValue(Object raw) {
        if (raw == null) {
            return "-";
        }
        String value = String.valueOf(raw)
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
        if (value.isEmpty()) {
            return "-";
        }
        if (value.length() > 600) {
            value = value.substring(0, 600) + "...";
        }
        boolean quote = value.chars().anyMatch(ch -> Character.isWhitespace(ch) || ch == '"' || ch == '=');
        String escaped = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
        return quote ? "\"" + escaped + "\"" : escaped;
    }
}
