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
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesPremiumIndex;
import com.metaverse.coin.strategy.core.Strategy;
import com.metaverse.coin.strategy.core.arbitrage.config.SingleExchangeSpotFuturesProperties;
import com.metaverse.coin.strategy.core.arbitrage.execution.SingleExchangeArbitrageExecutionResult;
import com.metaverse.coin.strategy.core.arbitrage.execution.SingleExchangeArbitrageOrderExecutor;
import com.metaverse.coin.strategy.core.arbitrage.execution.SingleExchangeSpotFuturesOrderPlan;
import com.metaverse.coin.strategy.core.arbitrage.monitor.SingleExchangeFundingRateMonitor;
import com.metaverse.coin.strategy.core.arbitrage.risk.SingleExchangeLiquidityAssessment;
import com.metaverse.coin.strategy.core.arbitrage.risk.SingleExchangeRiskController;
import com.metaverse.coin.strategy.core.arbitrage.risk.SingleExchangeRiskSnapshotData;
import com.metaverse.coin.strategy.core.arbitrage.risk.SingleExchangeTradeRiskAssessment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Spot-Futures Arbitrage Strategy (Single Exchange)
 * Strategy Logic:
 * 1. Build a real trade plan from current spot/futures prices.
 * 2. Open only when funding, basis and settlement timing all pass filters.
 * 3. Close when funding weakens, basis deteriorates or margin health becomes unsafe.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SingleExchangeSpotFuturesArbitrageStrategy implements Strategy {

    private final SingleExchangeFundingRateMonitor fundingRateMonitor;
    private final SingleExchangeArbitrageOrderExecutor orderExecutor;
    private final SingleExchangeRiskController riskController;
    private final SingleExchangeArbitrageFundingSettlementMapper fundingSettlementMapper;
    private final SingleExchangeArbitrageOrderMapper orderMapper;
    private final SingleExchangeArbitragePositionMapper positionMapper;
    private final SingleExchangeArbitrageRiskSnapshotMapper riskSnapshotMapper;
    private final SingleExchangeSpotFuturesProperties properties;

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

    private void executeLogic() {
        if (!running.get()) {
            return;
        }

        try {
            String symbol = properties.getSymbol();
            log.info("Checking arbitrage opportunities for {}", symbol);

            BinanceFuturesPremiumIndex premiumIndex = fundingRateMonitor.getBinanceFundingRate(symbol);
            if (premiumIndex == null || premiumIndex.getLastFundingRate() == null) {
                log.warn("Funding data unavailable for {}", symbol);
                return;
            }

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

            ActivePosition activePosition = activePositions.get(symbol);
            if (activePosition == null) {
                SingleExchangeLiquidityAssessment liquidityAssessment = riskController.evaluateLiquidityRisk(
                        marketPlan,
                        SingleExchangeRiskController.PHASE_OPEN
                );
                if (!liquidityAssessment.isPassed()) {
                    persistRiskSnapshot(
                            null,
                            null,
                            CHECK_STAGE_PRE_OPEN,
                            false,
                            buildPreOpenRiskReason("LIQUIDITY_REJECTED", liquidityAssessment.getRejectionReason()),
                            riskController.captureRiskSnapshotData(marketPlan)
                    );
                    log.warn("{} for {}: {}", LOG_LIQUIDITY_REJECT_OPEN, symbol, liquidityAssessment.getRejectionReason());
                    return;
                }

                String openBlockReason = getOpenBlockReason(
                        premiumIndex,
                        marketPlan,
                        liquidityAssessment.getEstimatedSlippageRate()
                );
                if (openBlockReason != null) {
                    log.info("Skip open for {}: {}", symbol, openBlockReason);
                    return;
                }

                SingleExchangeTradeRiskAssessment tradeRiskAssessment = riskController.evaluateTradeRisk(marketPlan);
                if (!tradeRiskAssessment.isPassed()) {
                    persistRiskSnapshot(
                            null,
                            null,
                            CHECK_STAGE_PRE_OPEN,
                            false,
                            buildPreOpenRiskReason("TRADE_RISK_REJECTED", tradeRiskAssessment.getRejectionReason()),
                            tradeRiskAssessment.getSnapshotData()
                    );
                    log.warn("Trade rejected by Risk Controller for {}", symbol);
                    return;
                }

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
                    SingleExchangeArbitragePosition openedPosition = markPositionOpened(persistedPosition, marketPlan, premiumIndex, result);
                    activePositions.put(symbol, ActivePosition.fromEntity(openedPosition));
                } else {
                    markPositionFailed(persistedPosition.getId(), result.getMessage());
                    log.warn("Failed to open arbitrage position for {}: {}", symbol, result.getMessage());
                }
                return;
            }

            activePosition = reconcileFundingSettlement(activePosition, premiumIndex, marketPlan);
            CloseDecision closeDecision = getCloseDecision(premiumIndex, marketPlan, activePosition);
            if (closeDecision == null) {
                return;
            }

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
            SingleExchangeLiquidityAssessment closeLiquidityAssessment = riskController.evaluateLiquidityRisk(
                    closePlan,
                    SingleExchangeRiskController.PHASE_CLOSE
            );
            persistRiskSnapshot(
                    activePosition.positionId(),
                    activePosition.positionCode(),
                    CHECK_STAGE_PRE_CLOSE,
                    closeLiquidityAssessment.isPassed(),
                    buildPreCloseRiskReason(closeDecision.reason(), closeLiquidityAssessment),
                    riskController.captureRiskSnapshotData(closePlan)
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
                markPositionClosed(activePosition, marketPlan, currentRate, closeResult);
                activePositions.remove(symbol);
            } else {
                revertPositionToOpened(activePosition);
                log.warn("Failed to close arbitrage position for {} after trigger: {}", symbol, closeDecision.reason());
            }
        } catch (Exception e) {
            log.error("Error in strategy execution loop", e);
        }
    }

    private void restorePersistedPositions() {
        try {
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

    private SingleExchangeArbitrageFundingSettlement buildFundingSettlement(ActivePosition activePosition,
                                                                            BinanceFuturesPremiumIndex premiumIndex,
                                                                            SingleExchangeSpotFuturesOrderPlan marketPlan,
                                                                            Long fundingTime) {
        BigDecimal markPrice = premiumIndex.getMarkPrice() != null ? premiumIndex.getMarkPrice() : defaultValue(marketPlan.getFuturesPrice());
        BigDecimal fundingRate = defaultValue(premiumIndex.getLastFundingRate());
        BigDecimal positionQty = defaultValue(activePosition.quantity());
        BigDecimal notionalValue = positionQty.multiply(defaultValue(markPrice)).setScale(8, RoundingMode.HALF_UP);
        BigDecimal settlementAmount = notionalValue.multiply(fundingRate).setScale(8, RoundingMode.HALF_UP);
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

    private FundingSettlementPersistResult persistFundingSettlement(SingleExchangeArbitrageFundingSettlement settlement) {
        try {
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

        BigDecimal expectedNetYield = estimateExpectedNetYield(currentRate, estimatedSlippageRate);
        if (expectedNetYield.compareTo(properties.getMinNetYieldRate()) < 0) {
            return "expected net yield " + expectedNetYield + " is below minimum " + properties.getMinNetYieldRate();
        }
        return null;
    }

    private CloseDecision getCloseDecision(BinanceFuturesPremiumIndex premiumIndex,
                                           SingleExchangeSpotFuturesOrderPlan marketPlan,
                                           ActivePosition activePosition) {
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

        BigDecimal marginRatio = riskController.getMarginRatio();
        if (marginRatio != null && marginRatio.compareTo(properties.getEmergencyCloseMarginRatio()) < 0) {
            return new CloseDecision(
                    "margin ratio " + marginRatio + " is below emergency threshold " + properties.getEmergencyCloseMarginRatio(),
                    true
            );
        }
        return null;
    }

    private BigDecimal estimateExpectedNetYield(BigDecimal currentRate, BigDecimal estimatedSlippageRate) {
        Integer minFundingPeriods = properties.getMinFundingPeriods();
        int fundingPeriods = minFundingPeriods == null || minFundingPeriods <= 0 ? 1 : minFundingPeriods;
        return currentRate.multiply(BigDecimal.valueOf(fundingPeriods))
                .subtract(properties.getEstimatedRoundTripFeeRate())
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
