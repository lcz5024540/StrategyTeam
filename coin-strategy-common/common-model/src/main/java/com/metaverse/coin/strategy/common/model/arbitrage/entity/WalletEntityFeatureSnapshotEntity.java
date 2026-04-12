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
@TableName("wallet_entity_feature_snapshot")
public class WalletEntityFeatureSnapshotEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 实体ID */
    private String entityId;

    /** 快照时间 */
    private Date snapshotTime;

    /** 该实体下的钱包数量 */
    private Integer walletCount;

    /** 合并后胜率（各钱包按交易次数加权平均） */
    private BigDecimal aggregatedWinRate;

    /** 合并后平均 ROI */
    private BigDecimal aggregatedRoi;

    /** 合并后累计 PnL (USDC) */
    private BigDecimal aggregatedPnlUsdc;

    /** 合并后总交易次数 */
    private Integer aggregatedTotalTrades;

    /** 实体维度综合评分 0-1 */
    private BigDecimal entityScore;

    /** 是否存在多钱包 */
    private Boolean isMultiWallet;

    /** 钱包地址列表（JSON 数组格式） */
    private String walletAddressesJson;

    private Date createdAt;
}
