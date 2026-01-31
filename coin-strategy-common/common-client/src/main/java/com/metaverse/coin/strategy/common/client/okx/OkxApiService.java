package com.metaverse.coin.strategy.common.client.okx;

import com.metaverse.coin.strategy.common.client.okx.service.*;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.account.*;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.algo.*;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.grid.*;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.order.*;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.order.booking.*;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.signal.*;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.recurring.*;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.market.*;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.rubik.*;
import com.metaverse.coin.strategy.common.model.okx.enums.ws.TdMode;
import okhttp3.OkHttpClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public class OkxApiService {

    private final OkxApi okxApi;
    private final OkHttpClient client;
    private final boolean simulated;

    public final OkxAccountService account;
    public final OkxTradeService trade;
    public final OkxAlgoTradingService algoTrading;
    public final OkxGridTradingService gridTrading;
    public final OkxSignalBotService signalBot;
    public final OkxRecurringBuyService recurringBuy;
    public final OkxMarketDataService marketData;
    public final OkxPublicDataService publicData;
    public final OkxFundingService funding;
    public final OkxRubikService rubik;

    public OkxApiService(OkxApi okxApi, OkHttpClient client, boolean simulated) {
        this.okxApi = okxApi;
        this.client = client;
        this.simulated = simulated;

        this.account = new OkxAccountService(okxApi);
        this.trade = new OkxTradeService(okxApi);
        this.algoTrading = new OkxAlgoTradingService(okxApi);
        this.gridTrading = new OkxGridTradingService(okxApi);
        this.signalBot = new OkxSignalBotService(okxApi);
        this.recurringBuy = new OkxRecurringBuyService(okxApi);
        this.marketData = new OkxMarketDataService(okxApi);
        this.publicData = new OkxPublicDataService(okxApi);
        this.funding = new OkxFundingService(okxApi);
        this.rubik = new OkxRubikService(okxApi);
    }

    // --- Account ---

    public OkxRestResponse<Balance> getBalance(String ccy) {
        return account.getBalance(ccy);
    }

    public OkxRestResponse<Object> setLeverage(String instId, String mgnMode, String lever) {
        return account.setLeverage(instId, mgnMode, lever);
    }

    public OkxRestResponse<Map<String, Object>> getPositions(String instType, String instId) {
        return account.getPositions(instType, instId);
    }

    public OkxRestResponse<OkxAccountConfig> getAccountConfig() {
        return account.getAccountConfig();
    }

    public OkxRestResponse<OkxMaxSize> getMaxSize(Map<String, Object> queryMap) {
        return account.getMaxSize(queryMap);
    }

    public OkxRestResponse<OkxPositionRisk> getPositionRisk(Map<String, Object> queryMap) {
        return account.getPositionRisk(queryMap);
    }

    public OkxRestResponse<OkxBill> getBills(Map<String, Object> queryMap) {
        return account.getBills(queryMap);
    }

    public OkxRestResponse<OkxBill> getBillsArchive(Map<String, Object> queryMap) {
        return account.getBillsArchive(queryMap);
    }

    public OkxRestResponse<OkxMaxAvailSize> getMaxAvailSize(Map<String, Object> queryMap) {
        return account.getMaxAvailSize(queryMap);
    }

    public OkxRestResponse<OkxTradeFee> getTradeFee(String instType, String instId, String uly, String category) {
        return account.getTradeFee(instType, instId, uly, category);
    }

    public OkxRestResponse<OkxInterestAccrued> getInterestAccrued(Map<String, Object> queryMap) {
        return account.getInterestAccrued(queryMap);
    }

    public OkxRestResponse<OkxInterestRate> getInterestRate(Map<String, Object> queryMap) {
        return account.getInterestRate(queryMap);
    }

    public OkxRestResponse<OkxMaxLoan> getMaxLoan(Map<String, Object> queryMap) {
        return account.getMaxLoan(queryMap);
    }

    public OkxRestResponse<OkxPositionMode> setPositionMode(String posMode) {
        return account.setPositionMode(posMode);
    }

    public OkxRestResponse<OkxLeverageInfo> getLeverageInfo(String instId, String mgnMode) {
        return account.getLeverageInfo(instId, mgnMode);
    }

    // --- Trade ---

    public OkxRestResponse<PlaceOrderRsp> openLong(String instId, TdMode tdMode, BigDecimal size, BigDecimal price) {
        return trade.openLong(instId, tdMode, size, price);
    }

    public OkxRestResponse<PlaceOrderRsp> openShort(String instId, TdMode tdMode, BigDecimal size, BigDecimal price) {
        return trade.openShort(instId, tdMode, size, price);
    }

    public OkxRestResponse<PlaceOrderRsp> closeLong(String instId, TdMode tdMode, BigDecimal size, BigDecimal price) {
        return trade.closeLong(instId, tdMode, size, price);
    }

    public OkxRestResponse<PlaceOrderRsp> closeShort(String instId, TdMode tdMode, BigDecimal size, BigDecimal price) {
        return trade.closeShort(instId, tdMode, size, price);
    }

    public OkxRestResponse<OkxClosePositionRsp> closePosition(String instId, String mgnMode, String posSide) {
        return trade.closePosition(instId, mgnMode, posSide);
    }

    public OkxRestResponse<CancelOrderRsp> cancelOrder(CancelOrderReq req) {
        return trade.cancelOrder(req);
    }

    public OkxRestResponse<OkxOrder> getOrder(String instId, String ordId, String clOrdId) {
        return trade.getOrder(instId, ordId, clOrdId);
    }

    public OkxRestResponse<OkxOrder> getOrdersHistory(Map<String, Object> queryMap) {
        return trade.getOrdersHistory(queryMap);
    }

    public OkxRestResponse<OkxOrder> getOrdersPending(Map<String, Object> queryMap) {
        return trade.getOrdersPending(queryMap);
    }

    // --- Algo Trading ---

    public OkxRestResponse<PlaceAlgoOrderRsp> placeAlgoOrder(Map<String, Object> req) {
        return algoTrading.placeAlgoOrder(req);
    }

    public OkxRestResponse<CancelAlgoOrderRsp> cancelAlgoOrder(List<Map<String, Object>> req) {
        return algoTrading.cancelAlgoOrder(req);
    }

    public OkxRestResponse<OkxAlgoOrder> getAlgoOrdersHistory(Map<String, Object> queryMap) {
        return algoTrading.getAlgoOrdersHistory(queryMap);
    }

    // --- Grid Trading ---

    public OkxRestResponse<PlaceGridAlgoOrderRsp> placeGridAlgoOrder(PlaceGridAlgoOrderReq req) {
        return gridTrading.placeGridAlgoOrder(req);
    }

    public OkxRestResponse<AmendGridAlgoOrderRsp> amendGridAlgoOrder(AmendGridAlgoOrderReq req) {
        return gridTrading.amendGridAlgoOrder(req);
    }

    public OkxRestResponse<AddGridInvestmentRsp> addGridInvestment(AddGridInvestmentReq req) {
        return gridTrading.addGridInvestment(req);
    }

    public OkxRestResponse<StopGridAlgoOrderRsp> stopGridAlgoOrder(StopGridAlgoOrderReq req) {
        return gridTrading.stopGridAlgoOrder(req);
    }

    public OkxRestResponse<ClosePositionForContractGridRsp> closePositionForContractGrid(ClosePositionForContractGridReq req) {
        return gridTrading.closePositionForContractGrid(req);
    }

    public OkxRestResponse<CancelClosePositionOrderForContractGridRsp> cancelClosePositionOrderForContractGrid(CancelClosePositionOrderForContractGridReq req) {
        return gridTrading.cancelClosePositionOrderForContractGrid(req);
    }

    public OkxRestResponse<InstantTriggerGridAlgoOrderRsp> instantTriggerGridAlgoOrder(InstantTriggerGridAlgoOrderReq req) {
        return gridTrading.instantTriggerGridAlgoOrder(req);
    }

    public OkxRestResponse<GridAlgoOrder> getGridAlgoOrdersHistory(Map<String, Object> queryMap) {
        return gridTrading.getGridAlgoOrdersHistory(queryMap);
    }

    public OkxRestResponse<GridAlgoOrder> getGridAlgoOrdersPending(Map<String, Object> queryMap) {
        return gridTrading.getGridAlgoOrdersPending(queryMap);
    }

    public OkxRestResponse<GridAlgoOrder> getGridAlgoOrderDetails(String algoOrdId, String algoClOrdId) {
        return gridTrading.getGridAlgoOrderDetails(algoOrdId, algoClOrdId);
    }

    public OkxRestResponse<GridAlgoSubOrder> getGridAlgoSubOrders(Map<String, Object> queryMap) {
        return gridTrading.getGridAlgoSubOrders(queryMap);
    }

    public OkxRestResponse<GridAlgoOrderPosition> getGridAlgoPositions(Map<String, Object> queryMap) {
        return gridTrading.getGridAlgoPositions(queryMap);
    }

    public OkxRestResponse<SpotGridWithdrawIncomeRsp> spotGridWithdrawIncome(SpotGridWithdrawIncomeReq req) {
        return gridTrading.spotGridWithdrawIncome(req);
    }

    public OkxRestResponse<ComputeGridMinInvestmentRsp> computeGridMinInvestment(Map<String, Object> queryMap) {
        return gridTrading.computeGridMinInvestment(queryMap);
    }

    public OkxRestResponse<ComputeGridMarginBalanceRsp> computeGridMarginBalance(Map<String, Object> queryMap) {
        return gridTrading.computeGridMarginBalance(queryMap);
    }

    public OkxRestResponse<AdjustGridMarginBalanceRsp> adjustGridMarginBalance(AdjustGridMarginBalanceReq req) {
        return gridTrading.adjustGridMarginBalance(req);
    }

    public OkxRestResponse<GridApiParam> getGridAiParam(Map<String, Object> queryMap) {
        return gridTrading.getGridAiParam(queryMap);
    }

    public OkxRestResponse<ComputeGridMarginBalanceRsp> computeGridMarginBalancePost(ComputeGridMarginBalanceReq req) {
        return gridTrading.computeGridMarginBalancePost(req);
    }

    public OkxRestResponse<AmendGridAlgoBasicParamRsp> amendGridAlgoBasicParam(AmendGridAlgoBasicParamReq req) {
        return gridTrading.amendGridAlgoBasicParam(req);
    }

    public OkxRestResponse<ComputeGridMinInvestmentRsp> computeGridMinInvestmentPost(ComputeGridMinInvestmentReq req) {
        return gridTrading.computeGridMinInvestmentPost(req);
    }

    public OkxRestResponse<RsiBackTestingRsp> rsiBackTesting(Map<String, Object> queryMap) {
        return gridTrading.rsiBackTesting(queryMap);
    }

    public OkxRestResponse<GridQuantityRsp> getGridQuantity(Map<String, Object> queryMap) {
        return gridTrading.getGridQuantity(queryMap);
    }

    // --- Signal Bot ---

    public OkxRestResponse<PlaceSignalAlgoOrderRsp> placeSignalAlgoOrder(PlaceSignalAlgoOrderReq req) {
        return signalBot.placeSignalAlgoOrder(req);
    }

    public OkxRestResponse<PlaceSignalAlgoSubOrderRsp> placeSignalAlgoSubOrder(PlaceSignalAlgoSubOrderReq req) {
        return signalBot.placeSignalAlgoSubOrder(req);
    }

    public OkxRestResponse<CancelSignalAlgoOrderRsp> cancelSignalAlgoOrder(CancelSignalAlgoOrderReq req) {
        return signalBot.cancelSignalAlgoOrder(req);
    }

    public OkxRestResponse<SignalAlgoOrder> getSignalAlgoOrdersPending(Map<String, Object> queryMap) {
        return signalBot.getSignalAlgoOrdersPending(queryMap);
    }

    public OkxRestResponse<SignalAlgoOrder> getSignalAlgoOrdersHistory(Map<String, Object> queryMap) {
        return signalBot.getSignalAlgoOrdersHistory(queryMap);
    }

    public OkxRestResponse<SignalAlgoOrder> getSignalAlgoOrderDetails(Map<String, Object> queryMap) {
        return signalBot.getSignalAlgoOrderDetails(queryMap);
    }

    public OkxRestResponse<SignalAlgoSubOrder> getSignalAlgoSubOrders(Map<String, Object> queryMap) {
        return signalBot.getSignalAlgoSubOrders(queryMap);
    }

    public OkxRestResponse<SignalAlgoPosition> getSignalAlgoPositions(Map<String, Object> queryMap) {
        return signalBot.getSignalAlgoPositions(queryMap);
    }

    public OkxRestResponse<CloseSignalPositionRsp> closeSignalPosition(CloseSignalPositionReq req) {
        return signalBot.closeSignalPosition(req);
    }

    public OkxRestResponse<ChangeSignalMarginBalanceRsp> changeSignalMarginBalance(ChangeSignalMarginBalanceReq req) {
        return signalBot.changeSignalMarginBalance(req);
    }

    // --- Recurring Buy ---

    public OkxRestResponse<PlaceRecurringBuyAlgoOrderRsp> placeRecurringBuyAlgoOrder(PlaceRecurringBuyAlgoOrderReq req) {
        return recurringBuy.placeRecurringBuyAlgoOrder(req);
    }

    public OkxRestResponse<CancelRecurringBuyAlgoOrderRsp> stopRecurringBuyAlgoOrder(CancelRecurringBuyAlgoOrderReq req) {
        return recurringBuy.stopRecurringBuyAlgoOrder(req);
    }

    public OkxRestResponse<AmendRecurringBuyAlgoOrderRsp> amendRecurringBuyAlgoOrder(AmendRecurringBuyAlgoOrderReq req) {
        return recurringBuy.amendRecurringBuyAlgoOrder(req);
    }

    public OkxRestResponse<RecurringAlgoOrder> getRecurringBuyAlgoOrdersPending(Map<String, Object> queryMap) {
        return recurringBuy.getRecurringBuyAlgoOrdersPending(queryMap);
    }

    public OkxRestResponse<RecurringAlgoOrder> getRecurringBuyAlgoOrdersHistory(Map<String, Object> queryMap) {
        return recurringBuy.getRecurringBuyAlgoOrdersHistory(queryMap);
    }

    public OkxRestResponse<RecurringAlgoOrder> getRecurringBuyAlgoOrderDetails(Map<String, Object> queryMap) {
        return recurringBuy.getRecurringBuyAlgoOrderDetails(queryMap);
    }

    public OkxRestResponse<RecurringAlgoSubOrder> getRecurringBuyAlgoSubOrders(Map<String, Object> queryMap) {
        return recurringBuy.getRecurringBuyAlgoSubOrders(queryMap);
    }

    // --- Market Data ---

    public OkxRestResponse<OkxTicker> getTickers(String instType, String uly) {
        return marketData.getTickers(instType, uly);
    }

    public OkxRestResponse<OkxTicker> getTicker(String instId) {
        return marketData.getTicker(instId);
    }

    public OkxRestResponse<OkxIndexTicker> getIndexTickers(String quoteCcy, String instId) {
        return marketData.getIndexTickers(quoteCcy, instId);
    }

    public OkxRestResponse<OkxOrderBook> getOrderBook(String instId, Integer sz) {
        return marketData.getOrderBook(instId, sz);
    }

    public OkxRestResponse<OkxCandle> getCandles(String instId, String bar, String after, String before, Integer limit) {
        return marketData.getCandles(instId, bar, after, before, limit);
    }

    public OkxRestResponse<OkxCandle> getHistoryCandles(String instId, String bar, String after, String before, Integer limit) {
        return marketData.getHistoryCandles(instId, bar, after, before, limit);
    }

    public OkxRestResponse<OkxCandle> getIndexCandles(String instId, String bar, String after, String before, Integer limit) {
        return marketData.getIndexCandles(instId, bar, after, before, limit);
    }

    public OkxRestResponse<OkxCandle> getMarkPriceCandles(String instId, String bar, String after, String before, Integer limit) {
        return marketData.getMarkPriceCandles(instId, bar, after, before, limit);
    }

    public OkxRestResponse<OkxTrade> getTrades(String instId, Integer limit) {
        return marketData.getTrades(instId, limit);
    }

    public OkxRestResponse<OkxTrade> getHistoryTrades(String instId, String type, String after, String before, Integer limit) {
        return marketData.getHistoryTrades(instId, type, after, before, limit);
    }

    public OkxRestResponse<OkxPlatformVolume> getPlatform24Volume() {
        return marketData.getPlatform24Volume();
    }

    public OkxRestResponse<OkxOracle> getOracle() {
        return marketData.getOracle();
    }

    public OkxRestResponse<OkxExchangeRate> getExchangeRate() {
        return marketData.getExchangeRate();
    }

    public OkxRestResponse<OkxIndexComponent> getIndexComponents(String index) {
        return marketData.getIndexComponents(index);
    }

    public OkxRestResponse<OkxTicker> getBlockTickers(String instType, String uly) {
        return marketData.getBlockTickers(instType, uly);
    }

    public OkxRestResponse<OkxTicker> getBlockTicker(String instId) {
        return marketData.getBlockTicker(instId);
    }

    public OkxRestResponse<OkxTrade> getBlockTrades(String instId) {
        return marketData.getBlockTrades(instId);
    }

    // --- Public Data ---

    public OkxRestResponse<Map<String, Object>> getInstruments(String instType, String uly, String instId) {
        return publicData.getInstruments(instType, uly, instId);
    }

    public OkxRestResponse<Map<String, Object>> getDeliveryExerciseHistory(String instType, String uly, String after, String before, Integer limit) {
        return publicData.getDeliveryExerciseHistory(instType, uly, after, before, limit);
    }

    public OkxRestResponse<Map<String, Object>> getOpenInterest(String instType, String uly, String instId) {
        return publicData.getOpenInterest(instType, uly, instId);
    }

    public OkxRestResponse<Map<String, Object>> getFundingRate(String instId) {
        return publicData.getFundingRate(instId);
    }

    public OkxRestResponse<Map<String, Object>> getFundingRateHistory(String instId, String after, String before, Integer limit) {
        return publicData.getFundingRateHistory(instId, after, before, limit);
    }

    public OkxRestResponse<Map<String, Object>> getPriceLimit(String instId) {
        return publicData.getPriceLimit(instId);
    }

    public OkxRestResponse<Map<String, Object>> getOptSummary(String uly, String expTime) {
        return publicData.getOptSummary(uly, expTime);
    }

    public OkxRestResponse<Map<String, Object>> getEstimatedPrice(String instId) {
        return publicData.getEstimatedPrice(instId);
    }

    public OkxRestResponse<Map<String, Object>> getDiscountRateInterestFreeQuota(String ccy) {
        return publicData.getDiscountRateInterestFreeQuota(ccy);
    }

    public OkxRestResponse<Map<String, Object>> getSystemTime() {
        return publicData.getSystemTime();
    }

    public OkxRestResponse<Map<String, Object>> getLiquidationOrders(String instType, String mgnMode, String instId, String ccy, String uly, String alias, String state, String before, String after, Integer limit) {
        return publicData.getLiquidationOrders(instType, mgnMode, instId, ccy, uly, alias, state, before, after, limit);
    }

    public OkxRestResponse<Map<String, Object>> getMarkPrice(String instType, String uly, String instId) {
        return publicData.getMarkPrice(instType, uly, instId);
    }

    public OkxRestResponse<Map<String, Object>> getPositionTiers(String instType, String uly, String instId, String tdMode, String tier) {
        return publicData.getPositionTiers(instType, uly, instId, tdMode, tier);
    }

    public OkxRestResponse<Map<String, Object>> getInterestRateLoanQuota() {
        return publicData.getInterestRateLoanQuota();
    }

    public OkxRestResponse<Map<String, Object>> getUnderlying(String instType) {
        return publicData.getUnderlying(instType);
    }

    public OkxRestResponse<Map<String, Object>> getInsuranceFund(String instType, String type, String uly, String ccy, String before, String after, Integer limit) {
        return publicData.getInsuranceFund(instType, type, uly, ccy, before, after, limit);
    }

    public OkxRestResponse<Map<String, Object>> convertContractCoin(String type, String instId, String sz, String px, String unit) {
        return publicData.convertContractCoin(type, instId, sz, px, unit);
    }

    // --- Funding Account ---

    public OkxRestResponse<Map<String, Object>> getCurrencies(String ccy) {
        return funding.getCurrencies(ccy);
    }

    public OkxRestResponse<Map<String, Object>> getAssetBalances(String ccy) {
        return funding.getAssetBalances(ccy);
    }

    public OkxRestResponse<Map<String, Object>> assetTransfer(Map<String, Object> req) {
        return funding.assetTransfer(req);
    }

    public OkxRestResponse<Map<String, Object>> getAssetBills(String ccy, String type, String after, String before, Integer limit) {
        return funding.getAssetBills(ccy, type, after, before, limit);
    }

    public OkxRestResponse<Map<String, Object>> getDepositLightning(String ccy, String amt, String to) {
        return funding.getDepositLightning(ccy, amt, to);
    }

    public OkxRestResponse<Map<String, Object>> getDepositAddress(String ccy) {
        return funding.getDepositAddress(ccy);
    }

    public OkxRestResponse<Map<String, Object>> getDepositHistory(String ccy, String depId, String txId, String type, String state, String after, String before, Integer limit) {
        return funding.getDepositHistory(ccy, depId, txId, type, state, after, before, limit);
    }

    public OkxRestResponse<Map<String, Object>> withdrawal(Map<String, Object> req) {
        return funding.withdrawal(req);
    }

    public OkxRestResponse<Map<String, Object>> getWithdrawalHistory(String ccy, String wdId, String clientId, String txId, String type, String state, String after, String before, Integer limit) {
        return funding.getWithdrawalHistory(ccy, wdId, clientId, txId, type, state, after, before, limit);
    }

    public OkxRestResponse<Map<String, Object>> cancelWithdrawal(Map<String, Object> req) {
        return funding.cancelWithdrawal(req);
    }

    public OkxRestResponse<Map<String, Object>> convertDustAssets(Map<String, Object> req) {
        return funding.convertDustAssets(req);
    }

    // --- Rubik (Trading Statistics) ---

    public OkxRestResponse<String> getSupportCoin() {
        return rubik.getSupportCoin();
    }

    public OkxRestResponse<OkxTakerVolume> getTakerVolume(String ccy, String instType, String begin, String end, String period) {
        return rubik.getTakerVolume(ccy, instType, begin, end, period);
    }

    public OkxRestResponse<OkxMarginLendingRatio> getMarginLendingRatio(String ccy, String begin, String end, String period) {
        return rubik.getMarginLendingRatio(ccy, begin, end, period);
    }

    public OkxRestResponse<OkxLongShortRatio> getLongShortAccountRatio(String instId, String begin, String end, String period) {
        return rubik.getLongShortAccountRatio(instId, begin, end, period);
    }

    public OkxRestResponse<OkxOpenInterestVolume> getOpenInterestVolume(String ccy, String instType, String begin, String end, String period) {
        return rubik.getOpenInterestVolume(ccy, instType, begin, end, period);
    }

    public OkxRestResponse<OkxOpenInterestVolume> getOptionOpenInterestVolume(String ccy, String period) {
        return rubik.getOptionOpenInterestVolume(ccy, period);
    }

    public OkxRestResponse<OkxPutCallRatio> getOptionOpenInterestVolumeRatio(String ccy, String period) {
        return rubik.getOptionOpenInterestVolumeRatio(ccy, period);
    }

    public OkxRestResponse<OkxOpenInterestVolumeExpiry> getOptionOpenInterestVolumeExpiry(String ccy, String period) {
        return rubik.getOptionOpenInterestVolumeExpiry(ccy, period);
    }

    public OkxRestResponse<OkxOpenInterestVolumeStrike> getOptionOpenInterestVolumeStrike(String ccy, String expTime, String period) {
        return rubik.getOptionOpenInterestVolumeStrike(ccy, expTime, period);
    }

    public OkxRestResponse<OkxOpenInterestHistory> getOpenInterestHistory(String instId, String period, String begin, String end, String limit) {
        return rubik.getOpenInterestHistory(instId, period, begin, end, limit);
    }

    public OkxRestResponse<OkxTakerVolume> getTakerVolumeContract(String instId, String period, String begin, String end, String limit) {
        return rubik.getTakerVolumeContract(instId, period, begin, end, limit);
    }

    public OkxRestResponse<OkxLongShortRatio> getLongShortPositionRatioContractTopTrader(String instId, String period, String begin, String end, String limit) {
        return rubik.getLongShortPositionRatioContractTopTrader(instId, period, begin, end, limit);
    }

    public OkxRestResponse<OkxLongShortRatio> getLongShortAccountRatioContract(String instId, String period, String begin, String end, String limit) {
        return rubik.getLongShortAccountRatioContract(instId, period, begin, end, limit);
    }

    public OkxRestResponse<OkxTakerVolume> getOptionTakerBlockVolume(String ccy, String period, String begin, String end) {
        return rubik.getOptionTakerBlockVolume(ccy, period, begin, end);
    }

    public OkxRestResponse<OkxTakerFlow> getTakerFlow(String ccy, String period) {
        return rubik.getTakerFlow(ccy, period);
    }
}
