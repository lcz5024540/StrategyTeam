package com.metaverse.coin.strategy.web.model.smartwallet;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * W2-T6 Leader 行为漂移 web 视图 (基于 profile centroid + cosine 距离).
 * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十一节.
 */
@Data
@Builder
public class LeaderDriftView {

    private String leaderAddress;
    /** ACTIVE / DRIFTED / DRIFTED_SEVERE / COLD_START / DISABLED. */
    private String status;
    /** Profile 聚合窗口(天). */
    private int lookbackDays;
    /** Profile 样本数. */
    private int sampleCount;
    private int minSamples;
    /** Profile centroid (7 维数组, 直接 JSON 输出). */
    private double[] profileCentroid;
    /** 最近一笔 trade 的特征向量. */
    private double[] lastEventVector;
    /** centroid 与 lastEvent 的 cosine 距离 (0=完全一致, 1=正交, 2=反向). */
    private Double cosineDistance;
    /** 当前生效门槛. */
    private Double driftThreshold;
    private Double severeDriftThreshold;
    /** Profile 时间窗起止. */
    private Date profileFrom;
    private Date profileTo;
    private String reason;
}
