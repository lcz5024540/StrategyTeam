package com.metaverse.coin.strategy.web.service.log;

public final class StrategyFlowLogFormatter {

    private StrategyFlowLogFormatter() {
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
            case "S9_POSITION_EXIT" -> "S9 持仓退出 / Position Exit";
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
}
