package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.HtxUsdtSwapApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxDepth;
import com.metaverse.coin.strategy.common.client.htx.model.HtxPriceLevel;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapAccount;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapContractInfo;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapFundingRate;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapKline;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOpenOrders;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrder;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrderRequest;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrderResult;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapPosition;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxUsdtSwapService;
import com.metaverse.coin.strategy.htx.config.HtxContractProperties;
import com.metaverse.coin.strategy.htx.config.HtxFundingArbitrageProperties;
import com.metaverse.coin.strategy.htx.config.HtxMarketMakerProperties;
import com.metaverse.coin.strategy.htx.config.HtxTrendProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxTrendRecorder;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendOrderEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendPositionEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendSignalEntity;
import com.metaverse.coin.strategy.htx.service.HtxTrendSignalCalculator.TrendSide;
import com.metaverse.coin.strategy.htx.service.HtxTrendSignalCalculator.TrendSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/** Contract-only trend following for HTX swaps, safe to run in observe-only mode. */
@Slf4j
@Service
@ConditionalOnBean(HtxUsdtSwapApiService.class)
@ConditionalOnProperty(prefix = "htx.trend", name = "enabled", havingValue = "true")
public class HtxTrendFollowingService {

    private static final String LONG = "LONG";
    private static final String SHORT = "SHORT";
    private static final String POSITION_SIDE_LONG = "long";
    private static final String POSITION_SIDE_SHORT = "short";
    private static final String SIDE_BUY = "buy";
    private static final String SIDE_SELL = "sell";
    private static final String ORDER_TYPE_MARKET = "market";
    private static final BigDecimal TWO = new BigDecimal("2");
    private static final LocalDateTime SIGNAL_HISTORY_FLOOR = LocalDateTime.of(1970, 1, 1, 0, 0);

    private final HtxUsdtSwapService swap;
    private final HtxContractProperties contractProperties;
    private final HtxFundingArbitrageProperties fundingArbitrageProperties;
    private final HtxMarketMakerProperties marketMakerProperties;
    private final HtxTrendProperties properties;
    private final HtxTrendRecorder recorder;
    private final HtxTrendSignalCalculator calculator;

    @Autowired
    public HtxTrendFollowingService(HtxUsdtSwapApiService api,
                                    HtxContractProperties contractProperties,
                                    HtxFundingArbitrageProperties fundingArbitrageProperties,
                                    HtxMarketMakerProperties marketMakerProperties,
                                    HtxTrendProperties properties,
                                    HtxTrendRecorder recorder) {
        this(api.getSwapService(), contractProperties, fundingArbitrageProperties, marketMakerProperties,
                properties, recorder, new HtxTrendSignalCalculator());
    }

    HtxTrendFollowingService(HtxUsdtSwapService swap,
                             HtxContractProperties contractProperties,
                             HtxFundingArbitrageProperties fundingArbitrageProperties,
                             HtxMarketMakerProperties marketMakerProperties,
                             HtxTrendProperties properties,
                             HtxTrendRecorder recorder,
                             HtxTrendSignalCalculator calculator) {
        this.swap = swap;
        this.contractProperties = contractProperties;
        this.fundingArbitrageProperties = fundingArbitrageProperties;
        this.marketMakerProperties = marketMakerProperties;
        this.properties = properties;
        this.recorder = recorder;
        this.calculator = calculator;
    }

    public RunResult runOnce() {
        List<String> contractCodes = liveContractCodes();
        if (contractCodes.isEmpty()) {
            return RunResult.of(RunCode.SKIPPED, null, "trend contract list is blank");
        }
        long openLikePositions = recorder.countOpenLike();
        int maxActivePositions = Math.max(1, properties.getMaxActivePositions());
        List<String> summaries = new ArrayList<>();
        RunResult selected = null;
        for (String contractCode : contractCodes) {
            boolean allowNewEntry = openLikePositions < maxActivePositions;
            RunResult result = runOnce(contractCode, allowNewEntry);
            RunResult tagged = tag(contractCode, result);
            summaries.add(contractCode + "=" + result.code() + ":" + result.message());
            if (opensPosition(result)) {
                openLikePositions++;
            }
            if (selected == null || rank(tagged.code()) > rank(selected.code())) {
                selected = tagged;
            }
            if (blocksFurtherEvaluation(result)) {
                break;
            }
        }
        if (selected == null) {
            return RunResult.of(RunCode.SKIPPED, null, "no trend contracts evaluated");
        }
        return RunResult.of(selected.code(), selected.positionId(), selected.message()
                + "; liveContracts=" + String.join(" | ", summaries));
    }

    private RunResult runOnce(String contractCode, boolean allowNewEntry) {
        String gate = configGate(contractCode);
        if (gate != null) {
            return RunResult.of(RunCode.SKIPPED, null, gate);
        }
        HtxSwapContractInfo contract = loadContract(contractCode);
        if (contract == null || !positive(contract.getContractSize())) {
            return RunResult.of(RunCode.SKIPPED, null, "contract info unavailable");
        }
        TrendSignal signal = loadSignal(contractCode);
        recordSignal(contractCode, signal);
        Optional<HtxTrendPositionEntity> active = recorder.findActive(contractCode);
        if (active.isPresent()) {
            return advance(active.get(), signal, contract);
        }
        if (!allowNewEntry) {
            return RunResult.of(RunCode.SKIPPED, null, "max active trend positions reached");
        }
        return openIfEligible(contractCode, signal, contract);
    }

