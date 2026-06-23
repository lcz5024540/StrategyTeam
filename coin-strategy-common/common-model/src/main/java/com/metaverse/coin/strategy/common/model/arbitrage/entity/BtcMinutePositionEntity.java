package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.Date;

/**
 * BTC 5 分钟涨跌策略仓位实体。
 */
@Data
@Accessors(chain = true)
@TableName("btc_minute_position")
public class BtcMinutePositionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 策略 ID */
    private String strategyId;

    /** 钱包 ID */
    private String walletId;

    /** Polymarket conditionId */
    private String marketId;

    /** Token ID */
    private String tokenId;

    /** 市场标题 */
    private String marketTitle;

    /** YES / NO */
    private String outcome;

    /** BUY / SELL */
    private String side;

    /** 入场时 BTC 价格 */
    private BigDecimal btcEntryPrice;

    /** 入场时 Polymarket 价格 */
    private BigDecimal entryPrice;

    /** 当前 Polymarket 价格 */
    private BigDecimal currentPrice;

    /** 持仓数量（shares） */
    private BigDecimal quantity;

    /** 投入金额（USDC） */
    private BigDecimal stakeUsdc;

    /** 交易手续费 */
    private BigDecimal tradingFeeUsdc;

    /** 公允概率 */
    private BigDecimal fairProbability;

    /** Edge */
    private BigDecimal edge;

    /** RSI at entry */
    private BigDecimal rsiAtEntry;

    /** 波动率 at entry */
    private BigDecimal volatilityAtEntry;

    /** 状态: OPEN / CLOSED / SETTLED */
    private String status;

    /** 执行模式: PAPER / LIVE */
    private String executionMode;

    /** CLOB 订单 ID（LIVE 模式） */
    private String externalOrderId;

    /** 市场结算时间 */
    private Date settlementTime;

    /** 结算结果: WIN / LOSE / null（未结算） */
    private String settlementResult;

    /** 已实现 PNL（结算后） */
    private BigDecimal realizedPnlUsdc;

    /** 信号原因 */
    private String signalReason;

    private Date createdAt;
    private Date updatedAt;
}
