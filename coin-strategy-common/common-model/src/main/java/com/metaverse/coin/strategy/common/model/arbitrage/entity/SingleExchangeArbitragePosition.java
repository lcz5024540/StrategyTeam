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
     * 交易对
     */
    private String symbol;

    /**
     * 策略类型
     */
    private String strategyType;

    /**
     * 状态: OPENING, OPENED, CLOSING, CLOSED, FAILED
     */
    private String status;

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
     * 更新时间
     */
    private Date updatedAt;
}
