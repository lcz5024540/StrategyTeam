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
}
