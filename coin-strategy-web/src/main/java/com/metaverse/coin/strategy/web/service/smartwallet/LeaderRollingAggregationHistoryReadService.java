package com.metaverse.coin.strategy.web.service.smartwallet;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.LeaderRollingAggregationDailyMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.LeaderRollingAggregationDailyEntity;
import com.metaverse.coin.strategy.web.model.smartwallet.LeaderRollingAggregationHistoryView;
import com.metaverse.coin.strategy.web.model.smartwallet.LeaderRollingAggregationHistoryView.Row;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Web BFF 端: W2-T2 灰度自动化 trend 视图 (第十八批).
 *
 * <p>直查 {@code leader_rolling_aggregation_daily} 表, 按 asOfDate desc 排序, 返回最近
 * N 天的 leader 滑动窗口胜率灰度健康历史. 支持 lookbackDays 过滤 (7 / 14 等).</p>
 *
 * <p>数据写入方: {@code LeaderRollingMonitorJob} 每日 09:30 Asia/Shanghai.</p>
 *
 * <p>详见 docs/polymarket/audit-observability/w2-t2-grayscale-sop.md.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaderRollingAggregationHistoryReadService {

    private static final int DEFAULT_HISTORY_DAYS = 30;
    private static final int MAX_HISTORY_DAYS = 180;

    private final LeaderRollingAggregationDailyMapper aggregationDailyMapper;

    public LeaderRollingAggregationHistoryView history(Integer historyDays, Integer lookbackDays) {
        int days = historyDays == null || historyDays <= 0
                ? DEFAULT_HISTORY_DAYS
                : Math.min(historyDays, MAX_HISTORY_DAYS);
        LocalDate today = LocalDate.now();
        LocalDate since = today.minusDays(days);

        LambdaQueryWrapper<LeaderRollingAggregationDailyEntity> q =
                new LambdaQueryWrapper<LeaderRollingAggregationDailyEntity>()
                        .ge(LeaderRollingAggregationDailyEntity::getAsOfDate, since)
                        .orderByDesc(LeaderRollingAggregationDailyEntity::getAsOfDate)
                        .orderByAsc(LeaderRollingAggregationDailyEntity::getLookbackDays);
        if (lookbackDays != null && lookbackDays > 0) {
            q.eq(LeaderRollingAggregationDailyEntity::getLookbackDays, lookbackDays);
        }
        List<LeaderRollingAggregationDailyEntity> records = aggregationDailyMapper.selectList(q);
        List<Row> rows = new ArrayList<>(records.size());
        String latestHealth = null;
        LocalDate latestDate = null;
        for (LeaderRollingAggregationDailyEntity e : records) {
            if (latestDate == null || e.getAsOfDate().isAfter(latestDate)) {
                latestDate = e.getAsOfDate();
                latestHealth = e.getHealthStatus();
            }
            rows.add(Row.builder()
                    .asOfDate(e.getAsOfDate())
                    .lookbackDays(e.getLookbackDays())
                    .windowSize(e.getWindowSize())
                    .minSamples(e.getMinSamples())
                    .minWinRate(e.getMinWinRate())
                    .activeLeaders(e.getActiveLeaders())
                    .evaluatedCount(e.getEvaluatedCount())
                    .activeCount(e.getActiveCount())
                    .coolingCount(e.getCoolingCount())
                    .coldCount(e.getColdCount())
                    .coolingRatio(e.getCoolingRatio())
                    .overallWinRate(e.getOverallWinRate())
                    .healthStatus(e.getHealthStatus())
                    .topCoolingLeadersJson(e.getTopCoolingLeadersJson())
                    .computedAt(e.getComputedAt())
                    .build());
        }

        return LeaderRollingAggregationHistoryView.builder()
                .generatedAt(new Date())
                .requestedLookbackDays(lookbackDays)
                .historyDays(days)
                .rowCount(rows.size())
                .latestHealthStatus(latestHealth)
                .latestAsOfDate(latestDate)
                .rows(rows)
                .build();
    }
}
