package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.AbnormalMarketManualReviewRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.AbnormalMarketManualReviewResult;
import com.metaverse.coin.strategy.job.smartwallet.model.OrderDiagnosticReportView;
import com.metaverse.coin.strategy.job.smartwallet.model.OrderDiagnosticView;
import com.metaverse.coin.strategy.job.smartwallet.model.OrderSimilarTradeEventView;
import com.metaverse.coin.strategy.job.smartwallet.model.PositionMarketDiagnosticView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class AbnormalMarketReviewService {

    public static final String ORDER_STATUS_MARKET_METADATA_INVALID = "MARKET_METADATA_INVALID";
    public static final String POSITION_STATUS_MARKET_METADATA_INVALID = "MARKET_METADATA_INVALID";

    private static final String ARCHIVE_ORDER_TABLE = "smart_wallet_order_archive_reset_bak";
    private static final int SIMILAR_EVENT_LIMIT = 20;

    private final SmartWalletOrderMapper orderMapper;
    private final SmartWalletPositionMapper positionMapper;
    private final SmartWalletFollowTaskMapper followTaskMapper;
    private final SmartWalletTradeEventMapper tradeEventMapper;
    private final SmartWalletMarketInfoService marketInfoService;
    private final PolymarketMarketMetadataService marketMetadataService;
    private final JdbcTemplate jdbcTemplate;

    public OrderDiagnosticReportView diagnoseOrders(List<Long> ids, String question, String outcome, BigDecimal price) {
        List<Long> requestedIds = normalizeIds(ids);
        Long minId = queryLong("SELECT MIN(id) FROM smart_wallet_order");
        Long maxId = queryLong("SELECT MAX(id) FROM smart_wallet_order");
        boolean archivePresent = tableExists(ARCHIVE_ORDER_TABLE);

        List<OrderSimilarTradeEventView> fallbackEvents = findSimilarTradeEvents(question, outcome, price);
        List<OrderDiagnosticView> rows = new ArrayList<>();
        for (Long orderId : requestedIds) {
            OrderDiagnosticView current = loadCurrentOrder(orderId);
            if (current != null) {
                rows.add(current);
                continue;
            }
            OrderDiagnosticView archive = archivePresent ? loadArchivedOrder(orderId) : null;
            if (archive != null) {
                rows.add(archive);
                continue;
            }
            rows.add(OrderDiagnosticView.builder()
                    .orderId(orderId)
                    .lookupStatus("MISSING_FROM_CURRENT_DB")
                    .similarTradeEvents(fallbackEvents)
                    .build());
        }

        return OrderDiagnosticReportView.builder()
                .requestedOrderIds(requestedIds)
                .currentMinOrderId(minId)
                .currentMaxOrderId(maxId)
                .archiveTablePresent(archivePresent)
                .orders(rows)
                .build();
    }

    public List<PositionMarketDiagnosticView> diagnosePositions(List<Long> ids, List<String> eventUrlPairs) {
        List<Long> requestedIds = normalizeIds(ids);
        Map<Long, String> eventUrlsByPosition = parsePositionEventUrls(eventUrlPairs);
        List<PositionMarketDiagnosticView> rows = new ArrayList<>();
        for (Long positionId : requestedIds) {
            rows.add(diagnosePosition(positionId, eventUrlsByPosition.get(positionId)));
        }
        return rows;
    }

    public AbnormalMarketManualReviewResult review(AbnormalMarketManualReviewRequest request) {
        if (request == null || !StringUtils.hasText(request.getAction())) {
            throw new IllegalArgumentException("manual review action is required.");
        }
        String action = request.getAction().trim().toUpperCase(Locale.ROOT);
        List<SmartWalletOrderEntity> orders = loadTargetOrders(request);
        List<String> notes = new ArrayList<>();
        if (orders.isEmpty()) {
            return AbnormalMarketManualReviewResult.builder()
                    .action(action)
                    .scannedOrders(0)
                    .updatedOrders(0)
                    .updatedPositions(0)
                    .orderIds(List.of())
                    .positionIds(List.of())
                    .notes(List.of("No matching orders were found for manual review."))
                    .build();
        }
        marketInfoService.populateOrders(orders);
        return switch (action) {
            case "CONFIRM_CANONICAL_MARKET" -> confirmCanonicalMarket(orders, request, notes);
            case "CONFIRM_EVENT_MARKET" -> confirmEventMarket(orders, request, notes);
            case "MARK_INVALID_METADATA" -> markInvalidMetadata(orders, request, notes);
            case "KEEP_PENDING_ORACLE" -> keepPendingOracle(orders, request, notes);
            default -> throw new IllegalArgumentException("Unsupported manual review action: " + request.getAction());
        };
    }

    public static String abnormalMarketReason(String tokenId,
                                              String marketSlug,
                                              String lifecycle,
                                              Boolean closed,
                                              Boolean acceptingOrders) {
        boolean missingToken = !StringUtils.hasText(tokenId);
        boolean missingSlug = !StringUtils.hasText(marketSlug);
        boolean closedUnresolved = SmartWalletMarketStateService.LIFECYCLE_CLOSED_UNRESOLVED.equalsIgnoreCase(lifecycle)
                || Boolean.TRUE.equals(closed)
                || Boolean.FALSE.equals(acceptingOrders);
        if (missingToken && missingSlug && closedUnresolved) {
            return "MANUAL_REVIEW_REQUIRED";
        }
        if (missingToken || missingSlug) {
            return "METADATA_MISSING";
        }
        return null;
    }

    private AbnormalMarketManualReviewResult confirmCanonicalMarket(List<SmartWalletOrderEntity> orders,
                                                                    AbnormalMarketManualReviewRequest request,
                                                                    List<String> notes) {
        if (!StringUtils.hasText(request.getCanonicalMarketId())) {
            throw new IllegalArgumentException("canonicalMarketId is required for CONFIRM_CANONICAL_MARKET.");
        }
        String canonicalMarketId = request.getCanonicalMarketId().trim();
        PolymarketApiClient.MarketMetadata metadata = marketMetadataService.getByConditionId(canonicalMarketId, true);
        if (metadata == null || metadata.getOutcomeTokenIds() == null || metadata.getOutcomeTokenIds().isEmpty()) {
            throw new IllegalStateException("Canonical market metadata must include outcome token ids.");
        }
        if (!canonicalMarketId.equalsIgnoreCase(metadata.getConditionId())) {
            throw new IllegalStateException("Canonical conditionId mismatch. requested=" + canonicalMarketId
                    + ", metadata=" + metadata.getConditionId());
        }

        int updatedOrders = 0;
        int updatedPositions = 0;
        Set<Long> positionIds = new LinkedHashSet<>();
        for (SmartWalletOrderEntity order : orders) {
            String tokenId = tokenForOutcome(metadata, order.getOutcome());
            if (!StringUtils.hasText(tokenId)) {
                notes.add("Skipped order " + order.getId() + " because outcome " + order.getOutcome()
                        + " is not present on canonical market " + canonicalMarketId + ".");
                continue;
            }
            Integer indexSet = metadata.resolveIndexSet(tokenId);
            SmartWalletMarketInfoEntity canonical = marketInfoService.getOrCreate(
                    canonicalMarketId,
                    order.getOutcome(),
                    metadata.getQuestion(),
                    tokenId,
                    indexSet,
                    metadata.getEndAt()
            );
            if (canonical.getId() == null) {
                notes.add("Skipped order " + order.getId() + " because canonical market_info could not be created.");
                continue;
            }
            order.setMarketInfoId(canonical.getId());
            appendOrderReviewNote(order, request, "CONFIRM_CANONICAL_MARKET");
            if (orderMapper.updateById(order) > 0) {
                updatedOrders++;
            }
            if (order.getPositionId() != null) {
                positionIds.add(order.getPositionId());
                SmartWalletPositionEntity position = positionMapper.selectById(order.getPositionId());
                if (position != null && !Objects.equals(position.getMarketInfoId(), canonical.getId())) {
                    position.setMarketInfoId(canonical.getId());
                    if (positionMapper.updateById(position) > 0) {
                        updatedPositions++;
                    }
                }
            }
            notes.add("Confirmed order " + order.getId() + " to canonical marketId=" + canonicalMarketId + ".");
        }
        marketInfoService.clearCache();
        return result("CONFIRM_CANONICAL_MARKET", orders, updatedOrders, updatedPositions,
                canonicalMarketId, null, null, new ArrayList<>(positionIds), notes);
    }

    private AbnormalMarketManualReviewResult confirmEventMarket(List<SmartWalletOrderEntity> orders,
                                                                AbnormalMarketManualReviewRequest request,
                                                                List<String> notes) {
        if (!StringUtils.hasText(request.getCanonicalMarketId())) {
            throw new IllegalArgumentException("canonicalMarketId is required for CONFIRM_EVENT_MARKET.");
        }
        if (!StringUtils.hasText(request.getEventUrl())) {
            throw new IllegalArgumentException("eventUrl is required for CONFIRM_EVENT_MARKET.");
        }
        if (!StringUtils.hasText(request.getTokenId())) {
            throw new IllegalArgumentException("tokenId is required for CONFIRM_EVENT_MARKET.");
        }
        String canonicalMarketId = request.getCanonicalMarketId().trim();
        String eventSlug = extractEventSlug(request.getEventUrl());
        if (!StringUtils.hasText(eventSlug)) {
            throw new IllegalArgumentException("eventUrl must contain a Polymarket event slug.");
        }
        PolymarketApiClient.MarketMetadata metadata =
                marketMetadataService.findEventMarketBySlugAndConditionId(eventSlug, canonicalMarketId);
        if (metadata == null) {
            throw new IllegalStateException("No exact conditionId match under event slug=" + eventSlug
                    + " for canonicalMarketId=" + canonicalMarketId + ".");
        }
        if (metadata.getOutcomeTokenIds() == null || metadata.getOutcomeTokenIds().isEmpty()) {
            throw new IllegalStateException("Event market metadata must include outcome token ids.");
        }

        String requestedTokenId = request.getTokenId().trim();
        int updatedOrders = 0;
        int updatedPositions = 0;
        Set<Long> positionIds = new LinkedHashSet<>();
        Set<Long> marketInfoIds = new LinkedHashSet<>();
        for (SmartWalletOrderEntity order : orders) {
            if (!canonicalMarketId.equalsIgnoreCase(order.getMarketId())) {
                throw new IllegalStateException("Order " + order.getId() + " currently points to marketId="
                        + order.getMarketId() + ", refusing to remap to " + canonicalMarketId + ".");
            }
            SmartWalletTradeEventEntity sourceEvent = sourceEventForOrder(order);
            if (StringUtils.hasText(request.getExternalTradeId())) {
                String actualTradeId = sourceEvent == null ? null : sourceEvent.getExternalTradeId();
                if (!request.getExternalTradeId().trim().equalsIgnoreCase(actualTradeId == null ? "" : actualTradeId.trim())) {
                    throw new IllegalStateException("Order " + order.getId() + " source trade mismatch. expected="
                            + request.getExternalTradeId() + ", actual=" + actualTradeId + ".");
                }
            }
            String outcomeTokenId = tokenForOutcome(metadata, order.getOutcome());
            if (!requestedTokenId.equals(outcomeTokenId)) {
                throw new IllegalStateException("Order " + order.getId() + " outcome token mismatch. outcome="
                        + order.getOutcome() + ", expectedToken=" + requestedTokenId + ", metadataToken=" + outcomeTokenId + ".");
            }
            SmartWalletMarketInfoEntity marketInfo = order.getMarketInfoId() == null ? null : marketInfoService.getById(order.getMarketInfoId());
            if (marketInfo == null) {
                marketInfo = marketInfoService.getOrCreate(canonicalMarketId, order.getOutcome(), metadata.getQuestion(),
                        outcomeTokenId, metadata.resolveIndexSet(outcomeTokenId), metadata.getEndAt());
                order.setMarketInfoId(marketInfo.getId());
            }
            applyMetadata(marketInfo, metadata, outcomeTokenId);
            marketInfoService.update(marketInfo);
            marketInfoIds.add(marketInfo.getId());
            appendOrderReviewNote(order, request, "CONFIRM_EVENT_MARKET");
            if (orderMapper.updateById(order) > 0) {
                updatedOrders++;
            }
            if (order.getPositionId() != null && positionIds.add(order.getPositionId())) {
                SmartWalletPositionEntity position = positionMapper.selectById(order.getPositionId());
                if (position != null && !Objects.equals(position.getMarketInfoId(), marketInfo.getId())) {
                    position.setMarketInfoId(marketInfo.getId());
                    if (positionMapper.updateById(position) > 0) {
                        updatedPositions++;
                    }
                }
            }
            notes.add("Confirmed order " + order.getId() + " against event slug=" + eventSlug
                    + ", conditionId=" + canonicalMarketId + ", tokenId=" + requestedTokenId + ".");
        }
        marketInfoService.clearCache();
        notes.add("Updated marketInfoIds=" + marketInfoIds + ". Run settlement sync after metadata repair.");
        return result("CONFIRM_EVENT_MARKET", orders, updatedOrders, updatedPositions,
                canonicalMarketId, eventSlug, requestedTokenId, new ArrayList<>(positionIds), notes);
    }

    private AbnormalMarketManualReviewResult markInvalidMetadata(List<SmartWalletOrderEntity> orders,
                                                                 AbnormalMarketManualReviewRequest request,
                                                                 List<String> notes) {
        int updatedOrders = 0;
        int updatedPositions = 0;
        Set<Long> positionIds = new LinkedHashSet<>();
        for (SmartWalletOrderEntity order : orders) {
            order.setStatus(ORDER_STATUS_MARKET_METADATA_INVALID);
            appendOrderReviewNote(order, request, "MARK_INVALID_METADATA");
            if (orderMapper.updateById(order) > 0) {
                updatedOrders++;
            }
            if (order.getPositionId() != null && positionIds.add(order.getPositionId())) {
                SmartWalletPositionEntity position = positionMapper.selectById(order.getPositionId());
                if (position != null) {
                    position.setStatus(POSITION_STATUS_MARKET_METADATA_INVALID);
                    if (positionMapper.updateById(position) > 0) {
                        updatedPositions++;
                    }
                }
            }
            notes.add("Marked order " + order.getId() + " as MARKET_METADATA_INVALID.");
        }
        return result("MARK_INVALID_METADATA", orders, updatedOrders, updatedPositions,
                null, null, null, new ArrayList<>(positionIds), notes);
    }

    private AbnormalMarketManualReviewResult keepPendingOracle(List<SmartWalletOrderEntity> orders,
                                                               AbnormalMarketManualReviewRequest request,
                                                               List<String> notes) {
        int updatedOrders = 0;
        for (SmartWalletOrderEntity order : orders) {
            order.setStatus("SETTLEMENT_PENDING_ORACLE");
            appendOrderReviewNote(order, request, "KEEP_PENDING_ORACLE");
            if (orderMapper.updateById(order) > 0) {
                updatedOrders++;
            }
            notes.add("Kept order " + order.getId() + " in SETTLEMENT_PENDING_ORACLE.");
        }
        return result("KEEP_PENDING_ORACLE", orders, updatedOrders, 0, null, null, null, List.of(), notes);
    }

    private AbnormalMarketManualReviewResult result(String action,
                                                    List<SmartWalletOrderEntity> orders,
                                                    int updatedOrders,
                                                    int updatedPositions,
                                                    String canonicalMarketId,
                                                    String eventSlug,
                                                    String tokenId,
                                                    List<Long> positionIds,
                                                    List<String> notes) {
        return AbnormalMarketManualReviewResult.builder()
                .action(action)
                .scannedOrders(orders.size())
                .updatedOrders(updatedOrders)
                .updatedPositions(updatedPositions)
                .canonicalMarketId(canonicalMarketId)
                .eventSlug(eventSlug)
                .tokenId(tokenId)
                .orderIds(orders.stream().map(SmartWalletOrderEntity::getId).toList())
                .positionIds(positionIds)
                .notes(notes)
                .build();
    }

    private List<SmartWalletOrderEntity> loadTargetOrders(AbnormalMarketManualReviewRequest request) {
        List<Long> orderIds = normalizeIds(request.getOrderIds());
        List<Long> positionIds = normalizeIds(request.getPositionIds());
        LambdaQueryWrapper<SmartWalletOrderEntity> wrapper = new LambdaQueryWrapper<>();
        if (!orderIds.isEmpty()) {
            wrapper.in(SmartWalletOrderEntity::getId, orderIds);
        } else if (!positionIds.isEmpty()) {
            wrapper.in(SmartWalletOrderEntity::getPositionId, positionIds)
                    .eq(SmartWalletOrderEntity::getSide, "BUY");
        } else if (StringUtils.hasText(request.getMarketId())) {
            wrapper.apply("market_info_id IN (SELECT id FROM smart_wallet_market_info WHERE market_id = {0})",
                    request.getMarketId().trim());
        } else {
            throw new IllegalArgumentException("orderIds, positionIds or marketId is required for manual review.");
        }
        return orderMapper.selectList(wrapper);
    }

    private void appendOrderReviewNote(SmartWalletOrderEntity order,
                                       AbnormalMarketManualReviewRequest request,
                                       String action) {
        String reviewNote = "[manualReview action=" + action
                + ", operator=" + safeText(request.getOperator())
                + ", externalTradeId=" + safeText(request.getExternalTradeId())
                + ", evidenceUrl=" + safeText(request.getEvidenceUrl())
                + ", note=" + safeText(request.getNote()) + "]";
        String existing = order.getErrorMessage();
        order.setErrorMessage(StringUtils.hasText(existing) ? existing + " " + reviewNote : reviewNote);
    }

    private String tokenForOutcome(PolymarketApiClient.MarketMetadata metadata, String outcome) {
        if (metadata == null || metadata.getOutcomeTokenIds() == null || !StringUtils.hasText(outcome)) {
            return null;
        }
        String normalized = outcome.trim();
        return metadata.getOutcomeTokenIds().entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getKey().trim().equalsIgnoreCase(normalized))
                .map(Map.Entry::getValue)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
    }

    private PositionMarketDiagnosticView diagnosePosition(Long positionId, String userProvidedUrl) {
        SmartWalletPositionEntity position = positionMapper.selectById(positionId);
        if (position == null) {
            return PositionMarketDiagnosticView.builder()
                    .positionId(positionId)
                    .lookupStatus("MISSING_POSITION")
                    .userProvidedUrl(userProvidedUrl)
                    .userProvidedEventSlug(extractEventSlug(userProvidedUrl))
                    .diagnosticNote("Position was not found in current database.")
                    .build();
        }
        marketInfoService.populate(position);
        SmartWalletOrderEntity order = orderMapper.selectOne(new LambdaQueryWrapper<SmartWalletOrderEntity>()
                .eq(SmartWalletOrderEntity::getPositionId, positionId)
                .eq(SmartWalletOrderEntity::getSide, "BUY")
                .orderByAsc(SmartWalletOrderEntity::getPlacedAt)
                .last("limit 1"));
        if (order != null) {
            marketInfoService.populate(order);
        }
        SmartWalletTradeEventEntity event = order == null ? null : sourceEventForOrder(order);
        String marketId = firstNonBlank(position.getMarketId(), order == null ? null : order.getMarketId());
        String outcome = firstNonBlank(position.getOutcome(), order == null ? null : order.getOutcome());
        String question = firstNonBlank(position.getQuestion(), order == null ? null : order.getQuestion());
        String eventSlug = extractEventSlug(userProvidedUrl);
        PolymarketApiClient.MarketMetadata eventMetadata = null;
        String eventTokenId = null;
        boolean exactConditionMatched = false;
        boolean outcomeTokenMatched = false;
        String diagnosticNote = null;
        if (StringUtils.hasText(eventSlug) && StringUtils.hasText(marketId)) {
            try {
                eventMetadata = marketMetadataService.findEventMarketBySlugAndConditionId(eventSlug, marketId);
                exactConditionMatched = eventMetadata != null;
                eventTokenId = tokenForOutcome(eventMetadata, outcome);
                outcomeTokenMatched = StringUtils.hasText(eventTokenId);
                if (!exactConditionMatched) {
                    diagnosticNote = "No exact conditionId match under user-provided event slug.";
                } else if (!outcomeTokenMatched) {
                    diagnosticNote = "Event conditionId matched, but outcome token was not found for outcome=" + outcome + ".";
                } else {
                    diagnosticNote = "Event conditionId and outcome token can be used for manual metadata repair.";
                }
            } catch (RuntimeException ex) {
                diagnosticNote = "Event metadata lookup failed: " + ex.getMessage();
            }
        }
        String tokenId = firstNonBlank(position.getTokenId(), order == null ? null : order.getTokenId());
        String marketSlug = firstNonBlank(position.getMarketInfo() == null ? null : position.getMarketInfo().getMarketSlug(),
                order == null ? null : order.getMarketSlug());
        String lifecycle = firstNonBlank(position.getMarketLifecycle(), order == null ? null : order.getMarketLifecycle());
        Boolean closed = position.getClosed() != null ? position.getClosed() : order == null ? null : order.getClosed();
        Boolean acceptingOrders = position.getAcceptingOrders() != null ? position.getAcceptingOrders() : order == null ? null : order.getAcceptingOrders();
        return PositionMarketDiagnosticView.builder()
                .positionId(positionId)
                .orderId(order == null ? null : order.getId())
                .lookupStatus("FOUND_CURRENT")
                .userProvidedUrl(userProvidedUrl)
                .userProvidedEventSlug(eventSlug)
                .marketInfoId(position.getMarketInfoId())
                .positionStatus(position.getStatus())
                .orderStatus(order == null ? null : order.getStatus())
                .marketId(marketId)
                .question(question)
                .outcome(outcome)
                .marketSlug(marketSlug)
                .tokenId(tokenId)
                .indexSet(position.getIndexSet())
                .marketEndAt(position.getMarketEndAt())
                .marketLifecycle(lifecycle)
                .closed(closed)
                .acceptingOrders(acceptingOrders)
                .resolvedOutcome(position.getResolvedOutcome())
                .abnormalMarketReason(abnormalMarketReason(tokenId, marketSlug, lifecycle, closed, acceptingOrders))
                .followTaskId(order == null ? null : order.getFollowTaskId())
                .sourceEventId(event == null ? null : event.getId())
                .sourceWalletAddress(event == null ? null : event.getWalletAddress())
                .externalTradeId(event == null ? null : event.getExternalTradeId())
                .eventSide(event == null ? null : event.getSide())
                .eventPrice(event == null ? null : event.getPrice())
                .eventSizeUsdc(event == null ? null : event.getSizeUsdc())
                .eventOpenedAt(event == null ? null : event.getOpenedAt())
                .eventExactConditionMatched(exactConditionMatched)
                .eventOutcomeTokenMatched(outcomeTokenMatched)
                .eventMatchedTokenId(eventTokenId)
                .eventMatchedQuestion(eventMetadata == null ? null : eventMetadata.getQuestion())
                .diagnosticNote(diagnosticNote)
                .build();
    }

    private SmartWalletTradeEventEntity sourceEventForOrder(SmartWalletOrderEntity order) {
        if (order == null || order.getFollowTaskId() == null) {
            return null;
        }
        SmartWalletFollowTaskEntity task = followTaskMapper.selectById(order.getFollowTaskId());
        if (task == null || task.getSourceEventId() == null) {
            return null;
        }
        return tradeEventMapper.selectById(task.getSourceEventId());
    }

    private void applyMetadata(SmartWalletMarketInfoEntity marketInfo,
                               PolymarketApiClient.MarketMetadata metadata,
                               String tokenId) {
        marketInfo.setQuestion(firstNonBlank(metadata.getQuestion(), marketInfo.getQuestion()));
        marketInfo.setMarketSlug(firstNonBlank(metadata.getSlug(), marketInfo.getMarketSlug()));
        marketInfo.setTokenId(tokenId);
        marketInfo.setIndexSet(metadata.resolveIndexSet(tokenId));
        marketInfo.setMarketEndAt(metadata.getEndAt());
        marketInfo.setSourceCategory(firstNonBlank(metadata.getCategory(), marketInfo.getSourceCategory()));
        marketInfo.setMarketLifecycle(resolveLifecycle(metadata));
        marketInfo.setClosed(metadata.isClosed());
        marketInfo.setAcceptingOrders(metadata.isAcceptingOrders());
        marketInfo.setResolvedOutcome(metadata.getResolvedOutcome());
        marketInfo.setLastMetadataSyncAt(new Date());
    }

    private String resolveLifecycle(PolymarketApiClient.MarketMetadata metadata) {
        if (metadata != null && metadata.isResolved()) {
            return SmartWalletMarketStateService.LIFECYCLE_RESOLVED;
        }
        if (metadata != null && (metadata.isClosed() || !metadata.isAcceptingOrders())) {
            return SmartWalletMarketStateService.LIFECYCLE_CLOSED_UNRESOLVED;
        }
        Long endAt = metadata == null ? null : metadata.getEndAt();
        if (endAt != null && endAt > 0 && endAt <= System.currentTimeMillis()) {
            return SmartWalletMarketStateService.LIFECYCLE_CLOSED_UNRESOLVED;
        }
        return SmartWalletMarketStateService.LIFECYCLE_ACTIVE;
    }

    private Map<Long, String> parsePositionEventUrls(List<String> eventUrlPairs) {
        if (eventUrlPairs == null || eventUrlPairs.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> result = new java.util.LinkedHashMap<>();
        for (String pair : eventUrlPairs) {
            if (!StringUtils.hasText(pair)) {
                continue;
            }
            int split = pair.indexOf('=');
            if (split <= 0 || split >= pair.length() - 1) {
                continue;
            }
            try {
                Long positionId = Long.valueOf(pair.substring(0, split).trim());
                result.put(positionId, pair.substring(split + 1).trim());
            } catch (NumberFormatException ignored) {
                // Ignore malformed diagnostic hint.
            }
        }
        return result;
    }

    private String extractEventSlug(String eventUrl) {
        if (!StringUtils.hasText(eventUrl)) {
            return null;
        }
        String value = eventUrl.trim();
        int queryIndex = value.indexOf('?');
        if (queryIndex >= 0) {
            value = value.substring(0, queryIndex);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        int lastSlash = value.lastIndexOf('/');
        return lastSlash >= 0 ? value.substring(lastSlash + 1).trim() : value;
    }

    private String firstNonBlank(String... candidates) {
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

    private OrderDiagnosticView loadCurrentOrder(Long orderId) {
        SmartWalletOrderEntity order = orderMapper.selectById(orderId);
        if (order == null) {
            return null;
        }
        marketInfoService.populate(order);
        SmartWalletFollowTaskEntity task = order.getFollowTaskId() == null ? null : followTaskMapper.selectById(order.getFollowTaskId());
        SmartWalletTradeEventEntity event = task == null || task.getSourceEventId() == null
                ? null
                : tradeEventMapper.selectById(task.getSourceEventId());
        return buildDiagnostic(order, task, event, "FOUND_CURRENT");
    }

    private OrderDiagnosticView loadArchivedOrder(Long orderId) {
        try {
            List<OrderDiagnosticView> rows = jdbcTemplate.query("""
                    SELECT o.id, o.follow_task_id, o.position_id, o.wallet_address, o.execution_address,
                           o.external_order_id, o.status, o.side, o.order_type, o.price, o.size, o.notional_usdc,
                           o.placed_at, o.filled_at, o.market_info_id, mi.market_id, mi.market_slug, mi.question,
                           mi.outcome, mi.token_id, mi.market_end_at, mi.market_lifecycle, mi.closed, mi.accepting_orders
                    FROM smart_wallet_order_archive_reset_bak o
                    LEFT JOIN smart_wallet_market_info mi ON mi.id = o.market_info_id
                    WHERE o.id = ?
                    LIMIT 1
                    """, (rs, rowNum) -> diagnosticFromResultSet(rs, "FOUND_ARCHIVE"), orderId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (DataAccessException ex) {
            log.warn("Archived order diagnostic lookup failed for orderId={}: {}", orderId, ex.getMessage());
            return null;
        }
    }

    private OrderDiagnosticView buildDiagnostic(SmartWalletOrderEntity order,
                                                SmartWalletFollowTaskEntity task,
                                                SmartWalletTradeEventEntity event,
                                                String lookupStatus) {
        String reason = abnormalMarketReason(order.getTokenId(), order.getMarketSlug(), order.getMarketLifecycle(),
                order.getClosed(), order.getAcceptingOrders());
        return OrderDiagnosticView.builder()
                .orderId(order.getId())
                .lookupStatus(lookupStatus)
                .followTaskId(order.getFollowTaskId())
                .positionId(order.getPositionId())
                .walletAddress(order.getWalletAddress())
                .executionAddress(order.getExecutionAddress())
                .externalOrderId(order.getExternalOrderId())
                .status(order.getStatus())
                .side(order.getSide())
                .orderType(order.getOrderType())
                .price(order.getPrice())
                .size(order.getSize())
                .notionalUsdc(order.getNotionalUsdc())
                .placedAt(order.getPlacedAt())
                .filledAt(order.getFilledAt())
                .marketInfoId(order.getMarketInfoId())
                .marketId(order.getMarketId())
                .marketSlug(order.getMarketSlug())
                .question(order.getQuestion())
                .outcome(order.getOutcome())
                .tokenId(order.getTokenId())
                .marketEndAt(order.getMarketEndAt())
                .marketLifecycle(order.getMarketLifecycle())
                .closed(order.getClosed())
                .acceptingOrders(order.getAcceptingOrders())
                .abnormalMarketReason(reason)
                .sourceEventId(task == null ? null : task.getSourceEventId())
                .eventWalletAddress(event == null ? null : event.getWalletAddress())
                .externalTradeId(event == null ? null : event.getExternalTradeId())
                .eventSide(event == null ? null : event.getSide())
                .eventPrice(event == null ? null : event.getPrice())
                .eventSizeUsdc(event == null ? null : event.getSizeUsdc())
                .eventOpenedAt(event == null ? null : event.getOpenedAt())
                .similarTradeEvents(StringUtils.hasText(order.getQuestion())
                        ? findSimilarTradeEvents(order.getQuestion(), order.getOutcome(), order.getPrice())
                        : List.of())
                .build();
    }

    private OrderDiagnosticView diagnosticFromResultSet(ResultSet rs, String lookupStatus) throws SQLException {
        String tokenId = rs.getString("token_id");
        String marketSlug = rs.getString("market_slug");
        String lifecycle = rs.getString("market_lifecycle");
        Boolean closed = getBoolean(rs, "closed");
        Boolean acceptingOrders = getBoolean(rs, "accepting_orders");
        return OrderDiagnosticView.builder()
                .orderId(rs.getLong("id"))
                .lookupStatus(lookupStatus)
                .followTaskId(getLong(rs, "follow_task_id"))
                .positionId(getLong(rs, "position_id"))
                .walletAddress(rs.getString("wallet_address"))
                .executionAddress(rs.getString("execution_address"))
                .externalOrderId(rs.getString("external_order_id"))
                .status(rs.getString("status"))
                .side(rs.getString("side"))
                .orderType(rs.getString("order_type"))
                .price(rs.getBigDecimal("price"))
                .size(rs.getBigDecimal("size"))
                .notionalUsdc(rs.getBigDecimal("notional_usdc"))
                .placedAt(getDate(rs, "placed_at"))
                .filledAt(getDate(rs, "filled_at"))
                .marketInfoId(getLong(rs, "market_info_id"))
                .marketId(rs.getString("market_id"))
                .marketSlug(marketSlug)
                .question(rs.getString("question"))
                .outcome(rs.getString("outcome"))
                .tokenId(tokenId)
                .marketEndAt(getLong(rs, "market_end_at"))
                .marketLifecycle(lifecycle)
                .closed(closed)
                .acceptingOrders(acceptingOrders)
                .abnormalMarketReason(abnormalMarketReason(tokenId, marketSlug, lifecycle, closed, acceptingOrders))
                .similarTradeEvents(findSimilarTradeEvents(rs.getString("question"), rs.getString("outcome"), rs.getBigDecimal("price")))
                .build();
    }

    private List<OrderSimilarTradeEventView> findSimilarTradeEvents(String question, String outcome, BigDecimal price) {
        if (!StringUtils.hasText(question) && !StringUtils.hasText(outcome) && price == null) {
            return List.of();
        }
        LambdaQueryWrapper<SmartWalletTradeEventEntity> wrapper = new LambdaQueryWrapper<SmartWalletTradeEventEntity>()
                .orderByDesc(SmartWalletTradeEventEntity::getOpenedAt)
                .last("limit " + SIMILAR_EVENT_LIMIT);
        if (StringUtils.hasText(question)) {
            wrapper.like(SmartWalletTradeEventEntity::getQuestion, question.trim());
        }
        if (StringUtils.hasText(outcome)) {
            wrapper.eq(SmartWalletTradeEventEntity::getOutcome, outcome.trim());
        }
        if (price != null) {
            BigDecimal lower = price.subtract(new BigDecimal("0.05"));
            BigDecimal upper = price.add(new BigDecimal("0.05"));
            wrapper.ge(SmartWalletTradeEventEntity::getPrice, lower)
                    .le(SmartWalletTradeEventEntity::getPrice, upper);
        }
        return tradeEventMapper.selectList(wrapper).stream()
                .map(event -> OrderSimilarTradeEventView.builder()
                        .sourceEventId(event.getId())
                        .walletAddress(event.getWalletAddress())
                        .externalTradeId(event.getExternalTradeId())
                        .marketId(event.getMarketId())
                        .question(event.getQuestion())
                        .outcome(event.getOutcome())
                        .side(event.getSide())
                        .price(event.getPrice())
                        .sizeUsdc(event.getSizeUsdc())
                        .openedAt(event.getOpenedAt())
                        .build())
                .toList();
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class,
                tableName);
        return count != null && count > 0;
    }

    private Long queryLong(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }

    private List<Long> normalizeIds(List<Long> ids) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream()
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .distinct()
                .toList();
    }

    private String safeText(String value) {
        return StringUtils.hasText(value) ? value.trim() : "-";
    }

    private Long getLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Boolean getBoolean(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    private Date getDate(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : new Date(timestamp.getTime());
    }
}