    private RunResult openIfEligible(String contractCode, TrendSignal signal, HtxSwapContractInfo contract) {
        if (signal.side() == TrendSide.FLAT) {
            return RunResult.of(RunCode.NO_SIGNAL, null, signal.reason());
        }
        String fundingCostGate = fundingCostGate(contractCode, signal.side(), properties.getMaxEntryFundingCostRate());
        if (fundingCostGate != null) {
            RunCode code = properties.isObserveOnly() ? RunCode.OBSERVED : RunCode.SKIPPED;
            return RunResult.of(code, null, fundingCostGate);
        }
        if (properties.isObserveOnly()) {
            return RunResult.of(RunCode.OBSERVED, null, "observe-only signal=" + signal.side()
                    + " reason=" + signal.reason());
        }
        String cooldownGate = cooldownGate(contractCode);
        if (cooldownGate != null) {
            return RunResult.of(RunCode.SKIPPED, null, cooldownGate);
        }
        TopOfBook book = topOfBook(contractCode);
        if (book == null) {
            return RunResult.of(RunCode.SKIPPED, null, "depth unavailable");
        }
        BigDecimal spreadRate = book.ask().subtract(book.bid()).divide(mid(book), 18, RoundingMode.HALF_UP);
        if (spreadRate.compareTo(properties.getMaxEntrySpreadRate()) > 0) {
            return RunResult.of(RunCode.SKIPPED, null, "spread too wide: " + spreadRate);
        }
        String openOrderGate = openOrderGate(contractCode);
        if (openOrderGate != null) {
            return RunResult.of(RunCode.SKIPPED, null, openOrderGate);
        }
        String externalPositionGate = externalPositionGate(contractCode);
        if (externalPositionGate != null) {
            return RunResult.of(RunCode.SKIPPED, null, externalPositionGate);
        }
        BigDecimal entryPrice = signal.side() == TrendSide.LONG ? book.ask() : book.bid();
        long volume = targetVolume(entryPrice, signal.atr(), contract.getContractSize());
        if (volume <= 0) {
            return RunResult.of(RunCode.SKIPPED, null, "target volume below minimum");
        }
        String expectedMoveGate = expectedMoveGate(signal, entryPrice, spreadRate);
        if (expectedMoveGate != null) {
            return RunResult.of(RunCode.SKIPPED, null, expectedMoveGate);
        }
        BigDecimal notional = BigDecimal.valueOf(volume).multiply(contract.getContractSize()).multiply(entryPrice);
        String marginGate = marginGate(contractCode, notional);
        if (marginGate != null) {
            return RunResult.of(RunCode.SKIPPED, null, marginGate);
        }
        String leverageGate = switchLeverage(contractCode, signal.side());
        if (leverageGate != null) {
            return RunResult.of(RunCode.SKIPPED, null, leverageGate);
        }
        String side = signal.side() == TrendSide.LONG ? LONG : SHORT;
        BigDecimal stopPrice = initialStop(signal, side, contract);
        BigDecimal takeProfitPrice = initialTakeProfit(signal, side, contract);
        String positionKey = positionKey(contractCode);
        String clientOrderId = Long.toString(numericClientOrderId());
        HtxTrendPositionEntity entity = new HtxTrendPositionEntity();
        entity.setPositionKey(positionKey);
        entity.setContractCode(contractCode);
        entity.setStatus(HtxTrendRecorder.INTENT);
        entity.setSide(side);
        entity.setContractSize(contract.getContractSize());
        entity.setContractVolume(volume);
        entity.setLeverage(properties.getLeverage());
        entity.setEntryClientOrderId(clientOrderId);
        entity.setEntryFilledContracts(BigDecimal.ZERO);
        entity.setEntryFee(BigDecimal.ZERO);
        entity.setCloseFilledContracts(BigDecimal.ZERO);
        entity.setCloseFee(BigDecimal.ZERO);
        entity.setStopPrice(stopPrice);
        entity.setTrailingStopPrice(stopPrice);
        entity.setTakeProfitPrice(takeProfitPrice);
        entity.setHighestPrice(signal.closePrice());
        entity.setLowestPrice(signal.closePrice());
        entity.setEntrySignal(signal.reason());
        entity.setMessage("trend entry intent signal=" + signal.side());
        long id = recorder.createIntent(entity);
        entity.setId(id);
        HtxSwapOrderRequest request = orderRequest(contractCode, signal.side(), false, clientOrderId, volume);
        recorder.createOrderIntent(orderIntent(entity, HtxTrendRecorder.LEG_ENTRY, signal.side().name(), request,
                "entry signal: " + signal.reason()));
        HtxRestResponse<HtxSwapOrderResult> response = swap.placeOrder(request);
        HtxSwapOrderResult accepted = response == null ? null : response.payload();
        Long orderId = accepted == null ? null : accepted.getOrderId();
        if (response == null || !response.ok() || orderId == null) {
            recorder.markOrderRejected(id, HtxTrendRecorder.LEG_ENTRY, clientOrderId,
                    "entry rejected: " + error(response));
            recorder.markStatus(id, HtxTrendRecorder.REJECTED, "entry rejected: " + error(response));
            return RunResult.of(RunCode.REJECTED, id, "entry rejected: " + error(response));
        }
        recorder.markOrderAccepted(id, HtxTrendRecorder.LEG_ENTRY, clientOrderId, orderId,
                accepted.getClientOrderId(), "entry accepted by exchange");
        recorder.markEntryAccepted(id, orderId, "entry sent side=" + side + " volume=" + volume);
        log.info("[HTX-TREND] entry sent id={} contract={} side={} orderId={} volume={} close={} atr={} adx={} reason={}",
                id, contractCode, side, orderId, volume, signal.closePrice(), signal.atr(), signal.adx(), signal.reason());
        return RunResult.of(RunCode.ENTRY_SENT, id, "entry order sent");
    }

    private RunResult advance(HtxTrendPositionEntity position, TrendSignal signal, HtxSwapContractInfo contract) {
        if (HtxTrendRecorder.ENTRY_SENT.equals(position.getStatus())) {
            return reconcileEntry(position);
        }
        if (HtxTrendRecorder.ACTIVE.equals(position.getStatus())) {
            return monitorActive(position, signal, contract);
        }
        if (HtxTrendRecorder.CLOSE_SENT.equals(position.getStatus())) {
            return reconcileClose(position);
        }
        return RunResult.of(RunCode.WAITING, position.getId(), "position status=" + position.getStatus());
    }

