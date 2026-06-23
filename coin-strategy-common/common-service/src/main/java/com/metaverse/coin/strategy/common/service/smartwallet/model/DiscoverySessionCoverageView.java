package com.metaverse.coin.strategy.common.service.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
@Builder
public class DiscoverySessionCoverageView {
    private Date generatedAt;
    private int lookbackDays;
    @Builder.Default
    private List<DiscoverySessionCoverageBucketView> profileBuckets = new ArrayList<>();
    @Builder.Default
    private List<DiscoverySessionCoverageBucketView> watchlistBuckets = new ArrayList<>();
}
