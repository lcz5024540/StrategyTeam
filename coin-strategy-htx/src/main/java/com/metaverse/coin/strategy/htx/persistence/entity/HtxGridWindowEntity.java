package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 每个现货交易对唯一的移动网格窗口；重启后继续沿用相同价格晶格。 */
@Data
@TableName("htx_grid_window")
public class HtxGridWindowEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String symbol;
    private BigDecimal anchorPrice;
    private BigDecimal lowerPrice;
    private BigDecimal upperPrice;
    private BigDecimal stepPct;
    private Integer levels;
    private Long epoch;
    private String state;
    private String breachDirection;
    private Integer breachCount;
    private LocalDateTime lastMigratedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