    private RunResult reconcileEntry(HtxTrendPositionEntity position) {
        QueryOrderResult query = queryOrder(position.getContractCode(), position.getEntryOrderId(),
                position.getEntryClientOrderId());
        if (!query.found()) {
            return RunResult.of(RunCode.WAITING, position.getId(), query.message());
        }
        HtxSwapOrder order = query.order();
        if (filled(order)) {
            BigDecimal avg = firstPositive(order.getTradeAveragePrice(), position.getEntryAvgPrice());
            BigDecimal highLow = avg == null ? BigDecimal.ZERO : avg;
            recorder.markOrderReconciled(position.getId(), HtxTrendRecorder.LEG_ENTRY,
                    position.getEntryClientOrderId(), HtxTrendRecorder.ORDER_FILLED, order.getOrderId(),
                    order.getClientOrderId(), exchangeState(order), nz(order.getTradeVolume()), avg, nz(order.getFee()),
                    order.getFeeCurrency(), order.getRealProfit(), "entry filled state=" + exchangeState(order));
            recorder.markEntryFilled(position.getId(), nz(order.getTradeVolume()), avg, nz(order.getFee()),
                    highLow, highLow, "entry filled state=" + exchangeState(order));
            return RunResult.of(RunCode.ACTIVE, position.getId(), "entry filled");
        }
        if (terminal(order)) {
            recorder.markOrderReconciled(position.getId(), HtxTrendRecorder.LEG_ENTRY,
                    position.getEntryClientOrderId(), HtxTrendRecorder.ORDER_TERMINAL, order.getOrderId(),
                    order.getClientOrderId(), exchangeState(order), nz(order.getTradeVolume()),
                    order.getTradeAveragePrice(), nz(order.getFee()), order.getFeeCurrency(), order.getRealProfit(),
                    "entry terminal without fill state=" + exchangeState(order));
            recorder.markStatus(position.getId(), HtxTrendRecorder.REJECTED,
                    "entry terminal without fill state=" + exchangeState(order));
            return RunResult.of(RunCode.REJECTED, position.getId(), "entry terminal without fill");
        }
        return RunResult.of(RunCode.WAITING, position.getId(), "waiting entry fill");
    }

    private RunResult monitorActive(HtxTrendPositionEntity position, TrendSignal signal, HtxSwapContractInfo contract) {
        Optional<HtxSwapPosition> exchangePosition = exchangePosition(position.getContractCode(), position.getSide());
        if (exchangePosition.isEmpty()) {
            recorder.markStatus(position.getId(), HtxTrendRecorder.UNKNOWN,
                    "managed position missing on exchange; manual review");
            return RunResult.of(RunCode.MANUAL_REVIEW, position.getId(), "managed position missing on exchange");
        }
        BigDecimal reference = firstPositive(exchangePosition.get().getMarkPrice(), exchangePosition.get().getLastPrice(),
                signal.closePrice(), position.getEntryAvgPrice());
        BigDecimal highest = max(firstPositive(position.getHighestPrice(), reference), reference);
        BigDecimal lowest = min(firstPositive(position.getLowestPrice(), reference), reference);
        BigDecimal trailingStop = trailingStop(position, signal, reference, highest, lowest, contract);
        BigDecimal feeProtectedStop = feeProtectedStop(position, highest, lowest, contract);
        if (feeProtectedStop != null) {
            trailingStop = LONG.equals(position.getSide())
                    ? max(trailingStop, feeProtectedStop) : min(trailingStop, feeProtectedStop);
        }
        BigDecimal takeProfit = position.getTakeProfitPrice();
        recorder.updateRiskLevels(position.getId(), position.getStopPrice(), trailingStop, takeProfit, highest, lowest,
                "holding signal=" + signal.side() + " ref=" + reference + feeProtectionMessage(feeProtectedStop));
        String exitReason = flatAfterFeeProtectionExitReason(position, signal, reference, feeProtectedStop);
        if (exitReason == null) {
            exitReason = exitReason(position.getSide(), signal, reference, trailingStop, takeProfit);
        }
        if (exitReason == null) {
            exitReason = fundingExitReason(position.getContractCode(), position.getSide());
        }
        if (exitReason == null) {
            return RunResult.of(RunCode.ACTIVE, position.getId(), "holding ref=" + reference
                    + " signal=" + signal.side() + " trailingStop=" + trailingStop);
        }
        return closePosition(position, exitReason);
    }

    private RunResult closePosition(HtxTrendPositionEntity position, String exitReason) {
        if (properties.isObserveOnly()) {
            return RunResult.of(RunCode.ACTIVE, position.getId(), "observe-only close signal: " + exitReason);
        }
        String clientOrderId = Long.toString(numericClientOrderId());
        TrendSide side = LONG.equals(position.getSide()) ? TrendSide.LONG : TrendSide.SHORT;
        HtxSwapOrderRequest request = orderRequest(position.getContractCode(), side, true, clientOrderId,
                position.getContractVolume());
        recorder.createOrderIntent(orderIntent(position, HtxTrendRecorder.LEG_CLOSE, position.getSide(), request,
                "exit signal: " + exitReason));
        HtxRestResponse<HtxSwapOrderResult> response = swap.placeOrder(request);
        HtxSwapOrderResult accepted = response == null ? null : response.payload();
        Long orderId = accepted == null ? null : accepted.getOrderId();
        if (response == null || !response.ok() || orderId == null) {
            recorder.markOrderRejected(position.getId(), HtxTrendRecorder.LEG_CLOSE, clientOrderId,
                    "close rejected: " + error(response));
            return RunResult.of(RunCode.WAITING, position.getId(), "close rejected: " + error(response));
        }
        recorder.markOrderAccepted(position.getId(), HtxTrendRecorder.LEG_CLOSE, clientOrderId, orderId,
                accepted.getClientOrderId(), "close accepted by exchange");
        recorder.markCloseAccepted(position.getId(), exitReason, clientOrderId, orderId,
                "close sent reason=" + exitReason);
        log.info("[HTX-TREND] close sent id={} contract={} side={} orderId={} volume={} reason={}",
                position.getId(), position.getContractCode(), position.getSide(), orderId,
                position.getContractVolume(), exitReason);
        return RunResult.of(RunCode.CLOSE_SENT, position.getId(), exitReason);
    }

