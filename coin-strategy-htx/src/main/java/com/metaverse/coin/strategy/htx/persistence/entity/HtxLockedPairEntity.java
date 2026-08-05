package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("htx_locked_pair")
public class HtxLockedPairEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String clientOrderId;
    private String symbol;
    private BigDecimal reqQuantity;
    private BigDecimal buyPrice;
    private BigDecimal sellPrice;
    private String status;
    private String resultCode;
    private Long buyOrderId;
    private Long sellOrderId;
    private BigDecimal buyFilledAmount;
    private BigDecimal sellFilledAmount;
    private BigDecimal netBaseDelta;
    private BigDecimal totalFeeBase;
    private BigDecimal totalFeeQuote;
    private BigDecimal netQuoteCost;
    private Boolean dryRun;
    private String message;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
