package com.metaverse.coin.strategy.core.arbitrage.decision;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SingleExchangeArbitrageFundingSettlementMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SingleExchangeArbitrageOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SingleExchangeArbitragePositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SingleExchangeArbitrageRiskSnapshotMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SingleExchangeArbitrageFundingSettlement;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SingleExchangeArbitrageOrder;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SingleExchangeArbitragePosition;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SingleExchangeArbitrageRiskSnapshot;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesIncome;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesPremiumIndex;
import com.metaverse.coin.strategy.core.Strategy;
import com.metaverse.coin.strategy.core.arbitrage.config.SingleExchangeSpotFuturesProperties;
import com.metaverse.coin.strategy.core.arbitrage.execution.SingleExchangeArbitrageExecutionResult;
import com.metaverse.coin.strategy.core.arbitrage.execution.SingleExchangeArbitrageOrderExecutor;
import com.metaverse.coin.strategy.core.arbitrage.execution.SingleExchangeSpotFuturesOrderPlan;
import com.metaverse.coin.strategy.core.arbitrage.market.BinanceQueryDataProvider;
import com.metaverse.coin.strategy.core.arbitrage.monitor.SingleExchangeFundingRateMonitor;
import com.metaverse.coin.strategy.core.arbitrage.risk.SingleExchangeLiquidityAssessment;
import com.metaverse.coin.strategy.core.arbitrage.risk.SingleExchangeRiskService;
import com.metaverse.coin.strategy.core.arbitrage.risk.SingleExchangeRiskSnapshotData;
import com.metaverse.coin.strategy.core.arbitrage.risk.SingleExchangeTradeRiskAssessment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Spot-Futures Arbitrage Strategy (Single Exchange)
 *
 * <p>单交易所现货-合约套利策略。当前实现默认围绕 Binance 单一交易对运行:
 * 当资金费率、基差、流动性和账户风险均满足条件时，同时买入现货并开合约空单;
 * 持仓期间持续记录资金费结算，并在收益吸引力下降或风险恶化时同时平掉两条腿。
 *
 * Strategy Logic:
 * 1. Build a real trade plan from current spot/futures prices.
 * 2. Open only when funding, basis and settlement timing all pass filters.
 * 3. Close when funding weakens, basis deteriorates or margin health becomes unsafe.
 */
@Component
@ConditionalOnExpression("'${binance.api.api-key:}' != '' && '${binance.api.secret-key:}' != ''")
@Slf4j
@RequiredArgsConstructor
public class SingleExchangeSpotFuturesArbitrageStrategy implements Strategy {

    private final SingleExchangeFundingRateMonitor fundingRateMonitor;
    private final SingleExchangeArbitrageOrderExecutor orderExecutor;
    private final SingleExchangeRiskService riskService;
    private final SingleExchangeArbitrageFundingSettlementMapper fundingSettlementMapper;
    private final SingleExchangeArbitrageOrderMapper orderMapper;
    private final SingleExchangeArbitragePositionMapper positionMapper;
    private final SingleExchangeArbitrageRiskSnapshotMapper riskSnapshotMapper;
    private final SingleExchangeSpotFuturesProperties properties;
    private final BinanceQueryDataProvider queryDataProvider;

    private static final String EXCHANGE_NAME = "BINANCE";
    private static final String STATUS_OPENING = "OPENING";
    private static final String STATUS_OPENED = "OPENED";
    private static final String STATUS_CLOSING = "CLOSING";
    private static final String STATUS_CLOSED = "CLOSED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String CHECK_STAGE_PRE_OPEN = "PRE_OPEN";
    private static final String CHECK_STAGE_PRE_CLOSE = "PRE_CLOSE";
    private static final String LOG_LIQUIDITY_REJECT_OPEN = "LIQUIDITY_REJECT_OPEN";
    private static final String LOG_LIQUIDITY_WARN_CLOSE = "LIQUIDITY_WARN_CLOSE";
    private static final String LOG_LIQUIDITY_WARN_EMERGENCY_CLOSE = "LIQUIDITY_WARN_EMERGENCY_CLOSE";
    private static final String FUNDING_DIRECTION_RECEIVED = "RECEIVED";
    private static final String FUNDING_DIRECTION_PAID = "PAID";
    // 资金费实际入账(FUNDING_FEE income)按结算点前后各留 5 分钟窗口拉取，覆盖交易所入账时间抖动。
    private static final long FUNDING_INCOME_WINDOW_MILLIS = TimeUnit.MINUTES.toMillis(5);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final Map<String, ActivePosition> activePositions = new ConcurrentHashMap<>();

