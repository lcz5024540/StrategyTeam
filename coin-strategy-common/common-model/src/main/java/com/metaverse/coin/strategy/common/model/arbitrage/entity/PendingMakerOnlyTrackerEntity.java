package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

/**
 * F-P1-4: MAKER_ONLY / MAKER_FIRST 订单超时跟踪持久化实体。
 *
 * <p>{@link com.metaverse.coin.strategy.job.smartwallet.service.MakerOnlyOrderTimeoutTracker}
 * 在 LIVE 网关成功下 MAKER_ONLY / MAKER_FIRST 单后写入此表，到 {@code priceImpactMakerOnlyTimeoutSeconds}
 * 触发时由 tracker 调 {@code PolymarketClobClientRouter.cancelOrder} 撤单，
 * 重启后 {@code @PostConstruct} 阶段回放 status=PENDING 行重建 in-memory tracker。</p>
 *
 * <p>DDL: {@code sql/polymarket/2026-05-17__p1_tracker_persistence_draft.sql}。</p>
 *
 * <p>与 {@link PendingOrderTimeoutTrackerEntity} 的区别：
 * <ul>
 *   <li>本表只覆盖 MAKER_ONLY / MAKER_FIRST，超时较短（≈10s）、和 priceImpactExecutionMode 强绑定；</li>
 *   <li>表里额外保留 {@code category} 列，为 F-P2-14 按品类分桶超时配置预留；</li>
 *   <li>{@code fallback_action} 区分超时后行为：CANCEL（默认）/ PROMOTE_TO_TAKER / DOWNGRADE_PAPER。</li>
 * </ul></p>
 */
@Data
@Accessors(chain = true)
@TableName("pending_maker_only_tracker")
public class PendingMakerOnlyTrackerEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** {@code smart_wallet_order.id} 反向索引；网关写入时未必拿得到，可暂存 0。 */
    private Long orderId;

    /** CLOB 远端 order id，撤单 / 重新核对状态时使用。 */
    @TableField("external_order_id")
    private String externalOrderId;

    /** {@code smart_wallet_follow_task.id}，可为 null。 */
    @TableField("follow_task_id")
    private Long followTaskId;

    private String marketId;
    private String outcome;

    /** 品类标签，便于按品类分桶超时（F-P2-14 留位）。 */
    private String category;

    /** MAKER_ONLY / MAKER_FIRST。 */
    @TableField("maker_strategy")
    private String makerStrategy;

    /** 挂单时间。 */
    private Date placedAt;

    /** 到期时间（{@code priceImpactMakerOnlyTimeoutSeconds} 后）。 */
    private Date expireAt;

    /** CANCEL / PROMOTE_TO_TAKER / DOWNGRADE_PAPER。默认 CANCEL。 */
    private String fallbackAction;

    /** PENDING / RESOLVED / EXPIRED / FAILED。 */
    private String status;

    private Date resolvedAt;
    private String resolvedReason;

    private Date createdAt;
    private Date updatedAt;
}
