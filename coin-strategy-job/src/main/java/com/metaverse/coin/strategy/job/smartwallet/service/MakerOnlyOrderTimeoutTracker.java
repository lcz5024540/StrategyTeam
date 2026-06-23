package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.PendingMakerOnlyTrackerMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.PendingMakerOnlyTrackerEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import javax.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * W2-T4 MAKER_ONLY 超时撤单跟踪器.
 *
 * <p><b>背景</b>: SignalRiskService 在 priceImpact 重段 (severeBps 300-500) 命中时,
 * 把 priceImpactExecutionMode 标记为 MAKER_ONLY, PolymarketOrderRequestFactory
 * 强制 MAKER. 但 MAKER 单子如果挂着不成交, 等于错过 leader 入场窗口 — 决议要求
 * 10s 超时主动撤单, 释放资金给下一笔.</p>
 *
 * <p><b>设计</b>: in-memory ConcurrentHashMap 跟踪 {@code (orderId → MakerOnlyEntry)},
 * 由 {@link LoggingPolymarketExecutionGateway} 在 LIVE 下单成功后调 {@link #register};
 * 一个 {@code @Scheduled(fixedDelay=2000)} 任务扫描 cache, 超时未撤单的逐一调
 * {@code PolymarketClobClientRouter.cancelOrder} 撤单, 撤单后从 cache 移除.</p>
 *
 * <p><b>F-P1-4 (2026-05-17 第四轮) 补完</b>: 增加 write-through 持久化到
 * {@code pending_maker_only_tracker} 表 + {@code @PostConstruct} 启动期回放，
 * 保证容器重启 / 滚动发布期间 MAKER_ONLY 挂单仍能按时取消，不依赖
 * {@code SmartWalletOrderPollingService} 兜底。共用 {@code execution-wallet.enable-pending-tracker-persistence}
 * 开关与 GTC tracker 同节奏；关闭时退化为纯 in-memory（与历史行为一致）。</p>
 *
 * <p><b>对开单效率 / 资金使用率 / 是否会开单 / 盈亏点 / 胜率</b>:
 * <ul>
 *   <li>开单效率: ⚪ 不变 — 仅影响"已下单未成交"的 MAKER_ONLY 单</li>
 *   <li>资金使用率: ⬆️ 微升 — 释放挂单占用的资金给下一笔信号</li>
 *   <li>是否会开单: ⚪ 不变 — 不影响新开单决策</li>
 *   <li>盈亏点: 🟡 中性偏正 — 避免"价格走过后还挂着无效单"造成的负偏差</li>
 *   <li>胜率: 🟡 中性 — MAKER 单挂着不成交本来就没参与胜率统计</li>
 * </ul>
 * 调度开关 {@code smart-wallet.signal-risk.maker-only-timeout-tracker-enabled} 默认 false,
 * 启用后才注册 / 扫描 / 撤单.</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十九节
 * 及 docs/polymarket/minimal-implementations-fix-plan.md §三 F-P1-4.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MakerOnlyOrderTimeoutTracker {

    private static final String LOG_PREFIX = "[MakerOnlyTimeout]";

    private final SmartWalletProperties properties;
    private final ExecutionWalletContextLoader walletContextLoader;
    private final PolymarketClobClientRouter clobClientRouter;

    /**
     * F-P1-4: mapper 可选注入，单元测试 / 持久化关闭时 bean 缺失也不应阻塞主流程。
     */
    @Autowired(required = false)
    private PendingMakerOnlyTrackerMapper trackerMapper;

    /**
     * orderId → entry, 内存索引.
     * <p>F-P1-4: 启动期由 {@link #replayPendingFromDb()} 从 {@code pending_maker_only_tracker}
     * 表回放重建（含进程崩溃前的存量），不再"重启即清空"。</p>
     */
    private final Map<String, MakerOnlyEntry> pending = new ConcurrentHashMap<>();

    /**
     * F-P1-4: 启动期回放 — 扫表 {@code status=PENDING} 重建内存 tracker。
     *
     * <p>仅在 {@code enable-pending-tracker-persistence=true} 且 mapper 注入成功时执行；
     * 任意失败均 swallow 退化为纯 in-memory（不阻塞应用启动）。</p>
     */
    @PostConstruct
    public void replayPendingFromDb() {
        if (!isEnabled() || !isPersistenceEnabled() || trackerMapper == null) {
            return;
        }
        try {
            QueryWrapper<PendingMakerOnlyTrackerEntity> q = new QueryWrapper<>();
            q.eq("status", "PENDING");
            List<PendingMakerOnlyTrackerEntity> rows = trackerMapper.selectList(q);
            int restored = 0;
            for (PendingMakerOnlyTrackerEntity row : rows) {
                if (row == null || row.getExternalOrderId() == null
                        || row.getExpireAt() == null) {
                    continue;
                }
                MakerOnlyEntry entry = new MakerOnlyEntry(
                        row.getExternalOrderId(),
                        null, // executionAddress 启动期未保存，scan 时通过 requireLiveContext 重新解析
                        row.getMakerStrategy(),
                        System.currentTimeMillis(),
                        row.getExpireAt().getTime());
                entry.persistenceId = row.getId();
                pending.put(row.getExternalOrderId(), entry);
                restored++;
            }
            log.info("{} action=startupReplay restored={} from pending_maker_only_tracker",
                    LOG_PREFIX, restored);
        } catch (RuntimeException e) {
            log.warn("{} action=startupReplay failed, running pure in-memory mode err={}",
                    LOG_PREFIX, e.toString());
        }
    }

    /** 注册一笔 MAKER_ONLY (或可选 MAKER_FIRST) 单, 待超时后撤. */
    public void register(String orderId, String executionAddress, String priceImpactMode) {
        if (!isEnabled()) return;
        if (orderId == null || orderId.isBlank()) return;
        if (priceImpactMode == null) return;
        if (!"MAKER_ONLY".equalsIgnoreCase(priceImpactMode)
                && !shouldTrackMakerFirst(priceImpactMode)) {
            return;
        }
        long timeoutMs = effectiveTimeoutMs();
        long now = System.currentTimeMillis();
        long expireAtMs = now + timeoutMs;
        MakerOnlyEntry entry = new MakerOnlyEntry(orderId, executionAddress, priceImpactMode, now, expireAtMs);

        // F-P1-4: write-through 持久化
        if (isPersistenceEnabled() && trackerMapper != null) {
            try {
                PendingMakerOnlyTrackerEntity row = new PendingMakerOnlyTrackerEntity()
                        .setOrderId(0L) // smart_wallet_order.id 此处未必可得，先占位
                        .setExternalOrderId(orderId)
                        .setMakerStrategy(priceImpactMode.toUpperCase())
                        .setPlacedAt(new Date(now))
                        .setExpireAt(new Date(expireAtMs))
                        .setFallbackAction("CANCEL")
                        .setStatus("PENDING")
                        .setCreatedAt(new Date(now))
                        .setUpdatedAt(new Date(now));
                trackerMapper.insert(row);
                entry.persistenceId = row.getId();
            } catch (RuntimeException e) {
                log.warn("{} action=register persistence-insert failed (continuing in-memory) orderId={} err={}",
                        LOG_PREFIX, orderId, e.toString());
            }
        }

        pending.put(orderId, entry);
        log.info("{} action=register orderId={} mode={} timeoutMs={} pending={}",
                LOG_PREFIX, orderId, priceImpactMode, timeoutMs, pending.size());
    }

    /** 主动取消跟踪 (订单已成交 / 已被外部撤单 / 等情况, 由调用方通知). */
    public void unregister(String orderId) {
        if (orderId == null) return;
        MakerOnlyEntry removed = pending.remove(orderId);
        if (removed == null) return;
        markPersisted(removed, "RESOLVED", "unregister: external resolution");
    }

    /** 扫描间隔可配; 默认 2 秒. */
    @Scheduled(fixedDelayString = "${smart-wallet.signal-risk.maker-only-scan-interval-ms:2000}")
    public void scan() {
        if (!isEnabled()) return;
        if (pending.isEmpty()) return;
        long now = System.currentTimeMillis();
        int cancelled = 0;
        int failed = 0;
        for (Iterator<Map.Entry<String, MakerOnlyEntry>> it = pending.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, MakerOnlyEntry> e = it.next();
            MakerOnlyEntry entry = e.getValue();
            if (now < entry.expireAtMs) continue;
            try {
                ExecutionWalletContextLoader.ExecutionWalletContext wallet =
                        walletContextLoader.requireLiveContext();
                Map<String, Object> resp = clobClientRouter.cancelOrder(wallet, entry.orderId);
                cancelled++;
                markPersisted(entry, "EXPIRED", String.valueOf(resp));
                log.info("{} action=cancelled orderId={} mode={} ageMs={} resp={}",
                        LOG_PREFIX, entry.orderId, entry.priceImpactMode,
                        now - entry.registeredAtMs, resp);
            } catch (RuntimeException ex) {
                failed++;
                markPersisted(entry, "FAILED", truncate(ex.toString()));
                log.warn("{} action=cancel-failed orderId={} mode={} reason={}",
                        LOG_PREFIX, entry.orderId, entry.priceImpactMode, ex.toString());
            } finally {
                it.remove();
            }
        }
        if (cancelled + failed > 0) {
            log.info("{} action=scan-done cancelled={} failed={} pending={}",
                    LOG_PREFIX, cancelled, failed, pending.size());
        }
    }

    private void markPersisted(MakerOnlyEntry entry, String status, String resolvedReason) {
        if (!isPersistenceEnabled() || trackerMapper == null || entry.persistenceId == null) return;
        try {
            PendingMakerOnlyTrackerEntity row = new PendingMakerOnlyTrackerEntity()
                    .setId(entry.persistenceId)
                    .setStatus(status)
                    .setResolvedAt(new Date())
                    .setResolvedReason(truncate(resolvedReason))
                    .setUpdatedAt(new Date());
            trackerMapper.updateById(row);
        } catch (RuntimeException e) {
            log.warn("{} action=mark-persisted update failed orderId={} status={} err={}",
                    LOG_PREFIX, entry.orderId, status, e.toString());
        }
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 250 ? s.substring(0, 250) : s;
    }

    private boolean isEnabled() {
        SmartWalletProperties.SignalRisk risk = properties == null ? null : properties.getSignalRisk();
        return risk != null && risk.isMakerOnlyTimeoutTrackerEnabled();
    }

    /** F-P1-4: 与 GTC tracker 共用持久化开关。 */
    private boolean isPersistenceEnabled() {
        SmartWalletProperties.ExecutionWallet ew =
                properties == null ? null : properties.getExecutionWallet();
        return ew != null && ew.isEnablePendingTrackerPersistence();
    }

    private boolean shouldTrackMakerFirst(String mode) {
        SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
        return risk != null && risk.isMakerOnlyTrackMakerFirst()
                && "MAKER_FIRST".equalsIgnoreCase(mode);
    }

    private long effectiveTimeoutMs() {
        SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
        int seconds = risk == null ? 10 : Math.max(1, risk.getPriceImpactMakerOnlyTimeoutSeconds());
        return seconds * 1000L;
    }

    /** 跟踪条目, 极简; persistenceId 可变以便 register 后回填. */
    static final class MakerOnlyEntry {
        /** F-P1-4: 持久化记录的主键，scan / unregister 时按此更新表状态。 */
        Long persistenceId;
        final String orderId;
        final String executionAddress;
        final String priceImpactMode;
        final long registeredAtMs;
        final long expireAtMs;

        MakerOnlyEntry(String orderId, String executionAddress, String priceImpactMode,
                       long registeredAtMs, long expireAtMs) {
            this.orderId = orderId;
            this.executionAddress = executionAddress;
            this.priceImpactMode = priceImpactMode;
            this.registeredAtMs = registeredAtMs;
            this.expireAtMs = expireAtMs;
        }
    }

    /** 测试 / 运维: 当前 pending 计数. */
    public int pendingCount() {
        return pending.size();
    }
}
