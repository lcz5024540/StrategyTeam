package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletMarketInfoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 2026-05-17 ray@cowork: 定时刷新 {@code smart_wallet_market_info.market_end_at}.
 *
 * <p><b>背景</b>: market_info migration 之后, 原本 position/order/task 表上的 endAt 字段被合并到
 * market_info 表; 但 migration 后并没有补建对 market_info 表本身的周期性刷新机制 —
 * 三个 backfill 方法 (在 SmartWalletAccountingService) 全部被改成 no-op.
 * 后果: market_info 76% 行 endAt=NULL, 风控 G6_MARKET_EXPIRED 占拒绝率 56%.</p>
 *
 * <p><b>本 Job 做什么</b>: 每 {@code interval-ms} 跑一次, 单次扫 {@code limit} 条
 * lifecycle=ACTIVE 但 endAt 缺失/已过期的市场, 强制从 Polymarket Gamma 重拉 endAt 并写回.</p>
 *
 * <p><b>开关</b>: {@code smart-wallet.market-info-refresh.enabled} (默认 true).<br/>
 * <b>间隔</b>: {@code smart-wallet.market-info-refresh.interval-ms} (默认 10 分钟).<br/>
 * <b>单批上限</b>: {@code smart-wallet.market-info-refresh.batch-size} (默认 200).</p>
 *
 * <p>对开单 / 资金 / 胜率无直接副作用 (只是修数据), 但能让 G6 拒绝率显著下降.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketInfoEndAtRefreshJob {

    private static final String LOG_PREFIX = "[MarketInfoEndAtRefreshJob]";

    private final SmartWalletProperties properties;
    private final SmartWalletMarketInfoService marketInfoService;
    private final PolymarketMarketMetadataService marketMetadataService;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(fixedDelayString = "${smart-wallet.market-info-refresh.interval-ms:600000}",
            initialDelayString = "${smart-wallet.market-info-refresh.initial-delay-ms:60000}")
    public void run() {
        if (!properties.isEnabled()) {
            return;
        }
        SmartWalletProperties.MarketInfoRefresh cfg = properties.getMarketInfoRefresh();
        if (cfg == null || !cfg.isEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("{} previous run still in progress, skipping this tick", LOG_PREFIX);
            return;
        }
        long startMs = System.currentTimeMillis();
        try {
            int batchSize = cfg.getBatchSize() <= 0 ? 200 : cfg.getBatchSize();
            List<SmartWalletMarketInfoEntity> stale = marketInfoService.listActiveWithStaleEndAt(batchSize);
            if (stale.isEmpty()) {
                log.debug("{} no stale market_info rows", LOG_PREFIX);
                return;
            }
            int updated = 0;
            int failed = 0;
            for (SmartWalletMarketInfoEntity info : stale) {
                try {
                    PolymarketApiClient.MarketMetadata metadata = marketMetadataService
                            .getByConditionId(info.getMarketId(), true, info.getQuestion());
                    if (metadata == null) {
                        failed++;
                        continue;
                    }
                    Long endAt = metadata.getEndAt();
                    if (endAt != null && endAt > 0 && !endAt.equals(info.getMarketEndAt())) {
                        info.setMarketEndAt(endAt);
                        info.setUpdatedAt(new Date());
                        marketInfoService.update(info);
                        updated++;
                    }
                } catch (RuntimeException ex) {
                    failed++;
                    log.debug("{} skip marketId={} reason={}", LOG_PREFIX, info.getMarketId(), ex.getMessage());
                }
            }
            long elapsedMs = System.currentTimeMillis() - startMs;
            log.info("{} action=刷新陈旧marketEndAt result=完成 scanned={} updated={} failed={} elapsedMs={}",
                    LOG_PREFIX, stale.size(), updated, failed, elapsedMs);
        } catch (RuntimeException e) {
            log.warn("{} run failed: {}", LOG_PREFIX, e.toString(), e);
        } finally {
            running.set(false);
        }
    }
}
