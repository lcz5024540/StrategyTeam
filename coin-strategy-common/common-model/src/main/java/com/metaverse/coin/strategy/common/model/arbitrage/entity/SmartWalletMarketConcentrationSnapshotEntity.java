package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.Date;

@Data
@Accessors(chain = true)
@TableName("smart_wallet_market_concentration_snapshot")
public class SmartWalletMarketConcentrationSnapshotEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String marketId;
    private String outcome;
    private Integer observedHolders;
    @TableField("top1_pct")
    private BigDecimal top1Pct;
    @TableField("top3_pct")
    private BigDecimal top3Pct;
    @TableField("top5_pct")
    private BigDecimal top5Pct;
    private Integer hhi;
    private Boolean highlyConcentrated;
    private Boolean extremelyConcentrated;
    private Date snapshotAt;
    private String topHolderAddressesJson;
    private Date createdAt;
    private Date updatedAt;
}
