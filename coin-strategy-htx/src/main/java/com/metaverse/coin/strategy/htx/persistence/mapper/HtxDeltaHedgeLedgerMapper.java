package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxDeltaHedgeLedgerEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface HtxDeltaHedgeLedgerMapper extends BaseMapper<HtxDeltaHedgeLedgerEntity> {

    @Select("SELECT * FROM htx_delta_hedge_ledger WHERE hedge_key = #{hedgeKey} LIMIT 1")
    HtxDeltaHedgeLedgerEntity findByHedgeKey(@Param("hedgeKey") String hedgeKey);
}
