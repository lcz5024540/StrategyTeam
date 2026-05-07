package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.Date;

@Data
@Accessors(chain = true)
@TableName("smart_wallet_profile")
public class SmartWalletProfileEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String walletAddress;
    private String chain;
    private String alias;
    private String source;
    private BigDecimal winRate;
    private BigDecimal realizedPnlUsdc;
    private Integer totalTrades;
    private BigDecimal averageSizeUsdc;
    private BigDecimal averageRoiRate;
    private BigDecimal maxDrawdownRate;
    private BigDecimal walletScore;
    private Boolean qualified;
    private Boolean watchEnabled;
    private Date lastTradeAt;
    private Date lastEvaluatedAt;
    private Date lastDiscoveredAt;
    private String lastDiscoveryQuery;
    @TableField("leaderboard_hit_count_30d")
    private Integer leaderboardHitCount30d;
    private String dominantSession;
    @TableField("asia_trade_ratio_30d")
    private BigDecimal asiaTradeRatio30d;
    @TableField("eu_trade_ratio_30d")
    private BigDecimal euTradeRatio30d;
    @TableField("us_trade_ratio_30d")
    private BigDecimal usTradeRatio30d;
    private String activeHourHistogramJson;
    @TableField("recent_win_rate_7d")
    private BigDecimal recentWinRate7d;
    @TableField("recent_win_rate_30d")
    private BigDecimal recentWinRate30d;
    @TableField("recent_trade_count_7d")
    private Integer recentTradeCount7d;
    @TableField("recent_trade_count_30d")
    private Integer recentTradeCount30d;
    @TableField("observe_cooldown_until")
    private Date observeCooldownUntil;
    @TableField("observe_cooldown_reason")
    private String observeCooldownReason;
    @TableField("hot_retention_until")
    private Date hotRetentionUntil;
    @TableField("hot_retention_reason")
    private String hotRetentionReason;
    @TableField("manual_watch_enabled")
    private Boolean manualWatchEnabled;
    @TableField("manual_recommended_exit_lane")
    private String manualRecommendedExitLane;
    @TableField("manual_override_note")
    private String manualOverrideNote;
    @TableField("manual_override_updated_at")
    private Date manualOverrideUpdatedAt;
    private String monitorTier;
    private String monitorReason;
    private Date lastEventSyncAt;
    private Date lastMetricsRefreshAt;

    /** closed-positions 增量同步：已落库的最大 closed_at(ms)，业务高水位 */
    @TableField("closed_sync_max_closed_at")
    private Long closedSyncMaxClosedAt;

    /** closed-positions 增量同步：已落库总数（一致性校验用） */
    @TableField("closed_sync_total_count")
    private Integer closedSyncTotalCount;

    /** closed-positions 增量同步数据质量：TRUTH / PARTIAL / UNTRUSTED / STUCK */
    @TableField("closed_sync_data_quality")
    private String closedSyncDataQuality;

    /** closed-positions 上次完整全量复扫时间 */
    @TableField("closed_sync_last_full_at")
    private Date closedSyncLastFullAt;

    private Date createdAt;
    private Date updatedAt;
}
