package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.common.client.htx.HtxUsdtSwapApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxAccount;
import com.metaverse.coin.strategy.common.client.htx.model.HtxBalance;
import com.metaverse.coin.strategy.common.client.htx.model.HtxDepth;
import com.metaverse.coin.strategy.common.client.htx.model.HtxOrder;
import com.metaverse.coin.strategy.common.client.htx.model.HtxPriceLevel;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapAccount;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapContractInfo;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapFundingRate;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOpenOrders;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrder;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrderRequest;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrderResult;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapPosition;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxMarketDataService;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxSpotService;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxUsdtSwapService;
import com.metaverse.coin.strategy.htx.config.HtxContractProperties;
import com.metaverse.coin.strategy.htx.config.HtxFundingArbitrageProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxFundingArbitrageRecorder;
import com.metaverse.coin.strategy.htx.persistence.HtxPersistenceException;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxFundingArbitrageCycleEntity;
import com.metaverse.coin.strategy.htx.util.HtxSymbolUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * HTX spot-long plus perpetual-short funding fee arbitrage.
 *
 * <p>The service owns only rows in {@code htx_funding_arbitrage_cycle}. It does not infer ownership
 * from aggregate exchange balances, which keeps old grid leftovers from being closed by this strategy.</p>
 */
@Slf4j
@Service
@ConditionalOnBean({HtxApiService.class, HtxUsdtSwapApiService.class})
@ConditionalOnProperty(prefix = "htx.funding-arbitrage", name = "enabled", havingValue = "true")
public class HtxFundingArbitrageService {

    private static final String SIDE_SELL = "sell";
    private static final String SIDE_BUY = "buy";
    private static final String POSITION_SIDE_SHORT = "short";
    private static final String MARKET_TYPE = "market";
    private static final String IOC = "ioc";
    private static final BigDecimal TWO = new BigDecimal("2");

    private final HtxApiService spotApi;
    private final HtxUsdtSwapService swap;
    private final HtxTradeProperties tradeProperties;
    private final HtxContractProperties contractProperties;
    private final HtxFundingArbitrageProperties properties;
    private final HtxFundingArbitrageRecorder recorder;

    @Autowired
    public HtxFundingArbitrageService(HtxApiService spotApi,
                                      HtxUsdtSwapApiService swapApi,
                                      HtxTradeProperties tradeProperties,
                                      HtxContractProperties contractProperties,
                                      HtxFundingArbitrageProperties properties,
                                      HtxFundingArbitrageRecorder recorder) {
        this(spotApi, swapApi.getSwapService(), tradeProperties, contractProperties, properties, recorder);
    }

    HtxFundingArbitrageService(HtxApiService spotApi,
                               HtxUsdtSwapService swap,
                               HtxTradeProperties tradeProperties,
                               HtxContractProperties contractProperties,
                               HtxFundingArbitrageProperties properties,
                               HtxFundingArbitrageRecorder recorder) {
        this.spotApi = spotApi;
        this.swap = swap;
        this.tradeProperties = tradeProperties;
        this.contractProperties = contractProperties;
        this.properties = properties;
        this.recorder = recorder;
    }

    public RunResult runOnce() {
        String symbol = normalizeSymbol(properties.getSymbol());
        String contractCode = normalizeContract(properties.getContractCode());
        String gate = liveGate(symbol, contractCode);
        if (gate != null) {
            return new RunResult(RunCode.LIVE_GATE_CLOSED, null, gate);
        }
        Optional<HtxFundingArbitrageCycleEntity> active = recorder.findActive(symbol, contractCode);
        if (active.isPresent()) {
            return advance(active.get());
        }
        if (!properties.isEntryEnabled()) {
            return new RunResult(RunCode.SKIPPED, null, "funding arbitrage entry disabled; no active cycle");
        }
        return openIfEligible(symbol, contractCode);
    }

