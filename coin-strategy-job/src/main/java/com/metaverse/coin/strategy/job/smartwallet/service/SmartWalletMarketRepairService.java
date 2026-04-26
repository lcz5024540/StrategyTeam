package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletClaimRetryMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFillMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletSettlementRetryMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEffectLabelMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletClaimRetryEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFillEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletSettlementRetryEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEffectLabelEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletMarketIdRepairRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletMarketIdRepairSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

@Service
@Slf4j
@RequiredArgsConstructor
public class SmartWalletMarketRepairService {

    private final SmartWalletPositionMapper positionMapper;
    private final SmartWalletOrderMapper orderMapper;
    private final SmartWalletFillMapper fillMapper;
    private final SmartWalletFollowTaskMapper followTaskMapper;
    private final SmartWalletTradeEventMapper tradeEventMapper;
    private final SmartWalletTradeEffectLabelMapper tradeEffectLabelMapper;
    private final SmartWalletSettlementRetryMapper settlementRetryMapper;
    private final SmartWalletClaimRetryMapper claimRetryMapper;
    private final SmartWalletMarketInfoService marketInfoService;
    private final PolymarketMarketMetadataService marketMetadataService;

    public SmartWalletMarketIdRepairSummary repairPositionMarketIds(SmartWalletMarketIdRepairRequest request) {
        List<Long> requestedPositionIds = normalizePositionIds(request);
        if (requestedPositionIds.isEmpty()) {
            return SmartWalletMarketIdRepairSummary.builder()
                    .requestedPositions(0)
                    .scannedPositions(0)
                    .repairedPositions(0)
                    .notes(List.of("No positionIds were provided for marketId repair."))
                    .build();
        }

        List<SmartWalletPositionEntity> positions = positionMapper.selectBatchIds(requestedPositionIds);
        if (positions == null) {
            positions = List.of();
        }
        marketInfoService.populatePositions(positions);

        List<Long> repairedPositionIds = new ArrayList<>();
        LinkedHashSet<String> repairedMarketIds = new LinkedHashSet<>();
        List<String> notes = new ArrayList<>();

        for (Long requestedPositionId : requestedPositionIds) {
            boolean found = positions.stream().anyMatch(item -> Objects.equals(item.getId(), requestedPositionId));
            if (!found) {
                notes.add("Skipped position " + requestedPositionId + " because it was not found.");
            }
        }

        for (SmartWalletPositionEntity position : positions) {
            if (position == null || position.getId() == null) {
                continue;
            }
            SmartWalletMarketInfoEntity currentMarketInfo = marketInfoService.getById(position.getMarketInfoId());
            if (currentMarketInfo == null || !StringUtils.hasText(currentMarketInfo.getMarketId())) {
                notes.add("Skipped position " + position.getId() + " because current market_info is missing.");
                continue;
            }

            String currentMarketId = currentMarketInfo.getMarketId().trim();
            String outcome = firstText(position.getOutcome(), currentMarketInfo.getOutcome());
            String question = firstText(position.getQuestion(), currentMarketInfo.getQuestion());
            String canonicalMarketId = resolveCanonicalMarketId(request, currentMarketId, question, outcome);
            if (!StringUtils.hasText(canonicalMarketId)) {
                notes.add("Skipped position " + position.getId() + " because canonical marketId could not be resolved.");
                continue;
            }
            canonicalMarketId = canonicalMarketId.trim();
            if (canonicalMarketId.equalsIgnoreCase(currentMarketId)) {
                notes.add("Position " + position.getId() + " already uses canonical marketId " + canonicalMarketId + ".");
                continue;
            }
            if (!StringUtils.hasText(outcome)) {
                notes.add("Skipped position " + position.getId() + " because outcome is missing.");
                continue;
            }

            SmartWalletMarketInfoEntity canonicalMarketInfo = marketInfoService.getOrCreate(
                    canonicalMarketId,
                    outcome,
                    question,
                    currentMarketInfo.getTokenId(),
                    currentMarketInfo.getIndexSet(),
                    currentMarketInfo.getMarketEndAt()
            );
            if (canonicalMarketInfo == null || canonicalMarketInfo.getId() == null) {
                notes.add("Skipped position " + position.getId() + " because canonical market_info could not be created.");
                continue;
            }

            relinkPosition(position, canonicalMarketInfo.getId());
            RepairLinks repairLinks = relinkOrdersAndFills(position.getId(), canonicalMarketInfo.getId());
            syncTradeEventMarketIds(repairLinks.sourceEventIds(), currentMarketId, canonicalMarketId);
            syncTradeEffectLabelMarketIds(repairLinks.sourceEventIds(), currentMarketId, canonicalMarketId);
            syncClaimRetryMarketIds(position.getId(), currentMarketId, canonicalMarketId);
            syncSettlementRetryMarketIds(currentMarketId, canonicalMarketId, question);

            repairedPositionIds.add(position.getId());
            repairedMarketIds.add(canonicalMarketId);
            notes.add("Repaired position " + position.getId()
                    + " marketId from " + currentMarketId
                    + " to " + canonicalMarketId + ".");
        }

        marketInfoService.clearCache();
        return SmartWalletMarketIdRepairSummary.builder()
                .requestedPositions(requestedPositionIds.size())
                .scannedPositions(positions.size())
                .repairedPositions(repairedPositionIds.size())
                .repairedPositionIds(repairedPositionIds)
                .repairedMarketIds(new ArrayList<>(repairedMarketIds))
                .notes(notes)
                .build();
    }

