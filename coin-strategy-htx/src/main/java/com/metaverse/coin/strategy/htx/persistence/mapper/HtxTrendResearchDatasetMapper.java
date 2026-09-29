package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendResearchDatasetEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface HtxTrendResearchDatasetMapper extends BaseMapper<HtxTrendResearchDatasetEntity> {

    @Select("SELECT id, dataset_key, source_kind, source_reference, captured_at, input_start_at, input_end_at, "
            + "contracts_json, periods_json, input_checksum, input_integrity, status, purpose, provenance_json, created_at "
            + "FROM htx_trend_research_dataset WHERE dataset_key = #{datasetKey}")
    HtxTrendResearchDatasetEntity findByDatasetKey(@Param("datasetKey") String datasetKey);

    @Update("UPDATE htx_trend_research_dataset SET status = #{status}, provenance_json = #{provenanceJson} "
            + "WHERE dataset_key = #{datasetKey} AND status = 'FREEZING'")
    int finishFreeze(@Param("datasetKey") String datasetKey,
                     @Param("status") String status,
                     @Param("provenanceJson") String provenanceJson);
}
