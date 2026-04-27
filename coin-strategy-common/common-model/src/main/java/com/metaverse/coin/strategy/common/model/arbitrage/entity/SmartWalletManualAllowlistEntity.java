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
@TableName("smart_wallet_manual_allowlist")
public class SmartWalletManualAllowlistEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String walletAddress;
    private String source;
    private String status;
    private String allowedExecutionModes;
    private BigDecimal stakeMultiplier;
    private Integer maxLiveOrdersPerDay;
    private Integer maxLiveOrdersPerRun;
    private Date expiresAt;
    private String reason;
    private String createdBy;
    private Date createdAt;
    private Date updatedAt;

    @TableField(exist = false)
    private Boolean active;
}
