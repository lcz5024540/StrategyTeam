package com.metaverse.coin.strategy.job.funding.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SingleExchangeArbitrageFundingSettlementMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SingleExchangeArbitrageOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SingleExchangeArbitragePositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SingleExchangeArbitrageFundingSettlement;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SingleExchangeArbitrageOrder;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SingleExchangeArbitragePosition;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesOrder;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesPremiumIndex;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.BinanceOrder;
import com.metaverse.coin.strategy.core.arbitrage.config.SingleExchangeSpotFuturesProperties;
import com.metaverse.coin.strategy.core.arbitrage.execution.SingleExchangeArbitrageExecutionResult;
import com.metaverse.coin.strategy.core.arbitrage.execution.SingleExchangeArbitrageOrderExecutor;
import com.metaverse.coin.strategy.core.arbitrage.execution.SingleExchangeSpotFuturesOrderPlan;
import com.metaverse.coin.strategy.core.arbitrage.market.BinanceQueryDataProvider;
import com.metaverse.coin.strategy.core.arbitrage.risk.SingleExchangeLiquidityAssessment;
import com.metaverse.coin.strategy.core.arbitrage.risk.SingleExchangeRiskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnExpression("'${binance.api.api-key:}' != '' && '${binance.api.secret-key:}' != ''")
public class FundingArbLifecycleService {

    public static final String STRATEGY_ID = "SINGLE_EXCHANGE_SPOT_FUTURES_ARBITRAGE_V1";
    public static final String EXCHANGE_NAME = "BINANCE";
    public static final String STATUS_OPENING = "OPENING";
    public static final String STATUS_OPENED = "OPENED";
    public static final String STATUS_CLOSING = "CLOSING";
    public static final String STATUS_CLOSED = "CLOSED";
    public static final String STATUS_FAILED = "FAILED";

    private static final String MARKET_TYPE_SPOT = "SPOT";
    private static final String MARKET_TYPE_FUTURES = "FUTURES";
    private static final String TRADE_PHASE_OPEN = "OPEN";
    private static final String TRADE_PHASE_CLOSE = "CLOSE";
    private static final String STATUS_FILLED = "FILLED";
    private static final String STATUS_PARTIALLY_FILLED = "PARTIALLY_FILLED";
    private static final String FUNDING_DIRECTION_RECEIVED = "RECEIVED";
    private static final String FUNDING_DIRECTION_PAID = "PAID";

    private final SingleExchangeArbitrageOrderExecutor orderExecutor;
    private final SingleExchangeRiskService riskService;
    private final SingleExchangeArbitragePositionMapper positionMapper;
    private final SingleExchangeArbitrageOrderMapper orderMapper;
    private final SingleExchangeArbitrageFundingSettlementMapper fundingSettlementMapper;
    private final SingleExchangeSpotFuturesProperties properties;
    private final BinanceQueryDataProvider queryDataProvider;

    public SingleExchangeArbitragePosition createOpeningPosition(SingleExchangeSpotFuturesOrderPlan plan,
                                                                 BinanceFuturesPremiumIndex premiumIndex,
                                                                 BigDecimal plannedUsdtAmount,
                                                                 Integer leverage) {
        SingleExchangeArbitragePosition position = new SingleExchangeArbitragePosition()
                .setPositionCode(generatePositionCode(plan.getSymbol()))
                .setExchangeName(EXCHANGE_NAME)
                .setSymbol(plan.getSymbol())
                .setSpotSymbol(plan.getSpotSymbol())
                .setFuturesSymbol(plan.getFuturesSymbol())
                .setStrategyType(STRATEGY_ID)
                .setStatus(STATUS_OPENING)
                .setLeverage(leverage == null || leverage <= 0 ? properties.getLeverage() : leverage)
                .setPlannedUsdtAmount(plannedUsdtAmount)
                .setQuantity(plan.getQuantity())
                .setSpotNotional(plan.getSpotNotional())
                .setFuturesNotional(plan.getFuturesNotional())
                .setTotalInvestment(estimateTotalInvestment(plan))
                .setEntryTime(new Date())
                .setEntrySpotPrice(plan.getSpotPrice())
                .setEntryFuturesPrice(plan.getFuturesPrice())
                .setEntryFundingRate(premiumIndex == null ? null : premiumIndex.getLastFundingRate())
                .setEntryBasisSpread(plan.getBasisSpread())
                .setEntryBasisRate(plan.getBasisRate())
                .setEntryNextFundingTime(premiumIndex == null ? null : premiumIndex.getNextFundingTime())
                .setRealizedPnl(BigDecimal.ZERO)
                .setFundingFeesEarned(BigDecimal.ZERO)
                .setUpdatedAt(new Date());
        positionMapper.insert(position);
        return position;
    }

