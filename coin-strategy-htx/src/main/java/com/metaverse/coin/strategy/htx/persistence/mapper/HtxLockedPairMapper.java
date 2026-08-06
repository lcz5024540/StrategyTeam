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
}
