package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

/**
 * F-P1-3: LIVE GTC 全局超时取消跟踪表实体。
 *
 * <p>所有 GTC 单（含 MAKER / TAKER / MAKER_ONLY）由 LIVE 网关在创建时写入此表，
 * 由 {@code GtcOrderTimeoutTracker} 在 {@code execution-wallet.gtc-default-timeout-seconds}
 * 到期后调 {@code PolymarketClobClientRouter.cancelOrder} 撤单；重启后扫表
 * 重建 in-memory tracker，保证容器重启 / 滚动发布期间挂单仍能按时取消。</p>
 *
 * <p>DDL: {@code sql/polymarket/2026-05-17__p1_tracker_persistence_draft.sql}.</p>
 *
 * <p><b>对开单效率 / 资金使用率 / 是否会开单 / 盈亏点 / 胜率</b>：
 * <ul>
 *   <li>资金使用率: ⬆️ 微升 — 长期挂着不成交的 GTC 单按时取消，资金回流；</li>
 *   <li>开单效率 / 是否会开单 / 胜率: ⚪ 不变 — 仅影响"已下单未成交"的存量；</li>
 *   <li>盈亏点: 🟡 中性偏正 — 避免价格已经走过后还在挂着的过期单子被撮合到不利价。</li>
 * </ul></p>
 */
@Data
@Accessors(chain = true)
@TableName("pending_order_timeout_tracker")
public class PendingOrderTimeoutTrackerEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** {@code smart_wallet_order.id} 反向索引。 */
    private Long orderId;

    /** CLOB 远端 order id，撤单 / 重新核对状态时使用。 */
    @TableField("external_order_id")
    private String externalOrderId;

    /** {@code smart_wallet_follow_task.id}，可为 null（退出 / 平仓单不挂 task）。 */
    @TableField("follow_task_id")
    private Long followTaskId;

    private String marketId;
    private String outcome;

    /** TAKER / MAKER / MAKER_ONLY / MAKER_FIRST。 */
    @TableField("order_strategy")
    private String orderStrategy;

    /** GTC 全局超时时间点，scan 任务在 {@code now >= expireAt} 时尝试撤单。 */
    private Date expireAt;

    /** PENDING / CANCELLED / CANCEL_FAILED / SUPERSEDED。 */
    private String cancelStatus;

    /** 已尝试撤单次数，避免无限重试。 */
    private Integer cancelAttempts;

    private Date lastAttemptAt;

    /** 最近一次撤单失败错误信息（截断 512）。 */
    private String cancelError;

    private Date createdAt;
    private Date updatedAt;
}
