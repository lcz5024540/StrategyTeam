package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.common.service.smartwallet.model.SmartWalletManualAllowlistRequest;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletManualAllowlistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 2026-05-19 ray@cowork: 每日自动提名"本周活跃 + 高 PnL + 高胜率" leader 入 manual_allowlist.
 *
 * <p>背景: 历史上 manual_allowlist 主要靠运维人工 SQL INSERT. 在 5/17 排查中发现
 * 我手动加的 TOP 7 钱包 (按 historical PnL 排序) 实际 7d 内零交易, 浪费了 syncHotWalletTrades
 * 的 quota, 也没贡献任何 trade_event. 用户明确要求: "需要找真正活跃的地址, 并且 pnl 高的".</p>
 *
 * <p>本 Job 每天 (默认 06:00 Asia/Shanghai) 跑一次, 从 {@code smart_wallet_profile} 表筛:
 * <ul>
 *   <li>realizedPnlUsdc &ge; {@code minPnlUsdc}</li>
 *   <li>totalTrades &ge; {@code minLifetimeTrades} (兜底, 排除单笔暴富钱包)</li>
 *   <li>winRate &ge; {@code minWinRate}</li>
 *   <li>maxDrawdownRate &le; {@code maxDrawdown}</li>
 *   <li>{@code recentTradeCount7d &ge; minRecent7dTrades} <b>或</b> {@code lastTradeAt} 在
 *       {@code activeWithinDays} 天内 (本周活跃)</li>
 * </ul>
 * 然后按 winRate 排序取 TOP N, upsert 到 manual_allowlist (PAPER 模式, 30 天过期, stake_multiplier 0.5).</p>
 *
 * <p>开关: {@code smart-wallet.auto-allowlist.enabled} (默认 false). 不打开就 noop, 不影响现有流程.</p>
 *
 * <p>注意: 这个 Job 只 upsert 不删除. 过期清理依然走 {@code SmartWalletManualAllowlistService#cleanupExpired()}.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeeklyTopLeaderAutoAllowlistJob {

    private static final long MILLIS_PER_DAY = 24L * 60L * 60L * 1000L;

    private final SmartWalletProperties properties;
    private final SmartWalletProfileStoreService profileStoreService;
    private final SmartWalletManualAllowlistService manualAllowlistService;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(cron = "${smart-wallet.auto-allowlist.cron:0 0 6 * * *}",
            zone = "${smart-wallet.auto-allowlist.zone:Asia/Shanghai}")
    public void run() {
        if (!properties.isEnabled()) {
            return;
        }
        SmartWalletProperties.AutoAllowlistConfig cfg = properties.getAutoAllowlist();
        if (cfg == null || !cfg.isEnabled()) {
            log.debug("[WeeklyTopLeaderAutoAllowlistJob] disabled, skip");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("[WeeklyTopLeaderAutoAllowlistJob] previous run still in progress, skip this tick");
            return;
        }
        long startedMs = System.currentTimeMillis();
        try {
            runOnce(cfg);
        } catch (RuntimeException e) {
            log.error("[WeeklyTopLeaderAutoAllowlistJob] unexpected failure: {}", e.toString(), e);
        } finally {
            running.set(false);
            log.info("[WeeklyTopLeaderAutoAllowlistJob] tick done in {} ms", System.currentTimeMillis() - startedMs);
        }
    }

    /** 暴露给手动触发 / 单测; 与 cron 共享同一段筛选 & upsert 逻辑. */
    public Stats runOnce(SmartWalletProperties.AutoAllowlistConfig cfg) {
        if (cfg == null) {
            return Stats.empty();
        }
        List<SmartWalletProfileEntity> all = profileStoreService.listAll();
        if (all == null || all.isEmpty()) {
            log.info("[WeeklyTopLeaderAutoAllowlistJob] empty profile list, skip");
            return Stats.empty();
        }
        Date now = new Date();
        List<SmartWalletProfileEntity> candidates = new ArrayList<>();
        int filterPnl = 0;
        int filterTrades = 0;
        int filterWinRate = 0;
        int filterDrawdown = 0;
        int filterInactive = 0;

        for (SmartWalletProfileEntity p : all) {
            if (p == null || p.getWalletAddress() == null) {
                continue;
            }
            BigDecimal pnl = p.getRealizedPnlUsdc();
            if (pnl == null || pnl.compareTo(cfg.getMinPnlUsdc()) < 0) {
                filterPnl++;
                continue;
            }
            Integer trades = p.getTotalTrades();
            if (trades == null || trades < cfg.getMinLifetimeTrades()) {
                filterTrades++;
                continue;
            }
            BigDecimal winRate = p.getWinRate();
            if (winRate == null || winRate.compareTo(cfg.getMinWinRate()) < 0) {
                filterWinRate++;
                continue;
            }
            BigDecimal drawdown = p.getMaxDrawdownRate();
            if (drawdown != null && drawdown.compareTo(cfg.getMaxDrawdown()) > 0) {
                filterDrawdown++;
                continue;
            }
            if (!isRecentlyActive(p, cfg, now)) {
                filterInactive++;
                continue;
            }
            candidates.add(p);
        }

        candidates.sort(Comparator
                .comparing((SmartWalletProfileEntity p) -> nullSafe(p.getWinRate())).reversed()
                .thenComparing(p -> nullSafe(p.getRealizedPnlUsdc()), Comparator.reverseOrder()));

        int topN = Math.max(cfg.getTopN(), 0);
        List<SmartWalletProfileEntity> picked = candidates.size() > topN
                ? new ArrayList<>(candidates.subList(0, topN))
                : candidates;

        int inserted = 0;
        int updated = 0;
        int skipped = 0;
        int failed = 0;

        for (SmartWalletProfileEntity p : picked) {
            String addr = p.getWalletAddress();
            try {
                boolean alreadyActive = manualAllowlistService.isActive(addr);
                if (alreadyActive && !cfg.isOverwriteExisting()) {
                    skipped++;
                    log.info("[WeeklyTopLeaderAutoAllowlistJob] {} already active in allowlist, skip", addr);
                    continue;
                }
                SmartWalletManualAllowlistRequest req = new SmartWalletManualAllowlistRequest();
                req.setWalletAddress(addr);
                req.setStatus(SmartWalletManualAllowlistService.STATUS_ACTIVE);
                req.setAllowedExecutionModes(normalizeMode(cfg.getDefaultMode()));
                req.setStakeMultiplier(cfg.getStakeMultiplier());
                req.setMaxLiveOrdersPerDay(cfg.getMaxLiveOrdersPerDay());
                req.setMaxLiveOrdersPerRun(cfg.getMaxLiveOrdersPerRun());
                req.setExpiresAt(new Date(now.getTime() + (long) cfg.getExpirationDays() * MILLIS_PER_DAY));
                req.setReason(buildReason(cfg.getReasonPrefix(), p));
                req.setCreatedBy(cfg.getCreatedBy());
                manualAllowlistService.upsert(addr, req);
                if (alreadyActive) {
                    updated++;
                } else {
                    inserted++;
                }
                log.info("[WeeklyTopLeaderAutoAllowlistJob] upsert addr={} pnl={} trades={} winRate={} recent7d={}",
                        addr, p.getRealizedPnlUsdc(), p.getTotalTrades(), p.getWinRate(), p.getRecentTradeCount7d());
            } catch (RuntimeException e) {
                failed++;
                log.warn("[WeeklyTopLeaderAutoAllowlistJob] upsert {} failed: {}", addr, e.toString());
            }
        }

        log.info("[WeeklyTopLeaderAutoAllowlistJob] summary: scanned={} candidates={} picked={} inserted={} updated={} skipped={} failed={} | "
                        + "rejects: pnl={} trades={} winRate={} drawdown={} inactive={}",
                all.size(), candidates.size(), picked.size(), inserted, updated, skipped, failed,
                filterPnl, filterTrades, filterWinRate, filterDrawdown, filterInactive);

        Stats stats = new Stats();
        stats.scanned = all.size();
        stats.candidates = candidates.size();
        stats.picked = picked.size();
        stats.inserted = inserted;
        stats.updated = updated;
        stats.skipped = skipped;
        stats.failed = failed;
        return stats;
    }

    private boolean isRecentlyActive(SmartWalletProfileEntity p,
                                     SmartWalletProperties.AutoAllowlistConfig cfg,
                                     Date now) {
        Integer recent7d = p.getRecentTradeCount7d();
        if (cfg.getMinRecent7dTrades() > 0 && recent7d != null && recent7d >= cfg.getMinRecent7dTrades()) {
            return true;
        }
        Date lastTradeAt = p.getLastTradeAt();
        if (lastTradeAt == null) {
            // 没有 7d 数据也没有 lastTradeAt → 当作不活跃
            return cfg.getMinRecent7dTrades() <= 0;
        }
        long cutoff = now.getTime() - (long) cfg.getActiveWithinDays() * MILLIS_PER_DAY;
        return lastTradeAt.getTime() >= cutoff;
    }

    private String normalizeMode(String mode) {
        if (mode == null) {
            return SmartWalletManualAllowlistService.MODE_PAPER;
        }
        String upper = mode.trim().toUpperCase();
        switch (upper) {
            case "LIVE":
                return SmartWalletManualAllowlistService.MODE_LIVE;
            case "BOTH":
                return SmartWalletManualAllowlistService.MODE_BOTH;
            case "PAPER":
            default:
                return SmartWalletManualAllowlistService.MODE_PAPER;
        }
    }

    private String buildReason(String prefix, SmartWalletProfileEntity p) {
        String base = prefix == null ? "AUTO_WEEKLY_TOP_LEADER" : prefix;
        return String.format("%s | pnl=%s trades=%s winRate=%s recent7d=%s",
                base,
                p.getRealizedPnlUsdc(),
                p.getTotalTrades(),
                p.getWinRate(),
                p.getRecentTradeCount7d());
    }

    private static BigDecimal nullSafe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 简单的统计 DTO; 给单测 / 手动触发用. */
    public static class Stats {
        public int scanned;
        public int candidates;
        public int picked;
        public int inserted;
        public int updated;
        public int skipped;
        public int failed;

        static Stats empty() {
            return new Stats();
        }
    }
}
