package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxInventoryLotEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface HtxInventoryLotMapper extends BaseMapper<HtxInventoryLotEntity> {

    @Select("SELECT * FROM htx_inventory_lot "
            + "WHERE symbol = #{symbol} AND status = 'OPEN' AND remaining_base > 0 "
            + "ORDER BY id ASC")
    List<HtxInventoryLotEntity> findOpenLots(@Param("symbol") String symbol);

    @Select("SELECT COALESCE(SUM(remaining_base), 0) FROM htx_inventory_lot "
            + "WHERE symbol = #{symbol} AND status = 'OPEN'")
    BigDecimal sumOpenRemainingBase(@Param("symbol") String symbol);
}
