package com.metaverse.coin.strategy.web.model.smartwallet;

import java.math.BigDecimal;
import java.util.Date;

public record SmartWalletCashLedgerView(Long id, String executionAddress, String asset, String entryType,
    String direction, BigDecimal amount, BigDecimal balanceAfter, String referenceType, Long referenceId,
    String marketId, String question, String memo, Date occurredAt) {}
