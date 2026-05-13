package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class SmartWalletOrderPollingService {

    private final SmartWalletProperties properties;
    private final SmartWalletOrderMapper orderMapper;
    private final PolymarketClobExecutionService clobExecutionService;
    private final SmartWalletAccountingService accountingService;

    @Scheduled(fixedDelayString = "${smart-wallet.polymarket.order-polling-fixed-delay-ms:2000}")
    public void pollPendingOrders() {
        if (!properties.isEnabled() || properties.getPolymarket() == null) {
            return;
        }

        // Fetch live or partially filled orders
        List<SmartWalletOrderEntity> pendingOrders = orderMapper.selectList(new LambdaQueryWrapper<SmartWalletOrderEntity>()
                .in(SmartWalletOrderEntity::getStatus, List.of("LIVE", "PARTIALLY_FILLED"))
                .eq(SmartWalletOrderEntity::getExecutionMode, "LIVE")
                .le(SmartWalletOrderEntity::getNextPollAt, new Date())
                .orderByAsc(SmartWalletOrderEntity::getNextPollAt)
                .last("limit 20"));

        long now = System.currentTimeMillis();

        for (SmartWalletOrderEntity order : pendingOrders) {
            if (order.getExternalOrderId() == null || order.getExternalOrderId().startsWith("local-")) {
                continue;
            }

            try {
                Map<String, Object> response = clobExecutionService.getOrder(order.getExternalOrderId());
                if (response != null && Boolean.TRUE.equals(response.get("success"))) {
                    Map<String, Object> orderData = (Map<String, Object>) response.get("response");
                    if (orderData != null && orderData.containsKey("status")) {
                        String status = String.valueOf(orderData.get("status"));
                        order.setStatus(status.toUpperCase());
                        
                        if ("MATCHED".equalsIgnoreCase(status) || "CANCELED".equalsIgnoreCase(status) || "EXPIRED".equalsIgnoreCase(status) || "FILLED".equalsIgnoreCase(status)) {
                            order.setNextPollAt(null);
                        } else {
                            applyExponentialBackoff(order, now);
                        }
                        
                        order.setUpdatedAt(new Date());
                        int affected = orderMapper.updateById(order);
                        if (affected == 0) {
                            log.warn("[OptimisticLock] pollPendingOrders: order {} version conflict, skipping", order.getId());
                            continue;
                        }

                        if ("MATCHED".equalsIgnoreCase(status) || "FILLED".equalsIgnoreCase(status) || "PARTIALLY_FILLED".equalsIgnoreCase(status)) {
                            java.math.BigDecimal filledSize = java.math.BigDecimal.ZERO;
                            java.math.BigDecimal avgPrice = order.getPrice(); // Fallback to limit price
                            if (orderData.containsKey("sizeMatched")) {
                                filledSize = new java.math.BigDecimal(String.valueOf(orderData.get("sizeMatched")));
                            }
                            if (orderData.containsKey("price")) {
                                avgPrice = new java.math.BigDecimal(String.valueOf(orderData.get("price")));
                            }
                            
                            if (filledSize.compareTo(java.math.BigDecimal.ZERO) > 0) {
                                accountingService.processOrderFill(order, filledSize, avgPrice, java.math.BigDecimal.ZERO);
                            }
                        }
                        log.debug("Updated order {} status to {}", order.getExternalOrderId(), status);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to poll order status for {}: {}", order.getExternalOrderId(), e.getMessage());
                applyExponentialBackoff(order, now);
                order.setUpdatedAt(new Date());
                int affected = orderMapper.updateById(order);
                if (affected == 0) {
                    log.warn("[OptimisticLock] pollPendingOrders error-path: order {} version conflict", order.getId());
                }
            }
        }
    }

    private void applyExponentialBackoff(SmartWalletOrderEntity order, long now) {
        Integer attempts = order.getPollAttempts();
        int currentAttempts = attempts == null ? 0 : attempts;
        
        // Exponential backoff logic: 1s -> 2s -> 4s -> 8s ... Max 30s
        long delaySeconds = (long) Math.pow(2, Math.min(currentAttempts, 5)); // 1, 2, 4, 8, 16, 32
        long delayMs = Math.min(delaySeconds * 1000, 30000); // cap at 30s
        
        order.setPollAttempts(currentAttempts + 1);
        order.setNextPollAt(new Date(now + delayMs));
    }
}
