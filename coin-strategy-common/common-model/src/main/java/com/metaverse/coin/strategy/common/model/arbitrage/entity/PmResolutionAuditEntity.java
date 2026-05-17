package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

/**
 * W1-T2.2 每次市场结算解析的审计持久化记录.
 *
 * <p>由 {@code SettlementSourceAuditService} (或直接由 {@code MarketResolutionResolver}
 * 调用方) 把内存 {@code ResolutionAuditRecord} 转换后写入. 同一 marketId 可有多条记录
 * (按 observed_at 排序看变化).</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十七节.</p>
 */
@Data
@Accessors(chain = true)
@TableName("pm_resolution_audit")
public class PmResolutionAuditEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String marketId;
    private Date observedAt;

    private String resolutionStatus;
    private String resolutionSource;
    private String winningOutcome;
    private String winningTokenId;
    private String manualCheckReason;

    private Boolean closed;
    private Boolean acceptingOrdersPresent;
    private Boolean acceptingOrders;

    private String umaResolutionStatus;
    private Boolean umaResolved;

    /**
     * F-P1-12: UMA Oracle 是否争议中 (disputed=true)。
     * <p>列默认 false，建表 DDL 见 sql/polymarket/2026-05-17__f-p1-12_uma_dispute_audit_columns_draft.sql。
     * DDL 上线前用 {@code @TableField(exist=false)} 自动忽略，避免 BadSqlGrammarException；
     * 上线后请删除该注解。
     */
    @TableField(exist = false)
    private Boolean umaDisputed;
    /**
     * F-P1-12: 第一次观测到 UMA 争议的时间戳。
     * <p>同上：DDL 上线前 {@code exist=false}，上线后删注解使其参与 SELECT/UPDATE。
     */
    @TableField(exist = false)
    private Date umaDisputeObservedAt;

    private String outcomesJson;
    private String outcomePricesJson;
    private String outcomeTokenIdsJson;

    private Date createdAt;
}
