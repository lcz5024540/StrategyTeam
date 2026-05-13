package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 跟单意图聚合服务 —— P0-2 核心。
 *
 * 解决 leader 分批建仓（试单、冰山单、补仓单）被拆散或被重复任务门控拦掉的问题。
 *
 * 核心逻辑：
 * 1. 同 walletAddress + marketId + outcome + side 在时间窗口内的事件，
 *    归属同一个 intentKey，被视作同一笔建仓意图。
 * 2. intentKey 格式: {walletAddress}:{marketId}:{outcome}:{side}:{windowStartEpochMs}
 * 3. 首笔事件创建意图并生成 follow task；后续碎片事件增量更新 stake，不再创建新任务。
 * 4. 聚合后总金额超过 minTriggerSizeUsdc 才真正提交执行。
 *
 * 窗口机制：
 * - 默认 5 分钟（可配置）
 * - 窗口起点 = 该 intentKey 下最早的未聚合事件时间
 * - 窗口结束后新事件开启新窗口（新 intentKey）
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FollowIntentAggregationService {

    private static final String LOG_PREFIX = "[FollowIntentAggregation]";

    private final SmartWalletTradeEventMapper tradeEventMapper;
    private final SmartWalletFollowTaskMapper followTaskMapper;
    private final SmartWalletProperties properties;

    // ════════════════════════════════════════════════════════════════════
    // Public API
    // ════════════════════════════════════════════════════════════════════

    /**
     * 对一笔新事件进行意图聚合判定。
     *
     * @param event 新到达的交易事件
     * @return 聚合结果
     */
    public IntentAggregationResult aggregate(SmartWalletTradeEventEntity event) {
        if (event == null || !StringUtils.hasText(event.getWalletAddress())
                || !StringUtils.hasText(event.getMarketId())
                || !StringUtils.hasText(event.getOutcome())
                || !StringUtils.hasText(event.getSide())) {
            return IntentAggregationResult.builder()
                    .aggregated(false)
                    .reason("MISSING_FIELDS")
                    .build();
        }

        long windowMs = getAggregationWindowMs();
        long eventTimeMs = event.getOpenedAt() != null ? event.getOpenedAt().getTime() : System.currentTimeMillis();

        // 查找窗口内同维度的近期事件
        Date windowStart = new Date(eventTimeMs - windowMs);
        List<SmartWalletTradeEventEntity> recentEvents = findRecentSameDimensionEvents(
                event.getWalletAddress(), event.getMarketId(), event.getOutcome(), event.getSide(), windowStart);

        if (recentEvents.isEmpty()) {
            // 没有近期同维度事件 —— 这是一个新意图的首笔
            String intentKey = buildIntentKey(event, eventTimeMs);
            log.info("{} action=意图聚合 result=NEW_INTENT eventId={} intentKey={} sizeUsdc={}",
                    LOG_PREFIX, event.getId(), intentKey, event.getSizeUsdc());
            return IntentAggregationResult.builder()
                    .aggregated(false)
                    .intentKey(intentKey)
                    .aggregatedSizeUsdc(safe(event.getSizeUsdc()))
                    .fragmentCount(1)
                    .isNewIntent(true)
                    .reason("FIRST_FRAGMENT")
                    .build();
        }

        // 找到近期事件 —— 尝试匹配已有的 follow task (intent)
        SmartWalletFollowTaskEntity existingIntentTask = findExistingIntentTask(
                event.getWalletAddress(), event.getMarketId(), event.getOutcome(), event.getSide(), windowStart);

        if (existingIntentTask != null && isPendingOrShadow(existingIntentTask)) {
            // 已有未执行的意图任务 → 增量更新
            BigDecimal additionalUsdc = safe(event.getSizeUsdc());
            BigDecimal previousStake = safe(existingIntentTask.getRecommendedStakeUsdc());
            BigDecimal newAggregatedStake = previousStake.add(additionalUsdc).setScale(2, RoundingMode.HALF_UP);
            int previousFragCount = parseFragmentCount(existingIntentTask);

            // 更新 follow task 的 stake 和 fragment count
            existingIntentTask.setRecommendedStakeUsdc(newAggregatedStake);
            existingIntentTask.setTriggerSizeUsdc(newAggregatedStake);
            String updatedTags = appendIntentMetadata(existingIntentTask, event.getId(), previousFragCount + 1);
            existingIntentTask.setSkipReasonTags(updatedTags);
            existingIntentTask.setUpdatedAt(new Date());
            followTaskMapper.updateById(existingIntentTask);

            String intentKey = existingIntentTask.getIntentKey() != null
                    ? existingIntentTask.getIntentKey()
                    : buildIntentKeyFromTask(existingIntentTask);

            log.info("{} action=意图聚合 result=MERGED eventId={} intentKey={} followTaskId={} " +
                            "previousStake={} additionalUsdc={} newStake={} fragmentCount={}",
                    LOG_PREFIX, event.getId(), intentKey, existingIntentTask.getId(),
                    previousStake, additionalUsdc, newAggregatedStake, previousFragCount + 1);

            return IntentAggregationResult.builder()
                    .aggregated(true)
                    .intentKey(intentKey)
                    .mergedIntoTaskId(existingIntentTask.getId())
                    .aggregatedSizeUsdc(newAggregatedStake)
                    .fragmentCount(previousFragCount + 1)
                    .isNewIntent(false)
                    .reason("MERGED_INTO_EXISTING_INTENT")
                    .build();
        }

        // 有近期事件但没有 pending task → 可能 task 已执行完，开新 intent
        long earliestEventMs = recentEvents.stream()
                .filter(e -> e.getOpenedAt() != null)
                .mapToLong(e -> e.getOpenedAt().getTime())
                .min()
                .orElse(eventTimeMs);

        // 判断是否在同一个窗口内
        if (eventTimeMs - earliestEventMs <= windowMs) {
            // 仍在窗口内但 task 已被消费 → 作为 scale-up 补充
            BigDecimal totalUsdc = recentEvents.stream()
                    .map(e -> safe(e.getSizeUsdc()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .add(safe(event.getSizeUsdc()));

            String intentKey = buildIntentKey(event, earliestEventMs);
            log.info("{} action=意图聚合 result=SCALE_UP_SUPPLEMENT eventId={} intentKey={} totalUsdc={} fragmentCount={}",
                    LOG_PREFIX, event.getId(), intentKey, totalUsdc, recentEvents.size() + 1);

            return IntentAggregationResult.builder()
                    .aggregated(false)
                    .intentKey(intentKey)
                    .aggregatedSizeUsdc(totalUsdc)
                    .fragmentCount(recentEvents.size() + 1)
                    .isNewIntent(false)
                    .isScaleUpSupplement(true)
                    .reason("SCALE_UP_SUPPLEMENT")
                    .build();
        }

        // 超出窗口 → 新意图
        String intentKey = buildIntentKey(event, eventTimeMs);
        log.info("{} action=意图聚合 result=NEW_INTENT_AFTER_WINDOW eventId={} intentKey={} sizeUsdc={}",
                LOG_PREFIX, event.getId(), intentKey, event.getSizeUsdc());

        return IntentAggregationResult.builder()
                .aggregated(false)
                .intentKey(intentKey)
                .aggregatedSizeUsdc(safe(event.getSizeUsdc()))
                .fragmentCount(1)
                .isNewIntent(true)
                .reason("NEW_WINDOW")
                .build();
    }

    /**
     * 检查是否应该作为"同 intentKey 的增量更新"而非"重复任务冲突"。
     *
     * 被 SmartWalletSignalRiskService 的 duplicate pending task 逻辑调用。
     *
     * @param marketId   市场 ID
     * @param outcome    outcome
     * @param side       BUY / SELL
     * @param walletAddress leader 钱包地址
     * @param eventTime  事件时间
     * @return true = 在同一意图窗口内，应走聚合而非拦截
     */
    public boolean isWithinActiveIntentWindow(String marketId, String outcome, String side,
                                               String walletAddress, Date eventTime) {
        long windowMs = getAggregationWindowMs();
        Date windowStart = new Date(eventTime.getTime() - windowMs);

        SmartWalletFollowTaskEntity existingTask = findExistingIntentTask(
                walletAddress, marketId, outcome, side, windowStart);

        return existingTask != null && isPendingOrShadow(existingTask);
    }

    // ════════════════════════════════════════════════════════════════════
    // DB queries
    // ════════════════════════════════════════════════════════════════════

    /**
     * 查找时间窗口内同 wallet+market+outcome+side 的交易事件。
     */
    private List<SmartWalletTradeEventEntity> findRecentSameDimensionEvents(String walletAddress, String marketId,
                                                                            String outcome, String side,
                                                                            Date windowStart) {
        return tradeEventMapper.selectList(new LambdaQueryWrapper<SmartWalletTradeEventEntity>()
                .eq(SmartWalletTradeEventEntity::getWalletAddress, walletAddress)
                .eq(SmartWalletTradeEventEntity::getMarketId, marketId)
                .eq(SmartWalletTradeEventEntity::getOutcome, outcome)
                .eq(SmartWalletTradeEventEntity::getSide, side)
                .ge(SmartWalletTradeEventEntity::getOpenedAt, windowStart)
                .orderByAsc(SmartWalletTradeEventEntity::getOpenedAt));
    }

    /**
     * 查找时间窗口内同维度的已有 follow task（PENDING / PAPER_SHADOW）。
     * 通过 follow_task 的 walletAddress + marketInfo 关联查找。
     */
    private SmartWalletFollowTaskEntity findExistingIntentTask(String walletAddress, String marketId,
                                                                String outcome, String side,
                                                                Date windowStart) {
        // 用 apply 子查询找 market_info_id
        return followTaskMapper.selectOne(new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                .eq(SmartWalletFollowTaskEntity::getWalletAddress, walletAddress)
                .eq(SmartWalletFollowTaskEntity::getSide, side)
                .in(SmartWalletFollowTaskEntity::getStatus, List.of("PENDING", "PAPER_SHADOW"))
                .ge(SmartWalletFollowTaskEntity::getCreatedAt, windowStart)
                .apply("market_info_id IN (SELECT id FROM smart_wallet_market_info WHERE market_id = {0} AND outcome = {1})",
                        marketId, outcome)
                .orderByDesc(SmartWalletFollowTaskEntity::getCreatedAt)
                .last("LIMIT 1"));
    }

    // ════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════

    private String buildIntentKey(SmartWalletTradeEventEntity event, long windowStartMs) {
        return String.format(Locale.ROOT, "%s:%s:%s:%s:%d",
                event.getWalletAddress().toLowerCase(Locale.ROOT),
                event.getMarketId(),
                event.getOutcome().toUpperCase(Locale.ROOT),
                event.getSide().toUpperCase(Locale.ROOT),
                windowStartMs / (getAggregationWindowMs())  // 离散化到窗口边界
        );
    }

    private String buildIntentKeyFromTask(SmartWalletFollowTaskEntity task) {
        long createdMs = task.getCreatedAt() != null ? task.getCreatedAt().getTime() : System.currentTimeMillis();
        return String.format(Locale.ROOT, "%s:%s:%s:%s:%d",
                task.getWalletAddress() != null ? task.getWalletAddress().toLowerCase(Locale.ROOT) : "unknown",
                task.getMarketId() != null ? task.getMarketId() : "unknown",
                task.getOutcome() != null ? task.getOutcome().toUpperCase(Locale.ROOT) : "unknown",
                task.getSide() != null ? task.getSide().toUpperCase(Locale.ROOT) : "BUY",
                createdMs / (getAggregationWindowMs())
        );
    }

    private boolean isPendingOrShadow(SmartWalletFollowTaskEntity task) {
        String s = task.getStatus();
        return "PENDING".equalsIgnoreCase(s) || "PAPER_SHADOW".equalsIgnoreCase(s);
    }

    private int parseFragmentCount(SmartWalletFollowTaskEntity task) {
        // fragment count 存在 skipReasonTags 中的 INTENT_FRAGMENTS=N 标记
        String tags = task.getSkipReasonTags();
        if (!StringUtils.hasText(tags)) return 1;
        for (String tag : tags.split(",")) {
            if (tag.startsWith("INTENT_FRAGMENTS=")) {
                try {
                    return Integer.parseInt(tag.substring("INTENT_FRAGMENTS=".length()));
                } catch (NumberFormatException ignored) {}
            }
        }
        return 1;
    }

    /**
     * 在 skipReasonTags 中追加意图聚合元数据。
     * 格式: INTENT_FRAGMENTS=N,INTENT_EVENT_IDS=1;2;3
     */
    private String appendIntentMetadata(SmartWalletFollowTaskEntity task, Long newEventId, int newFragCount) {
        String existing = task.getSkipReasonTags() != null ? task.getSkipReasonTags() : "";

        // 移除旧的 INTENT_ 标记
        StringBuilder cleaned = new StringBuilder();
        for (String tag : existing.split(",")) {
            if (!tag.startsWith("INTENT_FRAGMENTS=") && !tag.startsWith("INTENT_EVENT_IDS=") && !tag.isEmpty()) {
                if (cleaned.length() > 0) cleaned.append(",");
                cleaned.append(tag);
            }
        }

        // 收集已有 event IDs
        String eventIds = "";
        for (String tag : existing.split(",")) {
            if (tag.startsWith("INTENT_EVENT_IDS=")) {
                eventIds = tag.substring("INTENT_EVENT_IDS=".length());
            }
        }
        if (eventIds.isEmpty()) {
            eventIds = String.valueOf(task.getSourceEventId());
        }
        eventIds = eventIds + ";" + newEventId;

        if (cleaned.length() > 0) cleaned.append(",");
        cleaned.append("INTENT_FRAGMENTS=").append(newFragCount);
        cleaned.append(",INTENT_EVENT_IDS=").append(eventIds);
        return cleaned.toString();
    }

    private long getAggregationWindowMs() {
        SmartWalletProperties.IntentAggregationConfig config = properties.getIntentAggregation();
        if (config != null && config.getWindowMinutes() > 0) {
            return config.getWindowMinutes() * 60_000L;
        }
        return 5 * 60_000L; // 默认 5 分钟
    }

    private BigDecimal safe(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    // ════════════════════════════════════════════════════════════════════
    // Result model
    // ════════════════════════════════════════════════════════════════════

    @Data
    @Builder
    public static class IntentAggregationResult {
        /** 是否已合并到现有意图（true = 不需要创建新 follow task） */
        private boolean aggregated;
        /** 意图标识 */
        private String intentKey;
        /** 合并到的已有 followTask ID（仅 aggregated=true 时有值） */
        private Long mergedIntoTaskId;
        /** 聚合后总金额 */
        private BigDecimal aggregatedSizeUsdc;
        /** 碎片事件数量 */
        private int fragmentCount;
        /** 是否为新意图的首笔 */
        private boolean isNewIntent;
        /** 是否为已消费意图的 scale-up 补充 */
        @Builder.Default
        private boolean isScaleUpSupplement = false;
        /** 描述 */
        private String reason;
    }
}
