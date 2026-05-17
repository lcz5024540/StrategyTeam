package com.metaverse.coin.strategy.web.model.smartwallet;

import lombok.Builder;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 第十二批 第五波 web 接口: 结算三方 diff 报表 web 视图.
 * 对应 job 端 ResolutionTriDiffService.ResolutionTriDiffReport.
 *
 * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十九节.
 */
@Data
@Builder
public class ResolutionTriDiffView {

    private Date generatedAt;
    private int lookbackHours;
    private int totalAuditRows;
    private int anomalyTotal;
    private int oursVsUmaCount;
    private int oursVsGammaCount;
    private int umaVsGammaCount;
    private int historyConflictCount;
    private List<String> conflictedMarketIds;
    private List<TriDiffInstance> topInstances;

    @Data
    @Builder
    public static class TriDiffInstance {
        private String marketId;
        private Date observedAt;
        private String diffType;                // OURS_VS_UMA / OURS_VS_GAMMA / UMA_VS_GAMMA / HISTORY_CONFLICT
        private String resolutionStatus;
        private String winningOutcome;
        private boolean gammaClosed;
        private String umaResolutionStatus;
        private boolean umaResolved;
    }
}
