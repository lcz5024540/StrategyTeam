package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeOrderMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeOrderEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.TraceTimelineView;
import com.metaverse.coin.strategy.common.service.trace.TraceIds;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 按 trace_id 反查全链路时间线 (W1-T3).
 * 用于:
 *   - 线上问题定位 (一笔 follow_task 为什么没下单 / 没成交)
 *   - W1-T1 Live/Replay diff 报表的逐 trace 详情
 *   - W1-T4 影子记账与真实成交对账
 *
 * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TraceQueryService {

    private final SmartWalletTradeEventMapper tradeEventMapper;
    private final SmartWalletFollowTaskMapper followTaskMapper;
    private final SmartWalletOrderMapper orderMapper;
    private final SmartWalletTradeOrderMapper tradeOrderMapper;
    private final SmartWalletPositionMapper positionMapper;

    /**
     * 反查 trace_id 对应的全链路, 若任何表都没记录返回 found=false.
     */
    public TraceTimelineView queryByTraceId(String traceId) {
        if (!StringUtils.hasText(traceId)) {
            return TraceTimelineView.builder().traceId(traceId).found(false).build();
        }

        // 1) trade_event (优先 trace_id 唯一查; 兼容旧数据用派生 id 兜底)
        SmartWalletTradeEventEntity tradeEvent = findTradeEvent(traceId);

        // 2) follow_task (intent 聚合下可能多行)
        List<SmartWalletFollowTaskEntity> followTasks = followTaskMapper.selectList(
                new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                        .eq(SmartWalletFollowTaskEntity::getTraceId, traceId)
                        .orderByAsc(SmartWalletFollowTaskEntity::getId));

        // 3) order (拆单 / 重发 / exit 可能多行)
        List<SmartWalletOrderEntity> orders = orderMapper.selectList(
                new LambdaQueryWrapper<SmartWalletOrderEntity>()
                        .eq(SmartWalletOrderEntity::getTraceId, traceId)
                        .orderByAsc(SmartWalletOrderEntity::getId));

        // 4) trade_order (聚合后的入/出场)
        List<SmartWalletTradeOrderEntity> tradeOrders = tradeOrderMapper.selectList(
                new LambdaQueryWrapper<SmartWalletTradeOrderEntity>()
                        .eq(SmartWalletTradeOrderEntity::getTraceId, traceId)
                        .orderByAsc(SmartWalletTradeOrderEntity::getId));

        // 5) position (origin_trace_id 匹配; 仅 trace_id 对应的开仓那个仓位)
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(
                new LambdaQueryWrapper<SmartWalletPositionEntity>()
                        .eq(SmartWalletPositionEntity::getOriginTraceId, traceId)
                        .orderByAsc(SmartWalletPositionEntity::getId));

        boolean found = tradeEvent != null
                || !followTasks.isEmpty()
                || !orders.isEmpty()
                || !tradeOrders.isEmpty()
                || !positions.isEmpty();

        Date startedAt = tradeEvent != null ? tradeEvent.getOpenedAt() : earliestCreatedAt(followTasks);
        Date lastTouchedAt = latestUpdatedAt(tradeEvent, followTasks, orders, tradeOrders, positions);

        // 关键延迟 (best-effort, 任一节点缺失则字段为 null)
        Long eventToFirstTaskMs = diff(
                tradeEvent != null ? tradeEvent.getCreatedAt() : null,
                followTasks.stream().map(SmartWalletFollowTaskEntity::getCreatedAt).filter(Objects::nonNull)
                        .min(Comparator.naturalOrder()).orElse(null));
        Long firstTaskToFirstOrderMs = diff(
                followTasks.stream().map(SmartWalletFollowTaskEntity::getCreatedAt).filter(Objects::nonNull)
                        .min(Comparator.naturalOrder()).orElse(null),
                orders.stream().map(SmartWalletOrderEntity::getPlacedAt).filter(Objects::nonNull)
                        .min(Comparator.naturalOrder()).orElse(null));
        Long firstOrderToFilledMs = diff(
                orders.stream().map(SmartWalletOrderEntity::getPlacedAt).filter(Objects::nonNull)
                        .min(Comparator.naturalOrder()).orElse(null),
                orders.stream().map(SmartWalletOrderEntity::getFilledAt).filter(Objects::nonNull)
                        .min(Comparator.naturalOrder()).orElse(null));

        return TraceTimelineView.builder()
                .traceId(traceId)
                .found(found)
                .tradeEvent(tradeEvent)
                .followTasks(followTasks)
                .orders(orders)
                .tradeOrders(tradeOrders)
                .positions(positions)
                .startedAt(startedAt)
                .lastTouchedAt(lastTouchedAt)
                .eventToFirstTaskMs(eventToFirstTaskMs)
                .firstTaskToFirstOrderMs(firstTaskToFirstOrderMs)
                .firstOrderToFilledMs(firstOrderToFilledMs)
                .build();
    }

    /**
     * 兼容旧数据: 优先按 trace_id 查; 若失败但 trace_id 形如 "tev_xxx", 用派生 id 兜底查 trade_event.
     */
    private SmartWalletTradeEventEntity findTradeEvent(String traceId) {
        SmartWalletTradeEventEntity byTrace = tradeEventMapper.selectOne(
                new LambdaQueryWrapper<SmartWalletTradeEventEntity>()
                        .eq(SmartWalletTradeEventEntity::getTraceId, traceId)
                        .last("limit 1"));
        if (byTrace != null) return byTrace;
        Long derivedId = TraceIds.extractTradeEventId(traceId);
        if (derivedId == null) return null;
        return tradeEventMapper.selectById(derivedId);
    }

    private Date earliestCreatedAt(List<SmartWalletFollowTaskEntity> tasks) {
        return tasks.stream()
                .map(SmartWalletFollowTaskEntity::getCreatedAt)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    @SafeVarargs
    private final Date latestUpdatedAt(SmartWalletTradeEventEntity tradeEvent,
                                       List<? extends Object>... lists) {
        Stream<Date> head = tradeEvent == null
                ? Stream.empty()
                : Stream.of(tradeEvent.getUpdatedAt());
        Stream<Date> tail = Stream.of(lists)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .map(this::extractUpdatedAt);
        return Stream.concat(head, tail)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    private Date extractUpdatedAt(Object e) {
        if (e instanceof SmartWalletFollowTaskEntity x)  return x.getUpdatedAt();
        if (e instanceof SmartWalletOrderEntity x)       return x.getUpdatedAt();
        if (e instanceof SmartWalletTradeOrderEntity x)  return x.getUpdatedAt();
        if (e instanceof SmartWalletPositionEntity x)    return x.getUpdatedAt();
        return null;
    }

    private Long diff(Date earlier, Date later) {
        if (earlier == null || later == null) return null;
        long ms = later.getTime() - earlier.getTime();
        return ms < 0 ? null : ms;
    }

    /** 测试用 / 工具用: 让外部能直接拿到 mappers (谨慎使用). */
    public List<SmartWalletFollowTaskEntity> emptyTasksForTest() { return Collections.emptyList(); }
}
