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
@TableName("single_exchange_arbitrage_rebalance_record")
public class SingleExchangeArbitrageRebalanceRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long riskSnapshotId;
    private String exchangeName;
    private String symbol;
    private String asset;
    private String transferType;
    private String status;
    private String reasonCode;
    private BigDecimal currentMarginRatio;
    private BigDecimal thresholdMarginRatio;
    private BigDecimal targetMarginRatio;
    private BigDecimal futuresMarginBalance;
    private BigDecimal futuresMaintMargin;
    private BigDecimal targetMarginBalance;
    private BigDecimal requiredTransferAmount;
    private BigDecimal availableSpotBalance;
    private BigDecimal actualTransferAmount;
    private Long transferId;
    private String errorMessage;
    private Date createdAt;
    private Date updatedAt;
}
