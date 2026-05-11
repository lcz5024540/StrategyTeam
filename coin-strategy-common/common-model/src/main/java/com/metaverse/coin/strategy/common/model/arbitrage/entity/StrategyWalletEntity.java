package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 多策略钱包配置表实体。
 * 每个策略绑定独立的 Polymarket 钱包地址，资金不混用。
 */
@Data
@Accessors(chain = true)
@TableName("strategy_wallet")
public class StrategyWalletEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 钱包唯一标识，如 "follow-main", "weather-1", "btc-minute-1", "grid-1" */
    private String walletId;

    /** 绑定的策略 ID，如 "SMART_WALLET_FOLLOW", "WEATHER_ARB" */
    private String strategyId;

    /** 链名称，默认 polygon */
    private String chain;

    /** 链 ID，默认 137 (Polygon) */
    private Integer chainId;

    /** Polymarket 钱包地址 */
    private String address;

    /** 私钥（加密存储） */
    private String privateKey;

    /** 资金来源地址 */
    private String funderAddress;

    /** Polymarket CLOB API Key */
    private String apiKey;

    /** Polymarket CLOB API Secret（加密存储） */
    private String apiSecret;

    /** Polymarket CLOB API Passphrase */
    private String apiPassphrase;

    /** 是否启用 */
    private Boolean enabled;

    /** 该钱包的资金预算上限（USDC） */
    private BigDecimal capitalPoolUsdc;

    /** 备注 */
    private String notes;

    private Date createdAt;
    private Date updatedAt;
}
