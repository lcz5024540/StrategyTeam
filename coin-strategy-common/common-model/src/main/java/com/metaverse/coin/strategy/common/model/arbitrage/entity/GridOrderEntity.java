package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 网格策略挂单实体。
 * 记录每个网格层的挂单状态和成交情况。
 */
@Data
@Accessors(chain = true)
@TableName("grid_order")
public class GridOrderEntity {

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

    /** 网格层级编号 */
    private Integer levelIndex;

    /** YES / NO */
    private String outcome;

    /** BUY / SELL */
    private String side;

    /** 挂单价格 */
    private BigDecimal price;

    /** 挂单数量（shares） */
    private BigDecimal quantity;

    /** 挂单金额（USDC） */
    private BigDecimal amountUsdc;

    /** CLOB 外部订单 ID */
    private String externalOrderId;

    /** 订单状态: PENDING / LIVE / FILLED / PARTIALLY_FILLED / CANCELLED */
    private String status;

    /** 执行模式: PAPER / LIVE */
    private String executionMode;

    /** 成交数量 */
    private BigDecimal filledQuantity;

    /** 成交均价 */
    private BigDecimal filledAvgPrice;

    /** 对侧补单的订单 ID（成交后自动在对侧挂单） */
    private Long counterOrderId;

    /** 已实现利润（本次成交） */
    private BigDecimal realizedProfitUsdc;

    private Date createdAt;
    private Date updatedAt;
}