    private List<Long> normalizePositionIds(SmartWalletMarketIdRepairRequest request) {
        if (request == null || request.getPositionIds() == null) {
            return List.of();
        }
        LinkedHashSet<Long> normalized = new LinkedHashSet<>();
        for (Long positionId : request.getPositionIds()) {
            if (positionId != null && positionId > 0) {
                normalized.add(positionId);
            }
        }
        return new ArrayList<>(normalized);
    }

    private String resolveCanonicalMarketId(SmartWalletMarketIdRepairRequest request,
                                            String currentMarketId,
                                            String question,
                                            String outcome) {
        if (request != null && StringUtils.hasText(request.getCanonicalMarketId())) {
            return request.getCanonicalMarketId().trim();
        }
        List<String> outcomeHints = StringUtils.hasText(outcome) ? List.of(outcome) : List.of();
        return marketMetadataService.resolveCanonicalConditionId(currentMarketId, question, outcomeHints);
    }

    private void relinkPosition(SmartWalletPositionEntity position, Long canonicalMarketInfoId) {
        if (position == null || position.getId() == null || canonicalMarketInfoId == null) {
            return;
        }
        if (Objects.equals(position.getMarketInfoId(), canonicalMarketInfoId)) {
            return;
        }
        position.setMarketInfoId(canonicalMarketInfoId);
        int affected = positionMapper.updateById(position);
        if (affected == 0) {
            log.warn("[OptimisticLock] relinkPosition: position {} version conflict, will retry next repair cycle", position.getId());
        }
    }

    private RepairLinks relinkOrdersAndFills(Long positionId, Long canonicalMarketInfoId) {
        LinkedHashSet<Long> followTaskIds = new LinkedHashSet<>();
        LinkedHashSet<Long> sourceEventIds = new LinkedHashSet<>();

        List<SmartWalletOrderEntity> orders = orderMapper.selectList(new LambdaQueryWrapper<SmartWalletOrderEntity>()
                .eq(SmartWalletOrderEntity::getPositionId, positionId));
        for (SmartWalletOrderEntity order : orders) {
            if (order == null || order.getId() == null) {
                continue;
            }
            if (!Objects.equals(order.getMarketInfoId(), canonicalMarketInfoId)) {
                order.setMarketInfoId(canonicalMarketInfoId);
                int affected = orderMapper.updateById(order);
                if (affected == 0) {
                    log.warn("[OptimisticLock] relinkOrder: order {} version conflict", order.getId());
                }
            }
            if (order.getFollowTaskId() != null) {
                followTaskIds.add(order.getFollowTaskId());
            }
        }

        List<SmartWalletFillEntity> fills = fillMapper.selectList(new LambdaQueryWrapper<SmartWalletFillEntity>()
                .eq(SmartWalletFillEntity::getPositionId, positionId));
        for (SmartWalletFillEntity fill : fills) {
            if (fill == null || fill.getId() == null) {
                continue;
            }
            if (!Objects.equals(fill.getMarketInfoId(), canonicalMarketInfoId)) {
                fill.setMarketInfoId(canonicalMarketInfoId);
                fillMapper.updateById(fill);
            }
            if (fill.getFollowTaskId() != null) {
                followTaskIds.add(fill.getFollowTaskId());
            }
        }

        if (!followTaskIds.isEmpty()) {
            List<SmartWalletFollowTaskEntity> followTasks = followTaskMapper.selectBatchIds(followTaskIds);
            for (SmartWalletFollowTaskEntity followTask : followTasks) {
                if (followTask == null || followTask.getId() == null) {
                    continue;
                }
                if (!Objects.equals(followTask.getMarketInfoId(), canonicalMarketInfoId)) {
                    followTask.setMarketInfoId(canonicalMarketInfoId);
                    followTaskMapper.updateById(followTask);
                }
                if (followTask.getSourceEventId() != null) {
                    sourceEventIds.add(followTask.getSourceEventId());
                }
            }
        }

        return new RepairLinks(new ArrayList<>(followTaskIds), new ArrayList<>(sourceEventIds));
    }

