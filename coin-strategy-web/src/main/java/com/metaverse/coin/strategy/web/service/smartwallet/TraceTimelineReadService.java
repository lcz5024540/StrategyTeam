package com.metaverse.coin.strategy.web.service.smartwallet;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletSignalShadowMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeOrderMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletSignalShadowEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeOrderEntity;
import com.metaverse.coin.strategy.web.model.smartwallet.TraceTimelineWebView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Web BFF 端 trace_id 反查 (W1-T3 + W1-T4 整合).
 *
 * 直查 DB, 与 job 端 TraceQueryService 算法对齐, 额外联表 smart_wallet_signal_shadow
 * 让运营能看到"该 trace 是否被风控拒绝并落了影子记账".
 *
 * 沿用 web 模块既有 ReadService 命名 / 注入 / Lombok 模板, 不引入 job 端任何依赖.
 *
 * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十一节.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TraceTimelineReadService {

    /** trace_id 前缀, 与 job 端 TraceIds 保持一致. */
    private static final String TRADE_EVENT_PREFIX = "tev_";
    private static final int TRADE_EVENT_TRACE_LENGTH = 16;

    private final SmartWalletTradeEventMapper tradeEventMapper;
    private final SmartWalletFollowTaskMapper followTaskMapper;
    private final SmartWalletOrderMapper orderMapper;
    private final SmartWalletTradeOrderMapper tradeOrderMapper;
    private final SmartWalletPositionMapper positionMapper;
    private final SmartWalletSignalShadowMapper shadowMapper;

    public TraceTimelineWebView queryByTraceId(String traceId) {
        if (!StringUtils.hasText(traceId)) {
            return TraceTimelineWebView.builder().traceId(traceId).found(false).build();
        }

        SmartWalletTradeEventEntity tradeEvent = findTradeEvent(traceId);

        List<SmartWalletFollowTaskEntity> followTasks = followTaskMapper.selectList(
                new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                        .eq(SmartWalletFollowTaskEntity::getTraceId, traceId)
                        .orderByAsc(SmartWalletFollowTaskEntity::getId));

        List<SmartWalletOrderEntity> orders = orderMapper.selectList(
                new LambdaQueryWrapper<SmartWalletOrderEntity>()
                        .eq(SmartWalletOrderEntity::getTraceId, traceId)
                        .orderByAsc(SmartWalletOrderEntity::getId));

        List<SmartWalletTradeOrderEntity> tradeOrders = tradeOrderMapper.selectList(
                new LambdaQueryWrapper<SmartWalletTradeOrderEntity>()
                        .eq(SmartWalletTradeOrderEntity::getTraceId, traceId)
                        .orderByAsc(SmartWalletTradeOrderEntity::getId));

        List<SmartWalletPositionEntity> positions = positionMapper.selectList(
                new LambdaQueryWrapper<SmartWalletPositionEntity>()
                        .eq(SmartWalletPositionEntity::getOriginTraceId, traceId)
                        .orderByAsc(SmartWalletPositionEntity::getId));

        List<SmartWalletSignalShadowEntity> shadowRecords = shadowMapper.selectList(
                new LambdaQueryWrapper<SmartWalletSignalShadowEntity>()
                        .eq(SmartWalletSignalShadowEntity::getTraceId, traceId)
                        .orderByAsc(SmartWalletSignalShadowEntity::getId));

        boolean found = tradeEvent != null
                || !followTasks.isEmpty()
                || !orders.isEmpty()
                || !tradeOrders.isEmpty()
                || !positions.isEmpty()
                || !shadowRecords.isEmpty();

        Date startedAt = tradeEvent != null ? tradeEvent.getOpenedAt() : earliestCreatedAt(followTasks);
        Date lastTouchedAt = latestUpdatedAt(tradeEvent, followTasks, orders, tradeOrders, positions, shadowRecords);

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

        return TraceTimelineWebView.builder()
                .traceId(traceId)
                .found(found)
                .tradeEvent(tradeEvent)
                .followTasks(followTasks)
                .orders(orders)
                .tradeOrders(tradeOrders)
                .positions(positions)
                .shadowRecords(shadowRecords)
                .startedAt(startedAt)
                .lastTouchedAt(lastTouchedAt)
                .eventToFirstTaskMs(eventToFirstTaskMs)
                .firstTaskToFirstOrderMs(firstTaskToFirstOrderMs)
                .firstOrderToFilledMs(firstOrderToFilledMs)
                .build();
    }

    private SmartWalletTradeEventEntity findTradeEvent(String traceId) {
        SmartWalletTradeEventEntity byTrace = tradeEventMapper.selectOne(
                new LambdaQueryWrapper<SmartWalletTradeEventEntity>()
                        .eq(SmartWalletTradeEventEntity::getTraceId, traceId)
                        .last("limit 1"));
        if (byTrace != null) return byTrace;
        Long derivedId = extractTradeEventId(traceId);
        if (derivedId == null) return null;
        return tradeEventMapper.selectById(derivedId);
    }

    /** 反解 trace_id 派生的 trade_event id, 与 job 端 TraceIds 算法一致. */
    private Long extractTradeEventId(String traceId) {
        if (traceId == null || traceId.length() != TRADE_EVENT_TRACE_LENGTH) return null;
        if (!traceId.startsWith(TRADE_EVENT_PREFIX)) return null;
        try {
            return Long.parseLong(traceId.substring(TRADE_EVENT_PREFIX.length()));
        } catch (NumberFormatException ex) {
            return null;
        }
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
        if (e instanceof SmartWalletSignalShadowEntity x) return x.getUpdatedAt();
        return null;
    }

    private Long diff(Date earlier, Date later) {
        if (earlier == null || later == null) return null;
        long ms = later.getTime() - earlier.getTime();
        return ms < 0 ? null : ms;
    }
}
