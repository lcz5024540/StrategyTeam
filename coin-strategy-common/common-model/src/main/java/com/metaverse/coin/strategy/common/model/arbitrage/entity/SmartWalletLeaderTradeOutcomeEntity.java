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
@TableName("smart_wallet_leader_trade_outcome")
public class SmartWalletLeaderTradeOutcomeEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String walletAddress;
    private String marketId;
    private Long marketInfoId;
    private String question;
    private String outcome;
    private String tokenId;

    private Date firstTradeAt;
    private Date lastTradeAt;
    private Integer buyCount;
    private Integer sellCount;
    private BigDecimal buyShares;
    private BigDecimal sellShares;
    private BigDecimal netShares;
    private BigDecimal avgBuyPrice;
    private BigDecimal buyCostUsdc;
    private BigDecimal sellProceedsUsdc;

    private String resolvedOutcome;
    private BigDecimal settlementPrice;
    private BigDecimal estimatedSettlementValueUsdc;
    private BigDecimal estimatedRealizedPnlUsdc;
    private BigDecimal estimatedRoiRate;
    @TableField("is_win")
    private Boolean isWin;
    private String outcomeStatus;
    private BigDecimal sourceConfidence;
    private String dataSource;

    private Date createdAt;
    private Date updatedAt;
}