    private RunResult openIfEligible(String symbol, String contractCode) {
        FundingMarket market = loadFundingMarket(symbol, contractCode);
        if (!market.available()) {
            return new RunResult(RunCode.SKIPPED, null, market.reason());
        }
        if (market.fundingRate().compareTo(properties.getMinFundingRate()) < 0) {
            return new RunResult(RunCode.SKIPPED, null,
                    "funding below entry threshold: " + market.fundingRate());
        }
        if (abs(market.basisRate()).compareTo(properties.getMaxEntryBasisRate()) > 0) {
            return new RunResult(RunCode.SKIPPED, null,
                    "basis outside entry threshold: " + market.basisRate());
        }
        HtxSwapContractInfo contract = loadContract(contractCode);
        if (contract == null || contract.getContractSize() == null || contract.getContractSize().signum() <= 0) {
            return new RunResult(RunCode.SKIPPED, null, "contract info unavailable");
        }
        long volume = targetContracts(market.spotAsk(), contract.getContractSize());
        if (volume < properties.getMinContractVolume()) {
            return new RunResult(RunCode.SKIPPED, null, "target contract volume below minimum: " + volume);
        }
        BigDecimal baseAmount = contract.getContractSize().multiply(BigDecimal.valueOf(volume))
                .setScale(properties.getBaseScale(), RoundingMode.DOWN);
        if (baseAmount.signum() <= 0) {
            return new RunResult(RunCode.SKIPPED, null, "target spot base rounded to zero");
        }
        BigDecimal spotLimitPrice = entrySpotPrice(market.spotAsk());
        BigDecimal spotCost = baseAmount.multiply(spotLimitPrice);
        String balanceGate = balanceGate(contractCode, spotCost);
        if (balanceGate != null) {
            return new RunResult(RunCode.SKIPPED, null, balanceGate);
        }
        String leverageGate = switchShortLeverage(contractCode);
        if (leverageGate != null) {
            return new RunResult(RunCode.SKIPPED, null, leverageGate);
        }
        String openOrderGate = contractOpenOrdersGate(contractCode);
        if (openOrderGate != null) {
            return new RunResult(RunCode.SKIPPED, null, openOrderGate);
        }
        String cycleKey = cycleKey(symbol);
        String spotClientOrderId = spotClientOrderId(cycleKey, "BE");
        long cycleId = recorder.createIntent(cycleKey, symbol, contractCode, properties.getTargetQuoteNotional(),
                baseAmount, contract.getContractSize(), volume, properties.getLeverage(), market.fundingRate(),
                market.spotMid(), market.swapMid(), spotClientOrderId);
        HtxRestResponse<String> response = spot().placeOrder(tradeProperties.getApi().getSpotAccountId(), symbol,
                "buy-limit", baseAmount.toPlainString(), spotLimitPrice.toPlainString(), spotClientOrderId);
        Long orderId = parseSpotOrderId(response);
        if (orderId == null) {
            recorder.markStatus(cycleId, HtxFundingArbitrageRecorder.INTENT,
                    HtxFundingArbitrageRecorder.REJECTED, "spot entry rejected: " + error(response));
            return new RunResult(RunCode.REJECTED, cycleId, "spot entry rejected: " + error(response));
        }
        recorder.markSpotEntryAccepted(cycleId, HtxFundingArbitrageRecorder.INTENT, orderId,
                "spot entry sent amount=" + baseAmount + " price=" + spotLimitPrice);
        log.info("[HTX-FUNDING-ARB] spot entry sent cycleId={} symbol={} orderId={} amount={} price={} funding={} basis={}",
                cycleId, symbol, orderId, baseAmount, spotLimitPrice, market.fundingRate(), market.basisRate());
        return new RunResult(RunCode.SPOT_ENTRY_SENT, cycleId, "spot entry order sent");
    }

    private RunResult advance(HtxFundingArbitrageCycleEntity cycle) {
        String status = cycle.getStatus();
        if (HtxFundingArbitrageRecorder.SPOT_SENT.equals(status)) {
            return reconcileSpotEntry(cycle);
        }
        if (HtxFundingArbitrageRecorder.SPOT_FILLED.equals(status)
                || HtxFundingArbitrageRecorder.SHORT_DEGRADED.equals(status)) {
            return openShort(cycle);
        }
        if (HtxFundingArbitrageRecorder.SHORT_SENT.equals(status)) {
            return reconcileShortEntry(cycle);
        }
        if (HtxFundingArbitrageRecorder.ACTIVE.equals(status)) {
            return monitorActive(cycle);
        }
        if (HtxFundingArbitrageRecorder.EXITING_SHORT_SENT.equals(status)) {
            return reconcileShortClose(cycle);
        }
        if (HtxFundingArbitrageRecorder.SHORT_CLOSED.equals(status)) {
            return sellSpotExit(cycle);
        }
        if (HtxFundingArbitrageRecorder.EXITING_SPOT_SENT.equals(status)) {
            return reconcileSpotExit(cycle);
        }
        return new RunResult(RunCode.WAITING, cycle.getId(), "cycle status=" + status + " requires manual review");
    }

    private RunResult reconcileSpotEntry(HtxFundingArbitrageCycleEntity cycle) {
        HtxRestResponse<HtxOrder> response = querySpotOrder(cycle.getSpotEntryOrderId(),
                cycle.getSpotEntryClientOrderId());
        if (response == null || !response.ok() || response.getData() == null) {
            return new RunResult(RunCode.WAITING, cycle.getId(), "spot entry query failed: " + error(response));
        }
        HtxOrder order = response.getData();
        BigDecimal filledBase = nz(order.getFilledAmount());
        if (filledBase.signum() > 0 && (spotTerminal(order) || "filled".equalsIgnoreCase(order.getState()))) {
            BigDecimal funding = currentFunding(cycle.getContractCode()).orElse(cycle.getLatestFundingRate());
            recorder.markSpotEntryFill(cycle.getId(), HtxFundingArbitrageRecorder.SPOT_FILLED, funding,
                    filledBase, nz(order.getFilledCashAmount()), nz(order.getFilledFees()),
                    "spot entry filled state=" + order.getState());
            HtxFundingArbitrageCycleEntity updated = copySpotFill(cycle, filledBase,
                    nz(order.getFilledCashAmount()), nz(order.getFilledFees()), funding);
            return openShort(updated);
        }
        if (spotTerminal(order)) {
            recorder.markStatus(cycle.getId(), HtxFundingArbitrageRecorder.SPOT_SENT,
                    HtxFundingArbitrageRecorder.REJECTED, "spot entry terminal without fill: " + order.getState());
            return new RunResult(RunCode.REJECTED, cycle.getId(), "spot entry terminal without fill");
        }
        if (ageMs(cycle) >= properties.getSpotEntryTtlMs()) {
            HtxRestResponse<String> cancel = spot().cancelOrder(cycle.getSpotEntryOrderId());
            return new RunResult(RunCode.WAITING, cycle.getId(), "spot entry ttl cancel sent ok="
                    + (cancel != null && cancel.ok()));
        }
        return new RunResult(RunCode.WAITING, cycle.getId(), "waiting spot entry fill");
    }