    private RunResult reconcileClose(HtxTrendPositionEntity position) {
        QueryOrderResult query = queryOrder(position.getContractCode(), position.getCloseOrderId(),
                position.getCloseClientOrderId());
        if (!query.found()) {
            return RunResult.of(RunCode.WAITING, position.getId(), query.message());
        }
        HtxSwapOrder order = query.order();
        if (filled(order)) {
            recorder.markOrderReconciled(position.getId(), HtxTrendRecorder.LEG_CLOSE,
                    position.getCloseClientOrderId(), HtxTrendRecorder.ORDER_FILLED, order.getOrderId(),
                    order.getClientOrderId(), exchangeState(order), nz(order.getTradeVolume()),
                    order.getTradeAveragePrice(), nz(order.getFee()), order.getFeeCurrency(), order.getRealProfit(),
                    "close filled state=" + exchangeState(order));
            recorder.markCloseFilled(position.getId(), nz(order.getTradeVolume()), order.getTradeAveragePrice(),
                    nz(order.getFee()), order.getRealProfit(), "close filled state=" + exchangeState(order));
            return RunResult.of(RunCode.DONE, position.getId(), "position done");
        }
        if (terminal(order)) {
            recorder.markOrderReconciled(position.getId(), HtxTrendRecorder.LEG_CLOSE,
                    position.getCloseClientOrderId(), HtxTrendRecorder.ORDER_TERMINAL, order.getOrderId(),
                    order.getClientOrderId(), exchangeState(order), nz(order.getTradeVolume()),
                    order.getTradeAveragePrice(), nz(order.getFee()), order.getFeeCurrency(), order.getRealProfit(),
                    "close terminal without fill state=" + exchangeState(order));
            recorder.markStatus(position.getId(), HtxTrendRecorder.UNKNOWN,
                    "close terminal without fill state=" + exchangeState(order));
            return RunResult.of(RunCode.MANUAL_REVIEW, position.getId(), "close terminal without fill");
        }
        return RunResult.of(RunCode.WAITING, position.getId(), "waiting close fill");
    }

    private TrendSignal loadSignal(String contractCode) {
        HtxRestResponse<List<HtxSwapKline>> entryResponse = swap.historyKline(contractCode,
                properties.getPeriod(), properties.getKlineSize());
        if (entryResponse == null || !entryResponse.ok() || entryResponse.payload() == null) {
            return TrendSignal.skip("entry kline unavailable: " + error(entryResponse));
        }
        String trendPeriod = trendPeriod();
        HtxRestResponse<List<HtxSwapKline>> trendResponse = trendPeriod.equalsIgnoreCase(properties.getPeriod())
                ? entryResponse : swap.historyKline(contractCode, trendPeriod, properties.getTrendKlineSize());
        if (trendResponse == null || !trendResponse.ok() || trendResponse.payload() == null) {
            return TrendSignal.skip("trend kline unavailable: " + error(trendResponse));
        }
        return calculator.evaluate(entryResponse.payload(), trendResponse.payload(), properties, Instant.now().getEpochSecond());
    }

    private void recordSignal(String contractCode, TrendSignal signal) {
        HtxTrendSignalEntity entity = new HtxTrendSignalEntity();
        entity.setContractCode(contractCode);
        entity.setPeriod(properties.getPeriod());
        entity.setBarTime(signal.barTime());
        entity.setSignal(signal.side().name());
        entity.setClosePrice(signal.closePrice());
        entity.setEmaFast(signal.emaFast());
        entity.setEmaSlow(signal.emaSlow());
        entity.setBreakoutHigh(signal.breakoutHigh());
        entity.setBreakoutLow(signal.breakoutLow());
        entity.setAtr(signal.atr());
        entity.setAtrPct(signal.atrPct());
        entity.setAdx(signal.adx());
        entity.setReason(signal.reason());
        FundingMarket funding = currentFunding(contractCode);
        entity.setFundingRate(funding == null ? null : funding.fundingRate());
        TopOfBook book = topOfBook(contractCode);
        entity.setSpreadRate(book == null ? null : book.ask().subtract(book.bid()).divide(mid(book), 18, RoundingMode.HALF_UP));
        recorder.recordSignal(entity);
    }

    private HtxSwapContractInfo loadContract(String contractCode) {
        HtxRestResponse<List<HtxSwapContractInfo>> response = swap.contractInfo(contractCode);
        if (response == null || !response.ok() || response.payload() == null || response.payload().isEmpty()) {
            return null;
        }
        return response.payload().stream()
                .filter(item -> item != null && contractCode.equalsIgnoreCase(item.getContractCode()))
                .findFirst().orElse(response.payload().get(0));
    }

    private TopOfBook topOfBook(String contractCode) {
        HtxRestResponse<HtxDepth> response = swap.depth(contractCode, contractProperties.getDepthStep());
        HtxDepth depth = response == null ? null : response.payload();
        if (depth == null || depth.getBids() == null || depth.getAsks() == null
                || depth.getBids().isEmpty() || depth.getAsks().isEmpty()) {
            return null;
        }
        HtxPriceLevel bid = depth.getBids().get(0);
        HtxPriceLevel ask = depth.getAsks().get(0);
        if (bid == null || ask == null || !positive(bid.getPrice()) || !positive(ask.getPrice())) {
            return null;
        }
        return new TopOfBook(bid.getPrice(), ask.getPrice());
    }

