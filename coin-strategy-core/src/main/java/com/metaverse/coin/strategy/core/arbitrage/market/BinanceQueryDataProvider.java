package com.metaverse.coin.strategy.core.arbitrage.market;

import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesAccount;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesIncome;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesOrder;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesOrderBook;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesPositionRisk;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesPremiumIndex;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceOrderBook;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinancePriceTicker;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.BinanceOrder;

import java.util.List;

public interface BinanceQueryDataProvider {

    /** Binance futures income history 的资金费入账类型（GET /fapi/v1/income 的 incomeType 取值）。 */
    String INCOME_TYPE_FUNDING_FEE = "FUNDING_FEE";

    BinancePriceTicker getSpotPrice(String symbol);

    BinancePriceTicker getFuturesPrice(String symbol);

    BinanceFuturesPremiumIndex getPremiumIndex(String symbol);

    List<BinanceFuturesPremiumIndex> getPremiumIndices();

    BinanceOrderBook getSpotOrderBook(String symbol, Integer limit);

    BinanceFuturesOrderBook getFuturesOrderBook(String symbol, Integer limit);

    BinanceOrder getSpotOrder(String symbol, Long orderId, String origClientOrderId);

    BinanceFuturesOrder getFuturesOrder(String symbol, Long orderId, String origClientOrderId);

    BinanceFuturesAccount getFuturesAccount();

    List<BinanceFuturesPositionRisk> getPositionRisk(String symbol);

    /**
     * 查询指定合约在 [startTime, endTime] 区间的资金费实际入账（incomeType=FUNDING_FEE）。
     * 用于结算对账以交易所实际入账替代 premiumIndex 估算。正数为收取，负数为支付。
     *
     * @param symbol    合约交易对，null 表示查全部
     * @param startTime 起始毫秒时间戳，可为 null
     * @param endTime   结束毫秒时间戳，可为 null
     * @return 该区间的资金费入账明细；查询失败或无数据由实现约定（可能为空列表）
     */
    List<BinanceFuturesIncome> getFundingFeeIncome(String symbol, Long startTime, Long endTime);
}
