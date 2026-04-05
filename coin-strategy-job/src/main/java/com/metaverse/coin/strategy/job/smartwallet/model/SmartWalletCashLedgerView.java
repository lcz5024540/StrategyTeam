package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
@Builder
public class SmartWalletCashLedgerView {
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
}
