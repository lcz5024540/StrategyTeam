package com.metaverse.coin.strategy.job.hedge.service;

import com.metaverse.coin.strategy.common.dao.mapper.HedgeArbitrageLogMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.HedgeArbitrageLogEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.HedgeOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.HedgePositionEntity;
import com.metaverse.coin.strategy.job.hedge.config.HedgeProperties;
import com.metaverse.coin.strategy.job.hedge.model.HedgeOpportunity;
import com.metaverse.coin.strategy.job.hedge.model.HedgeRiskCheckResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;

/**
 * 对冲策略 PAPER 模式执行服务。
 *
 * 与 {@link HedgeOrderExecutorService} 的 LIVE 路径共享同一套风控 + Kelly 资金计算，
 * 但下单环节改为"本地模拟立即成交"：
 *   1. 不调用 {@code PolymarketClobClientRouter}
 *   2. 不消耗链上资金，不依赖钱包上下文
 *   3. YES / NO 两条订单记录创建后立刻以 bestAsk （含可选模拟滑点）视为 FILLED
 *   4. 持仓 mode = PAPER，订单 mode = PAPER
 *
 * 市场结算监控由 {@link HedgeSettlementMonitorJob} 在 mode=PAPER 分支内以"理论结算"方式
 * 直接写入 realized_pnl_usdc，不触发链上 redeem。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HedgePaperExecutionService {

    private static final String MODE = "PAPER";

    private final HedgeProperties hedgeProperties;
    private final HedgeRiskGuard riskGuard;
    private final HedgeSizeCalculatorService sizeCalculator;
    private final HedgePositionTracker positionTracker;
    private final HedgeArbitrageLogMapper logMapper;

    /**
     * 尝试以模拟仓方式建仓。
     *
     * @param opportunity      套利机会
     * @param availableCapital 当前可用资金上界（PAPER 也受 maxTotalExposureUsdc 约束以保证配置一致）
     * @return true 成功建立模拟仓
     */
    public boolean execute(HedgeOpportunity opportunity, BigDecimal availableCapital) {
        if (!hedgeProperties.isEnabled()) {
            saveLog(opportunity, "SKIP_DISABLED", "对冲策略未启用");
            return false;
        }

        // 复用 LIVE 风控：同一套流动性/净利率/重复建仓/全局敞口检查
        HedgeRiskCheckResult riskResult = riskGuard.check(opportunity, availableCapital);
        if (!riskResult.isPassed()) {
            String reason = String.join("; ", riskResult.getViolations());
            log.info("[HedgePaper] Risk blocked market={}: {}", opportunity.getMarketId(), reason);
            saveLog(opportunity, "SKIP_RISK", reason);
            return false;
        }

        BigDecimal totalUsdc = riskResult.getSuggestedSizeUsdc();
        BigDecimal quantity = sizeCalculator.calculateQuantity(opportunity, totalUsdc);
        BigDecimal expectedProfit = sizeCalculator.estimateProfitUsdc(opportunity, quantity);

        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            saveLog(opportunity, "SKIP_RISK", "计算出的模拟建仓数量为 0");
            return false;
        }

        // 1. 创建 PAPER 持仓
        HedgePositionEntity position = positionTracker.openPosition(
                opportunity, quantity, totalUsdc, expectedProfit,
                "paper-simulator", MODE);

        // 2. 以 bestAsk(+可选滑点) 立即成交双边
        BigDecimal slippage = hedgeProperties.getPaper().getSimulateFillSlippageRate();
        BigDecimal slipMul = BigDecimal.ONE.add(slippage == null ? BigDecimal.ZERO : slippage);

        BigDecimal yesFillPrice = opportunity.getYesBestAsk().multiply(slipMul)
                .setScale(6, RoundingMode.HALF_UP);
        BigDecimal noFillPrice = opportunity.getNoBestAsk().multiply(slipMul)
                .setScale(6, RoundingMode.HALF_UP);
        BigDecimal yesCost = yesFillPrice.multiply(quantity).setScale(4, RoundingMode.HALF_UP);
        BigDecimal noCost = noFillPrice.multiply(quantity).setScale(4, RoundingMode.HALF_UP);

        HedgeOrderEntity yesOrder = positionTracker.createOrder(
                position.getId(), opportunity.getMarketId(), opportunity.getYesTokenId(),
                "YES", yesFillPrice, quantity, MODE);
        HedgeOrderEntity noOrder = positionTracker.createOrder(
                position.getId(), opportunity.getMarketId(), opportunity.getNoTokenId(),
                "NO", noFillPrice, quantity, MODE);

        String paperYesOrderId = "paper-" + position.getId() + "-YES-" + System.currentTimeMillis();
        String paperNoOrderId = "paper-" + position.getId() + "-NO-" + System.currentTimeMillis();

        // 模拟器直接走完 "提交成功 → 轮询确认成交" 两步
        positionTracker.updateOrderFilled(yesOrder.getId(), paperYesOrderId, quantity, yesCost);
        positionTracker.updateOrderFilled(noOrder.getId(), paperNoOrderId, quantity, noCost);
        positionTracker.syncActualPositionIfBothFilled(position.getId());

        log.info("[HedgePaper] Opened PAPER position id={} market={} qty={} yesCost={} noCost={} expectedProfit={}",
                position.getId(), opportunity.getMarketId(), quantity, yesCost, noCost, expectedProfit);
        saveLog(opportunity, "EXECUTED", null, position.getId());
        return true;
    }

    // ---- helpers ----

    private void saveLog(HedgeOpportunity opp, String action, String skipReason) {
        saveLog(opp, action, skipReason, null);
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
                    .setMode(MODE)
                    .setScannedAt(new Date());
            logMapper.insert(entity);
        } catch (Exception e) {
            log.warn("[HedgePaper] Failed to save arbitrage log: {}", e.getMessage());
        }
    }
}