    public void persistOpenResult(SingleExchangeArbitragePosition position,
                                  SingleExchangeSpotFuturesOrderPlan plan,
                                  BinanceFuturesPremiumIndex premiumIndex,
                                  SingleExchangeArbitrageExecutionResult result) {
        persistOrderRecords(position.getId(), position.getPositionCode(), result.getOrderRecords());
        if (result.isSuccess()) {
            markPositionOpened(position, plan, premiumIndex, result);
            return;
        }
        markPositionFailed(position.getId(), result.getMessage());
    }

    public void trackPosition(SingleExchangeArbitragePosition position) {
        if (position == null || position.getId() == null || position.getSymbol() == null) {
            return;
        }
        refreshTrackedOrders(position);

        String symbol = position.getSymbol();
        BinanceFuturesPremiumIndex premiumIndex = queryDataProvider.getPremiumIndex(symbol);
        if (premiumIndex == null || premiumIndex.getLastFundingRate() == null) {
            log.warn("FundingArb tracking skipped for {} because funding data is unavailable", symbol);
            return;
        }

        SingleExchangeSpotFuturesOrderPlan marketPlan = orderExecutor.createSpotFuturesPlan(
                symbol,
                resolvePlannedUsdtAmount(position),
                position.getLeverage()
        );
        marketPlan.setFundingRate(premiumIndex.getLastFundingRate());
        if (reconcileInterruptedPositionState(position, premiumIndex, marketPlan)) {
            return;
        }
        if (!STATUS_OPENED.equalsIgnoreCase(position.getStatus())) {
            return;
        }
        reconcileFundingSettlement(position, premiumIndex, marketPlan);

        CloseDecision closeDecision = getCloseDecision(premiumIndex, marketPlan, position);
        if (closeDecision == null) {
            return;
        }

        SingleExchangeSpotFuturesOrderPlan closePlan = SingleExchangeSpotFuturesOrderPlan.builder()
                .symbol(symbol)
                .spotSymbol(position.getSpotSymbol() == null ? symbol : position.getSpotSymbol())
                .futuresSymbol(position.getFuturesSymbol() == null ? symbol : position.getFuturesSymbol())
                .quantity(position.getQuantity())
                .spotPrice(marketPlan.getSpotPrice())
                .futuresPrice(marketPlan.getFuturesPrice())
                .spotNotional(defaultValue(position.getQuantity()).multiply(defaultValue(marketPlan.getSpotPrice())))
                .futuresNotional(defaultValue(position.getQuantity()).multiply(defaultValue(marketPlan.getFuturesPrice())))
                .leverage(position.getLeverage())
                .build();
        SingleExchangeLiquidityAssessment liquidityAssessment = riskService.evaluateLiquidityRisk(
                closePlan,
                SingleExchangeRiskService.PHASE_CLOSE
        );
        if (!liquidityAssessment.isPassed()) {
            log.warn("FundingArb close liquidity warning for {}: {}, closeReason={}",
                    symbol, liquidityAssessment.getRejectionReason(), closeDecision.reason());
        }

        markPositionClosing(position);
        SingleExchangeArbitrageExecutionResult closeResult = orderExecutor.closeArbitragePosition(closePlan);
        persistOrderRecords(position.getId(), position.getPositionCode(), closeResult.getOrderRecords());
        if (closeResult.isSuccess()) {
            markPositionClosed(position, marketPlan, premiumIndex.getLastFundingRate(), closeResult);
            log.info("FundingArb position closed: positionId={}, symbol={}, reason={}",
                    position.getId(), symbol, closeDecision.reason());
        } else {
            revertPositionToOpened(position.getId(), closeResult.getMessage());
            log.warn("FundingArb position close failed: positionId={}, symbol={}, reason={}, message={}",
                    position.getId(), symbol, closeDecision.reason(), closeResult.getMessage());
        }
    }

