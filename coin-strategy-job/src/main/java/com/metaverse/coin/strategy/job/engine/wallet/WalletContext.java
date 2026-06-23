package com.metaverse.coin.strategy.job.engine.wallet;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 策略钱包运行时上下文。
 * 从 strategy_wallet 表加载，包含下单所需的全部凭证和配置。
 */
@Data
@Builder
public class WalletContext {

    /** 钱包唯一标识 */
    private String walletId;

    /** 绑定的策略 ID */
    private String strategyId;

    /** 链名称 */
    private String chain;

    /** 链 ID */
    private int chainId;

    /** 钱包地址 */
    private String address;

    /** 私钥（已解密） */
    private String privateKey;

    /** 资金来源地址 */
    private String funderAddress;

    /** CLOB API Base URL */
    private String apiBaseUrl;

    /** CLOB API Key */
    private String apiKey;

    /** CLOB API Secret（已解密） */
    private String apiSecret;

    /** CLOB API Passphrase */
    private String apiPassphrase;

    /** 是否启用 */
    private boolean enabled;

    /** 资金预算上限（USDC） */
    private BigDecimal capitalPoolUsdc;

    /** 是否已配置完整（地址 + 私钥） */
    private boolean configured;

    /** 是否有 API 凭证 */
    private boolean apiCredentialsPresent;
}
