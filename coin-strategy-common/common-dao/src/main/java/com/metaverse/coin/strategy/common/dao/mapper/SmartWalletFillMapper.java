package com.metaverse.coin.strategy.common.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFillEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

@Mapper
public interface SmartWalletFillMapper extends BaseMapper<SmartWalletFillEntity> {

    @Select("<script>"
            + "SELECT COUNT(*) FROM smart_wallet_fill"
            + "<where>"
            + "  <if test=\"mode == 'PAPER'\">"
            + "    (execution_address IS NULL OR execution_address = 'paper-book')"
            + "  </if>"
            + "  <if test=\"mode == 'LIVE'\">"
            + "    execution_address IS NOT NULL AND execution_address != 'paper-book'"
            + "  </if>"
            + "</where>"
            + "</script>")
    int countByMode(@Param("mode") String mode);

    @Select("<script>"
            + "SELECT COALESCE(SUM(notional_usdc), 0) FROM smart_wallet_fill"
            + "<where>"
            + "  <if test=\"mode == 'PAPER'\">"
            + "    (execution_address IS NULL OR execution_address = 'paper-book')"
            + "  </if>"
            + "  <if test=\"mode == 'LIVE'\">"
            + "    execution_address IS NOT NULL AND execution_address != 'paper-book'"
            + "  </if>"
            + "</where>"
            + "</script>")
    BigDecimal sumVolumeByMode(@Param("mode") String mode);
}