    public List<SingleExchangeArbitragePosition> loadTrackablePositions(int limit) {
        return positionMapper.selectList(
                new LambdaQueryWrapper<SingleExchangeArbitragePosition>()
                        .eq(SingleExchangeArbitragePosition::getStrategyType, STRATEGY_ID)
                        .eq(SingleExchangeArbitragePosition::getExchangeName, EXCHANGE_NAME)
                        .in(SingleExchangeArbitragePosition::getStatus, STATUS_OPENING, STATUS_OPENED, STATUS_CLOSING)
                        .orderByAsc(SingleExchangeArbitragePosition::getEntryNextFundingTime)
                        .orderByDesc(SingleExchangeArbitragePosition::getEntryTime)
                        .last("LIMIT " + Math.max(limit, 1))
        );
    }

    private void markPositionOpened(SingleExchangeArbitragePosition position,
                                    SingleExchangeSpotFuturesOrderPlan plan,
                                    BinanceFuturesPremiumIndex premiumIndex,
                                    SingleExchangeArbitrageExecutionResult result) {
        position.setStatus(STATUS_OPENED)
                .setSpotBuyOrderId(result.getSpotOrderId())
                .setFuturesSellOrderId(result.getFuturesOrderId())
                .setEntrySpotPrice(plan.getSpotPrice())
                .setEntryFuturesPrice(plan.getFuturesPrice())
                .setEntryFundingRate(premiumIndex == null ? null : premiumIndex.getLastFundingRate())
                .setEntryBasisSpread(plan.getBasisSpread())
                .setEntryBasisRate(plan.getBasisRate())
                .setEntryNextFundingTime(premiumIndex == null ? null : premiumIndex.getNextFundingTime())
                .setUpdatedAt(new Date())
                .setErrorMessage(null);
        positionMapper.updateById(position);
    }

    private void markPositionFailed(Long positionId, String message) {
        updatePosition(positionId, position -> position.setStatus(STATUS_FAILED)
                .setErrorMessage(message)
                .setUpdatedAt(new Date()));
    }

    private void markPositionClosing(SingleExchangeArbitragePosition position) {
        updatePosition(position.getId(), row -> row.setStatus(STATUS_CLOSING)
                .setUpdatedAt(new Date())
                .setErrorMessage(null));
    }

    private void markPositionClosed(SingleExchangeArbitragePosition position,
                                    SingleExchangeSpotFuturesOrderPlan marketPlan,
                                    BigDecimal currentFundingRate,
                                    SingleExchangeArbitrageExecutionResult closeResult) {
        BigDecimal spotPnl = defaultValue(marketPlan.getSpotPrice())
                .subtract(defaultValue(position.getEntrySpotPrice()))
                .multiply(defaultValue(position.getQuantity()));
        BigDecimal futuresPnl = defaultValue(position.getEntryFuturesPrice())
                .subtract(defaultValue(marketPlan.getFuturesPrice()))
                .multiply(defaultValue(position.getQuantity()));
        BigDecimal realizedPnl = spotPnl.add(futuresPnl).setScale(8, RoundingMode.HALF_UP);
        updatePosition(position.getId(), row -> row.setStatus(STATUS_CLOSED)
                .setExitTime(new Date())
                .setExitSpotPrice(marketPlan.getSpotPrice())
                .setExitFuturesPrice(marketPlan.getFuturesPrice())
                .setSpotSellOrderId(closeResult.getSpotOrderId())
                .setFuturesBuyOrderId(closeResult.getFuturesOrderId())
                .setExitFundingRate(currentFundingRate)
                .setExitBasisSpread(marketPlan.getBasisSpread())
                .setExitBasisRate(marketPlan.getBasisRate())
                .setRealizedPnl(realizedPnl)
                .setUpdatedAt(new Date())
                .setErrorMessage(null));
    }

    private void revertPositionToOpened(Long positionId, String message) {
        updatePosition(positionId, position -> position.setStatus(STATUS_OPENED)
                .setErrorMessage(message)
                .setUpdatedAt(new Date()));
    }

