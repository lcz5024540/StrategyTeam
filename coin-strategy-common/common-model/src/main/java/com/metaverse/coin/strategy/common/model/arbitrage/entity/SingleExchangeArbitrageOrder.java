package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.Date;

@Data
@Accessors(chain = true)
@TableName("single_exchange_arbitrage_order")
public class SingleExchangeArbitrageOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long positionId;
    private String positionCode;
    private String exchangeName;
    private String symbol;
    private String marketType;
    private String tradePhase;
    private String hedgeSide;
    private String side;
    private String positionSide;
    private String orderType;
    private String status;
    private String exchangeOrderId;
    private String clientOrderId;
    private BigDecimal requestedQty;
    private BigDecimal executedQty;
    private BigDecimal requestedPrice;
    private BigDecimal avgPrice;
    private BigDecimal quoteQty;
    private BigDecimal feeAmount;
    private String feeAsset;
    private Date placedAt;
    private Date filledAt;
    private String errorMessage;
    private String rawResponse;
    private Date createdAt;
    private Date updatedAt;
}