    private String openOrderGate(String contractCode) {
        HtxRestResponse<HtxSwapOpenOrders> response = swap.openOrders(contractCode);
        HtxSwapOpenOrders openOrders = response == null ? null : response.payload();
        if (response == null || !response.ok() || openOrders == null || openOrders.getOrders() == null) {
            return "contract open orders query failed: " + error(response);
        }
        if (!openOrders.getOrders().isEmpty()) {
            return "contract has open orders; wait count=" + openOrders.getOrders().size();
        }
        return null;
    }

    private String externalPositionGate(String contractCode) {
        HtxRestResponse<List<HtxSwapPosition>> response = swap.positionInfo(contractCode);
        if (response == null || !response.ok() || response.payload() == null) {
            return "position query failed: " + error(response);
        }
        BigDecimal openVolume = response.payload().stream()
                .filter(item -> item != null && positive(item.getVolume()))
                .map(HtxSwapPosition::getVolume)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (openVolume.signum() > 0) {
            return "external contract position exists; trend strategy will not take over volume=" + openVolume;
        }
        return null;
    }

    private Optional<HtxSwapPosition> exchangePosition(String contractCode, String side) {
        HtxRestResponse<List<HtxSwapPosition>> response = swap.positionInfo(contractCode);
        if (response == null || !response.ok() || response.payload() == null) {
            return Optional.empty();
        }
        String positionSide = LONG.equals(side) ? POSITION_SIDE_LONG : POSITION_SIDE_SHORT;
        return response.payload().stream()
                .filter(item -> item != null && positionSide.equalsIgnoreCase(item.getPositionSide()))
                .filter(item -> positive(item.getVolume()))
                .findFirst();
    }

    private String switchLeverage(String contractCode, TrendSide side) {
        String positionSide = side == TrendSide.LONG ? POSITION_SIDE_LONG : POSITION_SIDE_SHORT;
        HtxRestResponse<?> response = swap.switchLeverage(contractCode, positionSide, properties.getLeverage());
        if (response == null || !response.ok() || response.getData() == null) {
            return "leverage switch failed: " + error(response);
        }
        return null;
    }

    private String marginGate(String contractCode, BigDecimal notional) {
        HtxRestResponse<List<HtxSwapAccount>> response = swap.accountInfo(contractCode);
        if (response == null || !response.ok() || response.payload() == null) {
            return "contract margin unavailable: " + error(response);
        }
        BigDecimal margin = response.payload().stream()
                .filter(item -> item != null && item.getMarginAvailable() != null)
                .map(HtxSwapAccount::getMarginAvailable)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal required = notional.divide(BigDecimal.valueOf(Math.max(1, properties.getLeverage())), 18, RoundingMode.UP)
                .multiply(contractProperties.getMarginSafetyRatio());
        if (required.compareTo(properties.getMinContractMarginAvailable()) < 0) {
            required = properties.getMinContractMarginAvailable();
        }
        if (margin.compareTo(required) < 0) {
            return "contract margin insufficient available=" + margin + " required=" + required;
        }
        return null;
    }

    private String cooldownGate(String contractCode) {
        if (properties.getCooldownMs() <= 0) {
            return null;
        }
        Optional<LocalDateTime> latest = recorder.latestDoneAt(contractCode);
        if (latest.isEmpty()) {
            return null;
        }
        long age = Duration.between(latest.get(), LocalDateTime.now()).toMillis();
        if (age < properties.getCooldownMs()) {
            return "cooldown active remainingMs=" + (properties.getCooldownMs() - age);
        }
        return null;
    }

    private FundingMarket currentFunding(String contractCode) {
        HtxRestResponse<HtxSwapFundingRate> response = swap.fundingRate(contractCode);
        HtxSwapFundingRate funding = response == null ? null : response.payload();
        if (response == null || !response.ok() || funding == null || funding.getFundingRate() == null) {
            return null;
        }
        return new FundingMarket(funding.getFundingRate());
    }

    private String fundingCostGate(String contractCode, TrendSide side, BigDecimal maxCostRate) {
        if (maxCostRate == null || maxCostRate.signum() < 0) {
            return null;
        }
        FundingMarket funding = currentFunding(contractCode);
        if (funding == null || funding.fundingRate() == null) {
            return "funding unavailable; skip directional entry";
        }
        BigDecimal sideCost = side == TrendSide.LONG ? funding.fundingRate() : funding.fundingRate().negate();
        if (sideCost.compareTo(maxCostRate) > 0) {
            return "funding cost blocked side=" + side + " funding=" + funding.fundingRate()
                    + " sideCost=" + sideCost + " max=" + maxCostRate;
        }
        return null;
    }

    private String fundingExitReason(String contractCode, String side) {
        TrendSide trendSide = LONG.equals(side) ? TrendSide.LONG : TrendSide.SHORT;
        String gate = fundingCostGate(contractCode, trendSide, properties.getMaxHoldFundingCostRate());
        return gate == null ? null : "exit because " + gate;
    }

    private QueryOrderResult queryOrder(String contractCode, Long orderId, String clientOrderId) {
        HtxRestResponse<List<HtxSwapOrder>> response = orderId != null
                ? swap.queryOrder(contractCode, orderId) : swap.queryOrderByClientOrderId(contractCode, clientOrderId);
        if (response == null || !response.ok() || response.payload() == null || response.payload().isEmpty()) {
            return QueryOrderResult.failed("order query failed: " + error(response));
        }
        return QueryOrderResult.found(response.payload().get(0));
    }

