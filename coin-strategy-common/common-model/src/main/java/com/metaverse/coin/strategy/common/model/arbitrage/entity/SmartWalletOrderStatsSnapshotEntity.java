package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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
    // 2026-05-18 ray@cowork: `window` 是 MySQL 8.0+ window function 保留字, 不加反引号 MyBatis-Plus 生成的
    // SQL (INSERT/SELECT/UPDATE) 会抛 You have an error in your SQL syntax near 'window' —— 今日已抛 230+ 次,
    // 导致 ProfileOrderStatsSnapshotJob 入库失败 → followFitScore 不更新 → signal_score 全 0.
    @TableField("`window`")
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
