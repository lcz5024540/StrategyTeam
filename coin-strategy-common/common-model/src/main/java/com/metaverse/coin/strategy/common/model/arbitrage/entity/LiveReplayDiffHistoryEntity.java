package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;

/**
 * 下一轮 #7: Live↔Replay diff 日级历史.
 * 由 LiveReplayDiffReportService.runDailyDiff() 写入, web 端可查 trend.
 * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十七节.
 */
@Data
@Accessors(chain = true)
@TableName("live_replay_diff_history")
public class LiveReplayDiffHistoryEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

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

    private Date createdAt;
    private Date updatedAt;
}
