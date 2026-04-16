package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 套利策略仓位表
 */
@Data
@Accessors(chain = true)
@TableName("single_exchange_arbitrage_position")
public class SingleExchangeArbitragePosition {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 业务唯一持仓编号
     */
    private String positionCode;

    /**
     * 交易所名称
     */
    private String exchangeName;

    /**
     * 交易对
     */
    private String symbol;

    /**
     * 现货交易对
     */
    private String spotSymbol;

    /**
     * 合约交易对
     */
    private String futuresSymbol;

    /**
     * 策略类型
     */
    private String strategyType;

    /**
     * 状态: OPENING, OPENED, CLOSING, CLOSED, FAILED
     */
    private String status;

    /**
     * 合约杠杆
     */
    private Integer leverage;

    /**
     * 预算金额
     */
    private BigDecimal plannedUsdtAmount;

    /**
     * 现货名义金额
     */
    private BigDecimal spotNotional;

    /**
     * 合约名义金额
     */
    private BigDecimal futuresNotional;

    // --- 开仓信息 ---

    /**
     * 开仓时间
     */
    private Date entryTime;

    /**
     * 现货买入订单ID
     */
    private String spotBuyOrderId;

    /**
     * 合约做空订单ID
     */
    private String futuresSellOrderId;

    /**
     * 现货开仓均价
     */
    private BigDecimal entrySpotPrice;

    /**
     * 合约开仓均价
     */
    private BigDecimal entryFuturesPrice;

    /**
     * 持仓数量 (币)
     */
    private BigDecimal quantity;

    /**
     * 开仓时资金费率
     */
    private BigDecimal entryFundingRate;

    /**
     * 开仓时基差
     */
    private BigDecimal entryBasisSpread;

    /**
     * 开仓时基差率
     */
    private BigDecimal entryBasisRate;

    /**
     * 开仓时下一次资金费结算时间
     */
    private Long entryNextFundingTime;

    /**
     * 总投入USDT (双边)
     */
    private BigDecimal totalInvestment;

    // --- 平仓信息 ---

    /**
     * 平仓时间
     */
    private Date exitTime;

    /**
     * 现货卖出订单ID
     */
    private String spotSellOrderId;

    /**
     * 合约平空订单ID
     */
    private String futuresBuyOrderId;

    /**
     * 现货平仓均价
     */
    private BigDecimal exitSpotPrice;

    /**
     * 合约平仓均价
     */
    private BigDecimal exitFuturesPrice;

    /**
     * 平仓时资金费率
     */
    private BigDecimal exitFundingRate;

    /**
     * 平仓时基差
     */
    private BigDecimal exitBasisSpread;

    /**
     * 平仓时基差率
     */
    private BigDecimal exitBasisRate;

    // --- 收益统计 ---

    /**
     * 已实现盈亏 (USDT)
     */
    private BigDecimal realizedPnl;

    /**
     * 累计赚取的资金费 (预估)
     */
    private BigDecimal fundingFeesEarned;

    /**
     * 异常信息
     */
    private String errorMessage;

    /**
     * 更新时间
     */
    private Date updatedAt;
}