    private void updatePosition(Long positionId, java.util.function.Consumer<SingleExchangeArbitragePosition> mutator) {
        SingleExchangeArbitragePosition position = positionMapper.selectById(positionId);
        if (position == null) {
            log.warn("FundingArb position {} not found during update", positionId);
            return;
        }
        mutator.accept(position);
        positionMapper.updateById(position);
    }

    private void persistOrderRecords(Long positionId, String positionCode, List<SingleExchangeArbitrageOrder> orderRecords) {
        if (positionId == null || orderRecords == null || orderRecords.isEmpty()) {
            return;
        }
        for (SingleExchangeArbitrageOrder orderRecord : orderRecords) {
            if (orderRecord == null) {
                continue;
            }
            orderRecord.setPositionId(positionId)
                    .setPositionCode(positionCode)
                    .setCreatedAt(new Date())
                    .setUpdatedAt(new Date());
            orderMapper.insert(orderRecord);
        }
    }

    private void refreshTrackedOrders(SingleExchangeArbitragePosition position) {
        List<SingleExchangeArbitrageOrder> orders = orderMapper.selectList(
                new LambdaQueryWrapper<SingleExchangeArbitrageOrder>()
                        .eq(SingleExchangeArbitrageOrder::getPositionId, position.getId())
                        .notIn(SingleExchangeArbitrageOrder::getStatus, STATUS_FILLED, STATUS_FAILED)
        );
        for (SingleExchangeArbitrageOrder order : orders) {
            try {
                if (MARKET_TYPE_SPOT.equalsIgnoreCase(order.getMarketType())) {
                    refreshSpotOrder(order);
                } else if (MARKET_TYPE_FUTURES.equalsIgnoreCase(order.getMarketType())) {
                    refreshFuturesOrder(order);
                }
            } catch (Exception e) {
                order.setErrorMessage(e.getMessage())
                        .setUpdatedAt(new Date());
                orderMapper.updateById(order);
                log.warn("FundingArb order refresh failed: orderId={}, exchangeOrderId={}, symbol={}, message={}",
                        order.getId(), order.getExchangeOrderId(), order.getSymbol(), e.getMessage());
            }
        }
    }

    private boolean reconcileInterruptedPositionState(SingleExchangeArbitragePosition position,
                                                      BinanceFuturesPremiumIndex premiumIndex,
                                                      SingleExchangeSpotFuturesOrderPlan marketPlan) {
        if (STATUS_OPENING.equalsIgnoreCase(position.getStatus())) {
            List<SingleExchangeArbitrageOrder> openOrders = loadOrders(position.getId(), TRADE_PHASE_OPEN);
            if (hasFilledSpotAndFutures(openOrders)) {
                SingleExchangeArbitrageExecutionResult recoveredResult = buildRecoveredExecutionResult(openOrders);
                markPositionOpened(position, marketPlan, premiumIndex, recoveredResult);
                log.info("Recovered FundingArb OPENING position to OPENED: positionId={}, symbol={}",
                        position.getId(), position.getSymbol());
            }
            return true;
        }
        if (STATUS_CLOSING.equalsIgnoreCase(position.getStatus())) {
            List<SingleExchangeArbitrageOrder> closeOrders = loadOrders(position.getId(), TRADE_PHASE_CLOSE);
            if (hasFilledSpotAndFutures(closeOrders)) {
                SingleExchangeArbitrageExecutionResult recoveredResult = buildRecoveredExecutionResult(closeOrders);
                markPositionClosed(position, marketPlan, premiumIndex.getLastFundingRate(), recoveredResult);
                log.info("Recovered FundingArb CLOSING position to CLOSED: positionId={}, symbol={}",
                        position.getId(), position.getSymbol());
            } else {
                log.warn("FundingArb CLOSING position is still waiting for close fills: positionId={}, symbol={}",
                        position.getId(), position.getSymbol());
            }
            return true;
        }
        return false;
    }

    private List<SingleExchangeArbitrageOrder> loadOrders(Long positionId, String tradePhase) {
        return orderMapper.selectList(
                new LambdaQueryWrapper<SingleExchangeArbitrageOrder>()
                        .eq(SingleExchangeArbitrageOrder::getPositionId, positionId)
                        .eq(SingleExchangeArbitrageOrder::getTradePhase, tradePhase)
        );
    }

