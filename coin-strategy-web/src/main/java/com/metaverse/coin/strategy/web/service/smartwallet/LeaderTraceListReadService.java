package com.metaverse.coin.strategy.web.service.smartwallet;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.web.model.smartwallet.LeaderTraceListView;
import com.metaverse.coin.strategy.web.model.smartwallet.LeaderTraceListView.TraceItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Web BFF 端 第六波 web 接口: 按 leader 反查全部 trace_ids.
 *
 * <p>面向场景: 运维 / 量化研究员定位 "某 leader 最近 N 天落了哪些 trace_id, 是否都进入了
 * follow 链路 (processed=true)". 前端可以拿到 traceId 后再调 /trace/{traceId} 拿全链路详情.</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第二十节.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaderTraceListReadService {

    private static final int DEFAULT_LOOKBACK_DAYS = 7;
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 1000;

    private final SmartWalletTradeEventMapper tradeEventMapper;

    public LeaderTraceListView query(String leaderAddress, Integer lookbackDays, Integer limit) {
        if (!StringUtils.hasText(leaderAddress)) {
            return LeaderTraceListView.builder()
                    .leaderAddress(leaderAddress)
                    .lookbackDays(0)
                    .totalEvents(0)
                    .returnedEvents(0)
                    .truncated(false)
                    .traces(List.of())
                    .build();
        }
        int days = lookbackDays != null && lookbackDays > 0 ? lookbackDays : DEFAULT_LOOKBACK_DAYS;
        int lim = limit != null && limit > 0 ? Math.min(limit, MAX_LIMIT) : DEFAULT_LIMIT;

        String addr = leaderAddress.trim().toLowerCase();
        Date since = new Date(System.currentTimeMillis() - days * 86_400_000L);

        // 先取最近 lim + 1 条用于判断是否 truncated
        List<SmartWalletTradeEventEntity> events = tradeEventMapper.selectList(
                new LambdaQueryWrapper<SmartWalletTradeEventEntity>()
                        .eq(SmartWalletTradeEventEntity::getWalletAddress, addr)
                        .ge(SmartWalletTradeEventEntity::getCreatedAt, since)
                        .orderByDesc(SmartWalletTradeEventEntity::getCreatedAt)
                        .last("limit " + (lim + 1)));

        boolean truncated = events.size() > lim;
        if (truncated) {
            events = events.subList(0, lim);
        }

        List<TraceItem> items = events.stream().map(e -> TraceItem.builder()
                .tradeEventId(e.getId())
                .traceId(e.getTraceId())
                .marketId(e.getMarketId())
                .question(e.getQuestion())
                .outcome(e.getOutcome())
                .side(e.getSide())
                .price(e.getPrice())
                .sizeUsdc(e.getSizeUsdc())
                .openedAt(e.getOpenedAt())
                .processed(e.getProcessed())
                .build()).collect(Collectors.toList());

        return LeaderTraceListView.builder()
                .leaderAddress(addr)
                .lookbackDays(days)
                .totalEvents(items.size())
                .returnedEvents(items.size())
                .truncated(truncated)
                .traces(items)
                .build();
    }
}
