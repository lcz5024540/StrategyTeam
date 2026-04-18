package com.metaverse.coin.strategy.job.hedge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.HedgeOrderMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.HedgeOrderEntity;
import com.metaverse.coin.strategy.job.hedge.config.HedgeProperties;
import com.metaverse.coin.strategy.job.smartwallet.service.ExecutionWalletContextLoader;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketClobClientRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 对冲订单成交轮询 Job
 *
 * 背景：Polymarket GTC 限价单提交成功（拿到 orderId）并不代表立即成交。
 * 订单实际处于撮合队列中，状态可能是 live（挂单）、matched（成交）、canceled 等。
 * HedgeOrderExecutorService 提交后只将订单标为 LIVE；本 Job 负责定期轮询，
 * 将状态推进到 FILLED / CANCELLED / FAILED，并在双边均确认成交后同步持仓真实数量。
 *
 * 轮询策略：
 *   - 查询 status = 'LIVE' 且 next_poll_at <= now 的订单（每次最多 30 条）
 *   - 按指数退避调整 next_poll_at（5s → 10s → 20s … 最长 60s）
 *   - 超过最大轮询次数（默认 40 次 ≈ 30 分钟）后放弃，记录 WARN 由人工处理
 *
 * 对冲完整性保护（任意一边取消时）：
 *   - 若该边订单被取消：尝试取消另一边（尽力而为）
 *   - 若另一边已成交（FILLED）：记录 CRITICAL 警告，标记持仓为单边敞口风险
 *   - 无论如何均将持仓标记 CANCELLED，防止结算逻辑误判
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HedgeOrderPollingJob {

    private final HedgeProperties properties;
    private final HedgeOrderMapper orderMapper;
    private final HedgePositionTracker positionTracker;
    private final PolymarketClobClientRouter clobClientRouter;
    private final ExecutionWalletContextLoader walletContextLoader;

    /** 放弃轮询的最大尝试次数（约 40 次 × 退避均值 ≈ 30 分钟） */
    private static final int MAX_POLL_ATTEMPTS = 40;

    @Scheduled(fixedDelayString = "${polymarket.hedge.order-polling-fixed-delay-ms:5000}")
    public void pollPendingOrders() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            doPoll();
        } catch (Exception e) {
            log.error("[HedgePollingJob] Unexpected error during poll: {}", e.getMessage(), e);
        }
    }

    private void doPoll() {
        List<HedgeOrderEntity> liveOrders = orderMapper.selectList(
                new LambdaQueryWrapper<HedgeOrderEntity>()
                        .eq(HedgeOrderEntity::getStatus, "LIVE")
                        // 仅处理实盘订单：PAPER 模式下订单创建时即已写入 FILLED 状态，无需轮询
                        .and(w -> w.eq(HedgeOrderEntity::getMode, "LIVE")
                                .or().isNull(HedgeOrderEntity::getMode))
                        .isNotNull(HedgeOrderEntity::getExternalOrderId)
                        .le(HedgeOrderEntity::getNextPollAt, new Date())
                        .orderByAsc(HedgeOrderEntity::getNextPollAt)
                        .last("limit 30"));

        if (liveOrders.isEmpty()) return;

        ExecutionWalletContextLoader.ExecutionWalletContext context;
        try {
            context = walletContextLoader.requireLiveContext();
        } catch (Exception e) {
            log.warn("[HedgePollingJob] Execution wallet not ready, skipping poll: {}", e.getMessage());
            return;
        }

        for (HedgeOrderEntity order : liveOrders) {
            try {
                processOrder(order, context);
            } catch (Exception e) {
                log.warn("[HedgePollingJob] Error polling order id={} extId={}: {}",
                        order.getId(), order.getExternalOrderId(), e.getMessage());
                positionTracker.advancePollBackoff(order.getId(),
                        order.getPollAttempts() == null ? 0 : order.getPollAttempts());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void processOrder(HedgeOrderEntity order,
                              ExecutionWalletContextLoader.ExecutionWalletContext context) {
        int attempts = order.getPollAttempts() == null ? 0 : order.getPollAttempts();

        // 超过最大次数后放弃（由人工处理），但仍保留 LIVE 状态以便管理员查看
        if (attempts >= MAX_POLL_ATTEMPTS) {
            log.warn("[HedgePollingJob] Order id={} extId={} exceeded max poll attempts ({}), giving up",
                    order.getId(), order.getExternalOrderId(), MAX_POLL_ATTEMPTS);
            positionTracker.updateOrderFailed(order.getId(),
                    "超过最大轮询次数 " + MAX_POLL_ATTEMPTS + "，放弃确认");
            return;
        }

        Map<String, Object> response = clobClientRouter.getOrder(context, order.getExternalOrderId());
        if (response == null) {
            positionTracker.advancePollBackoff(order.getId(), attempts);
            return;
        }

        // 兼容直接返回 response 和嵌套在 "response" key 下的两种格式
        Map<String, Object> orderData = response.containsKey("response")
                ? (Map<String, Object>) response.get("response")
                : response;

        if (orderData == null || !orderData.containsKey("status")) {
            positionTracker.advancePollBackoff(order.getId(), attempts);
            return;
        }

        String rawStatus = String.valueOf(orderData.get("status")).toLowerCase();
        log.debug("[HedgePollingJob] Order id={} extId={} status={}", order.getId(), order.getExternalOrderId(), rawStatus);

        switch (rawStatus) {
            case "matched", "filled" -> handleFilled(order, orderData);
            case "canceled", "cancelled", "expired" -> handleCancelled(order, context, "Polymarket 返回: " + rawStatus);
            default -> positionTracker.advancePollBackoff(order.getId(), attempts); // live / partially_filled → keep polling
        }
    }

    private void handleFilled(HedgeOrderEntity order, Map<String, Object> orderData) {
        BigDecimal filledQty = parseDecimal(orderData, "size_matched", "sizeMatched", "filled");
        BigDecimal avgPrice = parseDecimal(orderData, "price");
        if (filledQty == null || filledQty.compareTo(BigDecimal.ZERO) <= 0) {
            // 取请求数量作为兜底（matched 状态通常表示完全成交）
            filledQty = order.getRequestedQuantity();
        }
        if (avgPrice == null || avgPrice.compareTo(BigDecimal.ZERO) <= 0) {
            avgPrice = order.getPrice();
        }
        BigDecimal costUsdc = filledQty.multiply(avgPrice).setScale(4, RoundingMode.HALF_UP);

        positionTracker.updateOrderFillConfirmed(order.getId(), filledQty, costUsdc);
        log.info("[HedgePollingJob] Order FILLED id={} extId={} filledQty={} cost={}",
                order.getId(), order.getExternalOrderId(), filledQty, costUsdc);

        // 如果双边都确认成交，同步持仓实际数量
        positionTracker.syncActualPositionIfBothFilled(order.getHedgePositionId());
    }

    private void handleCancelled(HedgeOrderEntity order,
                                  ExecutionWalletContextLoader.ExecutionWalletContext context,
                                  String reason) {
        positionTracker.updateOrderCancelledByPolling(order.getId(), reason);
        log.warn("[HedgePollingJob] Order CANCELLED id={} extId={} reason={}",
                order.getId(), order.getExternalOrderId(), reason);

        // 查找同持仓的另一笔订单
        List<HedgeOrderEntity> siblingOrders = positionTracker.getOrdersByPositionId(order.getHedgePositionId())
                .stream()
                .filter(o -> !o.getId().equals(order.getId()))
                .toList();

        for (HedgeOrderEntity sibling : siblingOrders) {
            if ("FILLED".equals(sibling.getStatus())) {
                // 严重：另一边已成交，持仓为单边敞口
                log.error("[HedgePollingJob] CRITICAL: Order id={} side={} is CANCELLED but sibling id={} side={} is already FILLED. " +
                                "Position id={} has SINGLE-SIDED exposure! Manual intervention required.",
                        order.getId(), order.getSide(),
                        sibling.getId(), sibling.getSide(),
                        order.getHedgePositionId());
            } else if ("LIVE".equals(sibling.getStatus()) && StringUtils.hasText(sibling.getExternalOrderId())) {
                // 尝试取消另一边（尽力而为）
                try {
                    clobClientRouter.cancelOrder(context, sibling.getExternalOrderId());
                    positionTracker.updateOrderCancelledByPolling(sibling.getId(), "兄弟单 " + order.getSide() + " 边被取消，联动取消");
                    log.info("[HedgePollingJob] Cancelled sibling order id={} side={} for position id={}",
                            sibling.getId(), sibling.getSide(), order.getHedgePositionId());
                } catch (Exception e) {
                    log.warn("[HedgePollingJob] Failed to cancel sibling order id={}: {}", sibling.getId(), e.getMessage());
                    positionTracker.updateOrderFailed(sibling.getId(), "尝试联动取消失败: " + e.getMessage());
                }
            }
        }

        // 标记持仓取消
        positionTracker.cancelPosition(order.getHedgePositionId(), "订单 " + order.getSide() + " 边被取消: " + reason);
    }

    private BigDecimal parseDecimal(Map<String, Object> data, String... keys) {
        for (String key : keys) {
            Object val = data.get(key);
            if (val == null) continue;
            try {
                BigDecimal bd = new BigDecimal(String.valueOf(val));
                if (bd.compareTo(BigDecimal.ZERO) > 0) return bd;
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }
}