    private boolean hasFilledSpotAndFutures(List<SingleExchangeArbitrageOrder> orders) {
        boolean spotFilled = false;
        boolean futuresFilled = false;
        for (SingleExchangeArbitrageOrder order : orders) {
            if (!isFilled(order.getStatus())) {
                continue;
            }
            if (MARKET_TYPE_SPOT.equalsIgnoreCase(order.getMarketType())) {
                spotFilled = true;
            }
            if (MARKET_TYPE_FUTURES.equalsIgnoreCase(order.getMarketType())) {
                futuresFilled = true;
            }
        }
        return spotFilled && futuresFilled;
    }

    private SingleExchangeArbitrageExecutionResult buildRecoveredExecutionResult(List<SingleExchangeArbitrageOrder> orders) {
        String spotOrderId = null;
        String futuresOrderId = null;
        for (SingleExchangeArbitrageOrder order : orders) {
            if (MARKET_TYPE_SPOT.equalsIgnoreCase(order.getMarketType())) {
                spotOrderId = order.getExchangeOrderId();
            }
            if (MARKET_TYPE_FUTURES.equalsIgnoreCase(order.getMarketType())) {
                futuresOrderId = order.getExchangeOrderId();
            }
        }
        return SingleExchangeArbitrageExecutionResult.builder()
                .success(true)
                .spotOrderId(spotOrderId)
                .futuresOrderId(futuresOrderId)
                .message("Recovered from persisted order records")
                .build();
    }

    private void refreshSpotOrder(SingleExchangeArbitrageOrder order) {
        BinanceOrder latest = queryDataProvider.getSpotOrder(order.getSymbol(), parseLong(order.getExchangeOrderId()), order.getClientOrderId());
        order.setStatus(latest.getStatus())
                .setExecutedQty(latest.getExecutedQty())
                .setRequestedPrice(latest.getPrice())
                .setQuoteQty(latest.getCummulativeQuoteQty())
                .setFilledAt(isFilled(latest.getStatus()) ? timestamp(latest.getUpdateTime()) : order.getFilledAt())
                .setRawResponse(String.valueOf(latest))
                .setUpdatedAt(new Date());
        orderMapper.updateById(order);
    }

    private void refreshFuturesOrder(SingleExchangeArbitrageOrder order) {
        BinanceFuturesOrder latest = queryDataProvider.getFuturesOrder(
                order.getSymbol(),
                parseLong(order.getExchangeOrderId()),
                order.getClientOrderId()
        );
        order.setStatus(latest.getStatus())
                .setExecutedQty(latest.getExecutedQty())
                .setRequestedPrice(latest.getPrice())
                .setAvgPrice(latest.getAvgPrice())
                .setQuoteQty(latest.getCumQuote())
                .setFilledAt(isFilled(latest.getStatus()) ? timestamp(latest.getUpdateTime()) : order.getFilledAt())
                .setRawResponse(String.valueOf(latest))
                .setUpdatedAt(new Date());
        orderMapper.updateById(order);
    }

    private void reconcileFundingSettlement(SingleExchangeArbitragePosition position,
                                            BinanceFuturesPremiumIndex premiumIndex,
                                            SingleExchangeSpotFuturesOrderPlan marketPlan) {
        Long recordedFundingTime = position.getEntryNextFundingTime();
        Long latestNextFundingTime = premiumIndex.getNextFundingTime();
        if (recordedFundingTime == null
                || latestNextFundingTime == null
                || latestNextFundingTime <= recordedFundingTime
                || System.currentTimeMillis() < recordedFundingTime) {
            return;
        }
        Long existingCount = fundingSettlementMapper.selectCount(
                new LambdaQueryWrapper<SingleExchangeArbitrageFundingSettlement>()
                        .eq(SingleExchangeArbitrageFundingSettlement::getPositionId, position.getId())
                        .eq(SingleExchangeArbitrageFundingSettlement::getFundingTime, recordedFundingTime)
        );
        BigDecimal settlementAmount = BigDecimal.ZERO;
        if (existingCount == null || existingCount == 0) {
            SingleExchangeArbitrageFundingSettlement settlement = buildFundingSettlement(
                    position,
                    premiumIndex,
                    marketPlan,
                    recordedFundingTime
            );
            fundingSettlementMapper.insert(settlement);
            settlementAmount = settlement.getSettlementAmount();
        }
        BigDecimal amountToApply = settlementAmount;
        updatePosition(position.getId(), row -> row.setEntryNextFundingTime(latestNextFundingTime)
                .setFundingFeesEarned(defaultValue(row.getFundingFeesEarned()).add(amountToApply))
                .setUpdatedAt(new Date()));
        position.setEntryNextFundingTime(latestNextFundingTime);
        position.setFundingFeesEarned(defaultValue(position.getFundingFeesEarned()).add(amountToApply));
    }

