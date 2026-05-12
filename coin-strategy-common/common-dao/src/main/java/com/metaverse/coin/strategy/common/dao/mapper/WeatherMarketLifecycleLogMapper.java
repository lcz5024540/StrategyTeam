package com.metaverse.coin.strategy.common.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.WeatherMarketLifecycleLogEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface WeatherMarketLifecycleLogMapper extends BaseMapper<WeatherMarketLifecycleLogEntity> {

    /**
     * 查找某个 marketId 是否已经有记录（判断首次扫描）
     */
    @Select("SELECT COUNT(1) FROM weather_market_lifecycle_log WHERE market_id = #{marketId} LIMIT 1")
    int countByMarketId(@Param("marketId") String marketId);

    /**
     * 获取所有已结算市场的生命周期数据（用于统计分析）
     */
    @Select("SELECT * FROM weather_market_lifecycle_log WHERE resolved_outcome IS NOT NULL ORDER BY end_at_ms DESC")
    List<WeatherMarketLifecycleLogEntity> selectResolvedMarkets();

    /**
     * 获取某市场的全部快照记录（观察 acceptingOrders 变化时点）
     */
    @Select("SELECT * FROM weather_market_lifecycle_log WHERE market_id = #{marketId} ORDER BY scanned_at ASC")
    List<WeatherMarketLifecycleLogEntity> selectByMarketIdOrdered(@Param("marketId") String marketId);

    /**
     * 获取最新一批扫描的所有记录
     */
    @Select("SELECT * FROM weather_market_lifecycle_log WHERE batch_id = #{batchId}")
    List<WeatherMarketLifecycleLogEntity> selectByBatchId(@Param("batchId") String batchId);

    /**
     * 获取所有首次出现的记录（用于计算市场上线提前量）
     */
    @Select("SELECT * FROM weather_market_lifecycle_log WHERE first_seen = true AND first_seen_lead_hours IS NOT NULL ORDER BY scanned_at DESC")
    List<WeatherMarketLifecycleLogEntity> selectFirstSeenRecords();
}