    @Override
    public String getStrategyId() {
        return "SINGLE_EXCHANGE_SPOT_FUTURES_ARBITRAGE_V1";
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("Starting Spot-Futures Arbitrage Strategy...");
            restorePersistedPositions();
            scheduler.scheduleAtFixedRate(this::executeLogic, 0, 1, TimeUnit.MINUTES);
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping Spot-Futures Arbitrage Strategy...");
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    public String runOnce() {
        boolean wasRunning = running.get();
        if (!wasRunning) {
            running.set(true);
            // 手动 run-once 可能绕过 start()，这里同样先恢复未完成仓位，避免重启后误判为空仓而重复开仓。
            restorePersistedPositions();
        }
        try {
            executeLogic();
            return "Single exchange spot-futures arbitrage run-once completed.";
        } finally {
            if (!wasRunning) {
                running.set(false);
            }
        }
    }

    private void executeLogic() {
        if (!running.get()) {
            return;
        }

        try {
            String symbol = properties.getSymbol();
            log.info("Checking arbitrage opportunities for {}", symbol);

            // 1. 读取合约资金费率。资金费率是本策略的收益来源，缺失时不做任何交易决策。
            BinanceFuturesPremiumIndex premiumIndex = fundingRateMonitor.getBinanceFundingRate(symbol);
            if (premiumIndex == null || premiumIndex.getLastFundingRate() == null) {
                log.warn("Funding data unavailable for {}", symbol);
                return;
            }

            // 2. 基于当前现货/合约价格构建一份可执行计划，后续所有风控和下单都使用同一份快照。
            BigDecimal currentRate = premiumIndex.getLastFundingRate();
            SingleExchangeSpotFuturesOrderPlan marketPlan = orderExecutor.createSpotFuturesPlan(
                    symbol,
                    properties.getTradeUsdtAmount(),
                    properties.getLeverage()
            );
            marketPlan.setFundingRate(currentRate);
            log.info(
                    "Opportunity snapshot for {}: fundingRate={}, basisRate={}, basisSpread={}, quantity={}, nextFundingTime={}",
                    symbol,
                    currentRate,
                    marketPlan.getBasisRate(),
                    marketPlan.getBasisSpread(),
                    marketPlan.getQuantity(),
                    premiumIndex.getNextFundingTime()
            );

            //注意：理论上一个 symbol 只会有一个未完成仓位
            ActivePosition activePosition = activePositions.get(symbol);
            if (activePosition == null) {
                // 3. 无持仓时进入开仓路径。先检查盘口深度/滑点，避免收益被成交冲击吞掉。
                SingleExchangeLiquidityAssessment liquidityAssessment = riskService.evaluateLiquidityRisk(
                        marketPlan,
                        SingleExchangeRiskService.PHASE_OPEN
                );

                if (!liquidityAssessment.isPassed()) {//流动性检查未通过时，持久化风险快照
                    persistRiskSnapshot(
                            null,
                            null,
                            CHECK_STAGE_PRE_OPEN,
                            false,
                            buildPreOpenRiskReason("LIQUIDITY_REJECTED", liquidityAssessment.getRejectionReason()),
                            riskService.captureRiskSnapshotData(marketPlan)
                    );
                    log.warn("{} for {}: {}", LOG_LIQUIDITY_REJECT_OPEN, symbol, liquidityAssessment.getRejectionReason());
                    return;
                }

                // 4. 再检查策略阈值: 资金费率、结算保护窗口、基差上下限、预估净收益。
                String openBlockReason = getOpenBlockReason(
                        premiumIndex,
                        marketPlan,
                        liquidityAssessment.getEstimatedSlippageRate()
                );
                if (openBlockReason != null) {
                    log.info("Skip open for {}: {}", symbol, openBlockReason);
                    return;
                }

                // 5. 账户级风控放在真正下单前，包括余额、保证金、杠杆缓冲等硬约束。
                SingleExchangeTradeRiskAssessment tradeRiskAssessment = riskService.evaluateTradeRisk(marketPlan);
                if (!tradeRiskAssessment.isPassed()) {//账户级风控未通过，持久化风险快照
                    persistRiskSnapshot(
                            null,
                            null,
                            CHECK_STAGE_PRE_OPEN,
                            false,
                            buildPreOpenRiskReason("TRADE_RISK_REJECTED", tradeRiskAssessment.getRejectionReason()),
                            tradeRiskAssessment.getSnapshotData()
                    );
                    log.warn("Trade rejected by Risk Service for {}", symbol);
                    return;
                }

                // 6. 先落库 OPENING 状态，再发真实/测试订单。这样即使进程中断，也能靠数据库恢复现场。
                SingleExchangeArbitragePosition persistedPosition = createOpeningPosition(marketPlan, premiumIndex);
                if (persistedPosition == null) {
                    log.warn("Skip open for {} because position state could not be persisted", symbol);
                    return;
                }
                persistRiskSnapshot(
                        persistedPosition.getId(),
                        persistedPosition.getPositionCode(),
                        CHECK_STAGE_PRE_OPEN,
                        true,
                        null,
                        tradeRiskAssessment.getSnapshotData()
                );

                log.info(
                        "Opening position for {} with quantity={}, spotNotional={}, futuresNotional={}, leverage={}",
                        symbol,
                        marketPlan.getQuantity(),
                        marketPlan.getSpotNotional(),
                        marketPlan.getFuturesNotional(),
                        marketPlan.getLeverage()
                );
                SingleExchangeArbitrageExecutionResult result = orderExecutor.openArbitragePosition(marketPlan);
                persistOrderRecords(persistedPosition.getId(), persistedPosition.getPositionCode(), result.getOrderRecords());
                if (result.isSuccess()) {
                    // 7. 开仓成功后更新为 OPENED，并写入内存 activePositions，下一轮开始走持仓管理路径。
                    SingleExchangeArbitragePosition openedPosition = markPositionOpened(persistedPosition, marketPlan, premiumIndex, result);
                    activePositions.put(symbol, ActivePosition.fromEntity(openedPosition));
                } else {
                    markPositionFailed(persistedPosition.getId(), result.getMessage());
                    log.warn("Failed to open arbitrage position for {}: {}", symbol, result.getMessage());
                }
                return;
            }

            // 8. 有持仓时先补记已跨过的资金费结算，再判断是否需要退出。
            activePosition = reconcileFundingSettlement(activePosition, premiumIndex, marketPlan);
            CloseDecision closeDecision = getCloseDecision(premiumIndex, marketPlan, activePosition);
            if (closeDecision == null) {
                return;
            }

            // 9. 平仓使用当前价格重建计划，但数量/杠杆沿用开仓持仓，确保两侧按原仓位对称退出。
            SingleExchangeSpotFuturesOrderPlan closePlan = SingleExchangeSpotFuturesOrderPlan.builder()
                    .symbol(symbol)
                    .spotSymbol(symbol)
                    .futuresSymbol(symbol)
                    .quantity(activePosition.quantity())
                    .spotPrice(marketPlan.getSpotPrice())
                    .futuresPrice(marketPlan.getFuturesPrice())
                    .spotNotional(activePosition.quantity().multiply(defaultValue(marketPlan.getSpotPrice())))
                    .futuresNotional(activePosition.quantity().multiply(defaultValue(marketPlan.getFuturesPrice())))
                    .leverage(activePosition.leverage())
                    .build();
            SingleExchangeLiquidityAssessment closeLiquidityAssessment = riskService.evaluateLiquidityRisk(
                    closePlan,
                    SingleExchangeRiskService.PHASE_CLOSE
            );
            // 10. 平仓流动性检查只记录告警，不阻断退出。风险触发后继续持仓通常比带滑点退出更危险。
            persistRiskSnapshot(
                    activePosition.positionId(),
                    activePosition.positionCode(),
                    CHECK_STAGE_PRE_CLOSE,
                    closeLiquidityAssessment.isPassed(),
                    buildPreCloseRiskReason(closeDecision.reason(), closeLiquidityAssessment),
                    riskService.captureRiskSnapshotData(closePlan)
            );
            if (!closeLiquidityAssessment.isPassed()) {
                String logType = closeDecision.emergency() ? LOG_LIQUIDITY_WARN_EMERGENCY_CLOSE : LOG_LIQUIDITY_WARN_CLOSE;
                log.warn("{} for {}: {}, reason={}",
                        logType,
                        symbol,
                        closeLiquidityAssessment.getRejectionReason(),
                        closeDecision.reason());
            }
            log.info(
                    "Closing position for {} because {}. positionId={}, quantity={}, currentFundingRate={}, currentBasisRate={}, entryBasisRate={}",
                    symbol,
                    closeDecision.reason(),
                    activePosition.positionId(),
                    activePosition.quantity(),
                    currentRate,
                    marketPlan.getBasisRate(),
                    activePosition.entryBasisRate()
            );
            markPositionClosing(activePosition);
            SingleExchangeArbitrageExecutionResult closeResult = orderExecutor.closeArbitragePosition(closePlan);
            persistOrderRecords(activePosition.positionId(), activePosition.positionCode(), closeResult.getOrderRecords());
            if (closeResult.isSuccess()) {
                // 11. 平仓成功后标记 CLOSED 并移除内存持仓，下一轮才允许重新寻找开仓机会。
                markPositionClosed(activePosition, marketPlan, currentRate, closeResult);
                activePositions.remove(symbol);
            } else {
                // 平仓失败时恢复为 OPENED，避免数据库长期停留在 CLOSING，后续轮次可继续尝试退出。
                revertPositionToOpened(activePosition);
                log.warn("Failed to close arbitrage position for {} after trigger: {}", symbol, closeDecision.reason());
            }
        } catch (Exception e) {
            log.error("Error in strategy execution loop", e);
        }
    }

    private void restorePersistedPositions() {
        try {
            // 启动恢复只关心未完成仓位，防止服务重启后忘记已有风险敞口而重复开仓。
            List<SingleExchangeArbitragePosition> persistedPositions = positionMapper.selectList(
                    new LambdaQueryWrapper<SingleExchangeArbitragePosition>()
                            .eq(SingleExchangeArbitragePosition::getStrategyType, getStrategyId())
                            .eq(SingleExchangeArbitragePosition::getSymbol, properties.getSymbol())
                            .in(SingleExchangeArbitragePosition::getStatus, STATUS_OPENING, STATUS_OPENED, STATUS_CLOSING)
                            .orderByDesc(SingleExchangeArbitragePosition::getEntryTime)
                            .orderByDesc(SingleExchangeArbitragePosition::getId)
            );

            if (persistedPositions == null || persistedPositions.isEmpty()) {
                return;
            }

            // 多条活跃记录属于异常状态，这里选择最新一条恢复，避免同时管理多组同 symbol 仓位。
            SingleExchangeArbitragePosition recovered = persistedPositions.stream()
                    .filter(position -> position.getQuantity() != null && position.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                    .max(Comparator.comparing(SingleExchangeArbitragePosition::getId))
                    .orElse(persistedPositions.get(0));

            if (persistedPositions.size() > 1) {
                log.warn("Found {} persisted active positions for {}, recovering the latest record id={}",
                        persistedPositions.size(), properties.getSymbol(), recovered.getId());
            }

            activePositions.put(properties.getSymbol(), ActivePosition.fromEntity(recovered));
            log.info("Recovered persisted position for {}: id={}, status={}, quantity={}",
                    properties.getSymbol(), recovered.getId(), recovered.getStatus(), recovered.getQuantity());
        } catch (Exception e) {
            log.error("Failed to restore persisted arbitrage positions for {}", properties.getSymbol(), e);
        }
    }

    private SingleExchangeArbitragePosition createOpeningPosition(SingleExchangeSpotFuturesOrderPlan marketPlan,
                                                                  BinanceFuturesPremiumIndex premiumIndex) {
        try {
            SingleExchangeArbitragePosition position = new SingleExchangeArbitragePosition()
                    .setPositionCode(generatePositionCode(marketPlan.getSymbol()))
                    .setExchangeName(EXCHANGE_NAME)
                    .setSymbol(marketPlan.getSymbol())
                    .setSpotSymbol(marketPlan.getSpotSymbol())
                    .setFuturesSymbol(marketPlan.getFuturesSymbol())
                    .setStrategyType(getStrategyId())
                    .setStatus(STATUS_OPENING)
                    .setLeverage(marketPlan.getLeverage())
                    .setPlannedUsdtAmount(properties.getTradeUsdtAmount())
                    .setQuantity(marketPlan.getQuantity())
                    .setSpotNotional(marketPlan.getSpotNotional())
                    .setFuturesNotional(marketPlan.getFuturesNotional())
                    .setTotalInvestment(estimateTotalInvestment(marketPlan))
                    .setEntryTime(new Date())
                    .setEntrySpotPrice(marketPlan.getSpotPrice())
                    .setEntryFuturesPrice(marketPlan.getFuturesPrice())
                    .setEntryFundingRate(premiumIndex.getLastFundingRate())
                    .setEntryBasisSpread(marketPlan.getBasisSpread())
                    .setEntryBasisRate(marketPlan.getBasisRate())
                    .setEntryNextFundingTime(premiumIndex.getNextFundingTime())
                    .setRealizedPnl(BigDecimal.ZERO)
                    .setFundingFeesEarned(BigDecimal.ZERO)
                    .setUpdatedAt(new Date());
            positionMapper.insert(position);
            return position;
        } catch (Exception e) {
            log.error("Failed to persist opening position for {}", marketPlan.getSymbol(), e);
            return null;
        }
    }

    private SingleExchangeArbitragePosition markPositionOpened(SingleExchangeArbitragePosition position,
                                                               SingleExchangeSpotFuturesOrderPlan marketPlan,
                                                               BinanceFuturesPremiumIndex premiumIndex,
                                                               SingleExchangeArbitrageExecutionResult result) {
        position.setStatus(STATUS_OPENED)
                .setSpotBuyOrderId(result.getSpotOrderId())
                .setFuturesSellOrderId(result.getFuturesOrderId())
                .setEntrySpotPrice(marketPlan.getSpotPrice())
                .setEntryFuturesPrice(marketPlan.getFuturesPrice())
                .setEntryFundingRate(premiumIndex.getLastFundingRate())
                .setEntryBasisSpread(marketPlan.getBasisSpread())
                .setEntryBasisRate(marketPlan.getBasisRate())
                .setEntryNextFundingTime(premiumIndex.getNextFundingTime())
                .setUpdatedAt(new Date())
                .setErrorMessage(null);
        try {
            positionMapper.updateById(position);
        } catch (Exception e) {
            log.error("Failed to mark position {} as opened", position.getId(), e);
        }
        return position;
    }

    private void markPositionFailed(Long positionId, String message) {
        updatePosition(positionId, position -> position.setStatus(STATUS_FAILED)
                .setErrorMessage(message)
                .setUpdatedAt(new Date()));
    }

    private void markPositionClosing(ActivePosition activePosition) {
        updatePosition(activePosition.positionId(), position -> position.setStatus(STATUS_CLOSING)
                .setUpdatedAt(new Date()));
    }

    private void markPositionClosed(ActivePosition activePosition,
                                    SingleExchangeSpotFuturesOrderPlan marketPlan,
                                    BigDecimal currentFundingRate,
                                    SingleExchangeArbitrageExecutionResult closeResult) {
        updatePosition(activePosition.positionId(), position -> position.setStatus(STATUS_CLOSED)
                .setExitTime(new Date())
                .setExitSpotPrice(marketPlan.getSpotPrice())
                .setExitFuturesPrice(marketPlan.getFuturesPrice())
                .setSpotSellOrderId(closeResult.getSpotOrderId())
                .setFuturesBuyOrderId(closeResult.getFuturesOrderId())
                .setExitFundingRate(currentFundingRate)
                .setExitBasisSpread(marketPlan.getBasisSpread())
                .setExitBasisRate(marketPlan.getBasisRate())
                .setUpdatedAt(new Date())
                .setErrorMessage(null));
    }

    private void revertPositionToOpened(ActivePosition activePosition) {
        updatePosition(activePosition.positionId(), position -> position.setStatus(STATUS_OPENED)
                .setUpdatedAt(new Date()));
    }

    private void updatePosition(Long positionId, java.util.function.Consumer<SingleExchangeArbitragePosition> mutator) {
        if (positionId == null) {
            return;
        }
        try {
            SingleExchangeArbitragePosition position = positionMapper.selectById(positionId);
            if (position == null) {
                log.warn("Position {} not found during persistence update", positionId);
                return;
            }
            mutator.accept(position);
            positionMapper.updateById(position);
        } catch (Exception e) {
            log.error("Failed to update persisted position {}", positionId, e);
        }
    }

    private void persistOrderRecords(Long positionId, String positionCode, List<SingleExchangeArbitrageOrder> orderRecords) {
        if (positionId == null || orderRecords == null || orderRecords.isEmpty()) {
            return;
        }
        for (SingleExchangeArbitrageOrder orderRecord : orderRecords) {
            if (orderRecord == null) {
                continue;
            }
            try {
                orderRecord.setPositionId(positionId)
                        .setPositionCode(positionCode)
                        .setCreatedAt(new Date())
                        .setUpdatedAt(new Date());
                orderMapper.insert(orderRecord);
            } catch (Exception e) {
                log.error("Failed to persist arbitrage order record for positionId={}, phase={}, hedgeSide={}",
                        positionId, orderRecord.getTradePhase(), orderRecord.getHedgeSide(), e);
            }
        }
    }

    private ActivePosition reconcileFundingSettlement(ActivePosition activePosition,
                                                      BinanceFuturesPremiumIndex premiumIndex,
                                                      SingleExchangeSpotFuturesOrderPlan marketPlan) {
        if (activePosition == null || activePosition.positionId() == null) {
            return activePosition;
        }
        Long lastRecordedFundingTime = activePosition.nextFundingTime();
        Long latestNextFundingTime = premiumIndex.getNextFundingTime();
        // Binance 返回的 nextFundingTime 前进，且当前时间已经跨过旧结算点，才认为产生了一次资金费结算。
        if (lastRecordedFundingTime == null
                || latestNextFundingTime == null
                || latestNextFundingTime <= lastRecordedFundingTime
                || System.currentTimeMillis() < lastRecordedFundingTime) {
            return activePosition;
        }

        SingleExchangeArbitrageFundingSettlement settlement = buildFundingSettlement(
                activePosition,
                premiumIndex,
                marketPlan,
                lastRecordedFundingTime
        );
        FundingSettlementPersistResult persistResult = persistFundingSettlement(settlement);
        if (!persistResult.proceed()) {
            return activePosition;
        }
        if (!advancePositionFundingState(
                activePosition.positionId(),
                latestNextFundingTime,
                settlement.getSettlementAmount(),
                persistResult.inserted())) {
            return activePosition;
        }

        ActivePosition updatedPosition = activePosition.withNextFundingTime(latestNextFundingTime);
        activePositions.put(properties.getSymbol(), updatedPosition);
        if (persistResult.inserted()) {
            log.info("Recorded funding settlement for {}: positionId={}, fundingTime={}, amount={}",
                    properties.getSymbol(), activePosition.positionId(), lastRecordedFundingTime, settlement.getSettlementAmount());
        }
        return updatedPosition;
    }

    /**
     * 计算本次资金费收入/支出：优先以交易所实际入账(FUNDING_FEE income)对账，取不到时兜底回落到
     * 按持仓名义价值 × 资金费率的估算。正数表示收取资金费，负数表示支付资金费。
     */
    private SingleExchangeArbitrageFundingSettlement buildFundingSettlement(ActivePosition activePosition,
                                                                            BinanceFuturesPremiumIndex premiumIndex,
                                                                            SingleExchangeSpotFuturesOrderPlan marketPlan,
                                                                            Long fundingTime) {
        BigDecimal markPrice = premiumIndex.getMarkPrice() != null ? premiumIndex.getMarkPrice() : defaultValue(marketPlan.getFuturesPrice());
        BigDecimal fundingRate = defaultValue(premiumIndex.getLastFundingRate());
        BigDecimal positionQty = defaultValue(activePosition.quantity());
        BigDecimal notionalValue = positionQty.multiply(defaultValue(markPrice)).setScale(8, RoundingMode.HALF_UP);
        BigDecimal estimatedSettlementAmount = notionalValue.multiply(fundingRate).setScale(8, RoundingMode.HALF_UP);
        // P0-2：以交易所实际资金费入账为准；取不到实际入账时 fail-closed 回落估算并已告警（见 resolveActualFundingIncome）。
        BigDecimal actualSettlementAmount = resolveActualFundingIncome(properties.getSymbol(), fundingTime, estimatedSettlementAmount);
        BigDecimal settlementAmount = actualSettlementAmount != null ? actualSettlementAmount : estimatedSettlementAmount;
        return new SingleExchangeArbitrageFundingSettlement()
                .setPositionId(activePosition.positionId())
                .setPositionCode(activePosition.positionCode())
                .setExchangeName(EXCHANGE_NAME)
                .setSymbol(properties.getSymbol())
                .setFundingTime(fundingTime)
                .setFundingRate(fundingRate)
                .setMarkPrice(markPrice)
                .setPositionQty(positionQty)
                .setNotionalValue(notionalValue)
                .setSettlementAmount(settlementAmount)
                .setDirection(settlementAmount.signum() >= 0 ? FUNDING_DIRECTION_RECEIVED : FUNDING_DIRECTION_PAID)
                .setCreatedAt(new Date());
    }

    /**
     * 拉取结算点附近的交易所实际 FUNDING_FEE 入账并求和作为实际结算额。
     * 取不到或异常时返回 null（fail-closed：由调用方回落估算并告警），不静默改数。
     */
    private BigDecimal resolveActualFundingIncome(String symbol, Long fundingTime, BigDecimal estimatedAmount) {
        if (fundingTime == null) {
            return null;
        }
        try {
            long windowStart = fundingTime - FUNDING_INCOME_WINDOW_MILLIS;
            long windowEnd = fundingTime + FUNDING_INCOME_WINDOW_MILLIS;
            List<BinanceFuturesIncome> incomes = queryDataProvider.getFundingFeeIncome(symbol, windowStart, windowEnd);
            if (incomes == null || incomes.isEmpty()) {
                log.warn("No actual FUNDING_FEE income for {} around fundingTime={}, fallback to estimated settlement {}",
                        symbol, fundingTime, estimatedAmount);
                return null;
            }
            BigDecimal actual = incomes.stream()
                    .map(BinanceFuturesIncome::getIncome)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(8, RoundingMode.HALF_UP);
            if (actual.compareTo(defaultValue(estimatedAmount)) != 0) {
                log.info("Funding settlement reconciled by actual income for {}: estimated={}, actual={}",
                        symbol, estimatedAmount, actual);
            }
            return actual;
        } catch (Exception e) {
            log.warn("Failed to query actual FUNDING_FEE income for {} at fundingTime={}, fallback to estimated settlement {}",
                    symbol, fundingTime, estimatedAmount, e);
            return null;
        }
    }

    private FundingSettlementPersistResult persistFundingSettlement(SingleExchangeArbitrageFundingSettlement settlement) {
        try {
            // 以 positionId + fundingTime 做幂等保护，避免重启或重复轮询导致同一结算点重复入账。
            Long existingCount = fundingSettlementMapper.selectCount(
                    new LambdaQueryWrapper<SingleExchangeArbitrageFundingSettlement>()
                            .eq(SingleExchangeArbitrageFundingSettlement::getPositionId, settlement.getPositionId())
                            .eq(SingleExchangeArbitrageFundingSettlement::getFundingTime, settlement.getFundingTime())
            );
            if (existingCount != null && existingCount > 0) {
                log.info("Skip duplicate funding settlement for positionId={}, fundingTime={}",
                        settlement.getPositionId(), settlement.getFundingTime());
                return new FundingSettlementPersistResult(true, false);
            }
            fundingSettlementMapper.insert(settlement);
            return new FundingSettlementPersistResult(true, true);
        } catch (Exception e) {
            log.error("Failed to persist funding settlement for positionId={}, fundingTime={}",
                    settlement.getPositionId(), settlement.getFundingTime(), e);
            return new FundingSettlementPersistResult(false, false);
        }
    }

    private boolean advancePositionFundingState(Long positionId,
                                                Long latestNextFundingTime,
                                                BigDecimal settlementAmount,
                                                boolean applySettlementAmount) {
        try {
            SingleExchangeArbitragePosition position = positionMapper.selectById(positionId);
            if (position == null) {
                log.warn("Position {} not found while advancing funding state", positionId);
                return false;
            }
            position.setEntryNextFundingTime(latestNextFundingTime)
                    .setUpdatedAt(new Date());
            if (applySettlementAmount) {
                position.setFundingFeesEarned(defaultValue(position.getFundingFeesEarned()).add(defaultValue(settlementAmount)));
            }
            positionMapper.updateById(position);
            return true;
        } catch (Exception e) {
            log.error("Failed to advance funding state for position {}", positionId, e);
            return false;
        }
    }

    private BigDecimal estimateTotalInvestment(SingleExchangeSpotFuturesOrderPlan marketPlan) {
        return defaultValue(marketPlan.getSpotNotional()).add(marketPlan.getRequiredFuturesMargin());
    }

    private String generatePositionCode(String tradeSymbol) {
        return "SEA-" + tradeSymbol + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    private String getOpenBlockReason(BinanceFuturesPremiumIndex premiumIndex,
                                      SingleExchangeSpotFuturesOrderPlan marketPlan,
                                      BigDecimal estimatedSlippageRate) {
        // 开仓过滤按“收益来源 -> 时间风险 -> 基差质量 -> 扣费后净收益”的顺序短路判断。
        BigDecimal currentRate = defaultValue(premiumIndex.getLastFundingRate());
        if (currentRate.compareTo(properties.getOpenThreshold()) <= 0) {
            return "funding rate " + currentRate + " is not above open threshold " + properties.getOpenThreshold();
        }
        if (isWithinSettlementProtectionWindow(premiumIndex.getNextFundingTime())) {
            return "next funding settlement is too close";
        }

        BigDecimal basisRate = defaultValue(marketPlan.getBasisRate());
        if (basisRate.compareTo(properties.getMinBasisRate()) < 0) {
            return "basis rate " + basisRate + " is below minimum " + properties.getMinBasisRate();
        }
        if (basisRate.compareTo(properties.getMaxBasisRate()) > 0) {
            return "basis rate " + basisRate + " is above maximum " + properties.getMaxBasisRate();
        }

        BigDecimal realRoundTripFeeRate = riskService.resolveRoundTripFeeRate(marketPlan.getSymbol());
        BigDecimal expectedNetYield = estimateExpectedNetYield(currentRate, estimatedSlippageRate, realRoundTripFeeRate);
        if (expectedNetYield.compareTo(properties.getMinNetYieldRate()) < 0) {
            return "expected net yield " + expectedNetYield + " is below minimum " + properties.getMinNetYieldRate();
        }
        return null;
    }

    /**
     * 获取关闭决策
     * @param premiumIndex
     * @param marketPlan
     * @param activePosition
     * @return
     */
    private CloseDecision getCloseDecision(BinanceFuturesPremiumIndex premiumIndex,
                                           SingleExchangeSpotFuturesOrderPlan marketPlan,
                                           ActivePosition activePosition) {
        // 平仓触发分为收益衰减、基差恶化、保证金风险三类; 保证金风险会标记为 emergency。
        BigDecimal currentRate = defaultValue(premiumIndex.getLastFundingRate());
        if (currentRate.compareTo(BigDecimal.ZERO) <= 0) {
            return new CloseDecision("funding rate turned non-positive", false);
        }
        if (currentRate.compareTo(properties.getCloseThreshold()) < 0) {
            return new CloseDecision("funding rate dropped below close threshold", false);
        }

        BigDecimal currentBasisRate = defaultValue(marketPlan.getBasisRate());
        BigDecimal basisWideningRate = currentBasisRate.subtract(activePosition.entryBasisRate());
        if (basisWideningRate.compareTo(properties.getMaxBasisDeteriorationRate()) > 0) {
            return new CloseDecision(
                    "basis widened by " + basisWideningRate + ", exceeding " + properties.getMaxBasisDeteriorationRate(),
                    false
            );
        }

        BigDecimal marginRatio = riskService.getMarginRatio();
        if (marginRatio != null && marginRatio.compareTo(properties.getEmergencyCloseMarginRatio()) < 0) {
            return new CloseDecision(
                    "margin ratio " + marginRatio + " is below emergency threshold " + properties.getEmergencyCloseMarginRatio(),
                    true
            );
        }
        return null;
    }

    private BigDecimal estimateExpectedNetYield(BigDecimal currentRate, BigDecimal estimatedSlippageRate) {
        // 兼容旧的两参入口: 无真实费率时以 null 委派，扣费回落静态兜底费率。
        return estimateExpectedNetYield(currentRate, estimatedSlippageRate, null);
    }

    private BigDecimal estimateExpectedNetYield(BigDecimal currentRate,
                                                BigDecimal estimatedSlippageRate,
                                                BigDecimal realRoundTripFeeRate) {
        Integer minFundingPeriods = properties.getMinFundingPeriods();
        int fundingPeriods = minFundingPeriods == null || minFundingPeriods <= 0 ? 1 : minFundingPeriods;
        // 往返手续费取账户真实费率与静态兜底的较大值(max)：真实费率更高时收紧闸门；取不到(null)时不低于静态下限。
        BigDecimal effectiveFeeRate = properties.getEstimatedRoundTripFeeRate();
        if (realRoundTripFeeRate != null && realRoundTripFeeRate.compareTo(effectiveFeeRate) > 0) {
            effectiveFeeRate = realRoundTripFeeRate;
        }
        // 预估净收益 = 预期资金费收益 - 双边交易手续费 - 开仓滑点; 用于过滤纸面收益很薄的机会。
        return currentRate.multiply(BigDecimal.valueOf(fundingPeriods))
                .subtract(effectiveFeeRate)
                .subtract(defaultValue(estimatedSlippageRate))
                .setScale(8, RoundingMode.HALF_UP);
    }

    private boolean isWithinSettlementProtectionWindow(Long nextFundingTime) {
        Long settlementProtectionWindowMinutes = properties.getSettlementProtectionWindowMinutes();
        if (nextFundingTime == null || settlementProtectionWindowMinutes == null || settlementProtectionWindowMinutes <= 0) {
            return false;
        }
        long millisUntilSettlement = nextFundingTime - System.currentTimeMillis();
        return millisUntilSettlement >= 0
                && millisUntilSettlement <= TimeUnit.MINUTES.toMillis(settlementProtectionWindowMinutes);
    }

    private BigDecimal defaultValue(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private void persistRiskSnapshot(Long positionId,
                                     String positionCode,
                                     String checkStage,
                                     boolean riskPassed,
                                     String rejectionReason,
                                     SingleExchangeRiskSnapshotData snapshotData) {
        try {
            SingleExchangeArbitrageRiskSnapshot snapshot = new SingleExchangeArbitrageRiskSnapshot()
                    .setPositionId(positionId)
                    .setPositionCode(positionCode)
                    .setExchangeName(EXCHANGE_NAME)
                    .setSymbol(properties.getSymbol())
                    .setCheckStage(checkStage)
                    .setSpotUsdtBalance(snapshotData != null ? snapshotData.getSpotUsdtBalance() : null)
                    .setFuturesAvailableBalance(snapshotData != null ? snapshotData.getFuturesAvailableBalance() : null)
                    .setRequiredMargin(snapshotData != null ? snapshotData.getRequiredMargin() : null)
                    .setBufferedRequiredMargin(snapshotData != null ? snapshotData.getBufferedRequiredMargin() : null)
                    .setMarginRatio(snapshotData != null ? snapshotData.getMarginRatio() : null)
                    .setRiskPassed(riskPassed)
                    .setRejectionReason(trimSnapshotReason(rejectionReason))
                    .setCreatedAt(new Date());
            riskSnapshotMapper.insert(snapshot);
        } catch (Exception e) {
            log.error("Failed to persist risk snapshot for symbol={}, stage={}", properties.getSymbol(), checkStage, e);
        }
    }

    private String buildPreOpenRiskReason(String prefix, String reason) {
        if (reason == null || reason.isEmpty()) {
            return prefix;
        }
        return prefix + ": " + reason;
    }

    private String buildPreCloseRiskReason(String closeReason, SingleExchangeLiquidityAssessment assessment) {
        StringBuilder builder = new StringBuilder("close trigger: ").append(closeReason);
        if (assessment != null && !assessment.isPassed() && assessment.getRejectionReason() != null) {
            builder.append("; liquidity warning: ").append(assessment.getRejectionReason());
        }
        return builder.toString();
    }

    private String trimSnapshotReason(String reason) {
        if (reason == null || reason.length() <= 255) {
            return reason;
        }
        return reason.substring(0, 255);
    }

    private record ActivePosition(
            Long positionId,
            String positionCode,
            BigDecimal quantity,
            BigDecimal entryBasisRate,
            BigDecimal entryFundingRate,
            Integer leverage,
            Long nextFundingTime
    ) {
        private static ActivePosition fromEntity(SingleExchangeArbitragePosition position) {
            return new ActivePosition(
                    position.getId(),
                    position.getPositionCode(),
                    position.getQuantity(),
                    position.getEntryBasisRate() == null ? BigDecimal.ZERO : position.getEntryBasisRate(),
                    position.getEntryFundingRate() == null ? BigDecimal.ZERO : position.getEntryFundingRate(),
                    position.getLeverage() == null ? leverageFallback() : position.getLeverage(),
                    position.getEntryNextFundingTime()
            );
        }

        private ActivePosition withNextFundingTime(Long nextFundingTime) {
            return new ActivePosition(
                    positionId,
                    positionCode,
                    quantity,
                    entryBasisRate,
                    entryFundingRate,
                    leverage,
                    nextFundingTime
            );
        }

        private static Integer leverageFallback() {
            return 1;
        }
    }

    private record CloseDecision(String reason, boolean emergency) {
    }

    private record FundingSettlementPersistResult(boolean proceed, boolean inserted) {
    }
}