    private SingleExchangeArbitrageFundingSettlement buildFundingSettlement(SingleExchangeArbitragePosition position,
                                                                            BinanceFuturesPremiumIndex premiumIndex,
                                                                            SingleExchangeSpotFuturesOrderPlan marketPlan,
                                                                            Long fundingTime) {
        BigDecimal markPrice = premiumIndex.getMarkPrice() == null ? defaultValue(marketPlan.getFuturesPrice()) : premiumIndex.getMarkPrice();
        BigDecimal fundingRate = defaultValue(premiumIndex.getLastFundingRate());
        BigDecimal positionQty = defaultValue(position.getQuantity());
        BigDecimal notionalValue = positionQty.multiply(markPrice).setScale(8, RoundingMode.HALF_UP);
        BigDecimal settlementAmount = notionalValue.multiply(fundingRate).setScale(8, RoundingMode.HALF_UP);
        return new SingleExchangeArbitrageFundingSettlement()
                .setPositionId(position.getId())
                .setPositionCode(position.getPositionCode())
                .setExchangeName(EXCHANGE_NAME)
                .setSymbol(position.getSymbol())
                .setFundingTime(fundingTime)
                .setFundingRate(fundingRate)
                .setMarkPrice(markPrice)
                .setPositionQty(positionQty)
                .setNotionalValue(notionalValue)
                .setSettlementAmount(settlementAmount)
                .setDirection(settlementAmount.signum() >= 0 ? FUNDING_DIRECTION_RECEIVED : FUNDING_DIRECTION_PAID)
                .setCreatedAt(new Date());
    }

    private CloseDecision getCloseDecision(BinanceFuturesPremiumIndex premiumIndex,
                                           SingleExchangeSpotFuturesOrderPlan marketPlan,
                                           SingleExchangeArbitragePosition position) {
        BigDecimal currentRate = defaultValue(premiumIndex.getLastFundingRate());
        if (currentRate.compareTo(BigDecimal.ZERO) <= 0) {
            return new CloseDecision("funding rate turned non-positive");
        }
        if (currentRate.compareTo(properties.getCloseThreshold()) < 0) {
            return new CloseDecision("funding rate dropped below close threshold");
        }
        BigDecimal basisWideningRate = defaultValue(marketPlan.getBasisRate()).subtract(defaultValue(position.getEntryBasisRate()));
        if (basisWideningRate.compareTo(properties.getMaxBasisDeteriorationRate()) > 0) {
            return new CloseDecision("basis widened by " + basisWideningRate);
        }
        BigDecimal marginRatio = riskService.getMarginRatio();
        if (marginRatio != null && marginRatio.compareTo(properties.getEmergencyCloseMarginRatio()) < 0) {
            return new CloseDecision("margin ratio " + marginRatio + " is below emergency threshold");
        }
        return null;
    }

    private BigDecimal resolvePlannedUsdtAmount(SingleExchangeArbitragePosition position) {
        BigDecimal planned = position.getPlannedUsdtAmount();
        return planned == null || planned.compareTo(BigDecimal.ZERO) <= 0 ? properties.getTradeUsdtAmount() : planned;
    }

    private BigDecimal estimateTotalInvestment(SingleExchangeSpotFuturesOrderPlan plan) {
        return defaultValue(plan.getSpotNotional()).add(plan.getRequiredFuturesMargin());
    }

    private String generatePositionCode(String symbol) {
        return "SEA-" + symbol + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    private boolean isFilled(String status) {
        return STATUS_FILLED.equalsIgnoreCase(status) || STATUS_PARTIALLY_FILLED.equalsIgnoreCase(status);
    }

    private Date timestamp(Long value) {
        return value == null ? null : new Date(value);
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Long.valueOf(value);
    }

    private BigDecimal defaultValue(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record CloseDecision(String reason) {
    }
}
