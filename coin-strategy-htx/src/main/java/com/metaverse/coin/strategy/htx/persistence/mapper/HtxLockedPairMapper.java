package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxLockedPairEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Mapper
public interface HtxLockedPairMapper extends BaseMapper<HtxLockedPairEntity> {

    /**
     * 当日累计实盘买腿名义之和（USDT）：非 dry-run、created_at &gt;= start 的 req_quantity × buy_price 求和。
     * 用于每日交易额上限风控；无匹配行时 COALESCE 返回 0。不依赖任何新增列，无需改表结构。
     */
    @Select("SELECT COALESCE(SUM(req_quantity * buy_price), 0) FROM htx_locked_pair "
            + "WHERE dry_run = 0 AND created_at >= #{start}")
    BigDecimal sumLiveQuoteNotionalSince(@Param("start") LocalDateTime start);

    /**
     * 当日做市风险名义之和（USDT）：只统计仍代表真实资金占用/成交风险的做市 cell。
     * <p>
     * 移动网格下移时会产生大量零成交 {@code MM_CANCELLED_BUY}，这些撤单不应继续消耗
     * daily-cap；否则窗口仍在 RUNNING，却因为历史撤单 churn 无法补新买档。
     * 对已买入/待卖/已完成/人工对账/粉尘态，优先使用真实买入数量 {@code buy_filled_amount}，
     * 缺失时回退请求数量，保持 fail-safe。
     */
    @Select("SELECT COALESCE(SUM("
            + "CASE WHEN status = 'MM_RESTING_BUY' "
            + "THEN req_quantity "
            + "ELSE COALESCE(NULLIF(buy_filled_amount, 0), req_quantity) END "
            + "* buy_price), 0) "
            + "FROM htx_locked_pair "
            + "WHERE dry_run = 0 "
            + "AND strategy = 'MARKET_MAKER' "
            + "AND created_at >= #{start} "
            + "AND status IN ('MM_RESTING_BUY', 'MM_RESTING_SELL', 'MM_SELL_RETRYABLE', "
            + "'MM_RECONCILE_REQUIRED', 'MM_DONE', 'MM_DUST')")
    BigDecimal sumMmRiskQuoteNotionalSince(@Param("start") LocalDateTime start);

    /**
     * 当日做市已实现净额（USDT）：MM_DONE cell 的真实累计 quote 现金流求和。
     * 正数=盈利，负数=亏损；口径为跨 symbol 全量（当日熔断按全局净亏损裁定）。
     * <p>
     * 仅统计 strategy=MARKET_MAKER、status=MM_DONE、非 dry-run、updated_at &gt;= start 的对；无匹配行 COALESCE 返回 0。
     * net_quote_cost 在买腿成交时记负的真实买入 cash，在每张卖单终态时累加真实卖出 cash 与 quote 手续费；
     * 未售完库存不会进入 MM_DONE，因此取消且零成交的卖单对已实现盈亏贡献为 0。
     */
    @Select("SELECT COALESCE(SUM(net_quote_cost), 0) "
            + "FROM htx_locked_pair "
            + "WHERE strategy = 'MARKET_MAKER' AND status = 'MM_DONE' AND dry_run = 0 AND updated_at >= #{start}")
    BigDecimal sumMmRealizedNetQuoteSince(@Param("start") LocalDateTime start);

    /**
     * 同一/近似买价刚出现零成交取消时，短期不再补同价买单，避免 post-only 立即取消后无限重开。
     */
    @Select("SELECT COUNT(*) FROM htx_locked_pair "
            + "WHERE dry_run = 0 "
            + "AND strategy = 'MARKET_MAKER' "
            + "AND symbol = #{symbol} "
            + "AND status = 'MM_CANCELLED_BUY' "
            + "AND updated_at >= #{since} "
            + "AND COALESCE(buy_filled_amount, 0) = 0 "
            + "AND ABS(buy_price - #{buyPrice}) <= (#{buyPrice} * #{gapPct})")
    Long countRecentZeroFillCancelledMmBuy(@Param("symbol") String symbol,
                                           @Param("buyPrice") BigDecimal buyPrice,
                                           @Param("gapPct") BigDecimal gapPct,
                                           @Param("since") LocalDateTime since);
}
