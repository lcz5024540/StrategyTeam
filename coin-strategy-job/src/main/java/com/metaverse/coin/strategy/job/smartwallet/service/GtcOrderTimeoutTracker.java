package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.PendingOrderTimeoutTrackerMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.PendingOrderTimeoutTrackerEntity;
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
 * F-P1-3: LIVE GTC 全局超时取消跟踪器。
 *
 * <p><b>背景</b>: 当前仅 {@link MakerOnlyOrderTimeoutTracker} 覆盖
 * MAKER_ONLY / MAKER_FIRST 模式 (≈10s 超时)；普通 TAKER GTC 单挂久了占资金、
 * leader 价格走过后还在挂着的过期单可能被撮合到不利价。本 tracker 给所有 GTC 单
 * 加一个 {@code gtc-default-timeout-seconds=60} 的全局兜底。</p>
 *
 * <p><b>对开单效率 / 资金使用率 / 是否会开单 / 盈亏点 / 胜率</b>:
 * <ul>
 *   <li>开单效率: ⚪ 不变 — 仅影响 "已下单未成交" 的存量单；</li>
 *   <li>资金使用率: ⬆️ — 长期挂单按时取消，资金回流；</li>
 *   <li>是否会开单: ⚪ 不变 — 不参与新开单决策；</li>
 *   <li>盈亏点: 🟡 中性偏正 — 避免过期挂单成交到不利价；</li>
 *   <li>胜率: 🟡 中性。</li>
 * </ul>
 * 调度开关 {@code smart-wallet.execution-wallet.enable-gtc-global-timeout} 默认 false，
 * 启用后才注册 / 扫描 / 撤单 / 写表。
 * 持久化开关 {@code execution-wallet.enable-pending-tracker-persistence} 默认 true，
 * 关闭时退化为纯 in-memory（与 MakerOnly 旧版同行为）。</p>
 *
 * <p>DDL: {@code sql/polymarket/2026-05-17__p1_tracker_persistence_draft.sql}.</p>
 *
 * <p>详见 docs/polymarket/minimal-implementations-fix-plan.md §三 F-P1-3。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GtcOrderTimeoutTracker {

    private static final String LOG_PREFIX = "[GtcTimeout]";

    private final SmartWalletProperties properties;
    private final ExecutionWalletContextLoader walletContextLoader;
    private final PolymarketClobClientRouter clobClientRouter;

    /** mapper 可选注入：单元测试 / 持久化关闭时 bean 缺失也不应阻塞主流程。 */
    @Autowired(required = false)
    private PendingOrderTimeoutTrackerMapper trackerMapper;

    /**
     * orderId → entry，in-memory 索引。
     * <p>启动期由 {@link #replayPendingFromDb()} 从表回放重建（含进程崩溃前的存量）。</p>
     */
    private final Map<String, GtcEntry> pending = new ConcurrentHashMap<>();

    /** 启动期回放：扫表中 cancelStatus=PENDING 的记录，重建内存 tracker。 */
    @PostConstruct
    public void replayPendingFromDb() {
        // Fix 5: 任一种 timeout 启用都 replay (覆盖入场 BUY 重启场景)
        if (!isAnyTimeoutEnabledForReplay() || !isPersistenceEnabled() || trackerMapper == null) {
            return;
        }
        try {
            QueryWrapper<PendingOrderTimeoutTrackerEntity> q = new QueryWrapper<>();
            q.eq("cancel_status", "PENDING");
            List<PendingOrderTimeoutTrackerEntity> rows = trackerMapper.selectList(q);
            int restored = 0;
            for (PendingOrderTimeoutTrackerEntity row : rows) {
                if (row == null || row.getExternalOrderId() == null
                        || row.getExpireAt() == null) {
                    continue;
                }
                pending.put(row.getExternalOrderId(), new GtcEntry(
                        row.getId(),
                        row.getOrderId(),
                        row.getExternalOrderId(),
                        null, // executionAddress 启动期未保存，撤单时再 require LIVE wallet
                        row.getOrderStrategy(),
                        System.currentTimeMillis(),
                        row.getExpireAt().getTime(),
                        row.getCancelAttempts() == null ? 0 : row.getCancelAttempts()));
                restored++;
            }
            log.info("{} action=startupReplay restored={} from pending_order_timeout_tracker", LOG_PREFIX, restored);
        } catch (RuntimeException e) {
            log.warn("{} action=startupReplay failed, will run in pure in-memory mode err={}",
                    LOG_PREFIX, e.toString());
        }
    }

    /**
     * 注册一笔 GTC 单（LIVE 网关成功下单后调用）。
     *
     * @param orderId          smart_wallet_order.id（数据库主键，nullable 允许）
     * @param externalOrderId  CLOB 远端 order id（撤单时使用，required）
     * @param executionAddress 执行钱包地址，撤单时优先使用
     * @param orderStrategy    TAKER / MAKER / MAKER_ONLY / MAKER_FIRST
     * @param marketId         conditionId（持久化用，便于审计）
     * @param outcome          YES / NO
     * @param followTaskId     可选关联任务 id
     */
    public void register(Long orderId, String externalOrderId, String executionAddress,
                         String orderStrategy, String marketId, String outcome, Long followTaskId) {
        if (!isEnabled()) return;
        registerInternal(orderId, externalOrderId, executionAddress,
                orderStrategy, marketId, outcome, followTaskId, effectiveTimeoutMs());
    }

    /**
     * Fix 5: 自定义超时的 register, 不检查 isEnabled, 由调用方决定 (例如入场 BUY 5min 单走这条路径)。
     *
     * <p>调用方需自行用 {@code enableEntryBuyTimeout} 等独立开关 guard, 防止误注册。</p>
     */
    public void registerWithTimeout(Long orderId, String externalOrderId, String executionAddress,
                                    String orderStrategy, String marketId, String outcome,
                                    Long followTaskId, long timeoutMs) {
        registerInternal(orderId, externalOrderId, executionAddress,
                orderStrategy, marketId, outcome, followTaskId, timeoutMs);
    }

    private void registerInternal(Long orderId, String externalOrderId, String executionAddress,
                                  String orderStrategy, String marketId, String outcome,
                                  Long followTaskId, long timeoutMs) {
        if (externalOrderId == null || externalOrderId.isBlank()) return;
        long expireAtMs = System.currentTimeMillis() + timeoutMs;
        GtcEntry entry = new GtcEntry(null, orderId, externalOrderId, executionAddress,
                orderStrategy, System.currentTimeMillis(), expireAtMs, 0);
        if (isPersistenceEnabled() && trackerMapper != null) {
            try {
                PendingOrderTimeoutTrackerEntity row = new PendingOrderTimeoutTrackerEntity()
                        .setOrderId(orderId == null ? 0L : orderId)
                        .setExternalOrderId(externalOrderId)
                        .setFollowTaskId(followTaskId)
                        .setMarketId(marketId)
                        .setOutcome(outcome)
                        .setOrderStrategy(orderStrategy)
                        .setExpireAt(new Date(expireAtMs))
                        .setCancelStatus("PENDING")
                        .setCancelAttempts(0)
                        .setCreatedAt(new Date())
                        .setUpdatedAt(new Date());
                trackerMapper.insert(row);
                entry.persistenceId = row.getId();
            } catch (RuntimeException e) {
                log.warn("{} action=register persistence-insert failed (continuing in-memory) externalOrderId={} err={}",
                        LOG_PREFIX, externalOrderId, e.toString());
            }
        }
        pending.put(externalOrderId, entry);
        log.info("{} action=register orderId={} externalOrderId={} strategy={} timeoutMs={} pending={}",
                LOG_PREFIX, orderId, externalOrderId, orderStrategy, timeoutMs, pending.size());
    }

    /** 主动取消跟踪（订单已成交 / 已被外部撤单 / 等情况，由调用方通知）。 */
    public void unregister(String externalOrderId, String reason) {
        if (externalOrderId == null) return;
        GtcEntry entry = pending.remove(externalOrderId);
        if (entry == null) return;
        if (isPersistenceEnabled() && trackerMapper != null && entry.persistenceId != null) {
            try {
                PendingOrderTimeoutTrackerEntity row = new PendingOrderTimeoutTrackerEntity()
                        .setId(entry.persistenceId)
                        .setCancelStatus("SUPERSEDED")
                        .setLastAttemptAt(new Date())
                        .setCancelError(reason)
                        .setUpdatedAt(new Date());
                trackerMapper.updateById(row);
            } catch (RuntimeException e) {
                log.warn("{} action=unregister persistence-update failed externalOrderId={} err={}",
                        LOG_PREFIX, externalOrderId, e.toString());
            }
        }
    }

    /** 扫描间隔可配，默认 3 秒。 */
    @Scheduled(fixedDelayString = "${smart-wallet.execution-wallet.gtc-cancel-scan-interval-ms:3000}")
    public void scan() {
        // Fix 5: scan 同时服务 GTC 全局超时 + 入场 BUY 超时, 任一开启都跑
        if (!isAnyTimeoutEnabled()) return;
        if (pending.isEmpty()) return;
        long now = System.currentTimeMillis();
        int cancelled = 0;
        int failed = 0;
        int abandoned = 0;
        SmartWalletProperties.ExecutionWallet ewCfg = properties.getExecutionWallet();
        int gtcMaxAttempts = Math.max(1, ewCfg.getGtcCancelMaxAttempts());
        int entryBuyMaxAttempts = Math.max(1, ewCfg.getEntryBuyCancelMaxAttempts());
        for (Iterator<Map.Entry<String, GtcEntry>> it = pending.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, GtcEntry> e = it.next();
            GtcEntry entry = e.getValue();
            if (now < entry.expireAtMs) continue;
            // Fix 5: 入场 BUY 用独立 maxAttempts (默认 2, 比 GTC 3 更保守)
            int maxAttempts = "ENTRY_BUY".equals(entry.orderStrategy) ? entryBuyMaxAttempts : gtcMaxAttempts;
            try {
                ExecutionWalletContextLoader.ExecutionWalletContext wallet =
                        walletContextLoader.requireLiveContext();
                Map<String, Object> resp = clobClientRouter.cancelOrder(wallet, entry.externalOrderId);
                cancelled++;
                markPersisted(entry, "CANCELLED", String.valueOf(resp));
                it.remove();
                log.info("{} action=cancelled externalOrderId={} strategy={} ageMs={} resp={}",
                        LOG_PREFIX, entry.externalOrderId, entry.orderStrategy,
                        now - entry.registeredAtMs, resp);
            } catch (RuntimeException ex) {
                entry.cancelAttempts++;
                if (entry.cancelAttempts >= maxAttempts) {
                    abandoned++;
                    markPersisted(entry, "CANCEL_FAILED", truncate(ex.toString()));
                    it.remove();
                    log.warn("{} action=abandoned externalOrderId={} strategy={} attempts={} lastErr={}",
                            LOG_PREFIX, entry.externalOrderId, entry.orderStrategy,
                            entry.cancelAttempts, ex.toString());
                } else {
                    failed++;
                    markPersisted(entry, "PENDING", truncate(ex.toString()));
                    log.warn("{} action=cancel-retry externalOrderId={} strategy={} attempts={} err={}",
                            LOG_PREFIX, entry.externalOrderId, entry.orderStrategy,
                            entry.cancelAttempts, ex.toString());
                }
            }
        }
        if (cancelled + failed + abandoned > 0) {
            log.info("{} action=scan-done cancelled={} retried={} abandoned={} pending={}",
                    LOG_PREFIX, cancelled, failed, abandoned, pending.size());
        }
    }

    private void markPersisted(GtcEntry entry, String status, String errorMessage) {
        if (!isPersistenceEnabled() || trackerMapper == null || entry.persistenceId == null) return;
        try {
            PendingOrderTimeoutTrackerEntity row = new PendingOrderTimeoutTrackerEntity()
                    .setId(entry.persistenceId)
                    .setCancelStatus(status)
                    .setCancelAttempts(entry.cancelAttempts)
                    .setLastAttemptAt(new Date())
                    .setCancelError(errorMessage)
                    .setUpdatedAt(new Date());
            trackerMapper.updateById(row);
        } catch (RuntimeException e) {
            log.warn("{} action=mark-persisted update failed externalOrderId={} status={} err={}",
                    LOG_PREFIX, entry.externalOrderId, status, e.toString());
        }
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    private boolean isEnabled() {
        SmartWalletProperties.ExecutionWallet ew =
                properties == null ? null : properties.getExecutionWallet();
        return ew != null && ew.isEnableGtcGlobalTimeout();
    }

    /** Fix 5: 任一种超时机制开启 (GTC 全局或入场 BUY) 都让 scan 跑。 */
    private boolean isAnyTimeoutEnabled() {
        SmartWalletProperties.ExecutionWallet ew =
                properties == null ? null : properties.getExecutionWallet();
        return ew != null && (ew.isEnableGtcGlobalTimeout() || ew.isEnableEntryBuyTimeout());
    }

    /** Fix 5: replay 同样需要看任一开关 (重启后入场 BUY 仍要被 cancel)。 */
    private boolean isAnyTimeoutEnabledForReplay() {
        return isAnyTimeoutEnabled();
    }

    private boolean isPersistenceEnabled() {
        SmartWalletProperties.ExecutionWallet ew =
                properties == null ? null : properties.getExecutionWallet();
        return ew != null && ew.isEnablePendingTrackerPersistence();
    }

    private long effectiveTimeoutMs() {
        SmartWalletProperties.ExecutionWallet ew = properties.getExecutionWallet();
        int seconds = ew == null ? 60 : Math.max(5, ew.getGtcDefaultTimeoutSeconds());
        return seconds * 1000L;
    }

    /** 跟踪条目；可变字段（cancelAttempts / persistenceId）便于在 scan / unregister 中更新。 */
    static final class GtcEntry {
        Long persistenceId;
        final Long orderId;
        final String externalOrderId;
        final String executionAddress;
        final String orderStrategy;
        final long registeredAtMs;
        final long expireAtMs;
        int cancelAttempts;

        GtcEntry(Long persistenceId, Long orderId, String externalOrderId, String executionAddress,
                 String orderStrategy, long registeredAtMs, long expireAtMs, int cancelAttempts) {
            this.persistenceId = persistenceId;
            this.orderId = orderId;
            this.externalOrderId = externalOrderId;
            this.executionAddress = executionAddress;
            this.orderStrategy = orderStrategy;
            this.registeredAtMs = registeredAtMs;
            this.expireAtMs = expireAtMs;
            this.cancelAttempts = cancelAttempts;
        }
    }

    /** 测试 / 运维: 当前 pending 计数. */
    public int pendingCount() {
        return pending.size();
    }
}
