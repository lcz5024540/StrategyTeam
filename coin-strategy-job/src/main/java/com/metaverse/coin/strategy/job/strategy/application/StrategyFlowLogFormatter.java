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
            Object value = "flowStage".equals(String.valueOf(key))
                    ? displayStage(keyValues[i + 1])
                    : keyValues[i + 1];
            builder.append(' ')
                    .append(key)
                    .append('=')
                    .append(formatValue(value));
        }
        return builder.toString();
    }

    public static String displayStage(Object raw) {
        if (raw == null) {
            return null;
        }
        return switch (String.valueOf(raw)) {
            case "S1_EVENT_CAPTURED" -> "S1 事件捕获 / Event Captured";
            case "S2_PROFILE_ADMISSION" -> "S2 钱包准入 / Profile Admission";
            case "S3_CANDIDATE_ENRICHED" -> "S3 候选增强 / Candidate Enriched";
            case "S4_MARKET_CHECK" -> "S4 市场检查 / Market Check";
            case "S5_RISK_EVALUATED" -> "S5 风控评估 / Risk Evaluated";
            case "S6_TASK_CREATED" -> "S6 任务创建 / Task Created";
            case "S7_TASK_CLAIMED" -> "S7 任务抢占 / Task Claimed";
            case "S8_ORDER_EXECUTED" -> "S8 订单执行 / Order Executed";
            default -> String.valueOf(raw);
        };
    }

    public static boolean isStage(String actual, String stageCode) {
        if (actual == null || stageCode == null) {
            return false;
        }
        return actual.equalsIgnoreCase(stageCode)
                || actual.equalsIgnoreCase(displayStage(stageCode));
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
