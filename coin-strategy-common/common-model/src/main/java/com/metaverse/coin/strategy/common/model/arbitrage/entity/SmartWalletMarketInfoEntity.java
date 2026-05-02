package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

/**
 * 公共市场信息表 —— 将 marketId / question / outcome / tokenId / indexSet / marketEndAt
 * 等在 order、fill、position、follow_task 中重复出现的市场维度字段统一收敛到本表。
 * <p>
 * 唯一键：(market_id, outcome)，同一市场不同 outcome 各一行。
 */
@Data
@Accessors(chain = true)
@TableName("smart_wallet_market_info")
public class SmartWalletMarketInfoEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** Polymarket conditionId / 条件市场 ID */
    private String marketId;

    /** Polymarket market URL slug */
    private String marketSlug;

    /** 市场问题描述 */
    private String question;

    /** Polymarket outcome label，如 YES / NO / 某候选名 */
    private String outcome;

    /** CLOB outcome token id */
    private String tokenId;

    /** CTF indexSet bitmask (1=outcome0/YES, 2=outcome1/NO) */
    private Integer indexSet;

    /** 市场预计结算时间戳 (毫秒) */
    private Long marketEndAt;

    /** Gamma 原始分类 */
    private String sourceCategory;

    /** 内部统一市场大类 */
    private String normalizedCategory;

    /** ACTIVE / CLOSED_UNRESOLVED / RESOLVED */
    private String marketLifecycle;

    /** Gamma closed 标记 */
    private Boolean closed;

    /** Gamma acceptingOrders 标记 */
    private Boolean acceptingOrders;

    /** 市场最终获胜 outcome */
    private String resolvedOutcome;

    /** 最近一次元数据同步时间 */
    private Date lastMetadataSyncAt;

    private Date createdAt;
    private Date updatedAt;
}