    private HtxSwapOrderRequest orderRequest(String contractCode, TrendSide trendSide, boolean reduceOnly,
                                             String clientOrderId, long volume) {
        boolean longSide = trendSide == TrendSide.LONG;
        String side = reduceOnly ? (longSide ? SIDE_SELL : SIDE_BUY) : (longSide ? SIDE_BUY : SIDE_SELL);
        String positionSide = longSide ? POSITION_SIDE_LONG : POSITION_SIDE_SHORT;
        return HtxUsdtSwapService.orderRequest(contractCode, Long.parseLong(clientOrderId), volume,
                side, positionSide, reduceOnly, null, ORDER_TYPE_MARKET);
    }

    private HtxTrendOrderEntity orderIntent(HtxTrendPositionEntity position, String leg, String trendSide,
                                            HtxSwapOrderRequest request, String reason) {
        HtxTrendOrderEntity entity = new HtxTrendOrderEntity();
        entity.setPositionId(position.getId());
        entity.setPositionKey(position.getPositionKey());
        entity.setContractCode(request.getContractCode());
        entity.setLeg(leg);
        entity.setTrendSide(trendSide);
        entity.setOrderSide(request.getSide());
        entity.setPositionSide(request.getPositionSide());
        entity.setReduceOnly(Boolean.TRUE.equals(request.getReduceOnly()));
        entity.setClientOrderId(request.getClientOrderId());
        entity.setRequestVolume(Long.parseLong(request.getVolume()));
        entity.setFilledContracts(BigDecimal.ZERO);
        entity.setFee(BigDecimal.ZERO);
        entity.setStatus(HtxTrendRecorder.ORDER_INTENT);
        entity.setReason(reason);
        entity.setMessage("prepared " + leg.toLowerCase(Locale.ROOT) + " order");
        return entity;
    }

    private long targetVolume(BigDecimal entryPrice, BigDecimal atr, BigDecimal contractSize) {
        if (!positive(entryPrice) || !positive(atr) || !positive(contractSize)) {
            return 0L;
        }
        BigDecimal stopDistance = atr.multiply(properties.getAtrStopMultiplier());
        if (stopDistance.signum() <= 0) {
            return 0L;
        }
        BigDecimal riskSizedNotional = properties.getRiskPerTradeUsdt()
                .divide(stopDistance.divide(entryPrice, 18, RoundingMode.UP), 18, RoundingMode.DOWN);
        BigDecimal notional = min(properties.getTargetQuoteNotional(), properties.getMaxQuoteNotional(), riskSizedNotional);
        if (notional.compareTo(properties.getMinQuoteNotional()) < 0) {
            return 0L;
        }
        BigDecimal perContract = entryPrice.multiply(contractSize);
        return notional.divide(perContract, 0, RoundingMode.DOWN).longValue();
    }

    private String expectedMoveGate(TrendSignal signal, BigDecimal entryPrice, BigDecimal spreadRate) {
        if (!properties.isFeeProtectionEnabled() || !positive(signal.atr()) || !positive(entryPrice)) {
            return null;
        }
        BigDecimal expectedMove = signal.atr().multiply(nonNegative(properties.getMinExpectedMoveAtrMultiplier()));
        BigDecimal requiredMove = entryPrice.multiply(roundTripCostRate(spreadRate));
        if (expectedMove.compareTo(requiredMove) < 0) {
            return "expected move too small after fees expectedMove=" + expectedMove
                    + " requiredMove=" + requiredMove + " atr=" + signal.atr();
        }
        return null;
    }

    private BigDecimal initialStop(TrendSignal signal, String side, HtxSwapContractInfo contract) {
        BigDecimal distance = signal.atr().multiply(properties.getAtrStopMultiplier());
        BigDecimal value = LONG.equals(side) ? signal.closePrice().subtract(distance) : signal.closePrice().add(distance);
        return scalePrice(value, contract);
    }

    private BigDecimal initialTakeProfit(TrendSignal signal, String side, HtxSwapContractInfo contract) {
        if (properties.getTakeProfitAtrMultiplier() == null || properties.getTakeProfitAtrMultiplier().signum() <= 0) {
            return null;
        }
        BigDecimal distance = signal.atr().multiply(properties.getTakeProfitAtrMultiplier());
        BigDecimal value = LONG.equals(side) ? signal.closePrice().add(distance) : signal.closePrice().subtract(distance);
        return scalePrice(value, contract);
    }

    private BigDecimal trailingStop(HtxTrendPositionEntity position, TrendSignal signal, BigDecimal reference,
                                    BigDecimal highest, BigDecimal lowest, HtxSwapContractInfo contract) {
        BigDecimal distance = positive(signal.atr()) ? signal.atr().multiply(properties.getTrailingAtrMultiplier())
                : reference.multiply(properties.getMinAtrPct()).multiply(properties.getTrailingAtrMultiplier());
        BigDecimal candidate = LONG.equals(position.getSide()) ? highest.subtract(distance) : lowest.add(distance);
        BigDecimal previous = firstPositive(position.getTrailingStopPrice(), position.getStopPrice(), candidate);
        if (LONG.equals(position.getSide())) {
            return scalePrice(max(previous, candidate), contract);
        }
        return scalePrice(min(previous, candidate), contract);
    }