    private RunResult openShort(HtxFundingArbitrageCycleEntity cycle) {
        BigDecimal netSpotBase = nz(cycle.getSpotEntryFilledBase()).subtract(nz(cycle.getSpotEntryFeeBase()));
        if (netSpotBase.signum() <= 0) {
            recorder.markStatus(cycle.getId(), cycle.getStatus(), HtxFundingArbitrageRecorder.UNKNOWN,
                    "spot entry net base is zero; manual review");
            return new RunResult(RunCode.MANUAL_REVIEW, cycle.getId(), "spot entry net base is zero");
        }
        BigDecimal contractSize = cycle.getContractSize();
        if (contractSize == null || contractSize.signum() <= 0) {
            HtxSwapContractInfo contract = loadContract(cycle.getContractCode());
            contractSize = contract == null ? null : contract.getContractSize();
        }
        if (contractSize == null || contractSize.signum() <= 0) {
            return new RunResult(RunCode.WAITING, cycle.getId(), "contract size unavailable");
        }
        long volume = netSpotBase.divide(contractSize, 0, RoundingMode.DOWN).longValue();
        if (volume < properties.getMinContractVolume()) {
            recorder.markStatus(cycle.getId(), cycle.getStatus(), HtxFundingArbitrageRecorder.UNKNOWN,
                    "spot entry is too small for contract hedge; manual review");
            return new RunResult(RunCode.MANUAL_REVIEW, cycle.getId(), "spot entry too small for short hedge");
        }
        String leverageGate = switchShortLeverage(cycle.getContractCode());
        if (leverageGate != null) {
            return new RunResult(RunCode.WAITING, cycle.getId(), leverageGate);
        }
        String clientOrderId = Long.toString(numericClientOrderId(cycle.getId(), 1));
        HtxSwapOrderRequest request = HtxSwapOrderRequest.builder()
                .contractCode(cycle.getContractCode())
                .marginMode(contractProperties.getMarginMode())
                .positionSide(POSITION_SIDE_SHORT)
                .side(SIDE_SELL)
                .type(MARKET_TYPE)
                .clientOrderId(clientOrderId)
                .volume(Long.toString(volume))
                .reduceOnly(false)
                .timeInForce(IOC)
                .build();
        HtxRestResponse<HtxSwapOrderResult> response = swap.placeOrder(request);
        HtxSwapOrderResult item = response == null ? null : response.getData();
        if (response == null || !response.ok() || item == null || item.getOrderId() == null) {
            recorder.markStatus(cycle.getId(), cycle.getStatus(), HtxFundingArbitrageRecorder.SHORT_DEGRADED,
                    "short entry rejected: " + swapError(response, item));
            return new RunResult(RunCode.SHORT_DEGRADED, cycle.getId(), "short entry rejected: " + swapError(response, item));
        }
        recorder.markShortEntryAccepted(cycle.getId(), volume, clientOrderId, item.getOrderId(),
                "short entry sent volume=" + volume);
        log.info("[HTX-FUNDING-ARB] short entry sent cycleId={} contract={} orderId={} volume={}",
                cycle.getId(), cycle.getContractCode(), item.getOrderId(), volume);
        return new RunResult(RunCode.SHORT_ENTRY_SENT, cycle.getId(), "short entry order sent");
    }

    private RunResult reconcileShortEntry(HtxFundingArbitrageCycleEntity cycle) {
        QuerySwapOrderResult queried = querySwapOrder(cycle.getContractCode(), cycle.getShortEntryOrderId(),
                cycle.getShortEntryClientOrderId());
        if (!queried.ok()) {
            return new RunResult(RunCode.WAITING, cycle.getId(), queried.reason());
        }
        HtxSwapOrder order = queried.order();
        BigDecimal filled = nz(order.getTradeVolume());
        BigDecimal requested = BigDecimal.valueOf(nz(cycle.getContractVolume()));
        if (filled.compareTo(requested) >= 0 || "filled".equalsIgnoreCase(exchangeState(order))) {
            BigDecimal funding = currentFunding(cycle.getContractCode()).orElse(cycle.getLatestFundingRate());
            recorder.markShortEntryFill(cycle.getId(), HtxFundingArbitrageRecorder.ACTIVE, filled,
                    order.getTradeAveragePrice(), nz(order.getFee()), funding,
                    "matched spot long and perpetual short active");
            return new RunResult(RunCode.ACTIVE, cycle.getId(), "funding arbitrage is active");
        }
        if (swapTerminal(order)) {
            recorder.markShortEntryFill(cycle.getId(), HtxFundingArbitrageRecorder.SHORT_DEGRADED, filled,
                    order.getTradeAveragePrice(), nz(order.getFee()), cycle.getLatestFundingRate(),
                    "short entry terminal partial/missing fill state=" + exchangeState(order));
            return new RunResult(RunCode.SHORT_DEGRADED, cycle.getId(), "short entry not fully filled");
        }
        return new RunResult(RunCode.WAITING, cycle.getId(), "waiting short entry fill");
    }

