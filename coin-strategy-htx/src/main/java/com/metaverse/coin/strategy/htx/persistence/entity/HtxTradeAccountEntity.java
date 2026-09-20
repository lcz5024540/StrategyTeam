package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Registered HTX trading account. Secrets are stored encrypted as ENC(...). */
@Data
@TableName("htx_trade_account")
public class HtxTradeAccountEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String accountLabel;
    private String exchange;
    private String marketType;
    private String accountRole;
    private Boolean enabled;
    private Boolean trendEnabled;
    private Boolean mirrorEnabled;
    private String apiKeyCipher;
    private String secretKeyCipher;
    private String baseUrl;
    private Boolean syncLeverage;
    private Boolean blockEntryOnLeverageSyncFailure;
    private Boolean blockPrimaryCloseUntilSynced;
    private Integer sortOrder;
    private String note;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
