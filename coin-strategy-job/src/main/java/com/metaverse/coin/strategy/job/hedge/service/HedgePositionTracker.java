package com.metaverse.coin.strategy.job.hedge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.HedgeOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.HedgePositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.HedgeOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.HedgePositionEntity;
import com.metaverse.coin.strategy.job.hedge.config.HedgeProperties;
import com.metaverse.coin.strategy.job.hedge.model.HedgeOpportunity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.List;

/**
 * 对冲持仓跟踪服务（对冲策略的仓位生命周期管理）
 *
 * 与跟单策略的 SmartWalletPositionMapper 完全隔离：
 *   - 读写的是 hedge_position 和 hedge_order 表
 *   - 不依赖 SmartWalletProperties，只注入 HedgeProperties
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HedgePositionTracker {

    private final HedgePositionMapper positionMapper;
    private final HedgeOrderMapper orderMapper;
    private final HedgeProperties properties;

    /**
     * 创建对冲持仓记录
     */
    public HedgePositionEntity openPosition(HedgeOpportunity opp, BigDecimal quantity,
                                             BigDecimal totalUsdc, BigDecimal expectedProfit,
                                             String executionAddress) {
        Date now = new Date();
        BigDecimal yesCost = opp.getYesBestAsk().multiply(quantity).setScale(4, RoundingMode.HALF_UP);
        BigDecimal noCost = opp.getNoBestAsk().multiply(quantity).setScale(4, RoundingMode.HALF_UP);

        HedgePositionEntity position = new HedgePositionEntity()
                .setMarketId(opp.getMarketId())
                .setQuestion(opp.getQuestion())
                .setYesTokenId(opp.getYesTokenId())
                .setNoTokenId(opp.getNoTokenId())
                .setYesEntryPrice(opp.getYesBestAsk())
                .setNoEntryPrice(opp.getNoBestAsk())
                .setProbDeviation(opp.getProbDeviation())
                .setGrossEdgeRate(opp.getGrossEdgeRate())
                .setQuantity(quantity)
                .setYesCostUsdc(yesCost)
                .setNoCostUsdc(noCost)
                .setTotalCostUsdc(totalUsdc)
                .setExpectedProfitUsdc(expectedProfit)
                .setYesLiquidityUsdc(opp.getYesLiquidityUsdc())
                .setNoLiquidityUsdc(opp.getNoLiquidityUsdc())
                .setExecutionAddress(executionAddress)
                .setStatus("OPEN")
                .setMarketEndAt(opp.getMarketEndAt())
                .setOpenedAt(now)
                .setCreatedAt(now)
                .setUpdatedAt(now);

        positionMapper.insert(position);
        log.info("[HedgePositionTracker] Opened position id={} market={} qty={} totalCost={} expectedProfit={}",
                position.getId(), opp.getMarketId(), quantity, totalUsdc, expectedProfit);
        return position;
    }

    /**
     * 记录双边订单
     */
    public HedgeOrderEntity createOrder(Long hedgePositionId, String marketId,
                                         String tokenId, String side,
                                         BigDecimal price, BigDecimal quantity) {
        Date now = new Date();
        HedgeOrderEntity order = new HedgeOrderEntity()
                .setHedgePositionId(hedgePositionId)
                .setMarketId(marketId)
                .setTokenId(tokenId)
                .setSide(side)
                .setOrderType(properties.getOrderType())
                .setPrice(price)
                .setRequestedQuantity(quantity)
                .setStatus("PENDING")
                .setPollAttempts(0)
                .setSubmittedAt(now)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        orderMapper.insert(order);
        return order;
    }

    /**
     * 订单成功提交到 Polymarket CLOB 后调用：记录 externalOrderId，状态改为 LIVE 等待轮询确认
     * （GTC 限价单提交成功 ≠ 成交，需轮询才能确认实际成交量）
     */
    public void updateOrderSubmitted(Long orderId, String externalOrderId) {
        Date now = new Date();
        orderMapper.update(null, new LambdaUpdateWrapper<HedgeOrderEntity>()
                .eq(HedgeOrderEntity::getId, orderId)
                .set(HedgeOrderEntity::getExternalOrderId, externalOrderId)
                .set(HedgeOrderEntity::getStatus, "LIVE")
                .set(HedgeOrderEntity::getPollAttempts, 0)
                .set(HedgeOrderEntity::getNextPollAt, new Date(now.getTime() + 5_000L))
                .set(HedgeOrderEntity::getUpdatedAt, now));
    }

    /**
     * 轮询确认订单已成交：记录实际成交数量和成本
     */
    public void updateOrderFillConfirmed(Long orderId, BigDecimal filledQty, BigDecimal costUsdc) {
        orderMapper.update(null, new LambdaUpdateWrapper<HedgeOrderEntity>()
                .eq(HedgeOrderEntity::getId, orderId)
                .set(HedgeOrderEntity::getStatus, "FILLED")
                .set(HedgeOrderEntity::getFilledQuantity, filledQty)
                .set(HedgeOrderEntity::getCostUsdc, costUsdc)
                .set(HedgeOrderEntity::getFilledAt, new Date())
                .set(HedgeOrderEntity::getNextPollAt, null)
                .set(HedgeOrderEntity::getUpdatedAt, new Date()));
    }

    /**
     * 轮询发现订单已被取消或过期
     */
    public void updateOrderCancelledByPolling(Long orderId, String reason) {
        orderMapper.update(null, new LambdaUpdateWrapper<HedgeOrderEntity>()
                .eq(HedgeOrderEntity::getId, orderId)
                .set(HedgeOrderEntity::getStatus, "CANCELLED")
                .set(HedgeOrderEntity::getErrorMessage, reason)
                .set(HedgeOrderEntity::getNextPollAt, null)
                .set(HedgeOrderEntity::getUpdatedAt, new Date()));
    }

    /**
     * 推进轮询退避（指数退避，最长 60s）
     */
    public void advancePollBackoff(Long orderId, int currentAttempts) {
        long delayMs = Math.min((long) Math.pow(2, Math.min(currentAttempts, 6)) * 1_000L, 60_000L);
        orderMapper.update(null, new LambdaUpdateWrapper<HedgeOrderEntity>()
                .eq(HedgeOrderEntity::getId, orderId)
                .set(HedgeOrderEntity::getPollAttempts, currentAttempts + 1)
                .set(HedgeOrderEntity::getNextPollAt, new Date(System.currentTimeMillis() + delayMs))
                .set(HedgeOrderEntity::getUpdatedAt, new Date()));
    }

    /**
     * 两边订单均成交后同步持仓实际数量和成本
     * （取双边成交量最小值以保证 delta 中性）
     */
    public void syncActualPositionIfBothFilled(Long positionId) {
        List<HedgeOrderEntity> orders = getOrdersByPositionId(positionId);
        if (orders.size() < 2) return;
        boolean allFilled = orders.stream().allMatch(o -> "FILLED".equals(o.getStatus()));
        if (!allFilled) return;

        BigDecimal yesQty = orders.stream()
                .filter(o -> "YES".equals(o.getSide()))
                .map(o -> o.getFilledQuantity() != null ? o.getFilledQuantity() : BigDecimal.ZERO)
                .findFirst().orElse(BigDecimal.ZERO);
        BigDecimal noQty = orders.stream()
                .filter(o -> "NO".equals(o.getSide()))
                .map(o -> o.getFilledQuantity() != null ? o.getFilledQuantity() : BigDecimal.ZERO)
                .findFirst().orElse(BigDecimal.ZERO);
        BigDecimal actualQty = yesQty.min(noQty);

        BigDecimal yesCost = orders.stream()
                .filter(o -> "YES".equals(o.getSide()))
                .map(o -> o.getCostUsdc() != null ? o.getCostUsdc() : BigDecimal.ZERO)
                .findFirst().orElse(BigDecimal.ZERO);
        BigDecimal noCost = orders.stream()
                .filter(o -> "NO".equals(o.getSide()))
                .map(o -> o.getCostUsdc() != null ? o.getCostUsdc() : BigDecimal.ZERO)
                .findFirst().orElse(BigDecimal.ZERO);
        BigDecimal actualTotalCost = yesCost.add(noCost).setScale(4, java.math.RoundingMode.HALF_UP);

        positionMapper.update(null, new LambdaUpdateWrapper<HedgePositionEntity>()
                .eq(HedgePositionEntity::getId, positionId)
                .set(HedgePositionEntity::getQuantity, actualQty)
                .set(HedgePositionEntity::getYesCostUsdc, yesCost)
                .set(HedgePositionEntity::getNoCostUsdc, noCost)
                .set(HedgePositionEntity::getTotalCostUsdc, actualTotalCost)
                .set(HedgePositionEntity::getUpdatedAt, new Date()));
        log.info("[HedgePositionTracker] Synced actual position id={} actualQty={} actualCost={}",
                positionId, actualQty, actualTotalCost);
    }

    /** 查询某持仓的所有订单 */
    public List<HedgeOrderEntity> getOrdersByPositionId(Long positionId) {
        return orderMapper.selectList(new LambdaQueryWrapper<HedgeOrderEntity>()
                .eq(HedgeOrderEntity::getHedgePositionId, positionId)
                .orderByAsc(HedgeOrderEntity::getId));
    }

    /** 更新订单状态（成交/失败，兼容旧路径）*/
    public void updateOrderFilled(Long orderId, String externalOrderId,
                                   BigDecimal filledQty, BigDecimal costUsdc) {
        orderMapper.update(null, new LambdaUpdateWrapper<HedgeOrderEntity>()
                .eq(HedgeOrderEntity::getId, orderId)
                .set(HedgeOrderEntity::getExternalOrderId, externalOrderId)
                .set(HedgeOrderEntity::getStatus, "FILLED")
                .set(HedgeOrderEntity::getFilledQuantity, filledQty)
                .set(HedgeOrderEntity::getCostUsdc, costUsdc)
                .set(HedgeOrderEntity::getFilledAt, new Date())
                .set(HedgeOrderEntity::getNextPollAt, null)
                .set(HedgeOrderEntity::getUpdatedAt, new Date()));
    }

    /** 更新订单为失败状态 */
    public void updateOrderFailed(Long orderId, String errorMessage) {
        orderMapper.update(null, new LambdaUpdateWrapper<HedgeOrderEntity>()
                .eq(HedgeOrderEntity::getId, orderId)
                .set(HedgeOrderEntity::getStatus, "FAILED")
                .set(HedgeOrderEntity::getErrorMessage, errorMessage)
                .set(HedgeOrderEntity::getUpdatedAt, new Date()));
    }

    /** 统计已结算持仓数 */
    public long countSettled() {
        return positionMapper.selectCount(new LambdaQueryWrapper<HedgePositionEntity>()
                .eq(HedgePositionEntity::getStatus, "SETTLED"));
    }

    /** 标记持仓进入结算中状态 */
    public void markSettling(Long positionId, String finalOutcome) {
        positionMapper.update(null, new LambdaUpdateWrapper<HedgePositionEntity>()
                .eq(HedgePositionEntity::getId, positionId)
                .set(HedgePositionEntity::getStatus, "SETTLING")
                .set(HedgePositionEntity::getFinalOutcome, finalOutcome)
                .set(HedgePositionEntity::getSettlingAt, new Date())
                .set(HedgePositionEntity::getUpdatedAt, new Date()));
    }

    /** 标记持仓已完成结算 */
    public void markSettled(Long positionId, BigDecimal claimedUsdc, BigDecimal feeUsdc) {
        HedgePositionEntity pos = positionMapper.selectById(positionId);
        if (pos == null) return;

        BigDecimal realizedPnl = claimedUsdc.subtract(pos.getTotalCostUsdc()).subtract(
                feeUsdc != null ? feeUsdc : BigDecimal.ZERO);

        positionMapper.update(null, new LambdaUpdateWrapper<HedgePositionEntity>()
                .eq(HedgePositionEntity::getId, positionId)
                .set(HedgePositionEntity::getStatus, "SETTLED")
                .set(HedgePositionEntity::getClaimedUsdc, claimedUsdc)
                .set(HedgePositionEntity::getFeeUsdc, feeUsdc)
                .set(HedgePositionEntity::getRealizedPnlUsdc, realizedPnl)
                .set(HedgePositionEntity::getSettledAt, new Date())
                .set(HedgePositionEntity::getUpdatedAt, new Date()));

        log.info("[HedgePositionTracker] Settled positionId={} claimed={} pnl={}",
                positionId, claimedUsdc, realizedPnl);
    }

    /** 标记持仓取消 */
    public void cancelPosition(Long positionId, String reason) {
        positionMapper.update(null, new LambdaUpdateWrapper<HedgePositionEntity>()
                .eq(HedgePositionEntity::getId, positionId)
                .set(HedgePositionEntity::getStatus, "CANCELLED")
                .set(HedgePositionEntity::getUpdatedAt, new Date()));
        log.info("[HedgePositionTracker] Cancelled positionId={} reason={}", positionId, reason);
    }

    /** 查询 OPEN 状态持仓列表 */
    public List<HedgePositionEntity> getOpenPositions() {
        return positionMapper.selectList(new LambdaQueryWrapper<HedgePositionEntity>()
                .eq(HedgePositionEntity::getStatus, "OPEN")
                .orderByDesc(HedgePositionEntity::getOpenedAt));
    }

    /** 查询 OPEN 或 SETTLING 状态持仓（用于结算监控）*/
    public List<HedgePositionEntity> getSettlementPendingPositions(int limit) {
        return positionMapper.selectList(new LambdaQueryWrapper<HedgePositionEntity>()
                .in(HedgePositionEntity::getStatus, List.of("OPEN", "SETTLING"))
                .orderByAsc(HedgePositionEntity::getOpenedAt)
                .last("limit " + limit));
    }

    /** 统计 SETTLED 持仓累计 PnL */
    public BigDecimal totalRealizedPnlUsdc() {
        return positionMapper.selectList(new LambdaQueryWrapper<HedgePositionEntity>()
                        .eq(HedgePositionEntity::getStatus, "SETTLED")
                        .select(HedgePositionEntity::getRealizedPnlUsdc))
                .stream()
                .map(p -> p.getRealizedPnlUsdc() != null ? p.getRealizedPnlUsdc() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
