package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Immutable market and structure evidence captured before a trend entry decision. */
@Data
@TableName("htx_trend_factor_snapshot")
public class HtxTrendFactorSnapshotEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String snapshotKey;
    private String contractCode;
    private String period;
    private Long barTime;
    private String marketRegime;
    private String factorsJson;
    private LocalDateTime createdAt;
}
