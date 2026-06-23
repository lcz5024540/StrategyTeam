package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.engine.monitor.AlertType;
import com.metaverse.coin.strategy.job.smartwallet.audit.SmartWalletAuditAlertSink;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * T-B7：事件摄取时延监控服务。
 *
 * <p>周期统计 {@code smart_wallet_trade_event} 在回看窗口内的摄取时延（leader 成交时间
 * {@code opened_at} → 我方入库时间 {@code created_at}，单位秒）的 AVG / P90，当 P90 超过阈值时
 * 经统一审计告警 Sink（strategy_alert + 飞书）外发 {@link AlertType#HEALTH_INGEST_LAG_HIGH}。</p>
 *
 * <p>背景：摄取滞后会让信号越过硬新鲜度门（{@code G5_SIGNAL_STALE_INGEST}），此前「事件滞后
 * 数十分钟」在 DB 里清晰可见却无任何指标盯它。本服务补上「先发现」一环——只发现、不改阈值、
 * 不下单。</p>
 *
 * <p><b>对开单效率 / 资金使用率 / 是否会开单 / 盈亏点 / 胜率均无任何直接影响</b>——纯只读
 * 聚合 + 内存计算，不写库不下单不调风控。外发失败 swallow，不阻断调度。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeEventIngestLagService {

    private static final int DEFAULT_WINDOW_HOURS = 4;
    private static final int DEFAULT_WARN_P90_SECONDS = 600;
    private static final int DEFAULT_MAX_SAMPLES = 20000;

    private final SmartWalletProperties properties;
    private final SmartWalletTradeEventMapper tradeEventMapper;
    private final SmartWalletAuditAlertSink auditAlertSink;

    /** Job 调度的实际工作：跑一次统计，输出日志，P90 超阈值时 WARN 外发。 */
    public IngestLagReport runScheduled() {
        SmartWalletProperties.Audit audit = properties.getAudit();
        int windowHours = positiveOr(audit == null ? null : audit.getIngestLagWindowHours(), DEFAULT_WINDOW_HOURS);
        int warnP90 = positiveOr(audit == null ? null : audit.getIngestLagWarnP90Seconds(), DEFAULT_WARN_P90_SECONDS);
        int maxSamples = positiveOr(audit == null ? null : audit.getIngestLagMaxSamples(), DEFAULT_MAX_SAMPLES);

        Date since = Date.from(Instant.now().minus(Duration.ofHours(windowHours)));
        List<Long> lags = tradeEventMapper.selectIngestLagSeconds(since, maxSamples);

        IngestLagReport report = computeReport(lags, windowHours, warnP90, maxSamples);

        if (report.getSampleCount() == 0) {
            log.info("[TradeEventIngestLag] windowHours={} no samples in window, skip", windowHours);
            return report;
        }

        log.info("[TradeEventIngestLag] windowHours={} samples={} avgSec={} p90Sec={} maxSec={} "
                        + "warnP90Sec={} exceeded={} truncated={}",
                report.getWindowHours(), report.getSampleCount(), report.getAvgSeconds(),
                report.getP90Seconds(), report.getMaxSeconds(), report.getWarnP90Seconds(),
                report.isExceeded(), report.isTruncated());

        if (report.isExceeded()) {
            publishAlert(report);
        }
        return report;
    }

    private IngestLagReport computeReport(List<Long> lags, int windowHours, int warnP90, int maxSamples) {
        IngestLagReport report = new IngestLagReport()
                .setWindowHours(windowHours)
                .setWarnP90Seconds(warnP90);
        if (lags == null || lags.isEmpty()) {
            return report.setSampleCount(0);
        }
        List<Long> sorted = new ArrayList<>(lags);
        sorted.sort(Long::compareTo);

        long sum = 0L;
        for (long v : sorted) {
            sum += v;
        }
        long p90 = percentile(sorted, 90);
        return report.setSampleCount(sorted.size())
                .setAvgSeconds(Math.round((double) sum / sorted.size()))
                .setP90Seconds(p90)
                .setMaxSeconds(sorted.get(sorted.size() - 1))
                .setTruncated(sorted.size() >= maxSamples)
                .setExceeded(p90 > warnP90);
    }

    private void publishAlert(IngestLagReport report) {
        try {
            String title = String.format("事件摄取延迟 P90 超阈值：%ds > %ds",
                    report.getP90Seconds(), report.getWarnP90Seconds());
            String message = String.format(
                    "近 %dh smart_wallet_trade_event 摄取时延：样本=%d，AVG=%ds，P90=%ds，MAX=%ds，阈值 P90=%ds。"
                            + "摄取滞后会让信号越过硬新鲜度门（G5_SIGNAL_STALE_INGEST），建议先压缩 HOT 同步时延、勿单纯调阈值。",
                    report.getWindowHours(), report.getSampleCount(), report.getAvgSeconds(),
                    report.getP90Seconds(), report.getMaxSeconds(), report.getWarnP90Seconds());
            auditAlertSink.publish(AlertType.HEALTH_INGEST_LAG_HIGH, title, message,
                    BigDecimal.valueOf(report.getP90Seconds()),
                    BigDecimal.valueOf(report.getWarnP90Seconds()));
        } catch (RuntimeException e) {
            log.warn("[TradeEventIngestLag] alert publish failed (swallowed) err={}", e.toString());
        }
    }

    /** 线性插值分位数；{@code sortedAsc} 已升序，{@code pct} ∈ [0,100]。 */
    private static long percentile(List<Long> sortedAsc, int pct) {
        if (sortedAsc.isEmpty()) {
            return 0L;
        }
        if (sortedAsc.size() == 1) {
            return sortedAsc.get(0);
        }
        double idx = (pct / 100.0) * (sortedAsc.size() - 1);
        int lo = (int) Math.floor(idx);
        int hi = (int) Math.ceil(idx);
        if (lo == hi) {
            return sortedAsc.get(lo);
        }
        double interp = sortedAsc.get(lo) + (sortedAsc.get(hi) - sortedAsc.get(lo)) * (idx - lo);
        return Math.round(interp);
    }

    private static int positiveOr(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    /** 一次摄取时延统计结果（便于 Job 串联与单测断言）。 */
    @Data
    @Accessors(chain = true)
    public static class IngestLagReport {
        private int windowHours;
        private int sampleCount;
        private long avgSeconds;
        private long p90Seconds;
        private long maxSeconds;
        private int warnP90Seconds;
        private boolean exceeded;
        /** 样本数触达 maxSamples 上限、可能被截断。 */
        private boolean truncated;
    }
}
