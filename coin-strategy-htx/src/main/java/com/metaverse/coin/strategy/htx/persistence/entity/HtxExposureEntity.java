package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("htx_exposure")
public class HtxExposureEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long pairId;
    private String symbol;
    private String exposedSide;
    private BigDecimal exposedAmount;
    private String status;
    private Long flattenOrderId;
    private Integer flattenRetries;
    private String detail;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