    private RunResult monitorActive(HtxFundingArbitrageCycleEntity cycle) {
        FundingMarket market = loadFundingMarket(cycle.getSymbol(), cycle.getContractCode());
        if (!market.available()) {
            return new RunResult(RunCode.ACTIVE, cycle.getId(), "holding; market check unavailable: " + market.reason());
        }
        String exitReason = null;
        if (market.fundingRate().compareTo(properties.getExitFundingRate()) <= 0) {
            exitReason = "funding fell to " + market.fundingRate();
        } else if (abs(market.basisRate()).compareTo(properties.getMaxHoldBasisRate()) > 0) {
            exitReason = "basis widened to " + market.basisRate();
        } else {
            String liquidation = liquidationGate(cycle.getContractCode(), market.swapMid());
            if (liquidation != null) {
                exitReason = liquidation;
            }
        }
        if (exitReason == null) {
            return new RunResult(RunCode.ACTIVE, cycle.getId(), "holding funding=" + market.fundingRate()
                    + " basis=" + market.basisRate());
        }
        return closeShort(cycle, exitReason);
    }

    private RunResult closeShort(HtxFundingArbitrageCycleEntity cycle, String exitReason) {
        long volume = nz(cycle.getShortEntryFilledContracts()).longValue();
        if (volume <= 0L) {
            volume = nz(cycle.getContractVolume());
        }
        if (volume <= 0L) {
            recorder.markStatus(cycle.getId(), cycle.getStatus(), HtxFundingArbitrageRecorder.UNKNOWN,
                    "cannot close short; filled volume missing");
            return new RunResult(RunCode.MANUAL_REVIEW, cycle.getId(), "cannot close short; filled volume missing");
        }
        String clientOrderId = Long.toString(numericClientOrderId(cycle.getId(), 2));
        HtxSwapOrderRequest request = HtxSwapOrderRequest.builder()
                .contractCode(cycle.getContractCode())
                .marginMode(contractProperties.getMarginMode())
                .positionSide(POSITION_SIDE_SHORT)
                .side(SIDE_BUY)
                .type(MARKET_TYPE)
                .clientOrderId(clientOrderId)
                .volume(Long.toString(volume))
                .reduceOnly(true)
                .timeInForce(IOC)
                .build();
        HtxRestResponse<HtxSwapOrderResult> response = swap.placeOrder(request);
        HtxSwapOrderResult item = response == null ? null : response.getData();
        if (response == null || !response.ok() || item == null || item.getOrderId() == null) {
            return new RunResult(RunCode.WAITING, cycle.getId(), "short close rejected: " + swapError(response, item));
        }
        recorder.markShortCloseAccepted(cycle.getId(), exitReason, clientOrderId, item.getOrderId(),
                "short close sent volume=" + volume);
        log.info("[HTX-FUNDING-ARB] short close sent cycleId={} orderId={} volume={} reason={}",
                cycle.getId(), item.getOrderId(), volume, exitReason);
        return new RunResult(RunCode.EXITING_SHORT_SENT, cycle.getId(), exitReason);
    }

    private RunResult reconcileShortClose(HtxFundingArbitrageCycleEntity cycle) {
        QuerySwapOrderResult queried = querySwapOrder(cycle.getContractCode(), cycle.getShortCloseOrderId(),
                cycle.getShortCloseClientOrderId());
        if (!queried.ok()) {
            return new RunResult(RunCode.WAITING, cycle.getId(), queried.reason());
        }
        HtxSwapOrder order = queried.order();
        BigDecimal requested = nz(cycle.getShortEntryFilledContracts());
        BigDecimal filled = nz(order.getTradeVolume());
        if (filled.compareTo(requested) >= 0 || "filled".equalsIgnoreCase(exchangeState(order))) {
            recorder.markShortCloseFill(cycle.getId(), HtxFundingArbitrageRecorder.SHORT_CLOSED,
                    filled, order.getTradeAveragePrice(), nz(order.getFee()), "short close filled; ready for spot exit");
            HtxFundingArbitrageCycleEntity updated = copyShortCloseFill(cycle, filled,
                    order.getTradeAveragePrice(), nz(order.getFee()));
            return sellSpotExit(updated);
        }
        if (swapTerminal(order)) {
            recorder.markShortCloseFill(cycle.getId(), HtxFundingArbitrageRecorder.UNKNOWN,
                    filled, order.getTradeAveragePrice(), nz(order.getFee()),
                    "short close incomplete state=" + exchangeState(order));
            return new RunResult(RunCode.MANUAL_REVIEW, cycle.getId(), "short close incomplete");
        }
        return new RunResult(RunCode.WAITING, cycle.getId(), "waiting short close fill");
    }

