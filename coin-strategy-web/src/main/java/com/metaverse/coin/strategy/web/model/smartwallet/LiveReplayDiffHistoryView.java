package com.metaverse.coin.strategy.web.model.smartwallet;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.LiveReplayDiffHistoryEntity;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;

/**
 * 下一轮 #7 Live↔Replay diff 历史 web 视图.
 * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十七节.
 */
@Data
@Builder
public class LiveReplayDiffHistoryView {

    private LocalDate asOfDate;
    private Date windowStart;
    private Date windowEnd;
    private Integer replayScannedEvents;
    private Integer replayAcceptedEvents;
    private BigDecimal replayRealizedPnlUsdc;
    private String replayPolicyTag;
    private Integer liveTaskTotal;
    private Integer liveTaskNonSkipped;
    private Integer liveTaskSkipped;
    private BigDecimal liveRealizedPnlUsdc;
    private Integer signalAcceptedDiff;
    private BigDecimal signalMissPct;
    private BigDecimal pnlDiffUsdc;
    private BigDecimal pnlDiffBps;
    private Integer pnlDiffThresholdBps;
    private BigDecimal signalMissThresholdPct;
    private Boolean pnlDiffExceeded;
    private Boolean signalMissExceeded;

    public static LiveReplayDiffHistoryView from(LiveReplayDiffHistoryEntity e) {
        if (e == null) return null;
        return LiveReplayDiffHistoryView.builder()
                .asOfDate(e.getAsOfDate())
                .windowStart(e.getWindowStart())
                .windowEnd(e.getWindowEnd())
                .replayScannedEvents(e.getReplayScannedEvents())
                .replayAcceptedEvents(e.getReplayAcceptedEvents())
                .replayRealizedPnlUsdc(e.getReplayRealizedPnlUsdc())
                .replayPolicyTag(e.getReplayPolicyTag())
                .liveTaskTotal(e.getLiveTaskTotal())
                .liveTaskNonSkipped(e.getLiveTaskNonSkipped())
                .liveTaskSkipped(e.getLiveTaskSkipped())
                .liveRealizedPnlUsdc(e.getLiveRealizedPnlUsdc())
                .signalAcceptedDiff(e.getSignalAcceptedDiff())
                .signalMissPct(e.getSignalMissPct())
                .pnlDiffUsdc(e.getPnlDiffUsdc())
                .pnlDiffBps(e.getPnlDiffBps())
                .pnlDiffThresholdBps(e.getPnlDiffThresholdBps())
                .signalMissThresholdPct(e.getSignalMissThresholdPct())
                .pnlDiffExceeded(e.getPnlDiffExceeded())
                .signalMissExceeded(e.getSignalMissExceeded())
                .build();
    }
}