    private BigDecimal feeProtectedStop(HtxTrendPositionEntity position, BigDecimal highest, BigDecimal lowest,
                                        HtxSwapContractInfo contract) {
        if (!properties.isFeeProtectionEnabled() || position == null || !positive(position.getEntryAvgPrice())
                || !positive(position.getContractSize()) || position.getContractVolume() == null
                || position.getContractVolume() <= 0L) {
            return null;
        }
        BigDecimal quantity = position.getContractSize().multiply(BigDecimal.valueOf(position.getContractVolume()));
        if (!positive(quantity)) {
            return null;
        }
        BigDecimal entryPrice = position.getEntryAvgPrice();
        BigDecimal entryNotional = entryPrice.multiply(quantity);
        BigDecimal entryFee = positive(position.getEntryFee())
                ? position.getEntryFee() : entryNotional.multiply(nonNegative(properties.getEstimatedTakerFeeRate()));
        BigDecimal estimatedCloseFee = entryNotional.multiply(nonNegative(properties.getEstimatedTakerFeeRate()));
        BigDecimal minProfit = entryNotional.multiply(nonNegative(properties.getMinNetProfitRate()))
                .max(nonNegative(properties.getMinFlatExitProfitUsdt()));
        BigDecimal priceDelta = entryFee.add(estimatedCloseFee).add(minProfit)
                .divide(quantity, 18, RoundingMode.UP);
        if (LONG.equals(position.getSide())) {
            BigDecimal protectedStop = scalePrice(entryPrice.add(priceDelta), contract, RoundingMode.UP);
            return highest.compareTo(protectedStop) >= 0 ? protectedStop : null;
        }
        BigDecimal protectedStop = scalePrice(entryPrice.subtract(priceDelta), contract, RoundingMode.DOWN);
        return lowest.compareTo(protectedStop) <= 0 ? protectedStop : null;
    }

    private String feeProtectionMessage(BigDecimal feeProtectedStop) {
        return feeProtectedStop == null ? "" : " feeProtectedStop=" + feeProtectedStop;
    }

    private String flatAfterFeeProtectionExitReason(HtxTrendPositionEntity position, TrendSignal signal,
                                                   BigDecimal reference, BigDecimal feeProtectedStop) {
        if (!properties.isExitFlatAfterFeeProtection() || feeProtectedStop == null
                || signal.side() != TrendSide.FLAT || !positive(reference)) {
            return null;
        }
        int requiredFlatBars = Math.max(1, properties.getFlatExitConfirmationBars());
        LocalDateTime since = position.getCreatedAt() == null ? SIGNAL_HISTORY_FLOOR : position.getCreatedAt();
        long flatBars = recorder.countRecentSignalsBySide(position.getContractCode(), TrendSide.FLAT.name(),
                since, requiredFlatBars);
        if (flatBars < requiredFlatBars) {
            return null;
        }
        if (LONG.equals(position.getSide()) && reference.compareTo(feeProtectedStop) >= 0) {
            return "long exit on confirmed flat trend after fee protection ref=" + reference
                    + " stop=" + feeProtectedStop + " flatBars=" + flatBars + "/" + requiredFlatBars;
        }
        if (SHORT.equals(position.getSide()) && reference.compareTo(feeProtectedStop) <= 0) {
            return "short exit on confirmed flat trend after fee protection ref=" + reference
                    + " stop=" + feeProtectedStop + " flatBars=" + flatBars + "/" + requiredFlatBars;
        }
        return null;
    }

    private BigDecimal roundTripCostRate(BigDecimal spreadRate) {
        return nonNegative(properties.getEstimatedTakerFeeRate()).multiply(TWO)
                .add(nonNegative(properties.getMinNetProfitRate()))
                .add(nonNegative(spreadRate));
    }

    private String exitReason(String side, TrendSignal signal, BigDecimal reference,
                              BigDecimal trailingStop, BigDecimal takeProfit) {
        if (LONG.equals(side)) {
            if (reference.compareTo(trailingStop) <= 0) {
                return "long trailing stop hit ref=" + reference + " stop=" + trailingStop;
            }
            if (takeProfit != null && reference.compareTo(takeProfit) >= 0) {
                return "long take profit hit ref=" + reference + " target=" + takeProfit;
            }
            if (signal.side() == TrendSide.SHORT) {
                return "long exit on opposite trend signal";
            }
            return null;
        }
        if (reference.compareTo(trailingStop) >= 0) {
            return "short trailing stop hit ref=" + reference + " stop=" + trailingStop;
        }
        if (takeProfit != null && reference.compareTo(takeProfit) <= 0) {
            return "short take profit hit ref=" + reference + " target=" + takeProfit;
        }
        if (signal.side() == TrendSide.LONG) {
            return "short exit on opposite trend signal";
        }
        return null;
    }


