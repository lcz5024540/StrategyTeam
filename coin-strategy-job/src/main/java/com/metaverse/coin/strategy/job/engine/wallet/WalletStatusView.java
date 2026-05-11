package com.metaverse.coin.strategy.job.engine.wallet;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 钱包状态视图，用于运维和仪表盘展示。
 */
@Data
@Builder
public class WalletStatusView {

    private String walletId;
    private String strategyId;
    private String address;
    private boolean enabled;
    private boolean configured;
    private BigDecimal capitalPoolUsdc;
    private String notes;
}