    private RunResult sellSpotExit(HtxFundingArbitrageCycleEntity cycle) {
        FundingMarket market = loadFundingMarket(cycle.getSymbol(), cycle.getContractCode());
        if (!market.available()) {
            return new RunResult(RunCode.WAITING, cycle.getId(), "spot exit waits for depth: " + market.reason());
        }
        BigDecimal sellable = nz(cycle.getSpotEntryFilledBase()).subtract(nz(cycle.getSpotEntryFeeBase()))
                .subtract(nz(cycle.getSpotExitFilledBase())).setScale(properties.getBaseScale(), RoundingMode.DOWN);
        if (sellable.signum() <= 0) {
            recorder.markSpotExitFill(cycle.getId(), HtxFundingArbitrageRecorder.DONE,
                    nz(cycle.getSpotExitFilledBase()), nz(cycle.getSpotExitFilledQuote()),
                    nz(cycle.getSpotExitFeeQuote()), "no remaining spot base; cycle done");
            return new RunResult(RunCode.DONE, cycle.getId(), "cycle done");
        }
        BigDecimal price = exitSpotPrice(market.spotBid());
        String clientOrderId = spotClientOrderId(cycle.getCycleKey(), "SX");
        HtxRestResponse<String> response = spot().placeOrder(tradeProperties.getApi().getSpotAccountId(),
                cycle.getSymbol(), "sell-limit", sellable.toPlainString(), price.toPlainString(), clientOrderId);
        Long orderId = parseSpotOrderId(response);
        if (orderId == null) {
            return new RunResult(RunCode.WAITING, cycle.getId(), "spot exit rejected: " + error(response));
        }
        recorder.markSpotExitAccepted(cycle.getId(), clientOrderId, orderId,
                "spot exit sent amount=" + sellable + " price=" + price);
        log.info("[HTX-FUNDING-ARB] spot exit sent cycleId={} orderId={} amount={} price={}",
                cycle.getId(), orderId, sellable, price);
        return new RunResult(RunCode.EXITING_SPOT_SENT, cycle.getId(), "spot exit sent");
    }

    private RunResult reconcileSpotExit(HtxFundingArbitrageCycleEntity cycle) {
        HtxRestResponse<HtxOrder> response = querySpotOrder(cycle.getSpotExitOrderId(),
                cycle.getSpotExitClientOrderId());
        if (response == null || !response.ok() || response.getData() == null) {
            return new RunResult(RunCode.WAITING, cycle.getId(), "spot exit query failed: " + error(response));
        }
        HtxOrder order = response.getData();
        BigDecimal filledBase = nz(order.getFilledAmount());
        BigDecimal targetBase = nz(cycle.getSpotEntryFilledBase()).subtract(nz(cycle.getSpotEntryFeeBase()));
        if (filledBase.compareTo(targetBase) >= 0 || "filled".equalsIgnoreCase(order.getState())) {
            recorder.markSpotExitFill(cycle.getId(), HtxFundingArbitrageRecorder.DONE, filledBase,
                    nz(order.getFilledCashAmount()), nz(order.getFilledFees()), "spot exit filled; cycle done");
            return new RunResult(RunCode.DONE, cycle.getId(), "cycle done");
        }
        if (spotTerminal(order)) {
            recorder.markSpotExitFill(cycle.getId(), HtxFundingArbitrageRecorder.UNKNOWN, filledBase,
                    nz(order.getFilledCashAmount()), nz(order.getFilledFees()),
                    "spot exit incomplete state=" + order.getState());
            return new RunResult(RunCode.MANUAL_REVIEW, cycle.getId(), "spot exit incomplete");
        }
        if (ageMs(cycle) >= properties.getSpotExitTtlMs()) {
            HtxRestResponse<String> cancel = spot().cancelOrder(cycle.getSpotExitOrderId());
            return new RunResult(RunCode.WAITING, cycle.getId(), "spot exit ttl cancel sent ok="
                    + (cancel != null && cancel.ok()));
        }
        return new RunResult(RunCode.WAITING, cycle.getId(), "waiting spot exit fill");
    }