    private List<String> liveContractCodes() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (properties.getContractCodes() != null) {
            for (String code : properties.getContractCodes()) {
                String normalized = normalize(code);
                if (!normalized.isEmpty()) {
                    result.add(normalized);
                }
            }
        }
        String legacy = normalize(properties.getContractCode());
        if (result.isEmpty() && !legacy.isEmpty()) {
            result.add(legacy);
        }
        return new ArrayList<>(result);
    }

    private static RunResult tag(String contractCode, RunResult result) {
        if (result == null) {
            return RunResult.of(RunCode.SKIPPED, null, contractCode + ": null result");
        }
        return RunResult.of(result.code(), result.positionId(), contractCode + ": " + result.message());
    }

    private static boolean opensPosition(RunResult result) {
        return result != null && result.code() == RunCode.ENTRY_SENT;
    }

    private static boolean blocksFurtherEvaluation(RunResult result) {
        if (result == null) {
            return false;
        }
        return result.code() == RunCode.ENTRY_SENT
                || result.code() == RunCode.ACTIVE
                || result.code() == RunCode.CLOSE_SENT
                || result.code() == RunCode.DONE
                || result.code() == RunCode.WAITING
                || result.code() == RunCode.REJECTED
                || result.code() == RunCode.MANUAL_REVIEW;
    }

    private static int rank(RunCode code) {
        if (code == RunCode.ENTRY_SENT || code == RunCode.CLOSE_SENT || code == RunCode.MANUAL_REVIEW
                || code == RunCode.REJECTED) {
            return 4;
        }
        if (code == RunCode.ACTIVE || code == RunCode.DONE || code == RunCode.WAITING) {
            return 3;
        }
        if (code == RunCode.OBSERVED) {
            return 2;
        }
        if (code == RunCode.SKIPPED) {
            return 1;
        }
        return 0;
    }

    private String configGate(String contractCode) {
        if (!properties.isEnabled()) {
            return "trend strategy disabled";
        }
        if (isBlank(contractCode)) {
            return "contract code is blank";
        }
        if (!contractProperties.isEnabled()) {
            return "contract module disabled";
        }
        if (!properties.isObserveOnly() && marketMakerProperties != null && marketMakerProperties.isEnabled()) {
            return "trend exclusive mode blocked: market-maker enabled";
        }
        if (!properties.isObserveOnly() && fundingArbitrageProperties != null && fundingArbitrageProperties.isEnabled()) {
            return "trend exclusive mode blocked: funding arbitrage enabled";
        }
        if (!properties.isObserveOnly() && (contractProperties.isDryRun() || !contractProperties.isLiveEnabled())) {
            return "trend live mode requires contract dry-run=false and live-enabled=true";
        }
        if (properties.getLeverage() <= 0 || properties.getLeverage() > contractProperties.getMaxLeverage()) {
            return "trend leverage outside configured max leverage";
        }
        return null;
    }

    private static boolean filled(HtxSwapOrder order) {
        return order != null && ("filled".equalsIgnoreCase(exchangeState(order)) || Integer.valueOf(6).equals(order.getStatus()))
                && nz(order.getTradeVolume()).signum() > 0;
    }

    private static boolean terminal(HtxSwapOrder order) {
        String state = exchangeState(order);
        return "filled".equalsIgnoreCase(state)
                || "partially_canceled".equalsIgnoreCase(state)
                || "canceled".equalsIgnoreCase(state)
                || "rejected".equalsIgnoreCase(state)
                || Integer.valueOf(6).equals(order.getStatus())
                || Integer.valueOf(5).equals(order.getStatus())
                || Integer.valueOf(7).equals(order.getStatus());
    }

    private static String exchangeState(HtxSwapOrder order) {
        if (order == null) {
            return null;
        }
        if (!isBlank(order.getState())) {
            return order.getState();
        }
        if (Integer.valueOf(6).equals(order.getStatus())) {
            return "filled";
        }
        if (Integer.valueOf(5).equals(order.getStatus())) {
            return "canceled";
        }
        if (Integer.valueOf(7).equals(order.getStatus())) {
            return "rejected";
        }
        return "new";
    }

    private static BigDecimal mid(TopOfBook book) {
        return book.bid().add(book.ask()).divide(TWO, 18, RoundingMode.HALF_UP);
    }

    private BigDecimal scalePrice(BigDecimal value, HtxSwapContractInfo contract) {
        return scalePrice(value, contract, RoundingMode.HALF_UP);
    }

    private BigDecimal scalePrice(BigDecimal value, HtxSwapContractInfo contract, RoundingMode mode) {
        if (value == null) {
            return null;
        }
        return value.setScale(priceScale(contract), mode);
    }

    private int priceScale(HtxSwapContractInfo contract) {
        BigDecimal priceTick = contract == null ? null : contract.getPriceTick();
        if (positive(priceTick)) {
            return Math.max(0, priceTick.stripTrailingZeros().scale());
        }
        return Math.max(0, properties.getPriceScale());
    }

    private String trendPeriod() {
        String configured = properties.getTrendPeriod();
        if (isBlank(configured)) {
            return properties.getPeriod();
        }
        return configured.trim();
    }

    private String positionKey(String contractCode) {
        return properties.getClientOrderIdPrefix() + "-" + contractCode.replace("-", "") + "-" + System.currentTimeMillis();
    }

    private long numericClientOrderId() {
        long random = ThreadLocalRandom.current().nextLong(100L, 999L);
        return (System.currentTimeMillis() % 10_000_000_000L) * 1_000L + random;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value == null || value.signum() < 0 ? BigDecimal.ZERO : value;
    }

    private static BigDecimal firstPositive(BigDecimal... values) {
        for (BigDecimal value : values) {
            if (positive(value)) {
                return value;
            }
        }
        return null;
    }

    private static BigDecimal max(BigDecimal left, BigDecimal right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.compareTo(right) >= 0 ? left : right;
    }

    private static BigDecimal min(BigDecimal left, BigDecimal right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.compareTo(right) <= 0 ? left : right;
    }

    private static BigDecimal min(BigDecimal first, BigDecimal second, BigDecimal third) {
        return min(min(first, second), third);
    }

    private static String error(HtxRestResponse<?> response) {
        if (response == null) {
            return "null response";
        }
        if (!isBlank(response.getErrCode()) || !isBlank(response.getErrMsg())) {
            return response.getErrCode() + ":" + response.getErrMsg();
        }
        if (response.getCode() != null || !isBlank(response.getMessage())) {
            return response.getCode() + ":" + response.getMessage();
        }
        return "status=" + response.getStatus();
    }

    public enum RunCode {
        OBSERVED, NO_SIGNAL, ENTRY_SENT, ACTIVE, CLOSE_SENT, DONE, WAITING, SKIPPED, REJECTED, MANUAL_REVIEW
    }

    public record RunResult(RunCode code, Long positionId, String message) {
        public static RunResult of(RunCode code, Long positionId, String message) {
            return new RunResult(code, positionId, message);
        }
    }

    private record TopOfBook(BigDecimal bid, BigDecimal ask) {
    }

    private record FundingMarket(BigDecimal fundingRate) {
    }

    private record QueryOrderResult(boolean found, HtxSwapOrder order, String message) {
        static QueryOrderResult found(HtxSwapOrder order) {
            return new QueryOrderResult(true, order, null);
        }

        static QueryOrderResult failed(String message) {
            return new QueryOrderResult(false, null, message);
        }
    }
}
