package com.metaverse.coin.strategy.web.service.strategy;

import com.metaverse.coin.strategy.web.mapper.HtxTrendDashboardMapper;
import com.metaverse.coin.strategy.web.model.HtxTrendDashboardViews;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/** Deliberately read-only dashboard projection; it never calls a trading endpoint. */
@Service
public class HtxTrendDashboardQueryService {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));
    private final HtxTrendDashboardMapper mapper;

    public HtxTrendDashboardQueryService(HtxTrendDashboardMapper mapper) { this.mapper = mapper; }

    public HtxTrendDashboardViews.Dashboard dashboard() {
        try {
            return new HtxTrendDashboardViews.Dashboard(TIME.format(Instant.now()), "HTX_TREND_EXECUTION_LEDGER",
                    summary(mapper.summary()), mapper.activePositions().stream().map(this::position).toList(),
                    mapper.recentPositions().stream().map(this::position).toList(),
                    mapper.dailyPnl().stream().map(this::daily).toList());
        } catch (DataAccessException exception) {
            return new HtxTrendDashboardViews.Dashboard(TIME.format(Instant.now()), "HTX_TREND_EXECUTION_LEDGER_UNAVAILABLE",
                    new HtxTrendDashboardViews.Summary(0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
                    List.of(), List.of(), List.of());
        }
    }

    private HtxTrendDashboardViews.Summary summary(Map<String, Object> row) {
        return new HtxTrendDashboardViews.Summary(number(row, "totalPositions").longValue(), number(row, "activePositions").longValue(),
                number(row, "closedPositions").longValue(), number(row, "winningPositions").longValue(), decimal(row, "totalTurnover"),
                decimal(row, "totalFees"), decimal(row, "realizedProfit"), decimal(row, "netRealizedProfit"));
    }
    private HtxTrendDashboardViews.Position position(Map<String, Object> row) {
        return new HtxTrendDashboardViews.Position(number(row, "id").longValue(), text(row, "contractCode"), text(row, "status"), text(row, "side"),
                number(row, "contractVolume").longValue(), decimal(row, "entryAvgPrice"), decimal(row, "closeAvgPrice"), decimal(row, "entryFee"),
                decimal(row, "closeFee"), decimal(row, "realizedProfit"), decimal(row, "stopPrice"), decimal(row, "trailingStopPrice"),
                text(row, "entrySignal"), text(row, "exitReason"), text(row, "createdAt"), text(row, "updatedAt"));
    }
    private HtxTrendDashboardViews.DailyPnl daily(Map<String, Object> row) {
        return new HtxTrendDashboardViews.DailyPnl(text(row, "tradeDate"), number(row, "closedPositions").longValue(),
                decimal(row, "realizedProfit"), decimal(row, "fees"), decimal(row, "netProfit"));
    }
    private static Number number(Map<String, Object> row, String key) { Object value = row.get(key); return value instanceof Number n ? n : 0; }
    private static BigDecimal decimal(Map<String, Object> row, String key) { Object value = row.get(key); return value instanceof BigDecimal d ? d : value instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : BigDecimal.ZERO; }
    private static String text(Map<String, Object> row, String key) { Object value = row.get(key); return value == null ? null : value.toString(); }
}
