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
@TableName("single_exchange_arbitrage_risk_snapshot")
public class SingleExchangeArbitrageRiskSnapshot {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long positionId;
    private String positionCode;
    private String exchangeName;
    private String symbol;
    private String checkStage;
    private BigDecimal spotUsdtBalance;
    private BigDecimal futuresAvailableBalance;
    private BigDecimal requiredMargin;
    private BigDecimal bufferedRequiredMargin;
    private BigDecimal marginRatio;
    private Boolean riskPassed;
    private String rejectionReason;
    private Date createdAt;
}
