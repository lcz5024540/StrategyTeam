package com.metaverse.coin.strategy.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.web.config.DashboardProperties;
import com.metaverse.coin.strategy.web.model.StrategyConflictRowView;
import com.metaverse.coin.strategy.web.model.StrategyDashboardOverviewView;
import com.metaverse.coin.strategy.web.model.StrategyDetailView;
import com.metaverse.coin.strategy.web.model.StrategyInsightView;
import com.metaverse.coin.strategy.web.model.StrategyMetricsView;
import com.metaverse.coin.strategy.web.model.StrategyOrderRowView;
import com.metaverse.coin.strategy.web.model.StrategyPositionRowView;
import com.metaverse.coin.strategy.web.model.StrategySummaryView;
import com.metaverse.coin.strategy.web.model.StrategyTimelinePointView;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class StrategyDashboardService {

    private static final String POLYMARKET_CODE = "POLYMARKET_COPY";
    private static final DateTimeFormatter BJT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withLocale(Locale.SIMPLIFIED_CHINESE)
            .withZone(ZoneId.of("Asia/Shanghai"));

    private final DashboardProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public StrategyDashboardService(DashboardProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public StrategyDashboardOverviewView overview() {
        StrategyDetailView polymarket = detail(POLYMARKET_CODE);
        List<StrategySummaryView> strategies = new ArrayList<>();
        strategies.add(polymarket.summary());
        strategies.add(placeholder("HEDGE", "对冲策略", "对冲", "PLANNED", "后续用于市场中性、风险对冲和跨市场保护仓位。"));
        strategies.add(placeholder("ROBOT", "机器人策略", "机器人", "PLANNED", "后续用于事件驱动机器人、盘口套利和自动化规则执行。"));
        strategies.add(placeholder("INTELLIGENT", "智能策略", "智能", "PLANNED", "后续用于模型评分、智能分层择时和多因子调度。"));
        return new StrategyDashboardOverviewView(
                BJT_FORMATTER.format(Instant.now()),
                POLYMARKET_CODE,
                strategies
        );
    }

    public StrategyDetailView detail(String strategyCode) {
        if (!POLYMARKET_CODE.equalsIgnoreCase(strategyCode)) {
            StrategySummaryView summary = placeholder(strategyCode, strategyCode, "待扩展", "PLANNED", "当前策略尚未接入实时数据。");
            return new StrategyDetailView(summary,
                    new StrategyMetricsView(0, 0, 0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                            0, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO),
                    List.of(new StrategyInsightView("INFO", "策略待接入", "当前页面结构已预留，后续可继续接入真实策略数据。")),
                    List.of(), List.of(), List.of(), List.of(), List.of());
        }

        JsonNode performance = getJson("/smart-wallet/reports/performance");
        JsonNode pnlSummary = getJson("/smart-wallet/pnl/summary");
        JsonNode capacity = getJson("/smart-wallet/capacity/summary");
        JsonNode positions = getJson("/smart-wallet/positions/live");
        JsonNode nextSettlements = getJson("/smart-wallet/positions/next-settlements?limit=8");
        JsonNode orders = getJson("/smart-wallet/orders?limit=30");
        JsonNode timeline = getJson("/smart-wallet/pnl/timeline?days=30");
        JsonNode conflicts = getJson("/smart-wallet/reports/conflicting-markets?limit=10&openOnly=false");

        StrategySummaryView summary = new StrategySummaryView(
                POLYMARKET_CODE,
                "Polymarket 跟单策略",
                "Polymarket",
                "ACTIVE",
                "基于 smart wallet 的 Polymarket 模拟跟单与风控策略。",
                intValue(performance, "openPositions"),
                decimalValue(performance, "netPnlUsdc"),
                decimalValue(performance, "realizedPnlUsdc"),
                decimalValue(performance, "unrealizedPnlUsdc"),
                decimalValue(performance, "winRate"),
                intValue(performance, "totalOrders")
        );

        StrategyMetricsView metrics = new StrategyMetricsView(
                intValue(performance, "totalOrders"),
                intValue(performance, "filledOrders"),
                intValue(performance, "openPositions"),
                intValue(performance, "closedPositions"),
                intValue(performance, "winningPositions"),
                intValue(performance, "losingPositions"),
                decimalValue(performance, "winRate"),
                decimalValue(pnlSummary, "tradedVolumeUsdc"),
                decimalValue(pnlSummary, "feeUsdc"),
                decimalValue(performance, "realizedPnlUsdc"),
                decimalValue(performance, "unrealizedPnlUsdc"),
                decimalValue(performance, "netPnlUsdc"),
                decimalValue(performance, "profitFactor"),
                decimalValue(performance, "maxDrawdownUsdc"),
                intValue(performance, "conflictingMarkets"),
                intValue(performance, "openConflictingMarkets"),
                decimalValue(performance, "conflictNetPnlUsdc"),
                intValue(capacity, "remainingPositionSlots"),
                decimalValue(capacity, "remainingCapitalBudgetUsdc")
        );

        return new StrategyDetailView(
                summary,
                metrics,
                buildInsights(metrics, capacity, nextSettlements),
                toPositions(positions),
                toPositions(nextSettlements),
                toOrders(orders),
                toTimeline(timeline),
                toConflicts(conflicts)
        );
    }

    private JsonNode getJson(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getSmartWalletBaseUrl() + path))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Request failed: " + path + " status=" + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Failed to fetch dashboard data from smart-wallet api: " + path, e);
        }
    }

    private List<StrategyInsightView> buildInsights(StrategyMetricsView metrics, JsonNode capacity, JsonNode nextSettlements) {
        List<StrategyInsightView> insights = new ArrayList<>();
        BigDecimal remainingCapital = metrics.remainingCapitalBudgetUsdc() == null ? BigDecimal.ZERO : metrics.remainingCapitalBudgetUsdc();
        if (remainingCapital.compareTo(BigDecimal.valueOf(100)) < 0) {
            insights.add(new StrategyInsightView("WARN", "资金预算偏紧", "剩余可用策略预算较低，建议优先等待结算或调低新开仓节奏。"));
        }
        if (metrics.openConflictingMarkets() != null && metrics.openConflictingMarkets() > 0) {
            insights.add(new StrategyInsightView("WARN", "存在冲突市场", "同一市场持有多个 outcome 会拖低胜率，建议优先检查冲突持仓。"));
        }
        if (metrics.maxDrawdownUsdc() != null && metrics.maxDrawdownUsdc().compareTo(BigDecimal.valueOf(300)) > 0) {
            insights.add(new StrategyInsightView("WARN", "回撤偏高", "当前策略回撤较高，建议收紧信号阈值或减少同时持仓数。"));
        }
        if (nextSettlements.isArray() && nextSettlements.size() > 0) {
            String nextBjt = textValue(nextSettlements.get(0), "marketEndAtBjt");
            String question = textValue(nextSettlements.get(0), "question");
            insights.add(new StrategyInsightView("INFO", "最近待结算市场", question + "，预计北京时间 " + nextBjt + "。"));
        } else {
            insights.add(new StrategyInsightView("INFO", "暂无待结算持仓", "当前没有未结算仓位，适合重新观察新策略机会。"));
        }
        if (metrics.winRate() != null && metrics.winRate().compareTo(BigDecimal.ZERO) == 0
                && metrics.closedPositions() != null && metrics.closedPositions() == 0) {
            insights.add(new StrategyInsightView("INFO", "当前胜率尚未成型", "暂时没有已结算仓位，胜率需要等待真实结算后再判断。"));
        }
        return insights;
    }

    private List<StrategyPositionRowView> toPositions(JsonNode array) {
        List<StrategyPositionRowView> result = new ArrayList<>();
        if (array == null || !array.isArray()) {
            return result;
        }
        for (JsonNode item : array) {
            result.add(new StrategyPositionRowView(
                    longValue(item, "id"),
                    textValue(item, "marketId"),
                    textValue(item, "question"),
                    textValue(item, "outcome"),
                    textValue(item, "status"),
                    decimalValue(item, "quantity"),
                    decimalValue(item, "averageEntryPrice"),
                    decimalValue(item, "costBasisUsdc"),
                    decimalValue(item, "realizedPnlUsdc"),
                    decimalValue(item, "unrealizedPnlUsdc"),
                    textValue(item, "marketEndAtBjt")
            ));
        }
        result.sort(Comparator.comparing(StrategyPositionRowView::marketEndAtBjt, Comparator.nullsLast(String::compareTo)));
        return result;
    }

    private List<StrategyOrderRowView> toOrders(JsonNode array) {
        List<StrategyOrderRowView> result = new ArrayList<>();
        if (array == null || !array.isArray()) {
            return result;
        }
        for (JsonNode item : array) {
            result.add(new StrategyOrderRowView(
                    longValue(item, "id"),
                    longValue(item, "followTaskId"),
                    textValue(item, "executionMode"),
                    textValue(item, "question"),
                    textValue(item, "outcome"),
                    textValue(item, "side"),
                    textValue(item, "status"),
                    decimalValue(item, "price"),
                    decimalValue(item, "size"),
                    decimalValue(item, "notionalUsdc"),
                    textValue(item, "marketEndAtBjt"),
                    textValue(item, "placedAt")
            ));
        }
        return result;
    }

    private List<StrategyTimelinePointView> toTimeline(JsonNode array) {
        List<StrategyTimelinePointView> result = new ArrayList<>();
        if (array == null || !array.isArray()) {
            return result;
        }
        for (JsonNode item : array) {
            result.add(new StrategyTimelinePointView(
                    textValue(item, "bucketAt"),
                    decimalValue(item, "netPnlUsdc"),
                    decimalValue(item, "cumulativeNetPnlUsdc")
            ));
        }
        return result;
    }

    private List<StrategyConflictRowView> toConflicts(JsonNode array) {
        List<StrategyConflictRowView> result = new ArrayList<>();
        if (array == null || !array.isArray()) {
            return result;
        }
        for (JsonNode item : array) {
            List<String> outcomes = new ArrayList<>();
            JsonNode outcomesNode = item.get("outcomes");
            if (outcomesNode != null && outcomesNode.isArray()) {
                outcomesNode.forEach(node -> outcomes.add(node.asText("")));
            }
            result.add(new StrategyConflictRowView(
                    textValue(item, "marketId"),
                    textValue(item, "question"),
                    outcomes,
                    intValue(item, "openPositionCount"),
                    decimalValue(item, "netPnlUsdc"),
                    textValue(item, "marketEndAtBjt")
            ));
        }
        return result;
    }

    private StrategySummaryView placeholder(String code, String name, String category, String status, String description) {
        return new StrategySummaryView(code, name, category, status, description, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0);
    }

    private BigDecimal decimalValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return BigDecimal.ZERO;
        }
        if (value.isNumber()) {
            return value.decimalValue();
        }
        try {
            return new BigDecimal(value.asText("0"));
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private Integer intValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? 0 : value.asInt(0);
    }

    private Long longValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asLong();
    }

    private String textValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }
}
