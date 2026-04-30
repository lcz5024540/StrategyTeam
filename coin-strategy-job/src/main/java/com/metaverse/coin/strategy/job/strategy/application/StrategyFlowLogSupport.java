package com.metaverse.coin.strategy.job.strategy.application;

import com.metaverse.coin.strategy.job.strategy.model.StrategyFlowLogEvent;
import com.metaverse.coin.strategy.job.strategy.model.StrategyLogEntryView;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class StrategyFlowLogSupport {

    static final String FLOW_PREFIX = "[POLY_FLOW]";
    static final String LEGACY_FOLLOW_PREFIX = "[POLY_FOLLOW]";

    private StrategyFlowLogSupport() {
    }

    static StrategyLogEntryView enrichEntry(StrategyLogQueryService.ParsedLog item,
                                            String strategy,
                                            String source,
                                            String redactedMessage) {
        FlowFields fields = parseFields(redactedMessage);
        return new StrategyLogEntryView(
                item.timestamp(),
                item.level(),
                item.thread(),
                item.logger(),
                strategy,
                source,
                redactedMessage,
                fields.action(),
                fields.result(),
                fields.flowStage(),
                fields.eventId(),
                fields.followTaskId(),
                fields.wallet(),
                fields.marketId(),
                fields.outcome(),
                fields.mode(),
                fields.stakeUsdc(),
                fields.entryLane(),
                fields.matchedStrategies(),
                fields.gateHighWaterMark(),
                fields.firstRejectGate(),
                fields.blockerCategories()
        );
    }

    static StrategyFlowLogEvent toEvent(StrategyLogEntryView entry) {
        FlowFields fields = parseFields(entry.message());
        boolean legacyFollow = isLegacyFollowEntry(entry.message(), fields);
        if (!fields.isFlow() && !legacyFollow) {
            return null;
        }
        String flowId = resolveFlowId(fields);
        return new StrategyFlowLogEvent(
                entry.timestamp(),
                entry.level(),
                entry.thread(),
                entry.logger(),
                entry.strategy(),
                entry.source(),
                entry.message(),
                flowId,
                fields.isFlow() ? fields.flowStage() : inferLegacyFlowStage(fields.action()),
                fields.action(),
                fields.result(),
                fields.eventId(),
                fields.followTaskId(),
                fields.wallet(),
                fields.marketId(),
                fields.outcome(),
                fields.mode(),
                fields.stakeUsdc(),
                fields.entryLane(),
                fields.matchedStrategies(),
                fields.gateHighWaterMark(),
                fields.firstRejectGate(),
                fields.blockerCategories(),
                fields.reasonSummary()
        );
    }

    static FlowFields parseFields(String message) {
        Map<String, String> values = parseKeyValues(message);
        boolean flow = message != null && message.contains(FLOW_PREFIX);
        return new FlowFields(
                flow,
                normalize(values.get("flowStage")),
                normalize(values.get("action")),
                normalize(values.get("result")),
                normalize(values.get("eventId")),
                normalize(values.get("followTaskId")),
                normalize(values.get("wallet")),
                normalize(values.get("marketId")),
                normalize(values.get("outcome")),
                normalize(values.get("mode")),
                normalize(values.get("stakeUsdc")),
                normalize(values.get("entryLane")),
                normalize(values.get("matchedStrategies")),
                normalize(values.get("gateHighWaterMark")),
                normalize(values.get("firstRejectGate")),
                normalize(values.get("blockerCategories")),
                normalize(values.get("reasonSummary"))
        );
    }

    private static Map<String, String> parseKeyValues(String message) {
        Map<String, String> result = new LinkedHashMap<>();
        if (message == null || message.isBlank()) {
            return result;
        }
        int cursor = message.indexOf(FLOW_PREFIX);
        String input = cursor >= 0
                ? message.substring(cursor + FLOW_PREFIX.length())
                : message;
        int length = input.length();
        int i = 0;
        while (i < length) {
            while (i < length && Character.isWhitespace(input.charAt(i))) {
                i++;
            }
            int keyStart = i;
            while (i < length && isKeyChar(input.charAt(i))) {
                i++;
            }
            if (keyStart == i || i >= length || input.charAt(i) != '=') {
                i++;
                continue;
            }
            String key = input.substring(keyStart, i);
            i++;
            String value;
            if (i < length && input.charAt(i) == '"') {
                StringBuilder builder = new StringBuilder();
                i++;
                boolean escaping = false;
                while (i < length) {
                    char ch = input.charAt(i++);
                    if (escaping) {
                        builder.append(switch (ch) {
                            case 'n' -> '\n';
                            case 't' -> '\t';
                            default -> ch;
                        });
                        escaping = false;
                    } else if (ch == '\\') {
                        escaping = true;
                    } else if (ch == '"') {
                        break;
                    } else {
                        builder.append(ch);
                    }
                }
                value = builder.toString();
            } else {
                int valueStart = i;
                while (i < length && !Character.isWhitespace(input.charAt(i))) {
                    i++;
                }
                value = input.substring(valueStart, i);
            }
            result.put(key, value);
        }
        return result;
    }

    private static boolean isKeyChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_' || ch == '-';
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank() || "-".equals(value)) {
            return null;
        }
        return value;
    }

    private static String resolveFlowId(FlowFields fields) {
        if (fields.eventId() != null) {
            return "event:" + fields.eventId();
        }
        if (fields.followTaskId() != null) {
            return "task:" + fields.followTaskId();
        }
        String wallet = fields.wallet() == null ? "-" : fields.wallet().toLowerCase(Locale.ROOT);
        String market = fields.marketId() == null ? "-" : fields.marketId();
        String outcome = fields.outcome() == null ? "-" : fields.outcome();
        return "signal:" + wallet + "|" + market + "|" + outcome;
    }

    private static boolean isLegacyFollowEntry(String message, FlowFields fields) {
        return message != null
                && message.contains(LEGACY_FOLLOW_PREFIX)
                && (fields.eventId() != null
                || fields.followTaskId() != null
                || fields.marketId() != null
                || fields.wallet() != null);
    }

    private static String inferLegacyFlowStage(String action) {
        if (action == null) {
            return "LEGACY_FOLLOW";
        }
        String normalized = action.toLowerCase(Locale.ROOT);
        if (normalized.contains("风控")) {
            return "S5_RISK_EVALUATED";
        }
        if (normalized.contains("任务")) {
            return "S6_TASK_CREATED";
        }
        if (normalized.contains("执行") || normalized.contains("订单") || normalized.contains("下单")) {
            return "S8_ORDER_EXECUTED";
        }
        if (normalized.contains("市场")) {
            return "S4_MARKET_CHECK";
        }
        if (normalized.contains("钱包")) {
            return "S2_PROFILE_ADMISSION";
        }
        return "LEGACY_FOLLOW";
    }

    record FlowFields(
            boolean isFlow,
            String flowStage,
            String action,
            String result,
            String eventId,
            String followTaskId,
            String wallet,
            String marketId,
            String outcome,
            String mode,
            String stakeUsdc,
            String entryLane,
            String matchedStrategies,
            String gateHighWaterMark,
            String firstRejectGate,
            String blockerCategories,
            String reasonSummary
    ) {
    }
}
