package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxContractLegEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface HtxContractLegMapper extends BaseMapper<HtxContractLegEntity> {

    @Select("SELECT COALESCE(SUM(COALESCE(l.realized_profit, 0) - COALESCE(l.fee, 0)), 0) "
            + "FROM htx_contract_leg l JOIN htx_contract_cycle c ON c.id = l.cycle_id "
            + "WHERE c.created_at >= #{since} AND c.message LIKE 'MAKER_GRID:%'")
    BigDecimal sumMakerGridNetSince(@Param("since") LocalDateTime since);

    @Select("SELECT COALESCE(SUM(COALESCE(l.realized_profit, 0) - COALESCE(l.fee, 0)), 0) "
            + "FROM htx_contract_leg l JOIN htx_contract_cycle c ON c.id = l.cycle_id "
            + "WHERE c.status = 'DONE' AND c.message LIKE 'MAKER_GRID:%' "
            + "GROUP BY c.id ORDER BY c.id DESC LIMIT #{limit}")
    List<BigDecimal> findRecentMakerGridNet(@Param("limit") int limit);
}
