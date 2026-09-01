package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxInventoryRecycleSagaEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface HtxInventoryRecycleSagaMapper extends BaseMapper<HtxInventoryRecycleSagaEntity> {

    @Select("SELECT * FROM htx_inventory_recycle_saga WHERE saga_key = #{sagaKey} LIMIT 1")
    HtxInventoryRecycleSagaEntity findBySagaKey(@Param("sagaKey") String sagaKey);
}
