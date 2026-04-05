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
@TableName("smart_wallet_cash_ledger")
public class SmartWalletCashLedgerEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String executionAddress;
    private String asset;
    private String entryType;
    private String direction;
    private BigDecimal amount;
    private BigDecimal balanceAfter;
    private String referenceType;
    private Long referenceId;
    private String marketId;
    private String question;
    private Date occurredAt;
    private Date createdAt;
}