    private FundingMarket loadFundingMarket(String symbol, String contractCode) {
        HtxRestResponse<HtxSwapFundingRate> fundingResponse = swap.fundingRate(contractCode);
        HtxSwapFundingRate funding = fundingResponse == null ? null : fundingResponse.payload();
        if (fundingResponse == null || !fundingResponse.ok() || funding == null || funding.getFundingRate() == null) {
            return FundingMarket.unavailable("funding unavailable: " + error(fundingResponse));
        }
        HtxRestResponse<HtxDepth> spotDepthResponse = marketData().depth(symbol, safeDepthTopN());
        HtxRestResponse<HtxDepth> swapDepthResponse = swap.depth(contractCode, contractProperties.getDepthStep());
        HtxDepth spotDepth = spotDepthResponse == null ? null : spotDepthResponse.payload();
        HtxDepth swapDepth = swapDepthResponse == null ? null : swapDepthResponse.payload();
        TopOfBook spotBook = topOfBook(spotDepth);
        TopOfBook swapBook = topOfBook(swapDepth);
        if (spotBook == null) {
            return FundingMarket.unavailable("spot depth unavailable: " + error(spotDepthResponse));
        }
        if (swapBook == null) {
            return FundingMarket.unavailable("swap depth unavailable: " + error(swapDepthResponse));
        }
        BigDecimal spotMid = mid(spotBook);
        BigDecimal swapMid = mid(swapBook);
        BigDecimal basis = swapMid.subtract(spotMid).divide(spotMid, 18, RoundingMode.HALF_UP);
        return new FundingMarket(true, null, funding.getFundingRate(), spotBook.bid(), spotBook.ask(),
                swapBook.bid(), swapBook.ask(), spotMid, swapMid, basis);
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

    private String balanceGate(String contractCode, BigDecimal spotCost) {
        BigDecimal spotQuote = spotQuoteAvailable();
        if (spotQuote == null) {
            return "spot quote balance unavailable";
        }
        BigDecimal requiredSpot = spotCost.add(properties.getMinSpotQuoteReserve());
        if (requiredSpot.compareTo(properties.getMinSpotQuoteAvailable()) < 0) {
            requiredSpot = properties.getMinSpotQuoteAvailable();
        }
        if (spotQuote.compareTo(requiredSpot) < 0) {
            return "spot quote balance insufficient available=" + spotQuote + " required=" + requiredSpot;
        }
        BigDecimal margin = contractMarginAvailable(contractCode);
        if (margin == null) {
            return "contract margin unavailable";
        }
        BigDecimal requiredMargin = properties.getTargetQuoteNotional()
                .divide(BigDecimal.valueOf(Math.max(1, properties.getLeverage())), 18, RoundingMode.UP)
                .multiply(contractProperties.getMarginSafetyRatio());
        if (requiredMargin.compareTo(properties.getMinContractMarginAvailable()) < 0) {
            requiredMargin = properties.getMinContractMarginAvailable();
        }
        if (margin.compareTo(requiredMargin) < 0) {
            return "contract margin insufficient available=" + margin + " required=" + requiredMargin;
        }
        return null;
    }

    private BigDecimal spotQuoteAvailable() {
        String accountId = tradeProperties.getApi().getSpotAccountId();
        HtxRestResponse<HtxAccount> response = spotApi.getAccountService().getAccountBalance(accountId);
        HtxAccount account = response == null ? null : response.payload();
        if (response == null || !response.ok() || account == null || account.getList() == null) {
            return null;
        }
        String quote = HtxSymbolUtil.quote(properties.getSymbol());
        return account.getList().stream()
                .filter(item -> item != null && "trade".equalsIgnoreCase(item.getType()))
                .filter(item -> quote.equalsIgnoreCase(item.getCurrency()))
                .map(HtxBalance::getBalance)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal contractMarginAvailable(String contractCode) {
        HtxRestResponse<List<HtxSwapAccount>> response = swap.accountInfo(contractCode);
        if (response == null || !response.ok() || response.payload() == null) {
            return null;
        }
        return response.payload().stream()
                .filter(item -> item != null && item.getMarginAvailable() != null)
                .map(HtxSwapAccount::getMarginAvailable)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String switchShortLeverage(String contractCode) {
        HtxRestResponse<?> response = swap.switchLeverage(contractCode, POSITION_SIDE_SHORT, properties.getLeverage());
        if (response == null || !response.ok() || response.getData() == null) {
            return "short leverage switch failed: " + error(response);
        }
        return null;
    }

    private String contractOpenOrdersGate(String contractCode) {
        HtxRestResponse<HtxSwapOpenOrders> response = swap.openOrders(contractCode);
        HtxSwapOpenOrders openOrders = response == null ? null : response.getData();
        if (response == null || !response.ok() || openOrders == null || openOrders.getOrders() == null) {
            return "contract open orders query failed: " + error(response);
        }
        if (!openOrders.getOrders().isEmpty()) {
            return "contract has open orders; wait before funding arbitrage entry count="
                    + openOrders.getOrders().size();
        }
        return null;
    }

    private String liquidationGate(String contractCode, BigDecimal referencePrice) {
        HtxRestResponse<List<HtxSwapPosition>> response = swap.positionInfo(contractCode);
        if (response == null || !response.ok() || response.payload() == null) {
            return "position query failed: " + error(response);
        }
        Optional<HtxSwapPosition> shortPosition = response.payload().stream()
                .filter(item -> item != null && POSITION_SIDE_SHORT.equalsIgnoreCase(item.getPositionSide()))
                .findFirst();
        if (shortPosition.isEmpty()) {
            return "short position missing";
        }
        BigDecimal liquidation = shortPosition.get().getLiquidationPrice();
        BigDecimal ref = firstPositive(shortPosition.get().getMarkPrice(), shortPosition.get().getLastPrice(), referencePrice);
        if (liquidation == null || liquidation.signum() <= 0 || ref == null || ref.signum() <= 0) {
            return "short liquidation/reference price unavailable";
        }
        BigDecimal distance = liquidation.subtract(ref).divide(ref, 18, RoundingMode.HALF_UP);
        if (distance.compareTo(properties.getMinLiquidationDistanceRate()) < 0) {
            return "short liquidation distance too small: " + distance;
        }
        return null;
    }

    private Optional<BigDecimal> currentFunding(String contractCode) {
        HtxRestResponse<HtxSwapFundingRate> response = swap.fundingRate(contractCode);
        HtxSwapFundingRate funding = response == null ? null : response.payload();
        if (response == null || !response.ok() || funding == null || funding.getFundingRate() == null) {
            return Optional.empty();
        }
        return Optional.of(funding.getFundingRate());
    }

    private HtxRestResponse<HtxOrder> querySpotOrder(Long orderId, String clientOrderId) {
        if (orderId != null) {
            return spot().queryOrder(orderId);
        }
        return spot().queryOrderByClientId(clientOrderId);
    }

    private QuerySwapOrderResult querySwapOrder(String contractCode, Long orderId, String clientOrderId) {
        HtxRestResponse<List<HtxSwapOrder>> response = orderId != null
                ? swap.queryOrder(contractCode, orderId) : swap.queryOrderByClientOrderId(contractCode, clientOrderId);
        if (response == null || !response.ok() || response.getData() == null || response.getData().isEmpty()) {
            return QuerySwapOrderResult.failed("swap order query failed: " + error(response));
        }
        return QuerySwapOrderResult.found(response.getData().get(0));
    }

    private String liveGate(String symbol, String contractCode) {
        if (isBlank(symbol) || isBlank(contractCode)) {
            return "symbol or contract code is blank";
        }
        if (!properties.isEnabled()) {
            return "funding arbitrage disabled";
        }
        if (!tradeProperties.getTrade().isEnabled()) {
            return "spot live trading disabled";
        }
        if (!contractProperties.isEnabled() || contractProperties.isDryRun() || !contractProperties.isLiveEnabled()) {
            return "contract live trading gates are closed";
        }
        if (isBlank(tradeProperties.getApi().getSpotAccountId())) {
            return "spot account id is blank";
        }
        if (properties.getTargetQuoteNotional() == null || properties.getTargetQuoteNotional().signum() <= 0) {
            return "target notional must be positive";
        }
        if (properties.getLeverage() <= 0 || properties.getLeverage() > contractProperties.getMaxLeverage()) {
            return "funding arbitrage leverage is outside configured contract max leverage";
        }
        return null;
    }

    private long targetContracts(BigDecimal spotAsk, BigDecimal contractSize) {
        if (spotAsk == null || spotAsk.signum() <= 0 || contractSize == null || contractSize.signum() <= 0) {
            return 0L;
        }
        BigDecimal perContractNotional = spotAsk.multiply(contractSize);
        return properties.getTargetQuoteNotional().divide(perContractNotional, 0, RoundingMode.DOWN).longValue();
    }

    private BigDecimal entrySpotPrice(BigDecimal ask) {
        return ask.multiply(BigDecimal.ONE.add(properties.getSpotEntrySlippageRate()))
                .setScale(properties.getPriceScale(), RoundingMode.UP);
    }

    private BigDecimal exitSpotPrice(BigDecimal bid) {
        return bid.multiply(BigDecimal.ONE.subtract(properties.getSpotExitSlippageRate()))
                .setScale(properties.getPriceScale(), RoundingMode.DOWN);
    }

    private int safeDepthTopN() {
        int depth = tradeProperties.getDepthGuard().getTopN();
        if (depth <= 1) {
            return 1;
        }
        if (depth <= 5) {
            return 5;
        }
        if (depth <= 10) {
            return 10;
        }
        return 20;
    }

    private long numericClientOrderId(Long cycleId, int leg) {
        long idPart = cycleId == null ? 0L : Math.min(Math.max(cycleId, 0L), 999_999L);
        long random = ThreadLocalRandom.current().nextLong(100L, 999L);
        long timePart = System.currentTimeMillis() % 10_000_000_000L;
        return timePart * 1_000_000L + idPart * 10L + leg + random;
    }

    private String cycleKey(String symbol) {
        return properties.getClientOrderIdPrefix() + "-" + symbol.toUpperCase(Locale.ROOT)
                + "-" + System.currentTimeMillis();
    }

    private String spotClientOrderId(String cycleKey, String suffix) {
        String value = cycleKey + "-" + suffix;
        return value.length() <= 64 ? value : value.substring(0, 64);
    }

    private TopOfBook topOfBook(HtxDepth depth) {
        if (depth == null || depth.getBids() == null || depth.getAsks() == null
                || depth.getBids().isEmpty() || depth.getAsks().isEmpty()) {
            return null;
        }
        HtxPriceLevel bid = depth.getBids().get(0);
        HtxPriceLevel ask = depth.getAsks().get(0);
        if (bid == null || ask == null || bid.getPrice() == null || ask.getPrice() == null
                || bid.getPrice().signum() <= 0 || ask.getPrice().signum() <= 0) {
            return null;
        }
        return new TopOfBook(bid.getPrice(), ask.getPrice());
    }

    private BigDecimal mid(TopOfBook book) {
        return book.bid().add(book.ask()).divide(TWO, 18, RoundingMode.HALF_UP);
    }

    private HtxFundingArbitrageCycleEntity copySpotFill(HtxFundingArbitrageCycleEntity cycle,
                                                         BigDecimal filledBase,
                                                         BigDecimal filledQuote,
                                                         BigDecimal feeBase,
                                                         BigDecimal funding) {
        cycle.setSpotEntryFilledBase(filledBase);
        cycle.setSpotEntryFilledQuote(filledQuote);
        cycle.setSpotEntryFeeBase(feeBase);
        cycle.setLatestFundingRate(funding);
        cycle.setStatus(HtxFundingArbitrageRecorder.SPOT_FILLED);
        return cycle;
    }

    private HtxFundingArbitrageCycleEntity copyShortCloseFill(HtxFundingArbitrageCycleEntity cycle,
                                                              BigDecimal filledContracts,
                                                              BigDecimal avgPrice,
                                                              BigDecimal fee) {
        cycle.setShortCloseFilledContracts(filledContracts);
        cycle.setShortCloseAvgPrice(avgPrice);
        cycle.setShortCloseFee(fee);
        cycle.setStatus(HtxFundingArbitrageRecorder.SHORT_CLOSED);
        return cycle;
    }

    private HtxSpotService spot() {
        return spotApi.getSpotService();
    }

    private HtxMarketDataService marketData() {
        return spotApi.getMarketDataService();
    }

    private static Long parseSpotOrderId(HtxRestResponse<String> response) {
        if (response == null || !response.ok() || isBlank(response.getData())) {
            return null;
        }
        try {
            return Long.parseLong(response.getData().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean spotTerminal(HtxOrder order) {
        if (order == null || order.getState() == null) {
            return false;
        }
        String state = order.getState();
        return "filled".equalsIgnoreCase(state)
                || "partial-canceled".equalsIgnoreCase(state)
                || "canceled".equalsIgnoreCase(state)
                || "rejected".equalsIgnoreCase(state);
    }

    private static boolean swapTerminal(HtxSwapOrder order) {
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

    private static long ageMs(HtxFundingArbitrageCycleEntity cycle) {
        LocalDateTime updatedAt = cycle.getUpdatedAt();
        if (updatedAt == null) {
            return Long.MAX_VALUE;
        }
        return Duration.between(updatedAt, LocalDateTime.now()).toMillis();
    }

    private static BigDecimal firstPositive(BigDecimal... values) {
        for (BigDecimal value : values) {
            if (value != null && value.signum() > 0) {
                return value;
            }
        }
        return null;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }

    private static BigDecimal abs(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.abs();
    }

    private static String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeContract(String contractCode) {
        return contractCode == null ? "" : contractCode.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String error(HtxRestResponse<?> response) {
        if (response == null) {
            return "response missing";
        }
        if (!isBlank(response.getMessage())) {
            return response.getMessage();
        }
        if (!isBlank(response.getErrMsg())) {
            return response.getErrMsg();
        }
        if (!isBlank(response.getErrCode())) {
            return response.getErrCode();
        }
        return "code=" + response.getCode() + " status=" + response.getStatus();
    }

    private static String swapError(HtxRestResponse<?> response, HtxSwapOrderResult item) {
        if (item != null && !isBlank(item.getMessage())) {
            return item.getMessage();
        }
        return error(response);
    }

    private record TopOfBook(BigDecimal bid, BigDecimal ask) {
    }

    private record FundingMarket(boolean available, String reason, BigDecimal fundingRate,
                                 BigDecimal spotBid, BigDecimal spotAsk, BigDecimal swapBid,
                                 BigDecimal swapAsk, BigDecimal spotMid, BigDecimal swapMid,
                                 BigDecimal basisRate) {
        static FundingMarket unavailable(String reason) {
            return new FundingMarket(false, reason, null, null, null, null, null, null, null, null);
        }
    }

    private record QuerySwapOrderResult(boolean ok, HtxSwapOrder order, String reason) {
        static QuerySwapOrderResult found(HtxSwapOrder order) {
            return new QuerySwapOrderResult(true, order, null);
        }

        static QuerySwapOrderResult failed(String reason) {
            return new QuerySwapOrderResult(false, null, reason);
        }
    }

    public record RunResult(RunCode code, Long cycleId, String message) {
    }

    public enum RunCode {
        LIVE_GATE_CLOSED,
        SKIPPED,
        SPOT_ENTRY_SENT,
        SHORT_ENTRY_SENT,
        SHORT_DEGRADED,
        ACTIVE,
        EXITING_SHORT_SENT,
        EXITING_SPOT_SENT,
        DONE,
        REJECTED,
        WAITING,
        MANUAL_REVIEW
    }
}
