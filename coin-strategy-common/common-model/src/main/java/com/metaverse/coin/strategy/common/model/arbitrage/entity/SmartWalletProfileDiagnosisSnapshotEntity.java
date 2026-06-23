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
 * 单地址综合诊断快照（Phase 4.4 引入）。
 * 由 job 端 {@code ProfileDiagnosisSnapshotJob} 定期写入；web 端 read service 直接读。
 * <p>{@code strengthsJson} / {@code risksJson} 存 JSON 数组字符串，read 时反序列化为 List<String>。</p>
 */
@Data
@Accessors(chain = true)
@TableName("smart_wallet_profile_diagnosis_snapshot")
public class SmartWalletProfileDiagnosisSnapshotEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String walletAddress;
    private String source;
    private String alias;
    private String archetype;
    private String followLane;
    private String archetypeReason;
    private BigDecimal archetypeConfidence;
    private String screenerTier;
    private Boolean highQualityAddress;
    private Boolean highFrequencyTradingAddress;
    private String winSourceType;
    private BigDecimal qualityScore;
    private BigDecimal replicabilityScore;
    private BigDecimal stabilityScore;
    private BigDecimal settlementWinRate;
    private BigDecimal latencyAlphaRate;
    private BigDecimal swingAlphaRate;
    private BigDecimal earlySettlementRate;
    /**
     * MyBatis-Plus 默认驼峰转下划线把数字视为小写字母, 会产出 dedup_order_count7d (数字前没下划线),
     * 而表 DDL 写的是 dedup_order_count_7d. 这里用 @TableField 显式锁住列名, 跟 DDL 对齐.
     */
    @TableField("dedup_order_count_7d")
    private Integer dedupOrderCount7d;
    @TableField("dedup_order_count_30d")
    private Integer dedupOrderCount30d;
    private BigDecimal medianHoldMinutes;
    private String dataQuality;
    private String recommendedExitLane;
    private String summary;
    /** strengths 序列化为 JSON 数组字符串，read 时反序列化 */
    private String strengthsJson;
    /** risks 序列化为 JSON 数组字符串，read 时反序列化 */
    private String risksJson;
    private Date generatedAt;
    private Date createdAt;
    private Date updatedAt;
}
