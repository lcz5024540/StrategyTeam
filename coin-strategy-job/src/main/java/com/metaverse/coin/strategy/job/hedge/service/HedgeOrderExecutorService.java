package com.metaverse.coin.strategy.job.hedge.service;

import com.metaverse.coin.strategy.common.dao.mapper.HedgeArbitrageLogMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.HedgeArbitrageLogEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.HedgeOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.HedgePositionEntity;
import com.metaverse.coin.strategy.job.hedge.config.HedgeExecutionMode;
import com.metaverse.coin.strategy.job.hedge.config.HedgeProperties;
import com.metaverse.coin.strategy.job.hedge.model.HedgeOpportunity;
import com.metaverse.coin.strategy.job.hedge.model.HedgeRiskCheckResult;
import com.metaverse.coin.strategy.job.smartwallet.service.ExecutionWalletContextLoader;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketClobClientRouter;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketMarketDataCacheService;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketOrderRequestFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 对冲订单执行服务
 *
 * 将套利机会转化为实际的双边订单：
 *   1. 风控检查（调用 HedgeRiskGuard）
 *   2. 并发提交 YES + NO 两边限价单（复用 PolymarketClobClientRouter，不重复实现执行逻辑）
 *   3. 任一边失败则取消另一边（对冲完整性保护）
 *   4. 记录扫描日志
 *
 * 执行路径与跟单策略的隔离：
 *   - 读取同一执行钱包上下文（共享硬件，但资金逻辑隔离）
 *   - 写操作仅写 hedge_position、hedge_order、hedge_arbitrage_log 三张表
 *   - signalId 使用 "HEDGE-{marketId}" 前缀，与跟单 signalId 不重叠
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HedgeOrderExecutorService {

    private final HedgeProperties hedgeProperties;
    private final HedgeRiskGuard riskGuard;
    private final HedgeSizeCalculatorService sizeCalculator;
    private final HedgePositionTracker positionTracker;
    private final HedgeArbitrageLogMapper logMapper;
    private final PolymarketClobClientRouter clobClientRouter;
    private final ExecutionWalletContextLoader walletContextLoader;
    private final ArbitrageDetectorService arbitrageDetector;
    private final PolymarketMarketDataCacheService marketDataCache;
    private final HedgePaperExecutionService paperExecutionService;

    /** 双边下单并发线程池（YES + NO 同时提交，减少时间窗口） */
    private final ExecutorService orderExecutor = Executors.newFixedThreadPool(2);

    /**
     * 尝试对给定机会执行对冲建仓
     *
     * @param opportunity    套利机会
     * @param availableCapital 当前可用资金上界（调用方提供，避免重复查库）
     * @return true = 成功建仓，false = 跳过或失败
     */
    public boolean execute(HedgeOpportunity opportunity, BigDecimal availableCapital) {
        if (!hedgeProperties.isEnabled()) {
            saveLog(opportunity, "SKIP_DISABLED", "对冲策略未启用", null);
            return false;
        }

        // PAPER 模式分流：不走 CLOB，由 HedgePaperExecutionService 处理
        if (hedgeProperties.getExecutionMode() == HedgeExecutionMode.PAPER) {
            return paperExecutionService.execute(opportunity, availableCapital);
        }

        // 风控检查
        HedgeRiskCheckResult riskResult = riskGuard.check(opportunity, availableCapital);
        if (!riskResult.isPassed()) {
            String reason = String.join("; ", riskResult.getViolations());
            log.info("[HedgeExecutor] Risk blocked market={}: {}", opportunity.getMarketId(), reason);
            saveLog(opportunity, "SKIP_RISK", reason, null);
            return false;
        }

        BigDecimal totalUsdc = riskResult.getSuggestedSizeUsdc();
        BigDecimal quantity = sizeCalculator.calculateQuantity(opportunity, totalUsdc);
        BigDecimal expectedProfit = sizeCalculator.estimateProfitUsdc(opportunity, quantity);

        if (!hedgeProperties.isAutoExecutionEnabled()) {
            log.info("[HedgeExecutor] DRY-RUN market={} qty={} totalUsdc={} expectedProfit={}",
                    opportunity.getMarketId(), quantity, totalUsdc, expectedProfit);
            saveLog(opportunity, "SKIP_DISABLED", "autoExecution=false（演练模式）", null);
            return false;
        }

        // P2-2：执行前强制刷新订单簿缓存并重新验证机会（防止基于过时价格下单）
        marketDataCache.invalidateOrderBook(opportunity.getYesTokenId());
        marketDataCache.invalidateOrderBook(opportunity.getNoTokenId());
        ArbitrageDetectorService.MarketTokenPair freshPair = new ArbitrageDetectorService.MarketTokenPair(
                opportunity.getMarketId(), opportunity.getQuestion(),
                opportunity.getYesTokenId(), opportunity.getNoTokenId(),
                opportunity.getMarketEndAt());
        Optional<HedgeOpportunity> freshOpportunity;
        try {
            freshOpportunity = arbitrageDetector.detectOpportunity(freshPair);
        } catch (Exception e) {
            log.warn("[HedgeExecutor] Pre-execution price refresh failed market={}: {}", opportunity.getMarketId(), e.getMessage());
            saveLog(opportunity, "SKIP_RISK", "执行前价格刷新失败: " + e.getMessage(), null);
            return false;
        }
        if (freshOpportunity.isEmpty()) {
            log.info("[HedgeExecutor] Opportunity vanished after price refresh, skipping market={}", opportunity.getMarketId());
            saveLog(opportunity, "SKIP_STALE", "执行前重新验证：价格已变化，机会消失", null);
            return false;
        }
        // 使用最新价格继续执行
        opportunity = freshOpportunity.get();
        totalUsdc = riskResult.getSuggestedSizeUsdc();
        quantity = sizeCalculator.calculateQuantity(opportunity, totalUsdc);
        expectedProfit = sizeCalculator.estimateProfitUsdc(opportunity, quantity);

        // 加载执行钱包上下文（复用跟单策略的执行钱包，资金逻辑由数据库表隔离）
        ExecutionWalletContextLoader.ExecutionWalletContext context;
        try {
            context = walletContextLoader.requireLiveContext();
        } catch (Exception e) {
            log.warn("[HedgeExecutor] ExecutionWallet not ready: {}", e.getMessage());
            saveLog(opportunity, "SKIP_RISK", "执行钱包未就绪: " + e.getMessage(), null);
            return false;
        }

        // 开仓记录
        HedgePositionEntity position = positionTracker.openPosition(
                opportunity, quantity, totalUsdc, expectedProfit, context.getAddress());

        // 并发提交双边订单
        boolean success = submitBothSides(position, opportunity, quantity, context);
        if (!success) {
            positionTracker.cancelPosition(position.getId(), "双边下单失败");
            saveLog(opportunity, "SKIP_RISK", "双边下单执行失败", null);
            return false;
        }

        saveLog(opportunity, "EXECUTED", null, position.getId());
        return true;
    }

    /**
     * 并发提交 YES 和 NO 两边订单，任一失败则回滚另一边
     */
    private boolean submitBothSides(HedgePositionEntity position,
                                     HedgeOpportunity opp, BigDecimal quantity,
                                     ExecutionWalletContextLoader.ExecutionWalletContext context) {
        HedgeOrderEntity yesOrderRecord = positionTracker.createOrder(
                position.getId(), opp.getMarketId(), opp.getYesTokenId(),
                "YES", opp.getYesBestAsk(), quantity);
        HedgeOrderEntity noOrderRecord = positionTracker.createOrder(
                position.getId(), opp.getMarketId(), opp.getNoTokenId(),
                "NO", opp.getNoBestAsk(), quantity);

        String hedgeSignalPrefix = "HEDGE-" + opp.getMarketId();

        // 含滑点的价格上限（防止成交价过高导致负收益）
        BigDecimal yesLimit = opp.getYesBestAsk()
                .multiply(BigDecimal.ONE.add(hedgeProperties.getMaxSlippageRate()))
                .setScale(4, RoundingMode.HALF_UP);
        BigDecimal noLimit = opp.getNoBestAsk()
                .multiply(BigDecimal.ONE.add(hedgeProperties.getMaxSlippageRate()))
                .setScale(4, RoundingMode.HALF_UP);

        CompletableFuture<OrderResult> yesFuture = CompletableFuture.supplyAsync(
                () -> submitOneOrder(context, hedgeSignalPrefix + "-YES",
                        opp.getMarketId(), opp.getQuestion(), "Yes",
                        opp.getYesTokenId(), yesLimit, quantity,
                        context.getAddress(), context.getFunderAddress()),
                orderExecutor);

        CompletableFuture<OrderResult> noFuture = CompletableFuture.supplyAsync(
                () -> submitOneOrder(context, hedgeSignalPrefix + "-NO",
                        opp.getMarketId(), opp.getQuestion(), "No",
                        opp.getNoTokenId(), noLimit, quantity,
                        context.getAddress(), context.getFunderAddress()),
                orderExecutor);

        OrderResult yesResult;
        OrderResult noResult;
        try {
            yesResult = yesFuture.get(30, TimeUnit.SECONDS);
            noResult = noFuture.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("[HedgeExecutor] Timeout waiting for order submission market={}", opp.getMarketId(), e);
            positionTracker.updateOrderFailed(yesOrderRecord.getId(), "超时: " + e.getMessage());
            positionTracker.updateOrderFailed(noOrderRecord.getId(), "超时: " + e.getMessage());
            return false;
        }

        if (!yesResult.success()) {
            positionTracker.updateOrderFailed(yesOrderRecord.getId(), yesResult.error());
            positionTracker.updateOrderFailed(noOrderRecord.getId(), "YES边失败，NO边中止");
            log.warn("[HedgeExecutor] YES order failed market={}: {}", opp.getMarketId(), yesResult.error());
            return false;
        }
        if (!noResult.success()) {
            positionTracker.updateOrderFailed(noOrderRecord.getId(), noResult.error());
            // 尝试取消已提交的 YES 订单（尽力而为）
            tryCancelViaRouter(context, yesResult.externalOrderId());
            positionTracker.updateOrderFailed(yesOrderRecord.getId(), "NO边失败，已请求取消YES单");
            log.warn("[HedgeExecutor] NO order failed market={}: {}", opp.getMarketId(), noResult.error());
            return false;
        }

        // 两边订单已成功提交到 Polymarket CLOB，标记为 LIVE 等待轮询确认实际成交量
        // （GTC 限价单：提交成功 ≠ 立即成交，实际成交由 HedgeOrderPollingJob 异步确认）
        positionTracker.updateOrderSubmitted(yesOrderRecord.getId(), yesResult.externalOrderId());
        positionTracker.updateOrderSubmitted(noOrderRecord.getId(), noResult.externalOrderId());

        log.info("[HedgeExecutor] Both sides submitted (pending fill confirmation): market={} qty={} yesOrderId={} noOrderId={}",
                opp.getMarketId(), quantity, yesResult.externalOrderId(), noResult.externalOrderId());
        return true;
    }

    private OrderResult submitOneOrder(ExecutionWalletContextLoader.ExecutionWalletContext context,
                                        String signalId, String marketId, String question,
                                        String outcome, String tokenId,
                                        BigDecimal limitPrice, BigDecimal size,
                                        String executionAddress, String funderAddress) {
        try {
            PolymarketOrderRequestFactory.PreparedPolymarketOrder order =
                    PolymarketOrderRequestFactory.PreparedPolymarketOrder.builder()
                            .signalId(signalId)
                            .marketId(marketId)
                            .question(question)
                            .outcome(outcome)
                            .side("BUY")
                            .tokenId(tokenId)
                            .limitPrice(limitPrice)
                            .size(size)
                            .stakeUsdc(limitPrice.multiply(size).setScale(4, RoundingMode.HALF_UP))
                            .executionAddress(executionAddress)
                            .funderAddress(funderAddress)
                            .tickSize(new BigDecimal("0.01"))
                            .negRisk(false)
                            .build();

            Map<String, Object> response = clobClientRouter.placeOrder(context, order);
            if (response == null) {
                return new OrderResult(false, null, "null response from CLOB router");
            }

            // 解析 orderId（参考现有 LoggingPolymarketExecutionGateway 的解析逻辑）
            String orderId = extractOrderId(response);
            boolean success = orderId != null && !orderId.isBlank();
            return new OrderResult(success, orderId,
                    success ? null : "No orderId in response: " + response);

        } catch (Exception e) {
            log.error("[HedgeExecutor] Order submission error signalId={}: {}", signalId, e.getMessage());
            return new OrderResult(false, null, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private String extractOrderId(Map<String, Object> response) {
        Object orderId = response.get("orderId");
        if (orderId instanceof String s && StringUtils.hasText(s)) return s;
        Object orderMsg = response.get("orderID");
        if (orderMsg instanceof String s && StringUtils.hasText(s)) return s;
        // 兼容嵌套结构
        Object data = response.get("data");
        if (data instanceof Map<?, ?> m) {
            Object nested = ((Map<String, Object>) m).get("orderId");
            if (nested instanceof String s && StringUtils.hasText(s)) return s;
        }
        return null;
    }

    private void tryCancelViaRouter(ExecutionWalletContextLoader.ExecutionWalletContext context,
                                     String externalOrderId) {
        if (!StringUtils.hasText(externalOrderId)) return;
        try {
            clobClientRouter.cancelOrder(context, externalOrderId);
        } catch (Exception e) {
            log.warn("[HedgeExecutor] Failed to cancel order {}: {}", externalOrderId, e.getMessage());
        }
    }

    private void saveLog(HedgeOpportunity opp, String action, String skipReason, Long positionId) {
        try {
            HedgeArbitrageLogEntity entity = new HedgeArbitrageLogEntity()
                    .setMarketId(opp.getMarketId())
                    .setQuestion(opp.getQuestion())
                    .setYesTokenId(opp.getYesTokenId())
                    .setNoTokenId(opp.getNoTokenId())
                    .setYesBestAsk(opp.getYesBestAsk())
                    .setNoBestAsk(opp.getNoBestAsk())
                    .setYesLiquidityUsdc(opp.getYesLiquidityUsdc())
                    .setNoLiquidityUsdc(opp.getNoLiquidityUsdc())
                    .setTotalImpliedProb(opp.getTotalImpliedProb())
                    .setProbDeviation(opp.getProbDeviation())
                    .setGrossEdgeRate(opp.getGrossEdgeRate())
                    .setAction(action)
                    .setSkipReason(skipReason)
                    .setHedgePositionId(positionId)
                    .setMode(hedgeProperties.getExecutionMode() == HedgeExecutionMode.PAPER ? "PAPER" : "LIVE")
                    .setScannedAt(new Date());
            logMapper.insert(entity);
        } catch (Exception e) {
            log.warn("[HedgeExecutor] Failed to save arbitrage log: {}", e.getMessage());
        }
    }

    private record OrderResult(boolean success, String externalOrderId, String error) {
    }
}