    private void syncTradeEventMarketIds(List<Long> sourceEventIds, String currentMarketId, String canonicalMarketId) {
        if (sourceEventIds == null || sourceEventIds.isEmpty()) {
            return;
        }
        List<SmartWalletTradeEventEntity> events = tradeEventMapper.selectBatchIds(sourceEventIds);
        for (SmartWalletTradeEventEntity event : events) {
            if (event == null || event.getId() == null || !StringUtils.hasText(event.getMarketId())) {
                continue;
            }
            if (!currentMarketId.equalsIgnoreCase(event.getMarketId().trim())) {
                continue;
            }
            event.setMarketId(canonicalMarketId);
            tradeEventMapper.updateById(event);
        }
    }

    private void syncTradeEffectLabelMarketIds(List<Long> sourceEventIds, String currentMarketId, String canonicalMarketId) {
        if (sourceEventIds == null || sourceEventIds.isEmpty()) {
            return;
        }
        List<SmartWalletTradeEffectLabelEntity> labels = tradeEffectLabelMapper.selectList(
                new LambdaQueryWrapper<SmartWalletTradeEffectLabelEntity>()
                        .in(SmartWalletTradeEffectLabelEntity::getSourceEventId, sourceEventIds));
        for (SmartWalletTradeEffectLabelEntity label : labels) {
            if (label == null || label.getId() == null || !StringUtils.hasText(label.getMarketId())) {
                continue;
            }
            if (!currentMarketId.equalsIgnoreCase(label.getMarketId().trim())) {
                continue;
            }
            label.setMarketId(canonicalMarketId);
            tradeEffectLabelMapper.updateById(label);
        }
    }

    private void syncClaimRetryMarketIds(Long positionId, String currentMarketId, String canonicalMarketId) {
        List<SmartWalletClaimRetryEntity> claimRetries = claimRetryMapper.selectList(
                new LambdaQueryWrapper<SmartWalletClaimRetryEntity>()
                        .eq(SmartWalletClaimRetryEntity::getPositionId, positionId));
        for (SmartWalletClaimRetryEntity claimRetry : claimRetries) {
            if (claimRetry == null || claimRetry.getId() == null || !StringUtils.hasText(claimRetry.getMarketId())) {
                continue;
            }
            if (!currentMarketId.equalsIgnoreCase(claimRetry.getMarketId().trim())) {
                continue;
            }
            claimRetry.setMarketId(canonicalMarketId);
            claimRetryMapper.updateById(claimRetry);
        }
    }

    private void syncSettlementRetryMarketIds(String currentMarketId, String canonicalMarketId, String question) {
        LambdaQueryWrapper<SmartWalletSettlementRetryEntity> query = new LambdaQueryWrapper<SmartWalletSettlementRetryEntity>()
                .eq(SmartWalletSettlementRetryEntity::getMarketId, currentMarketId);
        if (StringUtils.hasText(question)) {
            query.eq(SmartWalletSettlementRetryEntity::getQuestion, question);
        }
        List<SmartWalletSettlementRetryEntity> settlementRetries = settlementRetryMapper.selectList(query);
        for (SmartWalletSettlementRetryEntity settlementRetry : settlementRetries) {
            if (settlementRetry == null || settlementRetry.getId() == null) {
                continue;
            }
            settlementRetry.setMarketId(canonicalMarketId);
            settlementRetryMapper.updateById(settlementRetry);
        }
    }

    private String firstText(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate)) {
                return candidate.trim();
            }
        }
        return null;
    }

    private record RepairLinks(List<Long> followTaskIds, List<Long> sourceEventIds) {
    }
}
