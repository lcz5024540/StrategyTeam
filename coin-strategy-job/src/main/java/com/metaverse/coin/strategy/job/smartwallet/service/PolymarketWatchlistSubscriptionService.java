package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * F-P1-7 / F-P1-8 第十一轮：watchlist 自动订阅协调服务。
 *
 * <p>背景：第十轮把真实 {@link PolymarketWebSocketClient} 落地，但订阅 API
 * （{@code subscribeMarkets} / {@code subscribeUsers}）必须由上游调用方触发。
 * 第十轮上线 SOP 里"上线 checklist 第 2 项"明确写：
 * "运维确认 Polymarket WS endpoint 实际格式 → test 灰度 7d → prod"。
 *
 * <p>本服务把"上游调用方"具体化：
 * <ul>
 *   <li>从 {@code SmartWalletProfileStoreService.listExecutableWatchlist()} 拉当前 HOT 钱包 →
 *       增量地推到 user channel；</li>
 *   <li>从 {@code SmartWalletTradeEventMapper} 拉最近 N 条 tradeEvent → 提取 marketId →
 *       调 {@code marketMetadataService.getByConditionId} 拿 outcomeTokenIds → 增量地推到 market channel。</li>
 * </ul>
 *
 * <p>设计要点：
 * <ol>
 *   <li><b>幂等增量</b>：本地 {@code Set<String>} 维护"已推送"集合，每周期只推差量；
 *       WS client 自身的 {@code pendingOnOpenSends} 负责重连时 replay，本服务不重复维护。</li>
 *   <li><b>分批兜底</b>：单周期推送上限 {@code wsAutoSubscribeMaxNewMarketsPerCycle} / WalletsPerCycle
 *       （默认各 200），避免冷启动时一次性推送上万 tokenId 导致上游限频。</li>
 *   <li><b>幂等失败 fallback</b>：subscribe 失败时不从本地 set 移除，下一周期不会重试同一批；
 *       依赖 WS client 在 onOpen 时 replay {@code pendingOnOpenSends}（已包含本周期的 payload）。
 *       如果担心 client 内部 buffer 增长无界，可在配置里短期把 enableWsAutoSubscribe 关掉。</li>
 *   <li><b>主开关默认 OFF</b>：与 F-P1-7 / F-P1-8 第十轮 client 主开关一致，先灰度后切 prod。</li>
 * </ol>
 *
 * <p>F-P1-7 / F-P1-8 失真度对齐：本服务上线后，WS client 才能真正订阅到具体的
 * tokenId / walletAddress（之前只连接 channel，订阅集合一直为空），
 * 下游 hot-path / trade-event push consumer 才能拿到非空数据。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PolymarketWatchlistSubscriptionService {

    private static final String LOG_PREFIX = "[POLY_WS_SUB]";

    private final SmartWalletProperties properties;
    private final PolymarketWebSocketClient wsClient;
    private final SmartWalletProfileStoreService profileStoreService;
    private final SmartWalletTradeEventMapper tradeEventMapper;
    private final PolymarketMarketMetadataService marketMetadataService;

    /** 已推送过 subscribeMarkets 的 tokenId 集合（lowercase normalized）。 */
    private final Set<String> subscribedTokens = ConcurrentHashMap.newKeySet();
    /** 已推送过 subscribeUsers 的 walletAddress 集合（lowercase normalized）。 */
    private final Set<String> subscribedWallets = ConcurrentHashMap.newKeySet();

    private final AtomicLong refreshCycles = new AtomicLong();
    private final AtomicLong newMarketsSubscribedTotal = new AtomicLong();
    private final AtomicLong newWalletsSubscribedTotal = new AtomicLong();
    private final AtomicLong tokenLookupFailures = new AtomicLong();
    private volatile java.util.Date lastRefreshAt;

    /**
     * 周期性刷新订阅：默认 60s 一次（initialDelay 30s 让 WS client 先完成首次连接）。
     *
     * <p>失败 swallow，绝不抛回 Spring scheduler 线程；外部观察通过 {@link #getStats()}。
     */
    @Scheduled(fixedDelayString = "${smart-wallet.polymarket.ws-auto-subscribe-refresh-interval-ms:60000}",
            initialDelayString = "${smart-wallet.polymarket.ws-auto-subscribe-initial-delay-ms:30000}")
    public void refreshSubscriptions() {
        SmartWalletProperties.PolymarketMonitor pm = polymarketConfig();
        if (pm == null) {
            return;
        }
        if (!pm.isEnableWsClient() || !pm.isEnableWsAutoSubscribe()) {
            // 主开关未开启时本服务 noop（不打 warn 避免噪音）
            return;
        }
        try {
            int newWallets = refreshUserChannelSubscriptions(pm);
            int newMarkets = refreshMarketChannelSubscriptions(pm);
            refreshCycles.incrementAndGet();
            lastRefreshAt = new java.util.Date();
            if (newWallets > 0 || newMarkets > 0) {
                log.info("{} action=刷新订阅 result=完成 newWallets={} newMarkets={} cumWallets={} cumMarkets={}",
                        LOG_PREFIX, newWallets, newMarkets, subscribedWallets.size(), subscribedTokens.size());
            } else if (log.isDebugEnabled()) {
                log.debug("{} action=刷新订阅 result=完成 newWallets=0 newMarkets=0 cumWallets={} cumMarkets={}",
                        LOG_PREFIX, subscribedWallets.size(), subscribedTokens.size());
            }
        } catch (Exception e) {
            log.warn("{} action=刷新订阅 result=异常 reason={}", LOG_PREFIX, rootMessage(e));
        }
    }

    /**
     * 拉 HOT-tier 钱包，与本地 subscribed 集合做 diff，调 {@code subscribeUsers(...)} 推送增量。
     *
     * @return 本周期实际新增订阅的 wallet 数（受 maxNewWalletsPerCycle 上限约束）
     */
    private int refreshUserChannelSubscriptions(SmartWalletProperties.PolymarketMonitor pm) {
        List<SmartWalletProfileEntity> hotWallets;
        try {
            hotWallets = profileStoreService.listExecutableWatchlist();
        } catch (Exception e) {
            log.warn("{} action=读取HOT钱包 result=异常 reason={}", LOG_PREFIX, rootMessage(e));
            return 0;
        }
        if (hotWallets == null || hotWallets.isEmpty()) {
            return 0;
        }

        int maxNew = Math.max(1, pm.getWsAutoSubscribeMaxNewWalletsPerCycle());
        List<String> batch = new ArrayList<>(maxNew);
        for (SmartWalletProfileEntity w : hotWallets) {
            if (w == null || !StringUtils.hasText(w.getWalletAddress())) continue;
            String addr = w.getWalletAddress().trim().toLowerCase();
            if (subscribedWallets.add(addr)) {
                batch.add(addr);
                if (batch.size() >= maxNew) break;
            }
        }
        if (batch.isEmpty()) {
            return 0;
        }
        boolean ok = wsClient.subscribeUsers(batch);
        if (!ok && log.isDebugEnabled()) {
            // WS 未连接时 sendSubscribe 返回 false，但 payload 已 buffer 到 pendingOnOpenSends；
            // 下次 onOpen 时会 replay，所以本地 subscribed 集合不需要回滚
            log.debug("{} action=订阅user channel result=已缓冲待连接replay batch={}",
                    LOG_PREFIX, batch.size());
        }
        newWalletsSubscribedTotal.addAndGet(batch.size());
        return batch.size();
    }

    /**
     * 拉最近 N 条 tradeEvent → 提取 marketId → 拿 outcomeTokenIds → diff → subscribe。
     *
     * @return 本周期实际新增订阅的 tokenId 数（受 maxNewMarketsPerCycle 上限约束）
     */
    private int refreshMarketChannelSubscriptions(SmartWalletProperties.PolymarketMonitor pm) {
        int lookback = Math.max(1, pm.getWsAutoSubscribeMarketLookback());
        List<SmartWalletTradeEventEntity> events;
        try {
            events = tradeEventMapper.selectList(new LambdaQueryWrapper<SmartWalletTradeEventEntity>()
                    .orderByDesc(SmartWalletTradeEventEntity::getOpenedAt)
                    .last("limit " + lookback));
        } catch (Exception e) {
            log.warn("{} action=读取tradeEvent result=异常 reason={}", LOG_PREFIX, rootMessage(e));
            return 0;
        }
        if (events == null || events.isEmpty()) {
            return 0;
        }
        // 去重 marketId，避免对同一个 conditionId 调多次 getByConditionId
        Set<String> uniqueMarketIds = new LinkedHashSet<>();
        for (SmartWalletTradeEventEntity event : events) {
            if (event == null || !StringUtils.hasText(event.getMarketId())) continue;
            uniqueMarketIds.add(event.getMarketId().trim());
        }
        if (uniqueMarketIds.isEmpty()) {
            return 0;
        }

        int maxNew = Math.max(1, pm.getWsAutoSubscribeMaxNewMarketsPerCycle());
        List<String> batch = new ArrayList<>(maxNew);
        for (String marketId : uniqueMarketIds) {
            try {
                PolymarketApiClient.MarketMetadata md = marketMetadataService.getByConditionId(marketId);
                if (md == null || md.getOutcomeTokenIds() == null || md.getOutcomeTokenIds().isEmpty()) {
                    continue;
                }
                for (Map.Entry<String, String> entry : md.getOutcomeTokenIds().entrySet()) {
                    String tokenId = entry.getValue();
                    if (!StringUtils.hasText(tokenId)) continue;
                    String normalized = tokenId.trim();
                    if (subscribedTokens.add(normalized)) {
                        batch.add(normalized);
                        if (batch.size() >= maxNew) break;
                    }
                }
                if (batch.size() >= maxNew) break;
            } catch (Exception e) {
                tokenLookupFailures.incrementAndGet();
                log.debug("{} action=拉取marketMetadata result=失败 marketId={} reason={}",
                        LOG_PREFIX, marketId, rootMessage(e));
            }
        }
        if (batch.isEmpty()) {
            return 0;
        }
        boolean ok = wsClient.subscribeMarkets(batch);
        if (!ok && log.isDebugEnabled()) {
            log.debug("{} action=订阅market channel result=已缓冲待连接replay batch={}",
                    LOG_PREFIX, batch.size());
        }
        newMarketsSubscribedTotal.addAndGet(batch.size());
        return batch.size();
    }

    /**
     * 手动重置已订阅集合，重新触发全量 push。
     * <p>用于运维场景：WS endpoint 切换 / 长断线 / 上游通知 buffer 失效后强制重订阅。
     */
    public void resetSubscriptionState() {
        int prevTokens = subscribedTokens.size();
        int prevWallets = subscribedWallets.size();
        subscribedTokens.clear();
        subscribedWallets.clear();
        log.info("{} action=重置订阅状态 prevTokens={} prevWallets={}",
                LOG_PREFIX, prevTokens, prevWallets);
    }

    public SubscriptionStats getStats() {
        SmartWalletProperties.PolymarketMonitor pm = polymarketConfig();
        return SubscriptionStats.builder()
                .enabled(pm != null && pm.isEnableWsClient() && pm.isEnableWsAutoSubscribe())
                .refreshCycles(refreshCycles.get())
                .lastRefreshAt(lastRefreshAt)
                .subscribedTokenCount(subscribedTokens.size())
                .subscribedWalletCount(subscribedWallets.size())
                .newMarketsSubscribedTotal(newMarketsSubscribedTotal.get())
                .newWalletsSubscribedTotal(newWalletsSubscribedTotal.get())
                .tokenLookupFailures(tokenLookupFailures.get())
                .build();
    }

    private SmartWalletProperties.PolymarketMonitor polymarketConfig() {
        return properties == null ? null : properties.getPolymarket();
    }

    private String rootMessage(Throwable t) {
        if (t == null) return "null";
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }

    @lombok.Data
    @lombok.Builder
    public static class SubscriptionStats {
        private boolean enabled;
        private long refreshCycles;
        private java.util.Date lastRefreshAt;
        private int subscribedTokenCount;
        private int subscribedWalletCount;
        private long newMarketsSubscribedTotal;
        private long newWalletsSubscribedTotal;
        private long tokenLookupFailures;
    }
}
