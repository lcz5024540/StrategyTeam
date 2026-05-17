package com.metaverse.coin.strategy.web.service.smartwallet;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.LiveReplayDiffHistoryMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.LiveReplayDiffHistoryEntity;
import com.metaverse.coin.strategy.web.model.smartwallet.LiveReplayDiffHistoryView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Web BFF 端 Live↔Replay diff 历史查询 (下一轮 #7).
 *
 * 直查 {@code live_replay_diff_history} 表, 支持:
 *   - latest: 取最新一行
 *   - history(limitDays): 按日期 desc 取近 N 天
 *
 * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十七节.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveReplayDiffHistoryReadService {

    private static final int DEFAULT_LIMIT = 30;
    private static final int MAX_LIMIT = 365;

    private final LiveReplayDiffHistoryMapper historyMapper;

    public LiveReplayDiffHistoryView latest() {
        LiveReplayDiffHistoryEntity row = historyMapper.selectOne(
                new LambdaQueryWrapper<LiveReplayDiffHistoryEntity>()
                        .orderByDesc(LiveReplayDiffHistoryEntity::getAsOfDate)
                        .last("limit 1"));
        return LiveReplayDiffHistoryView.from(row);
    }

    public List<LiveReplayDiffHistoryView> history(Integer limitDays) {
        int lim = limitDays == null || limitDays <= 0 ? DEFAULT_LIMIT : Math.min(limitDays, MAX_LIMIT);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        LocalDate since = today.minusDays(lim);
        List<LiveReplayDiffHistoryEntity> rows = historyMapper.selectList(
                new LambdaQueryWrapper<LiveReplayDiffHistoryEntity>()
                        .ge(LiveReplayDiffHistoryEntity::getAsOfDate, since)
                        .orderByDesc(LiveReplayDiffHistoryEntity::getAsOfDate)
                        .last("limit " + lim));
        return rows.stream().map(LiveReplayDiffHistoryView::from).collect(Collectors.toList());
    }
}
