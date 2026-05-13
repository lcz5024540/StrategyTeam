package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 单地址订单窗口统计快照（Phase 4.4 引入）。
 * 由 job 端 {@code ProfileOrderStatsSnapshotJob} 定期写入；web 端 read service 直接读。
 * <p>主键复合：(wallet_address, source, window)。</p>
 */
@Data
@Accessors(chain = true)
@TableName("smart_wallet_order_stats_snapshot")
public class SmartWalletOrderStatsSnapshotEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String walletAddress;
    private String source;
    private String window;
    private Date generatedAt;
    private Integer closedOrderCount;
    private Integer winCount;
    private Integer lossCount;
    private Integer drawCount;
    private BigDecimal closedWinRate;
    private BigDecimal closedRealizedPnlUsdc;
    private BigDecimal activeUnrealizedPnlUsdc;
    private BigDecimal netPnlUsdc;
    private BigDecimal averageRoiRate;
    private Integer activePositionCount;
    private Integer pendingSettlementCount;
    private BigDecimal earlySettlementRate;
    private BigDecimal settlementWinRate;
    private Date createdAt;
    private Date updatedAt;
}
